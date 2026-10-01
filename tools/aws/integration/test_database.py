"""PostgreSQL17에서 RDS 역할 경계와 전체 migration 권한을 검증한다."""

import secrets
import sys
import unittest

from tools.aws.integration.support import DockerTest

IMAGE_TAR = sys.argv.pop(1)
IMAGE = "scenetrip-integration/postgis:17-3.5"


class DatabaseTests(DockerTest):
    def setUp(self):
        super().setUp()
        self.load_image(IMAGE_TAR)
        self.passwords = {
            "POSTGRES_PASSWORD": secrets.token_urlsafe(32),
            "RUNTIME_PASSWORD": secrets.token_urlsafe(32),
            "MIGRATION_PASSWORD": secrets.token_urlsafe(32),
        }
        self.sensitive = tuple(self.passwords.values())
        name = self.unique_name("database")
        self.addCleanup(self.command, "rm", "--force", "--volumes", name, check=False)
        self.command(
            "run",
            "--detach",
            "--name",
            name,
            "--platform",
            "linux/amd64",
            "--label",
            "scenetrip.test=aws-integration",
            "--env",
            "POSTGRES_PASSWORD",
            "--tmpfs",
            "/var/lib/postgresql/data:rw,nosuid,mode=1777",
            IMAGE,
            extra_env=self.passwords,
        )
        self.database = name
        self.wait_until(
            lambda: (
                self.command(
                    "exec",
                    name,
                    "pg_isready",
                    "-h",
                    "127.0.0.1",
                    "-U",
                    "postgres",
                    check=False,
                ).returncode
                == 0
            ),
            "PostgreSQL",
        )
        self.source = (self.root / "tools/aws/db-bootstrap.sh").read_text()

    def sql(self, source, user="postgres", database="postgres", check=True):
        return self.command(
            "exec",
            "-i",
            "--env",
            "RUNTIME_PASSWORD",
            "--env",
            "MIGRATION_PASSWORD",
            self.database,
            "psql",
            "-X",
            "-qAt",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            user,
            "-d",
            database,
            stdin=source,
            extra_env=self.passwords,
            check=check,
        )

    def bootstrap(self, user="bootstrap_admin", check=True):
        return self.command(
            "exec",
            "-i",
            "--env",
            "RUNTIME_PASSWORD",
            "--env",
            "MIGRATION_PASSWORD",
            "--env",
            f"PGUSER={user}",
            "--env",
            "PGHOST=/var/run/postgresql",
            self.database,
            "sh",
            stdin=self.source,
            extra_env=self.passwords,
            check=check,
        )

    def initialize_restricted_database(self):
        self.sql("CREATE ROLE bootstrap_admin LOGIN NOSUPERUSER CREATEDB CREATEROLE;")
        self.assertTrue(
            self.sql("SHOW server_version_num;").stdout.strip().startswith("17")
        )
        body = self.source.partition("<<'SQL'\n")[2].rsplit("\nSQL", 1)[0]
        prefix = body.split("\\connect scenetrip", 1)[0]
        self.assertIn("CREATE DATABASE scenetrip", prefix)
        self.sql(prefix, "bootstrap_admin")
        self.assertEqual(
            self.sql(
                "SELECT rolsuper FROM pg_roles WHERE rolname='bootstrap_admin'"
            ).stdout.strip(),
            "f",
        )
        # 로컬 PostgreSQL에는 RDS의 확장 설치 권한이 없어 이 단계만 superuser로 수행한다.
        self.sql(
            "CREATE EXTENSION postgis; CREATE EXTENSION pg_trgm;", database="scenetrip"
        )
        self.bootstrap()
        self.assertEqual(
            self.sql(
                "SELECT datcollate || '/' || datctype FROM pg_database WHERE datname='scenetrip'"
            ).stdout.strip(),
            "C/C.UTF-8",
        )

    def apply_migrations(self):
        migrations = sorted(
            (self.root / "services/scene-api/src/main/resources/db/migration").glob(
                "V*.sql"
            ),
            key=lambda path: int(path.name.split("__")[0][1:]),
        )
        # 번호가 1 부터 빈 칸 없이 이어지는지 본다. 마지막 번호를 박아 두면 마이그레이션이 늘
        # 때마다 이 줄을 고쳐야 하고(V15 로그인에서 CI 가 그렇게 깨졌다), 정작 지켜야 할 것은
        # 「빈 번호·겹친 번호가 없다」 이다 — 비운 번호에 뒤늦게 낮은 번호가 들어오면 Flyway 가
        # 이미 적용된 DB 에서 거부한다.
        numbers = [int(path.name.split("__")[0][1:]) for path in migrations]
        self.assertEqual(numbers, list(range(1, len(numbers) + 1)))
        for migration in migrations:
            result = self.sql(
                "BEGIN;\n" + migration.read_text() + "\nCOMMIT;",
                "app_migrate",
                "scenetrip",
                check=False,
            )
            self.assertEqual(
                result.returncode, 0, migration.name + ": " + result.stderr
            )

    def assert_runtime_privileges(self):
        dml = """
INSERT INTO place(type, geom) VALUES ('test', ST_SetSRID(ST_MakePoint(127,37),4326)::geography) RETURNING id;
UPDATE place SET popularity_score=1 WHERE type='test';
SELECT count(*) FROM place WHERE type='test' AND popularity_score=1;
DELETE FROM place WHERE type='test';
"""
        self.assertIn(
            "1", self.sql(dml, "app_runtime", "scenetrip").stdout.splitlines()
        )
        # 로그인 표(V15)도 런타임이 쓸 수 있어야 한다. 마이그레이션 역할이 만든 표에 기본 권한
        # (ALTER DEFAULT PRIVILEGES)이 걸리는지 — 걸리지 않으면 원격에서만 로그인이 permission
        # denied 로 깨진다.
        login = """
INSERT INTO app_user(id, registered_at) VALUES ('00000000-0000-0000-0000-0000000000a1', now());
INSERT INTO user_identity(provider, subject, user_id) VALUES ('google', 'it', '00000000-0000-0000-0000-0000000000a1');
INSERT INTO refresh_token(id, token_hash, user_id, family_id, expires_at)
  VALUES (gen_random_uuid(), '\\x00', '00000000-0000-0000-0000-0000000000a1', gen_random_uuid(), now());
UPDATE refresh_token SET used_at = now() WHERE user_id = '00000000-0000-0000-0000-0000000000a1';
SELECT count(*) FROM refresh_token WHERE used_at IS NOT NULL;
DELETE FROM app_user WHERE id = '00000000-0000-0000-0000-0000000000a1';
"""
        self.assertIn(
            "1", self.sql(login, "app_runtime", "scenetrip").stdout.splitlines()
        )
        for source in (
            "CREATE TABLE forbidden(id integer);",
            "INSERT INTO flyway_schema_history VALUES(1);",
            "SELECT * FROM flyway_schema_history;",
        ):
            with self.subTest(denied=source.split()[0]):
                denied = self.sql(source, "app_runtime", "scenetrip", check=False)
                self.assertNotEqual(denied.returncode, 0)
                self.assertIn("permission denied", denied.stderr)

    def test_bootstrap_migrations_and_runtime_privilege_boundary(self):
        self.initialize_restricted_database()
        self.apply_migrations()
        self.sql(
            "CREATE TABLE flyway_schema_history(installed_rank integer PRIMARY KEY);",
            "app_migrate",
            "scenetrip",
        )
        self.bootstrap()
        self.bootstrap()
        self.assertEqual(
            self.sql(
                "SELECT count(*) FROM pg_roles WHERE rolname IN ('app_migrate','app_runtime')"
            ).stdout.strip(),
            "2",
        )
        self.assert_runtime_privileges()


if __name__ == "__main__":
    unittest.main()
