"""구글 ID 토큰 발급 CLI(mint_google_tokens) 단위 테스트 — 명세만 보고 쓴 테스트다.

CLI 가 만든 토큰이 가짜 서버(fake_external)의 /google/jwks 공개키로 RS256 검증되는지,
클레임(iss·aud·nonce·sub·email·exp)이 맞는지, 키 파일이 다시 쓰이는지 확인한다.
RS256 검증은 표준 라이브러리만으로 직접 한다(s^e mod n 을 EMSA-PKCS1-v1_5 인코딩과 비교).
"""

import base64
import hashlib
import json
import os
import tempfile
import unittest
import urllib.request

import fake_external
import mint_google_tokens

AUD = "test-client.apps.googleusercontent.com"
SHA256_DIGEST_INFO = bytes.fromhex("3031300d060960864801650304020105000420")


# ---------------------------------------------------------------- 도우미


def b64url_decode(s):
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def b64url_int(s):
    return int.from_bytes(b64url_decode(s), "big")


def split_jwt(token):
    header_b64, payload_b64, sig_b64 = token.split(".")
    header = json.loads(b64url_decode(header_b64))
    claims = json.loads(b64url_decode(payload_b64))
    return (
        header,
        claims,
        f"{header_b64}.{payload_b64}".encode("ascii"),
        b64url_decode(sig_b64),
    )


def rs256_verify(signing_input, signature, jwk):
    """RS256 서명 검증 — 맞으면 True."""
    n = b64url_int(jwk["n"])
    e = b64url_int(jwk["e"])
    k = (n.bit_length() + 7) // 8
    if len(signature) != k:
        return False
    m = pow(int.from_bytes(signature, "big"), e, n).to_bytes(k, "big")
    t = SHA256_DIGEST_INFO + hashlib.sha256(signing_input).digest()
    expected = b"\x00\x01" + b"\xff" * (k - len(t) - 3) + b"\x00" + t
    return m == expected


