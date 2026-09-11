"""chunked 요청 몸체 시험 (MZ2AZ-320).

**이 자리는 에이전트만 따로 시험하면 절대 드러나지 않는다.** `curl` 과 파이썬
`urllib` 은 몸체 길이를 `Content-Length` 로 적어 보내고, scene-api(Java HttpClient)만
`Transfer-Encoding: chunked` 로 보낸다. 그래서 단위 시험도 실기도 전부 초록인데 앱을
붙이면 「어느 작품으로 돌지 받지 못했다」 → 400 이 났다 (정승길, 2026-09-11 실측).

그 실수를 다시 하지 않도록, 여기서는 **소켓에 직접 chunked 를 써서** 보낸다.
`urllib` 을 쓰면 이 시험이 시험 구실을 못 한다.
"""

from __future__ import annotations

import json
import socket
import threading
import unittest
from http.server import ThreadingHTTPServer

from web.server import Desk, Handler

from tests.fixtures import seoul_incheon_book


def chunked(payload: bytes, size: int = 7) -> bytes:
    """몸체를 조각내어 chunked 로 감싼다. 조각을 작게 잘라야 이어붙이기가 시험된다."""
    out = bytearray()
    for i in range(0, len(payload), size):
        piece = payload[i : i + size]
        out += f"{len(piece):x}\r\n".encode() + piece + b"\r\n"
    return bytes(out) + b"0\r\n\r\n"


class chunked_몸체(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # 운영 코드와 같은 방식으로 붙인다 (`Handler.desk`). 시험용 하위 클래스를
        # 두어 다른 시험의 Handler 를 건드리지 않는다.
        class TestHandler(Handler):
            desk = Desk(seoul_incheon_book(), "csv(시험)", {})

        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), TestHandler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def send(self, body: dict, *, use_chunked: bool) -> tuple[int, dict]:
        payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
        if use_chunked:
            head = (
                "POST /plan HTTP/1.1\r\n"
                f"Host: 127.0.0.1:{self.port}\r\n"
                "Content-Type: application/json\r\n"
                "Transfer-Encoding: chunked\r\n"
                "Connection: close\r\n\r\n"
            ).encode()
            wire = head + chunked(payload)
        else:
            head = (
                "POST /plan HTTP/1.1\r\n"
                f"Host: 127.0.0.1:{self.port}\r\n"
                "Content-Type: application/json\r\n"
                f"Content-Length: {len(payload)}\r\n"
                "Connection: close\r\n\r\n"
            ).encode()
            wire = head + payload

        with socket.create_connection(("127.0.0.1", self.port), timeout=20) as s:
            s.sendall(wire)
            buf = b""
            while True:
                part = s.recv(65536)
                if not part:
                    break
                buf += part

        head_raw, _, rest = buf.partition(b"\r\n\r\n")
        status = int(head_raw.split(b" ")[1])
        return status, json.loads(rest.decode("utf-8") or "{}")

    def test_chunked_로_보낸_일정_요청이_통한다(self):
        """scene-api 가 보내는 모양 그대로. 이것이 실패하면 앱에서 400 이 난다."""
        status, body = self.send({"titles": ["도깨비"], "days": 2}, use_chunked=True)
        self.assertEqual(status, 200, body)
        self.assertEqual(len(body["plan"]["days"]), 2)

    def test_Content_Length_도_그대로_통한다(self):
        """curl·urllib 이 쓰는 길. 고치면서 이쪽을 깨뜨리지 않았는지 본다."""
        status, body = self.send({"titles": ["도깨비"], "days": 1}, use_chunked=False)
        self.assertEqual(status, 200, body)
        self.assertEqual(len(body["plan"]["days"]), 1)

    def test_chunked_빈_몸체는_계약_오류로_거절한다(self):
        status, body = self.send({}, use_chunked=True)
        self.assertEqual(status, 400)
        self.assertEqual(body["code"], "INVALID_PARAMETER")

    def test_조각이_여럿이어도_이어붙인다(self):
        """조각 하나만 읽고 멈추면 JSON 이 깨져 400 이 된다."""
        big = {"titles": ["도깨비"], "days": 2, "avoid": ["x" * 200]}
        status, body = self.send(big, use_chunked=True)
        self.assertEqual(status, 200, body)


if __name__ == "__main__":
    unittest.main()
