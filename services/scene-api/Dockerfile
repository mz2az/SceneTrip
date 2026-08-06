# scene-api 컨테이너 이미지.
#
# **이 Dockerfile 은 빌드하지 않는다.** Bazel 이 만든 실행 가능 jar 를 담기만 한다
# (ADR 0003). 그래서 빌드 단계가 없고, 최종 이미지에 JDK·Gradle 이 들어가지 않는다 —
# 티켓이 요구한 "빌드 도구를 최종 이미지에 남기지 않는다" 를 멀티스테이지보다 더
# 확실하게 달성한다.
#
# 빌드 컨텍스트는 tools/scripts/image-build.sh 가 임시 디렉터리에 만든다. Bazel
# 산출물은 bazel-bin/ 아래에 있는데 그것이 워크스페이스 밖을 가리키는 심볼릭 링크라,
# 모듈 폴더를 컨텍스트로 쓰면 도커가 그 파일에 닿지 못한다.

FROM eclipse-temurin:21-jre

# 비-root 로 실행한다. 컨테이너가 뚫렸을 때 노드에서 할 수 있는 일을 줄인다.
# UID 를 숫자로 고정해 두면 Deployment 의 runAsUser 와 맞추기 쉽다.
RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --chown=app:app bin.jar /app/app.jar

# OpenTelemetry 자바 에이전트 (ADR 0004).
#
# 여기서 받지 않는다 — image-build.sh 가 Bazel 로 받아 컨텍스트에 넣어 준 것을 담기만
# 한다. 버전과 sha256 은 MODULE.bazel 한 곳이 정본이다.
COPY --chown=app:app opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar

USER 10001:10001
EXPOSE 8080

# exec 형식이라 java 가 PID 1 이 된다. 쉘을 거치면 SIGTERM 이 java 에 전달되지 않아
# application.yaml 의 graceful shutdown 이 동작하지 않고 파드가 강제 종료된다.
# 에이전트를 ENTRYPOINT 가 아니라 JAVA_TOOL_OPTIONS 로 붙이지 않는 이유: 그 환경변수는
# ConfigMap 이 이미 힙 설정에 쓰고 있어, 한쪽을 고치면 다른 쪽이 지워진다. 에이전트는
# 이미지의 성질이므로 이미지가 들고 있는 편이 맞다.
#
# 끄는 방법은 OTEL_SDK_DISABLED=true 다 — 에이전트는 붙지만 아무것도 내보내지 않는다.
# 수집기가 없는 환경에서 앱이 접속 실패로 시끄러워지는 것을 막는다.
ENTRYPOINT ["java", "-javaagent:/app/opentelemetry-javaagent.jar", "-jar", "/app/app.jar"]
