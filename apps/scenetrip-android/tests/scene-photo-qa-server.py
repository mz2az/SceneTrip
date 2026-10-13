"""실제 데이터와 분리한 씬 확대 시험용 PNG 서버."""

import json
import struct
import zlib
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


def test_image():
    colors = ((225, 45, 45), (40, 170, 90), (40, 90, 225))
    rows = b"".join(
        bytes([0]) + b"".join(bytes(colors[x // 300]) for x in range(WIDTH))
        for _ in range(HEIGHT)
    )
    return (
        bytes.fromhex("89504e470d0a1a0a")
        + chunk(b"IHDR", struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(rows))
        + chunk(b"IEND", b"")
    )


IMAGE = test_image()


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/photo.png":
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
