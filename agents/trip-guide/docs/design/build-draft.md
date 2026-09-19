# trip-guide Bazel 타깃 적용 기록

2026-09-19 DEV·PRD 배포 기반 작업에서 초안을 실제 `BUILD.bazel`로 옮겼다.
현재 실행·테스트·패키징의 기준은 [모듈 README](../../README.md)와
[`BUILD.bazel`](../../BUILD.bazel)이다.

## 활성화한 기반

- `MODULE.bazel`의 `rules_python` 2.2.0과 CPython 3.13.13 도구 체인.
- 외부 Python 패키지가 없으므로 pip 확장·빈 requirements 파일은 만들지 않는다.
- `rules_oci` 2.2.6과 digest가 고정된 Distroless Python 3 Debian 13 이미지.
- 소스와 런타임 프롬프트·설정·스키마를 명시적으로 나열한다. 파일을 추가할 때
  `srcs` 또는 `data`를 함께 고친다.
- 런파일에서 심볼릭 링크를 원본 소스로 따라가지 않도록 데이터 경로를 계산한다.
  샌드박스에 선언되지 않은 작업 디렉터리 파일을 읽지 않는다.

## 타깃과 검증

| 타깃 | 목적 |
| --- | --- |
| `:trip-guide` | 공유 Python 라이브러리 |
| `:bin` | CLI (`just agent-run trip-guide`) |
| `:web` | 로컬 브라우저 시연 (`127.0.0.1`) |
| `:server` | EKS 내부 운영 서버 (`0.0.0.0:8899`) |
| `:unit_test` | 알고리즘·도구·HTTP 입력 경계 회귀 |
| `:eval_test` | 외부 모델 없는 5종 지표 평가 |
| `:image` | linux/amd64 비루트 OCI 이미지 |
| `:push` | 수동 배포 레시피에서 ECR 전송 |

`just test //agents/trip-guide:unit_test`, `just agent-eval trip-guide`,
`just build //agents/trip-guide:image`를 사용한다. 배포 서버는 로컬 시연 경로를
노출하지 않고 본문·호출 수·동시 실행·세션 상한을 적용한다. 에이전트의 컨테이너 배포는
[DEV·PRD ADR](../../../../docs/architecture/adr/0015-aws-manual-environments.md)의
내부 워크로드 결정에 따른다.
