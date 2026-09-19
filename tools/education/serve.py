"""저장소 docs만 localhost에 제공하는 강의 미리보기 서버."""

import argparse
import os
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    if not 1024 <= args.port <= 65535:
        parser.error("포트는 1024~65535 사이여야 합니다")
    directory = Path(os.environ["BUILD_WORKSPACE_DIRECTORY"]) / "docs"
    handler = partial(SimpleHTTPRequestHandler, directory=str(directory))
    print(f"문서 미리보기: http://127.0.0.1:{args.port}/education/", flush=True)
    with ThreadingHTTPServer(("127.0.0.1", args.port), handler) as server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            print("문서 미리보기를 종료했습니다")


if __name__ == "__main__":
    main()
