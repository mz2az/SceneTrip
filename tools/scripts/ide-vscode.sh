#!/usr/bin/env bash
# VS Code 자바 확장이 이 워크스페이스를 읽을 수 있게 .vscode/settings.json 을 만든다.
# 호출: just ide
#
# 왜 필요한가:
#
# Red Hat 자바 확장(redhat.java)이 프로젝트를 인식하는 경로는 Maven(pom.xml) ·
# Gradle(build.gradle) · Eclipse(.classpath) 셋뿐이다. 이 저장소는 Bazel 로만 빌드하므로
# 셋 다 없고, 확장은 "invisible project" 모드로 떨어져 .java 파일을 낱장으로 취급한다.
# 클래스패스가 비어 있으니 import 를 따라갈 대상이 없고, 정의로 가기(F12·Ctrl+클릭)가
# 조용히 아무 일도 하지 않는다.
#
# 그래서 확장이 이해하는 유일한 대체 수단인 java.project.* 설정을 직접 채워 준다.
# 소스 루트와 jar 목록을 알려 주면 invisible project 모드에서도 클래스패스가 선다.
#
# 왜 손으로 쓰지 않고 생성하는가:
#
# jar 경로에 Bazel 의 output_base 가 들어간다. 그 값은 사용자 이름과 워크스페이스
# 경로의 해시로 만들어져 **기계마다 다르다**. 저장소에 고정 문자열로 커밋하면 그것을
# 쓴 사람 말고는 전부 깨진다. 그래서 `bazel info` 로 물어보고 그때그때 쓴다.
# shellcheck source=tools/scripts/_lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$REPO_ROOT" || die "$REPO_ROOT 로 이동할 수 없습니다"

BAZEL_BIN="${BAZEL:-bazel}"
SETTINGS=".vscode/settings.json"
EXTENSIONS=".vscode/extensions.json"

# 생성물임을 표시하는 문구. 덮어쓰기 전에 이 표식으로 "우리가 만든 파일인가" 를 본다.
MARKER="just ide 가 생성한 파일"

have "$BAZEL_BIN" || die "bazel 을 찾을 수 없습니다 — 'just setup' 을 먼저 실행하세요"

# --- 1. 생성된 소스와 의존성을 실체화한다 ---------------------------------------
#
# 계약에서 만들어지는 인터페이스(PlacesApi 등)는 소스 폴더에 없다. 빌드해야 bazel-bin
# 아래에 생긴다. 빌드하지 않은 상태로 설정만 써 두면 그 경로가 존재하지 않아,
# 컨트롤러의 `implements PlacesApi` 에서 여전히 정의로 갈 수 없다.
#
# Maven jar 도 마찬가지다. 선언만 되어 있고 아직 받지 않은 아티팩트는 output_base 에
# 없다. 빌드가 실제 다운로드를 일으킨다.
# **자바에 필요한 것만 짓는다.** 이 명령이 하는 일은 자바 확장이 읽을 클래스패스를 만드는
# 것뿐이므로, 짓는 범위도 거기까지여야 한다.
#
#   //services/...                            서비스 구현·테스트. @maven 의 자바 의존성을 끌어온다
#   //contracts/openapi:scene_api_spring_lib  계약에서 생성한 자바 인터페이스
#
# 예전에는 `//...` 였다. 그것을 쓴 시점(2026-08-06)에는 apps/ 에 README 뿐이라 곧 "자바
# 전부" 와 같은 뜻이었는데, 사흘 뒤 안드로이드 모듈이 들어오며 뜻이 조용히 넓어졌다.
# 그때부터 ANDROID_HOME 이 없는 기계에서는 aapt2 를 못 찾아 **분석 단계에서** 죽었고,
# 분석 실패는 전체를 멈추므로 .vscode/settings.json 이 아예 써지지 않았다 (MZ2AZ-282).
#
# 자바 코드 탐색과 APK·앱 빌드는 아무 상관이 없다. Swift 도 Kotlin/Android 도 자바 언어
# 서버가 읽지 않는다. 그래서 **Android SDK 도 Xcode 도 없이 돈다** — 백엔드만 만지는
# 사람이나 리눅스에서도 쓸 수 있다.
#
# libs/java 나 새 서비스가 생기면 여기에 한 줄 더한다. `//...` 로 넓게 잡고 예외를 빼는
# 방식이 손은 덜 가지만, 그러면 이 목록이 "무엇이 필요한가" 를 더 이상 말해 주지 못한다 —
# 조용히 넓어지는 것이 애초의 문제였다.
log "생성 소스와 의존성 빌드 (처음에는 몇 분 걸릴 수 있습니다)"
"$BAZEL_BIN" build //services/... //contracts/openapi:scene_api_spring_lib >/dev/null ||
  die "빌드 실패 — 'just build //services/... //contracts/...' 로 원인을 확인하세요"