class MintCase(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.dir = tmp.name
        self.key = os.path.join(self.dir, "key.json")
        self.jwk_out = os.path.join(self.dir, "jwk.json")
        self.out = os.path.join(self.dir, "tokens.json")

    def mint(self, count=3, nonce=None, hours=None, jwk_out=None, out=None):
        argv = [
            "--key", self.key,
            "--jwk-out", jwk_out or self.jwk_out,
            "--count", str(count),
            "--aud", AUD,
            "--out", out or self.out,
        ]  # fmt: skip
        if nonce is not None:
            argv += ["--nonce", nonce]
        if hours is not None:
            argv += ["--hours", str(hours)]
        self.assertEqual(mint_google_tokens.main(argv), 0)
        with open(out or self.out, encoding="utf-8") as f:
            tokens = json.load(f)
        with open(jwk_out or self.jwk_out, encoding="utf-8") as f:
            jwk = json.load(f)
        return tokens, jwk


# ---------------------------------------------------------------- CLI 단독


class MintTokensTest(MintCase):
    def test_tokens_file_shape_and_claims(self):
        tokens, jwk = self.mint(count=5, nonce="fixed-nonce", hours=2)
        self.assertIsInstance(tokens, list)
        self.assertEqual(len(tokens), 5)
        self.assertEqual(jwk["kty"], "RSA")
        subs, emails = set(), set()
        for entry in tokens:
            self.assertIsInstance(entry["sub"], str)
            self.assertIsInstance(entry["idToken"], str)
            self.assertEqual(entry["nonce"], "fixed-nonce")
            header, claims, _, _ = split_jwt(entry["idToken"])
            self.assertEqual(header["alg"], "RS256")
            self.assertEqual(header["kid"], jwk["kid"])
            self.assertEqual(claims["iss"], "https://accounts.google.com")
            self.assertEqual(claims["aud"], AUD)
            self.assertEqual(claims["sub"], entry["sub"])
            self.assertEqual(claims["nonce"], "fixed-nonce")
            self.assertIs(claims["email_verified"], True)
            self.assertIsInstance(claims["email"], str)
            self.assertIsInstance(claims["iat"], int)
            self.assertAlmostEqual(claims["exp"] - claims["iat"], 2 * 3600, delta=60)
            subs.add(claims["sub"])
            emails.add(claims["email"])
        self.assertEqual(len(subs), 5, "sub 가 겹친다")
        self.assertEqual(len(emails), 5, "email 이 겹친다")

    def test_default_validity_is_24_hours(self):
        tokens, _ = self.mint(count=1)
        _, claims, _, _ = split_jwt(tokens[0]["idToken"])
        self.assertAlmostEqual(claims["exp"] - claims["iat"], 24 * 3600, delta=60)

    def test_without_nonce_each_token_has_its_own(self):
        tokens, _ = self.mint(count=4)
        nonces = []
        for entry in tokens:
            _, claims, _, _ = split_jwt(entry["idToken"])
            self.assertIsInstance(entry["nonce"], str)
            self.assertTrue(entry["nonce"])
            self.assertEqual(claims["nonce"], entry["nonce"])
            nonces.append(entry["nonce"])
        self.assertEqual(len(set(nonces)), 4, f"nonce 가 겹친다: {nonces}")

    def test_signature_verifies_against_own_jwk(self):
        tokens, jwk = self.mint(count=2)
        for entry in tokens:
            _, _, signing_input, sig = split_jwt(entry["idToken"])
            self.assertTrue(rs256_verify(signing_input, sig, jwk))

    def test_tampered_payload_fails_verification(self):
        # 검증 도우미가 아무 것이나 통과시키지 않는지 확인
        tokens, jwk = self.mint(count=1)
        _, _, signing_input, sig = split_jwt(tokens[0]["idToken"])
        self.assertFalse(rs256_verify(signing_input + b"x", sig, jwk))

    def test_key_is_persisted_and_reused(self):
        _, jwk1 = self.mint(count=1)
        self.assertTrue(os.path.exists(self.key))
        jwk2_path = os.path.join(self.dir, "jwk2.json")
        out2 = os.path.join(self.dir, "tokens2.json")
        tokens2, jwk2 = self.mint(count=1, jwk_out=jwk2_path, out=out2)
        self.assertEqual(jwk1["n"], jwk2["n"])
        self.assertEqual(jwk1["e"], jwk2["e"])
        self.assertEqual(jwk1["kid"], jwk2["kid"])
        # 두 번째 실행의 토큰도 첫 번째 공개키로 검증된다
        _, _, signing_input, sig = split_jwt(tokens2[0]["idToken"])
        self.assertTrue(rs256_verify(signing_input, sig, jwk1))

    def test_generated_key_is_2048_bit(self):
        _, jwk = self.mint(count=1)
        self.assertEqual(b64url_int(jwk["n"]).bit_length(), 2048)


# ---------------------------------------------------------------- CLI + 가짜 서버 JWKS


class MintWithServerJwksTest(MintCase):
    def test_minted_token_verifies_against_served_jwks(self):
        tokens, jwk = self.mint(count=3, nonce="n-1", hours=1)
        with open(self.jwk_out, encoding="utf-8") as f:
            jwk_json = f.read()
        env = {"FAKE_LATENCY_SCALE": "0", "FAKE_GOOGLE_JWK_JSON": jwk_json}
        handle = fake_external.run_in_thread(fake_external.Config.from_env(env))
        self.addCleanup(handle.stop)

        url = f"http://127.0.0.1:{handle.port}/google/jwks"
        with urllib.request.urlopen(url, timeout=10) as resp:
            self.assertEqual(resp.status, 200)
            served = json.load(resp)

        by_kid = {k["kid"]: k for k in served["keys"]}
        self.assertIn(jwk["kid"], by_kid)
        served_jwk = by_kid[jwk["kid"]]
        self.assertEqual(served_jwk["kty"], "RSA")
        self.assertEqual(served_jwk["n"], jwk["n"])

        subs = set()
        for entry in tokens:
            header, claims, signing_input, sig = split_jwt(entry["idToken"])
            self.assertTrue(rs256_verify(signing_input, sig, by_kid[header["kid"]]))
            self.assertEqual(claims["iss"], "https://accounts.google.com")
            self.assertEqual(claims["aud"], AUD)
            self.assertEqual(claims["nonce"], "n-1")
            self.assertIs(claims["email_verified"], True)
            self.assertAlmostEqual(claims["exp"] - claims["iat"], 3600, delta=60)
            subs.add(claims["sub"])
        self.assertEqual(len(subs), 3)


if __name__ == "__main__":
    unittest.main()
