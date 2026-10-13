"""로컬 kind·API와 출처가 확인된 사진만 사용하는 적재 통신."""

import base64
import hashlib
import hmac
import http.client
import json
import os
import subprocess
import time
import urllib.error
import urllib.request
import uuid

from community_guide_rules import PHOTO_HOSTS, local_url, public_url

MAX_PHOTO_BYTES = 10 * 1024 * 1024


def photo_type(blob):
    if blob.startswith(b"\xff\xd8\xff") and blob.endswith(b"\xff\xd9"):
        return "image/jpeg"
    if blob.startswith(b"\x89PNG\r\n\x1a\n") and blob.endswith(b"IEND\xaeB`\x82"):
        return "image/png"
    if (
        blob[:4] == b"RIFF"
        and blob[8:12] == b"WEBP"
        and int.from_bytes(blob[4:8], "little") + 8 == len(blob)
    ):
        return "image/webp"
    raise ValueError("사진 파일이 잘렸거나 실제 형식을 확인하지 못했습니다")


class APIError(RuntimeError):
    def __init__(self, status):
        self.status = status
        super().__init__(f"로컬 API 응답: {status}")


class LocalRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        local_url(new_url)
        return super().redirect_request(
            request, response, code, message, headers, new_url
        )


class PhotoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        if public_url(new_url).hostname not in PHOTO_HOSTS:
            raise ValueError("사진 공급자 밖으로 연결하지 않습니다")
        return super().redirect_request(
            request, response, code, message, headers, new_url
        )


def store_state(path, state):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, ensure_ascii=False, indent=2))
    temporary.chmod(0o600)
    temporary.replace(path)


def local_author(user):
    if os.environ.get("SCENETRIP_DB_HOST"):
        raise ValueError("공유 DB 접속 설정에서는 실행하지 않습니다")
    context = (
        subprocess.check_output(["kubectl", "config", "current-context"])
        .decode()
        .strip()
    )
    if context != "kind-scenetrip":
        raise ValueError("로컬 kind-scenetrip 컨텍스트가 필요합니다")
    owner = str(uuid.UUID(user))
    sql = """
BEGIN;
PREPARE guide_author(uuid,text) AS INSERT INTO app_user
 (id,registered_at,nickname,nickname_confirmed) VALUES($1,now(),$2,true)
 ON CONFLICT(id) DO NOTHING;
EXECUTE guide_author(:'owner',:'nickname');
SELECT nickname=:'nickname' AND nickname_confirmed AND registered_at IS NOT NULL
 FROM app_user WHERE id=:'owner'::uuid;
COMMIT;
"""
    result = subprocess.run(
        [
            "kubectl",
            "--context",
            "kind-scenetrip",
            "exec",
            "-i",
            "statefulset/postgres",
            "-n",
            "scenetrip",
            "--",
            "psql",
            "-X",
            "-q",
            "-A",
            "-t",
            "-U",
            "scenetrip",
            "-d",
            "scenetrip",
            "-v",
            "ON_ERROR_STOP=1",
            "-v",
            "owner=" + owner,
            "-v",
            "nickname=씬트립가이드",
            "-f",
            "-",
        ],
        input=sql.encode(),
        capture_output=True,
        check=False,
    )
    if result.returncode or b"t" not in result.stdout.splitlines():
        raise RuntimeError("로컬 가이드 편집 계정 확인에 실패했습니다")


def access_token(user):
    key = base64.b64decode(os.environ["SCENETRIP_AUTH_JWT_SECRET"], validate=True)
    if len(key) < 32:
        raise ValueError("로컬 JWT 설정 길이가 올바르지 않습니다")

    def encode(value):
        raw = json.dumps(value, separators=(",", ":")).encode()
        return base64.urlsafe_b64encode(raw).decode().rstrip("=")

    now = int(time.time())
    message = (
        encode({"alg": "HS256", "typ": "JWT"})
        + "."
        + encode(
            {
                "iss": "scene-api",
                "sub": str(uuid.UUID(user)),
                "iat": now,
                "exp": now + 3600,
                "jti": str(uuid.uuid4()),
            }
        )
    )
    signature = (
        base64.urlsafe_b64encode(
            hmac.new(key, message.encode(), hashlib.sha256).digest()
        )
        .decode()
        .rstrip("=")
    )
    return message + "." + signature


