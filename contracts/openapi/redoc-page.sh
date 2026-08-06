#!/usr/bin/env bash
# 명세와 Redoc 번들을 합쳐 **혼자 열리는 HTML 한 장**을 만든다.
# 빌드가 부른다 (contracts/openapi/BUILD.bazel 의 :scene_api_html). 사람이 직접 부르지 않는다.
#
#   $1  Redoc 번들 자바스크립트
#   $2  정규화된 openapi.json
#   $3  써 넣을 HTML 경로
#
# 왜 파일 하나로 합치는가:
#
# Redoc 은 보통 명세를 URL 로 불러오는데, file:// 로 열면 브라우저가 그 요청을 막는다
# (CORS). 그러면 문서가 빈 화면이 된다. 명세를 페이지 안에 박아 넣으면 서버도, 네트워크도
# 필요 없다 — 파일 하나만 남에게 보내도 그대로 열린다.
set -euo pipefail

REDOC_JS="$1"
SPEC_JSON="$2"
OUT="$3"

{
  cat <<'HEAD'
<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SceneTrip scene-api v1</title>
<style>
  body { margin: 0; padding: 0; }
</style>
</head>
<body>
<div id="redoc"></div>
<script>
HEAD

  # Redoc 번들. 파일 안에 그대로 넣어야 네트워크 없이 돈다.
  cat "$REDOC_JS"

  cat <<'MID'
</script>
<script>
Redoc.init(
MID

  # 명세를 자바스크립트 값으로 박아 넣는다.
  #
  # '<' 를 < 로 바꾸는 이유: 설명 글에 '</script' 가 들어가면 브라우저가 거기서
  # 스크립트가 끝났다고 보고 페이지가 깨진다. JSON 문자열 안에서 < 는 '<' 와 같은
  # 뜻이고, JSON 문법 자체는 '<' 를 쓰지 않으므로 전부 바꿔도 안전하다.
  #
  # 지금 명세에는 '<' 가 없지만, 나중에 누가 설명에 HTML 태그를 적는 순간 조용히 깨진다.
  # 그때 원인을 찾기는 매우 어렵다 — 지금 막아 둔다.
  sed 's/</\\u003c/g' "$SPEC_JSON"

  cat <<'TAIL'
, {
  // 한국어 본문이라 글꼴을 지정한다. Redoc 기본값은 Montserrat·Roboto 인데 둘 다
  // 웹폰트라 네트워크 없이는 받지 못하고, 한글은 어차피 없다. 운영체제가 이미 가진
  // 글꼴로 지정하면 받을 것이 없다.
  theme: {
    typography: {
      fontFamily: '-apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", "Noto Sans KR", "Malgun Gothic", sans-serif',
      headings: {
        fontFamily: '-apple-system, BlinkMacSystemFont, "Apple SD Gothic Neo", "Noto Sans KR", "Malgun Gothic", sans-serif'
      },
      code: {
        fontFamily: 'ui-monospace, SFMono-Regular, Menlo, Consolas, monospace'
      }
    }
  },
  // 200 응답은 펼쳐 둔다. 가장 많이 보는 것이고, 접혀 있으면 엔드포인트마다 한 번씩
  // 눌러야 한다.
  expandResponses: '200',
  // 예시 JSON 을 세 단계까지 펼친다. items 안의 객체까지 보인다.
  jsonSampleExpandLevel: 3,
  // 명세에 적은 순서를 유지한다. 알파벳순으로 섞으면 화면 흐름(검색 → 목록 → 상세 →
  // 장바구니)과 어긋난다.
  sortTagsAlphabetically: false,
  sortOperationsAlphabetically: false
}, document.getElementById('redoc'));
</script>
</body>
</html>
TAIL
} >"$OUT"
