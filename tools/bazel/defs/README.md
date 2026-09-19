# tools/bazel/defs

재사용하는 Starlark 매크로. 각 `.bzl` 파일은 docstring 으로 자기 매크로를 설명한다.

`mobile_api_config.bzl`은 iOS·Android의 API 주소를 검증하고 Swift·Kotlin 설정
파일을 만든다. 빈 설정만 로컬 기본값을 사용한다. 원격 주소는 HTTPS·DNS 이름·
`/v1` 경로로 제한하며 셸을 거치지 않는다. `just test //tools/bazel/defs:unit_test`로
주소 검증과 플랫폼별 생성 결과를 확인한다.

저장소가 커지면서 여기로 올 만한 것들: 표준 이름·태그를 갖춘
라이브러리 + 바이너리 + 이미지 + 테스트를 한 번에 묶는 서비스 매크로, proto 번들 매크로,
린트 애스펙트.