class LocalAPI:
    def __init__(self, user, install):
        self.token = access_token(user)
        self.install = str(uuid.UUID(install))
        self.last_request = 0.0

    def call(self, method, path, body=None, *, headers=None, member=True):
        url = local_url(
            path if path.startswith("http") else "http://localhost:8081/v1" + path
        )
        head = {"Accept-Language": "ko", **(headers or {})}
        if url.startswith("http://localhost:8081/"):
            time.sleep(max(0, 1.1 - (time.monotonic() - self.last_request)))
            self.last_request = time.monotonic()
            head["X-Install-Id"] = self.install
            if member:
                head["Authorization"] = "Bearer " + self.token
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body, ensure_ascii=False).encode()
            head["Content-Type"] = "application/json"
        try:
            opener = urllib.request.build_opener(LocalRedirect())
            with opener.open(
                urllib.request.Request(url, body, head, method=method), timeout=30
            ) as response:
                content = response.read()
                if response.headers.get_content_type() == "application/json":
                    return json.loads(content) if content else None
                return content
        except urllib.error.HTTPError as error:
            raise APIError(error.code) from None
        except urllib.error.URLError:
            raise RuntimeError(
                "로컬 연결이 끊겼습니다. 상태 파일을 보존했습니다"
            ) from None
        except (http.client.HTTPException, TimeoutError, ConnectionError):
            raise RuntimeError(
                "로컬 API 연결이 끝났습니다. 같은 상태 파일로 재개합니다"
            ) from None


def download_photo(url):
    request = urllib.request.Request(
        url, headers={"User-Agent": "SceneTrip-local-guide-curation"}
    )
    opener = urllib.request.build_opener(PhotoRedirect())
    with opener.open(request, timeout=30) as response:
        if public_url(response.geturl()).hostname not in PHOTO_HOSTS:
            raise ValueError("사진 공급자가 다른 사이트로 연결했습니다")
        if int(response.headers.get("Content-Length", "0")) > MAX_PHOTO_BYTES:
            return None
        blob = response.read(MAX_PHOTO_BYTES + 1)
    return blob if len(blob) <= MAX_PHOTO_BYTES else None


def photo_file(photo, directory):
    parsed = public_url(photo["url"])
    if parsed.hostname not in PHOTO_HOSTS:
        raise ValueError("확인한 사진 공급자 주소만 내려받습니다")
    path = directory / (hashlib.sha256(photo["url"].encode()).hexdigest() + ".image")
    if not path.exists():
        download_url = photo["url"]
        blob = download_photo(download_url)
        if blob is None and "_image2_1" in download_url:
            download_url = download_url.replace("_image2_1", "_image3_1")
            blob = download_photo(download_url)
        if not blob:
            raise ValueError(
                "10MB 이하의 제공 사진을 확인하지 못했습니다: " + photo["caption"]
            )
        photo_type(blob)
        temporary = path.with_suffix(".partial")
        temporary.write_bytes(blob)
        temporary.chmod(0o600)
        store_state(
            path.with_suffix(".download.json"),
            {
                "originalUrl": photo["url"],
                "downloadUrl": download_url,
                "bytes": len(blob),
                "sha256": hashlib.sha256(blob).hexdigest(),
            },
        )
        temporary.replace(path)
    if path.stat().st_size > MAX_PHOTO_BYTES:
        raise ValueError("보관한 사진도 업로드 크기 한도를 넘을 수 없습니다")
    blob = path.read_bytes()
    content_type = photo_type(blob)
    metadata_path = path.with_suffix(".download.json")
    metadata = json.loads(metadata_path.read_text()) if metadata_path.exists() else {}
    if (
        metadata.get("sha256")
        and metadata["sha256"] != hashlib.sha256(blob).hexdigest()
    ):
        raise ValueError("보관한 사진이 다운로드 원본 해시와 다릅니다")
    return path, content_type