OUTPUT_BASE="$("$BAZEL_BIN" info output_base)"
[ -d "$OUTPUT_BASE" ] || die "output_base 를 찾을 수 없습니다: $OUTPUT_BASE"

# --- 2. 소스 루트 찾기 ----------------------------------------------------------
#
# 모듈이 늘어나도 이 스크립트를 고치지 않도록 규칙으로 찾는다 (AGENTS.md §2 의 배치 규칙).
#   <module>/src/main/java   구현
#   <module>/tests/java      테스트
#
# bazel-* 는 잘라 낸다. 심볼릭 링크라 그대로 두면 같은 파일을 두 경로로 두 번 읽는다.
source_roots=()
while IFS= read -r dir; do
  [ -n "$dir" ] && source_roots+=("${dir#./}")
done < <(
  find . -type d \( -name 'bazel-*' -o -name '.git' -o -name '.venv' \) -prune \
    -o -type d \( -path '*/src/main/java' -o -path '*/tests/java' \) -print 2>/dev/null | sort
)

# 생성된 소스. 계약에서 만들어져 bazel-bin 아래에 있고, 커밋되지 않는다.
#
# 워크스페이스 상대 경로(bazel-bin/...)로 넣는다. output_base 아래의 절대 경로를 쓰면
# 워크스페이스 바깥이 되어 invisible project 의 소스 루트로 인정되지 않는다.
while IFS= read -r dir; do
  [ -n "$dir" ] && source_roots+=("${dir#./}")
done < <(
  find -L bazel-bin/contracts -type d -path '*/src/main/java' -print 2>/dev/null | sort
)

