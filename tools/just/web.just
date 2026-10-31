# 웹앱(apps/scenetrip-web) 명령. 루트 justfile 이 import 한다. 모듈 설명: apps/scenetrip-web/README.md, ADR 0028.
#
# 빌드·시험·린트는 Bazel 이 한다(호스트 Node·npm 을 쓰지 않는다). 개발 서버만 예외다 — 아래 web-dev 참고.

WEB_TARGET := "//apps/scenetrip-web"

# 프로덕션 번들을 만든다. 산출물은 bazel-bin/apps/scenetrip-web/dist (Cloudflare Pages 가 올리는 것과 같은 구조: index.html·assets/·_headers·_redirects)
[group('web')]
web-build:
    {{BAZEL}} build {{WEB_TARGET}}:web
    @echo "웹 번들: bazel-bin/apps/scenetrip-web/dist"

# 웹 단위 시험(vitest). 인자로 타깃을 바꿀 수 있다
[group('web')]
web-test *targets="//apps/scenetrip-web:unit_test":
    ./tools/scripts/bazel-test.sh {{targets}} --test_output=all

# 웹 린트(eslint)와 타입 검사(tsc)
[group('web')]
web-lint:
    {{BAZEL}} test {{WEB_TARGET}}:lint {{WEB_TARGET}}:typecheck

# 웹 생성물(API 타입·영어 문구표·해태 그림)을 만든다. 커밋하지 않는 파일이라 번들·시험이 알아서 먼저 만든다 — 내용을 보고 싶을 때만
[group('web')]
web-gen:
    {{BAZEL}} build {{WEB_TARGET}}:gen

# **Bazel 밖에서 도는 유일한 웹 명령이다.** 개발 서버는 소스 트리를 직접 감시해 저장하는 즉시 화면을 바꿔야(HMR) 하는데,
# Bazel 은 소스를 bin 폴더에 복사해 거기서 돌리므로 그 계약과 맞지 않는다. 그래서 이것은 산출물의 정본이 아니라 개발 편의다 —
# 배포되는 번들·시험·린트의 정본은 web-build·web-test·web-lint 다. 호스트에 Node 22.12 이상이 필요하다(vite 8 의 요구).
#
# 웹 개발 서버(http://localhost:5173) — DEV API 를 /v1 로 대신 전달하고 GET 응답을 10분 캐시한다. 호스트 Node·npm 사용(Bazel 밖)
[group('web')]
web-dev:
    #!/usr/bin/env bash
    set -euo pipefail
    cd apps/scenetrip-web
    [ -d node_modules ] || npm ci
    exec npm run dev
