"""JUnit 5 테스트 규칙.

Bazel 의 `java_test` 는 **JUnit 4 러너**를 기본으로 쓴다. 우리가 쓰는 것은 Spring Boot
가 가져오는 JUnit 5(Jupiter)이므로, 그대로 두면 테스트가 "발견되지 않음" 으로 조용히
0 개 실행되고 초록불이 뜬다. 검사하지 않은 것이 통과로 보고되는, 이 저장소가 여러 번
겪은 그 실패 방식이다.

그래서 러너를 JUnit Platform 콘솔 런처로 바꾼다. Java 모듈이 늘 때마다 같은 설정을
복사하지 않도록 매크로로 묶었다 — AGENTS.md §4 "반복되는 빌드 패턴은 매크로로".

사용법:

    load("//tools/bazel/defs:java_junit5_test.bzl", "java_junit5_test")

    java_junit5_test(
        name = "unit_test",
        srcs = glob(["tests/java/**/*.java"]),
        test_package = "com.mz2az.scenetrip.sceneapi",
        deps = [":scene-api-lib"],
        tags = ["unit"],
    )
"""

load("@rules_java//java:defs.bzl", "java_test")

# 테스트 코드가 컴파일 시점에 보는 것
_JUNIT5_DEPS = [
    "@maven//:org_junit_jupiter_junit_jupiter",
    "@maven//:org_junit_jupiter_junit_jupiter_api",
]

# 실행 시점에만 필요한 것. 엔진과 런처가 여기 없으면 테스트가 0 개 발견된다.
_JUNIT5_RUNTIME_DEPS = [
    "@maven//:org_junit_jupiter_junit_jupiter_engine",
    "@maven//:org_junit_platform_junit_platform_console",
    "@maven//:org_junit_platform_junit_platform_launcher",
    "@maven//:org_junit_platform_junit_platform_reporting",
]

def java_junit5_test(name, srcs, test_package, deps = [], runtime_deps = [], **kwargs):
    """JUnit 5 로 도는 java_test 를 만든다.

    Args:
      name: 타깃 이름. AGENTS.md §4.1 을 따라 보통 `unit_test`.
      srcs: 테스트 소스 파일들.
      test_package: 훑을 자바 패키지. 이 아래의 테스트만 실행한다.
      deps: 컴파일 의존성. 보통 테스트 대상 라이브러리.
      runtime_deps: 실행 시점 의존성.
      **kwargs: java_test 에 그대로 넘긴다 (tags, size, jvm_flags 등).
    """
    java_test(
        name = name,
        srcs = srcs,
        args = [
            "execute",
            "--select-package=" + test_package,
            # 테스트가 하나도 발견되지 않으면 실패시킨다. 이것이 없으면 오타 하나로
            # 테스트 전체가 사라져도 게이트가 초록으로 남는다.
            "--fail-if-no-tests",
            "--disable-banner",
            "--details=flat",
        ],
        main_class = "org.junit.platform.console.ConsoleLauncher",
        use_testrunner = False,
        deps = deps + _JUNIT5_DEPS,
        runtime_deps = runtime_deps + _JUNIT5_RUNTIME_DEPS,
        **kwargs
    )