[ ${#source_roots[@]} -gt 0 ] || die "소스 루트를 하나도 찾지 못했습니다"

# --- 3. jar 모으기 --------------------------------------------------------------
#
# rules_jvm_external 은 아티팩트마다 외부 저장소를 하나씩 만들고 jar 를 그 안의
# file/v1/<그룹>/<이름>/<버전>/ 에 둔다. 해석된 것 전부를 클래스패스에 넣는다 —
# 확장은 어느 모듈이 무엇을 쓰는지 모르므로, 여기서는 Bazel 처럼 정밀하게 나눌 수 없다.
#
# -L 로 심볼릭 링크를 따라간다. output_base 안은 링크가 흔하다.
jars=()
while IFS= read -r jar; do
  [ -n "$jar" ] && jars+=("$jar")
done < <(
  find -L "$OUTPUT_BASE/external" -path '*rules_jvm_external*maven*' \
    -name '*.jar' ! -name '*-sources.jar' ! -name '*-javadoc.jar' -print 2>/dev/null | sort
)

[ ${#jars[@]} -gt 0 ] || die "Maven jar 를 찾지 못했습니다 — 빌드가 실제로 성공했는지 확인하세요"

# --- 4. 기존 파일 보호 ----------------------------------------------------------
mkdir -p .vscode
if [ -f "$SETTINGS" ] && ! grep -q "$MARKER" "$SETTINGS"; then
  cp "$SETTINGS" "$SETTINGS.bak"
  warn "손으로 쓴 $SETTINGS 를 발견해 $SETTINGS.bak 으로 옮겼습니다 — 필요한 설정을 다시 합치세요"
fi

# --- 5. settings.json 쓰기 ------------------------------------------------------
#
# VS Code 의 settings.json 은 JSONC 라 주석을 허용한다. 이 파일을 손으로 고쳐도
# 다음 `just ide` 가 덮어쓴다는 사실을 파일 안에 적어 둔다.
{
  echo "{"
  echo "  // $MARKER. 직접 고치지 말고 \`just ide\` 를 다시 실행하세요."
  echo "  //"
  echo "  // 이 저장소는 Bazel 로 빌드해 pom.xml·build.gradle 이 없다. 자바 확장은 그것들이"
  echo "  // 없으면 클래스패스를 세우지 못하므로, 소스 루트와 jar 를 여기서 직접 알려 준다."
  echo "  // 경로에 든 output_base 는 기계마다 다르다 — 그래서 커밋된 값을 믿지 말고 다시 생성한다."
  echo ""
  echo "  \"java.project.sourcePaths\": ["
  for i in "${!source_roots[@]}"; do
    if [ "$i" -eq $((${#source_roots[@]} - 1)) ]; then
      printf '    "%s"\n' "${source_roots[$i]}"
    else
      printf '    "%s",\n' "${source_roots[$i]}"
    fi
  done
  echo "  ],"
  echo ""
  echo "  // 확장이 컴파일 산출물을 둘 곳. .vscode/* 는 이미 gitignore 대상이다."
  echo "  \"java.project.outputPath\": \".vscode/jdt-output\","
  echo ""
  echo "  // Bazel 이 받아 둔 의존성. sources jar 는 받지 않으므로 라이브러리 내부로 들어가면"
  echo "  // 바이트코드에서 복원한 뼈대만 보인다. 우리 코드와 생성된 인터페이스는 진짜 소스로 열린다."
  echo "  \"java.project.referencedLibraries\": ["
  for i in "${!jars[@]}"; do
    if [ "$i" -eq $((${#jars[@]} - 1)) ]; then
      printf '    "%s"\n' "${jars[$i]}"
    else
      printf '    "%s",\n' "${jars[$i]}"
    fi
  done
  echo "  ],"
  echo ""
  echo "  // Maven·Gradle 을 찾아 나서지 않게 한다. 없는 것을 찾다가 실패하면 확장이"
  echo "  // 프로젝트 자체를 포기하고, 위에 세워 둔 클래스패스까지 같이 버려진다."
  echo "  \"java.import.maven.enabled\": false,"
  echo "  \"java.import.gradle.enabled\": false,"
  echo ""
  echo "  // bazel-* 는 output_base 로 가는 심볼릭 링크다. 감시·검색 대상에 넣으면 빌드 산출물"
  echo "  // 수만 개를 훑는다. 위 sourcePaths 는 명시한 경로라 이 제외에 영향받지 않는다."
  echo "  \"files.watcherExclude\": {"
  echo "    \"**/bazel-bin/**\": true,"
  echo "    \"**/bazel-out/**\": true,"
  echo "    \"**/bazel-testlogs/**\": true,"
  echo "    \"**/bazel-SceneTrip/**\": true"
  echo "  },"
  echo "  \"search.exclude\": {"
  echo "    \"**/bazel-bin/**\": true,"
  echo "    \"**/bazel-out/**\": true,"
  echo "    \"**/bazel-testlogs/**\": true,"
  echo "    \"**/bazel-SceneTrip/**\": true"
  echo "  }"
  echo "}"
} >"$SETTINGS"

# --- 6. 확장 추천 ---------------------------------------------------------------
#
# 이 파일이 있으면 저장소를 처음 연 사람에게 VS Code 가 설치를 권한다.
# .gitignore 가 settings.json 과 함께 이 파일만 예외로 열어 두었다.
cat >"$EXTENSIONS" <<'EOF'
{
  // 자바 확장 팩이 없으면 위 java.project.* 설정을 읽는 주체가 아예 없다.
  "recommendations": [
    "redhat.java",
    "vscjava.vscode-java-debug",
    "vscjava.vscode-java-test"
  ]
}
EOF

log "$SETTINGS 작성 — 소스 루트 ${#source_roots[@]}개, jar ${#jars[@]}개"
for root in "${source_roots[@]}"; do
  printf '      %s\n' "$root"
done
log "VS Code 에서 '개발자: 창 다시 로드'(Cmd+Shift+P) 를 실행하면 적용됩니다"
