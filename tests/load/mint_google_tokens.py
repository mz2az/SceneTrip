#!/usr/bin/env python3
"""부하 시험용 구글 ID 토큰 묶음 만들기 — k6 에는 RSA 서명이 없어서 시험 전에 미리 만든다(MZ2AZ-502).

DEV 의 구글 공개키 주소를 가짜 바깥 서버(`platform/helm/scenetrip/files/fake_external.py` 의 `/google/jwks`)로 돌린 동안,
여기서 만든 토큰은 진짜 `POST /auth/google` 을 통과한다. 운영 코드에 시험용 구멍을 만들지 않는 방법이다(e2e 와 같다).

    # 키를 만들거나 읽고, 가짜에 줄 공개키와 토큰 500 개를 쓴다
    python3 tests/load/mint_google_tokens.py --key ~/.scenetrip-load/google-key.json \\
        --jwk-out jwk.json --count 500 --aud <구글 client id> --nonce load-nonce --hours 24 --out tokens.json
    # 가짜에는 공개키만: FAKE_GOOGLE_JWK_JSON="$(cat jwk.json)"

키 파일에는 **비밀키**가 들어 있다(권한 600). 저장소에 넣지 않는다 — 받아 주는 곳은 시험 중인 DEV 뿐이지만 그 동안은
이 키로 아무 계정이나 만들 수 있다. 시험이 끝나면 지워도 된다.

표준 라이브러리만 쓴다. RSA 서명은 `pow(m, d, n)`(tests/e2e/support.py 와 같은 방식).
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import random
import secrets
import sys
import time

GOOGLE_ISSUER = "https://accounts.google.com"


def _b64url(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def _int_bytes(n: int) -> bytes:
    return n.to_bytes((n.bit_length() + 7) // 8, "big")


def _is_probable_prime(n: int, rounds: int = 40) -> bool:
    if n < 2:
        return False
    for p in (2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37):
        if n % p == 0:
            return n == p
    d, s = n - 1, 0
    while d % 2 == 0:
        d //= 2
        s += 1
    rng = random.SystemRandom()
    for _ in range(rounds):
        a = rng.randrange(2, n - 2)
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(s - 1):
            x = pow(x, 2, n)
            if x == n - 1:
                break
        else:
            return False
    return True


def _prime(bits: int) -> int:
    while True:
        c = secrets.randbits(bits) | (1 << (bits - 1)) | (1 << (bits - 2)) | 1
        if _is_probable_prime(c):
            return c


class RsaKey:
    """RS256 서명용 RSA 키."""

    E = 65537
    # DigestInfo 머리 — SHA-256 (RFC 8017 §9.2)
    _SHA256_PREFIX = bytes.fromhex("3031300d060960864801650304020105000420")

    def __init__(self, n: int, d: int, kid: str) -> None:
        self.n, self.d, self.kid = n, d, kid

    @classmethod
    def generate(cls, bits: int = 2048, kid: str | None = None) -> RsaKey:
        while True:
            p, q = _prime(bits // 2), _prime(bits // 2)
            phi = (p - 1) * (q - 1)
            if p != q and phi % cls.E and (p * q).bit_length() == bits:
                break
        return cls(p * q, pow(cls.E, -1, phi), kid or "load-" + secrets.token_hex(4))

    @classmethod
    def load_or_create(cls, path: str) -> RsaKey:
        """있으면 읽고, 없으면 만들어 저장한다. 같은 키를 다시 쓰면 가짜에 넘긴 공개키를 바꿀 일이 없다."""
        if os.path.exists(path):
            with open(path, encoding="utf-8") as f:
                d = json.load(f)
            return cls(int(d["n"]), int(d["d"]), d["kid"])
        key = cls.generate()
        parent = os.path.dirname(os.path.abspath(path))
        os.makedirs(parent, exist_ok=True)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump({"n": str(key.n), "d": str(key.d), "kid": key.kid}, f)
        return key

    def jwk(self) -> dict:
        return {
            "kty": "RSA",
            "alg": "RS256",
            "use": "sig",
            "kid": self.kid,
            "n": _b64url(_int_bytes(self.n)),
            "e": _b64url(_int_bytes(self.E)),
        }

    def sign(self, message: bytes) -> bytes:
        k = (self.n.bit_length() + 7) // 8
        t = self._SHA256_PREFIX + hashlib.sha256(message).digest()
        em = b"\x00\x01" + b"\xff" * (k - len(t) - 3) + b"\x00" + t
        return pow(int.from_bytes(em, "big"), self.d, self.n).to_bytes(k, "big")


def jwt_rs256(key: RsaKey, claims: dict) -> str:
    header = {"alg": "RS256", "typ": "JWT", "kid": key.kid}
    signing_input = (
        _b64url(json.dumps(header, separators=(",", ":")).encode())
        + "."
        + _b64url(json.dumps(claims, separators=(",", ":")).encode())
    )
    return signing_input + "." + _b64url(key.sign(signing_input.encode("ascii")))


def mint_tokens(
    key: RsaKey,
    count: int,
    aud: str,
    *,
    nonce: str | None = None,
    hours: float = 24.0,
    now: int | None = None,
) -> list[dict]:
    """구글처럼 생긴 ID 토큰 `count` 개. sub·email 은 토큰마다 다르다(가상 사용자마다 다른 회원)."""
    now = int(time.time()) if now is None else now
    run = secrets.token_hex(3)
    out = []
    for i in range(1, count + 1):
        sub = f"load-{run}-{i:06d}"
        token_nonce = nonce if nonce is not None else secrets.token_urlsafe(12)
        claims = {
            "iss": GOOGLE_ISSUER,
            "aud": aud,
            "sub": sub,
            "email": f"{sub}@loadtest.scenetrip.invalid",
            "email_verified": True,
            "name": f"부하시험 {i}",
            "nonce": token_nonce,
            "iat": now,
            "exp": now + int(hours * 3600),
        }
        out.append(
            {"sub": sub, "idToken": jwt_rs256(key, claims), "nonce": token_nonce}
        )
    return out


def _path(p: str) -> str:
    """`bazel run` 은 작업 디렉터리를 옮긴다 — 상대 경로는 부른 자리 기준으로 푼다."""
    p = os.path.expanduser(p)
    base = os.environ.get("BUILD_WORKING_DIRECTORY")
    return os.path.join(base, p) if base and not os.path.isabs(p) else p


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description="부하 시험용 구글 ID 토큰 묶음을 만든다.")
    ap.add_argument(
        "--key",
        required=True,
        help="비밀키 JSON. 없으면 RSA 2048 을 만들어 저장한다(권한 600).",
    )
    ap.add_argument(
        "--jwk-out", help="가짜 바깥 서버에 줄 공개 JWK 를 쓸 파일. 없으면 표준 출력."
    )
    ap.add_argument(
        "--count", type=int, default=0, help="만들 토큰 수(0 이면 공개키만)."
    )
    ap.add_argument(
        "--aud",
        help="구글 client id — scene-api 의 scenetrip.auth.google.client-ids 중 하나.",
    )
    ap.add_argument("--nonce", help="모든 토큰에 같은 nonce. 없으면 토큰마다 새로.")
    ap.add_argument("--hours", type=float, default=24.0, help="유효 시간(기본 24).")
    ap.add_argument("--out", help="토큰 JSON 을 쓸 파일 — [{sub, idToken, nonce}].")
    args = ap.parse_args(argv)

    if args.count < 0:
        ap.error("--count 는 0 이상")
    if args.count and not (args.aud and args.out):
        ap.error("--count 를 주면 --aud 와 --out 도 필요하다")

    key = RsaKey.load_or_create(_path(args.key))
    jwk = json.dumps(key.jwk())
    if args.jwk_out:
        with open(_path(args.jwk_out), "w", encoding="utf-8") as f:
            f.write(jwk + "\n")
    else:
        print(jwk)

    if args.count:
        tokens = mint_tokens(
            key, args.count, args.aud, nonce=args.nonce, hours=args.hours
        )
        with open(_path(args.out), "w", encoding="utf-8") as f:
            json.dump(tokens, f, ensure_ascii=False)
        print(
            f"토큰 {len(tokens)} 개 → {args.out} (kid {key.kid}, {args.hours}시간)",
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
