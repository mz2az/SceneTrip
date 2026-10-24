"""PostgreSQL17에서 RDS 역할 경계와 전체 migration 권한을 검증한다."""

import secrets
import sys
import unittest

from tools.aws.integration.support import DockerTest

IMAGE_TAR = sys.argv.pop(1)
IMAGE = "scenetrip-integration/postgis:17-3.5"
BASE_ENV = ("RUNTIME_PASSWORD", "MIGRATION_PASSWORD")
METABASE_ENV = ("METABASE_RO_PASSWORD", "METABASE_APP_PASSWORD")
# 계획 metabase-prd.md §3-4 의 뷰 목록.
ANALYTICS_VIEWS = (
    "contents",
    "places",
    "daily_new_installs",
    "daily_new_members",
    "daily_active_viewers",
    "daily_entity_views",
    "daily_actions",
    "daily_chat",
)


class DatabaseTests(DockerTest):
    def setUp(self):
        super().setUp()
        self.load_image(IMAGE_TAR)
        self.passwords = {
            "POSTGRES_PASSWORD": secrets.token_urlsafe(32),
            "RUNTIME_PASSWORD": secrets.token_urlsafe(32),
            "MIGRATION_PASSWORD": secrets.token_urlsafe(32),
            "METABASE_RO_PASSWORD": secrets.token_urlsafe(32),
            "METABASE_APP_PASSWORD": secrets.token_urlsafe(32),
        }
        # docker exec 에 넘길 비밀값 이름. Metabase 시험만 METABASE_* 를 더한다 — 넘기지 않은
        # 이름은 컨테이너 안에 없다(지금 배포처럼).
        self.env_names = BASE_ENV
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

    def env_flags(self, names=None):
        flags = []
        for name in self.env_names if names is None else names:
            flags += ["--env", name]
        return flags

    def sql(self, source, user="postgres", database="postgres", check=True):
        return self.command(
            "exec",
            "-i",
            *self.env_flags(),
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

    def bootstrap(self, user="bootstrap_admin", check=True, env_names=None):
        return self.command(
            "exec",
            "-i",
            *self.env_flags(env_names),
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
        # poi_update.sql 의 levenshtein() — fuzzystrmatch 는 위에서 미리 만들지 않으므로
        # bootstrap 자신이 scenetrip 에 켰는지를 본다(RDS 갱신 적재 실패, 2026-10-13).
        self.assertEqual(
            self.sql(
                "SELECT levenshtein('a', 'b');", "app_runtime", "scenetrip"
            ).stdout.strip(),
            "1",
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

    def password_login(self, user, database, env_name, wrong=False):
        """비밀번호로 TCP 접속한다. 소켓·127.0.0.1 은 이미지가 trust 라 비밀번호를 보지 않으므로
        컨테이너 자신의 바깥 주소로 붙는다(scram). 비밀번호는 컨테이너 안 환경변수로만 쓴다."""
        suffix = "-wrong" if wrong else ""
        script = (
            f'PGPASSWORD="${{{env_name}}}{suffix}" psql -X -qAt '
            '-h "$(hostname -i | cut -d " " -f 1)" '
            f'-U {user} -d {database} -c "SELECT current_user"'
        )
        return self.command(
            "exec",
            "--env",
            env_name,
            self.database,
            "sh",
            "-c",
            script,
            extra_env=self.passwords,
            check=False,
        )

    def metabase_state(self):
        """멱등 비교용 — Metabase 계정·DB·스키마의 속성과 권한을 한 줄씩."""
        return (
            self.sql(
                """
SELECT 'role ' || rolname || ' login=' || rolcanlogin || ' super=' || rolsuper
       || ' limit=' || rolconnlimit || ' config=' || coalesce(array_to_string(rolconfig, ','), '')
FROM pg_roles WHERE rolname IN ('metabase_ro', 'metabase_app')
UNION ALL
SELECT 'db ' || datname || ' owner=' || pg_get_userbyid(datdba)
       || ' enc=' || pg_encoding_to_char(encoding) || ' acl=' || coalesce(datacl::text, '')
FROM pg_database WHERE datname IN ('metabase', 'scenetrip')
ORDER BY 1;
"""
            ).stdout
            + self.sql(
                """
SELECT 'schema ' || nspname || ' owner=' || pg_get_userbyid(nspowner) || ' acl=' || coalesce(nspacl::text, '')
FROM pg_namespace WHERE nspname IN ('analytics', 'public')
UNION ALL
SELECT 'default ' || pg_get_userbyid(defaclrole) || ' ' || coalesce(n.nspname, '') || ' '
       || defaclobjtype::text || ' ' || defaclacl::text
FROM pg_default_acl d LEFT JOIN pg_namespace n ON n.oid = d.defaclnamespace
ORDER BY 1;
""",
                database="scenetrip",
            ).stdout
        )

    def assert_denied(self, source, user, database, *reasons):
        result = self.sql(source, user, database, check=False)
        self.assertNotEqual(result.returncode, 0, source)
        self.assertTrue(
            any(reason in result.stderr for reason in reasons),
            source + ": " + result.stderr,
        )

    def test_metabase_accounts_with_passwords(self):
        """§3-4 계정 — 두 비밀번호가 다 있으면 metabase_app·metabase_ro·DB metabase·스키마 analytics."""
        self.env_names = BASE_ENV + METABASE_ENV
        self.initialize_restricted_database()

        # 권한을 걸려면 스키마가 먼저 있어야 하므로 bootstrap 이 app_migrate 주인으로 만든다.
        self.assertEqual(
            self.sql(
                "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname='analytics'",
                database="scenetrip",
            ).stdout.strip(),
            "app_migrate",
        )
        self.apply_migrations()
        self.bootstrap()
        before = self.metabase_state()
        self.bootstrap()
        self.assertEqual(
            self.metabase_state(), before, "bootstrap 을 다시 돌리면 같아야 한다"
        )

        # 계정: 로그인 가능, superuser 아님. metabase_ro 는 접속 5 개.
        self.assertEqual(
            self.sql(
                """
SELECT rolname || ' ' || rolcanlogin || ' ' || rolsuper || ' ' || rolconnlimit
FROM pg_roles WHERE rolname IN ('metabase_ro', 'metabase_app') ORDER BY rolname;
"""
            ).stdout.splitlines(),
            ["metabase_app true false -1", "metabase_ro true false 5"],
        )
        # 비밀번호가 실제로 걸렸는지 — 맞는 것은 붙고 틀린 것은 거부된다.
        for user, database, env_name in (
            ("metabase_ro", "scenetrip", "METABASE_RO_PASSWORD"),
            ("metabase_app", "metabase", "METABASE_APP_PASSWORD"),
        ):
            with self.subTest(login=user):
                ok = self.password_login(user, database, env_name)
                self.assertEqual(ok.returncode, 0, ok.stderr)
                self.assertEqual(ok.stdout.strip(), user)
                bad = self.password_login(user, database, env_name, wrong=True)
                self.assertNotEqual(bad.returncode, 0)
                self.assertIn("password authentication failed", bad.stderr)

        # DB metabase: UTF8, 주인 metabase_app. metabase_app 은 scenetrip 에 못 붙는다.
        self.assertEqual(
            self.sql(
                "SELECT pg_get_userbyid(datdba) || ' ' || pg_encoding_to_char(encoding) "
                "FROM pg_database WHERE datname='metabase'"
            ).stdout.strip(),
            "metabase_app UTF8",
        )
        self.assertEqual(
            self.sql("SELECT current_user", "metabase_app", "metabase").stdout.strip(),
            "metabase_app",
        )
        self.assert_denied(
            "SELECT 1", "metabase_app", "scenetrip", "permission denied for database"
        )

        # metabase_ro 의 역할 설정.
        self.assertEqual(
            self.sql(
                "SHOW statement_timeout; SHOW default_transaction_read_only;",
                "metabase_ro",
                "scenetrip",
            ).stdout.splitlines(),
            ["30s", "on"],
        )

        # analytics 의 모든 뷰를 읽는다 — 계획의 여덟 개가 다 있어야 한다.
        views = self.sql(
            "SELECT table_name FROM information_schema.views "
            "WHERE table_schema='analytics' ORDER BY 1",
            "app_migrate",
            "scenetrip",
        ).stdout.split()
        self.assertTrue(set(ANALYTICS_VIEWS) <= set(views), views)
        for view in views:
            with self.subTest(select=view):
                self.sql(
                    f"SELECT count(*) FROM analytics.{view};",
                    "metabase_ro",
                    "scenetrip",
                )
        # 앞으로 app_migrate 가 analytics 에 만드는 것도 읽힌다(default privileges).
        self.sql(
            "CREATE VIEW analytics.it_probe AS SELECT 1 AS n;",
            "app_migrate",
            "scenetrip",
        )
        self.assertEqual(
            self.sql(
                "SELECT n FROM analytics.it_probe;", "metabase_ro", "scenetrip"
            ).stdout.strip(),
            "1",
        )

        # public 은 못 읽는다 — 개인정보는 DB 권한으로 막는다.
        for table in ("app_user", "user_identity", "user_device", "place"):
            with self.subTest(denied=table):
                self.assert_denied(
                    f"SELECT count(*) FROM public.{table};",
                    "metabase_ro",
                    "scenetrip",
                    "permission denied",
                )
        # 쓰기·만들기는 어디에도 못 한다. 기본은 읽기 전용 트랜잭션이고, 읽기·쓰기로 풀어도 권한이 없다.
        for source in (
            "CREATE TABLE analytics.it_forbidden(id integer);",
            "CREATE TABLE public.it_forbidden(id integer);",
            "INSERT INTO public.app_user(id) VALUES (gen_random_uuid());",
        ):
            with self.subTest(read_only=source):
                self.assert_denied(
                    source,
                    "metabase_ro",
                    "scenetrip",
                    "read-only transaction",
                )
        for source in (
            "CREATE TABLE analytics.it_forbidden(id integer);",
            "CREATE TABLE public.it_forbidden(id integer);",
            "CREATE VIEW analytics.it_forbidden AS SELECT 1;",
            "DELETE FROM public.app_user;",
        ):
            with self.subTest(read_write=source):
                self.assert_denied(
                    "BEGIN READ WRITE;\n" + source + "\nCOMMIT;",
                    "metabase_ro",
                    "scenetrip",
                    "permission denied",
                )

    def test_bootstrap_migrations_and_runtime_privilege_boundary(self):
        self.initialize_restricted_database()
        self.apply_migrations()
        self.sql(
            "CREATE TABLE flyway_schema_history(installed_rank integer PRIMARY KEY);",
            "app_migrate",
            "scenetrip",
        )
        self.bootstrap()
        # Metabase 비밀번호가 하나만 있어도 그 부분은 건너뛴다(둘 다 있을 때만, §3-4).
        self.bootstrap(env_names=BASE_ENV + ("METABASE_RO_PASSWORD",))
        self.assertEqual(
            self.sql(
                "SELECT count(*) FROM pg_roles WHERE rolname IN ('app_migrate','app_runtime')"
            ).stdout.strip(),
            "2",
        )
        # METABASE_* 없이 돈 지금 배포는 Metabase 계정·DB 를 만들지 않는다.
        self.assertEqual(
            self.sql(
                "SELECT (SELECT count(*) FROM pg_roles WHERE rolname LIKE 'metabase%')"
                " + (SELECT count(*) FROM pg_database WHERE datname = 'metabase')"
            ).stdout.strip(),
            "0",
        )
        self.assert_runtime_privileges()


if __name__ == "__main__":
    unittest.main()
