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

USER 10001:10001
EXPOSE 8080

# exec 형식이라 java 가 PID 1 이 된다. 쉘을 거치면 SIGTERM 이 java 에 전달되지 않아
# application.yaml 의 graceful shutdown 이 동작하지 않고 파드가 강제 종료된다.
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
