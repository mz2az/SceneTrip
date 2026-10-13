"""실제 데이터와 분리한 씬 확대 시험용 PNG 서버."""

import json
import struct
import zlib
from collections import Counter
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

WIDTH = 900
HEIGHT = 450


def chunk(kind, data):
    return (
        struct.pack(">I", len(data))
        + kind
        + data
        + struct.pack(">I", zlib.crc32(kind + data))
    )


def test_image(width=WIDTH, height=HEIGHT):
    colors = ((225, 45, 45), (40, 170, 90), (40, 90, 225))
    rows = b"".join(
        bytes([0]) + b"".join(bytes(colors[x * 3 // width]) for x in range(width))
        for _ in range(height)
    )
    return (
        bytes.fromhex("89504e470d0a1a0a")
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows))
        + chunk(b"IEND", b"")
    )


IMAGE = test_image()
PORTRAIT = test_image(450, 900)
REQUESTS = Counter()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        REQUESTS[self.path] += 1
        if self.path == "/portrait.png":
            data, status, mime = PORTRAIT, 200, "image/png"
        elif self.path == "/photo.png" or (
            self.path.startswith("/flaky.png?case=") and REQUESTS[self.path] > 1
        ):
            data, status, mime = IMAGE, 200, "image/png"
        else:
            data, status, mime = (
                json.dumps({"code": "PHOTO_NOT_FOUND"}).encode(),
                404,
                "application/json",
            )
        self.send_response(status)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *_args):
        pass


if __name__ == "__main__":
    print("씬 사진 시험 PNG 서버: 127.0.0.1:8898", flush=True)
    ThreadingHTTPServer(("127.0.0.1", 8898), Handler).serve_forever()
