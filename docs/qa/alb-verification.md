# ALB 전환 검증 기록

대상: 2026-09-20 ALB 후속 변경. [계획](../project/plans/alb-deployment.md),
[ADR 0016](../architecture/adr/0016-alb-http-ingress.md)을 따른다.
실제 AWS 리소스를 변경하거나 배포한 기록이 아니다.

## 검증 범위

DEV·PRD Terraform 보안 그룹, Auto Mode Ingress와 gateway ClusterIP, 전달 헤더 신뢰,
배포 준비 대기·실패 처리, 아키텍처와 운영 문서, 교육 원본·HTML·그림 출처를 확인한다.

## 완료한 검사

| 검사 | 결과 |
| --- | --- |
| `just check-awake` | 내부의 `just check` 통과: 포맷·린트·99개 빌드 타깃·12개 테스트 타깃 |
| `just aws-check` | AWS·도구·Terraform 검사 4개 타깃과 DEV·PRD 실제 Helm 렌더 통과 |
| AWS 실행기 단위 시험 | 47개 통과. 잘못된 ALB 타입·SG·VPC·subnet, 시간 초과, TLS 실패, 읽기 전용 verify 경계 포함 |
| Terraform | 정적 경계 11개, 고정 provider의 mock 실행 7개 통과. AWS 계정 접근 없음 |
| `just aws-test-integration` | PostgreSQL·nginx Docker 통합 2/2 통과 |
| 교육 단위 시험 | 21개 통과. ALB 선언·ClusterIP·출처 변경·35장 구성 검사 |
| `just education-check`·`just education-graph-check` | 최종 포맷 후 재생성한 HTML·SVG·출처 및 실제 Terraform DOT와 소스 일치 |
| 실제 브라우저 탐색 | AWS·로컬 발표 각각 23개 동작 검사 통과 |
| 실제 브라우저 화면 | HTML 4개 모두 390px·1440px 가로 넘침 없음. 새 ALB 설명 장과 PRD SVG 시각 확인 |
| 독립 코드·보안 리뷰 | 조치가 필요한 CRITICAL/HIGH/MEDIUM 발견 없음 |

nginx 시험은 고정 이미지의 실제 relay→gateway→backend 연결을 쓴다. relay가 TCP 접속자의
주소를 XFF 끝에 추가하며, 승인 클라이언트 200·비승인 403·직접 gateway 접근 403을 확인했다.
위조한 전달 헤더 제거, 인코딩 경로 우회 차단, XFF 값을 80번 바꿔도 실제 IP 기준 429,
다른 승인 IP의 200을 확인했다. 이는 Pod별 요청 제한이며 전역 사용자 할당량 시험은 아니다.

`aws-verify`와 Actions `verify`는 apply·이미지 push·DB migration·Helm·정리 작업을 호출하지
않는다는 회귀 검사를 포함한다. 배포 후 실행하는 NetworkPolicy 차단 Job을 재실행하지 않는다.

## 커버리지

Bazel이 생성한 실제 LCOV의 실행 행 수를 확인했다. 저장소의 `coverage-report.sh`는
아직 안내용이므로 이 수치가 그 스크립트의 자동 기준 강제 결과라는 뜻은 아니다.

| 범위 | 실행 행 커버리지 |
| --- | --- |
| `tools/aws/alb.py` | 74/74, 100% |
| AWS 실행기 4개 파일 합계 | 460/501, 91.8% |
| 교육 생성기 합계 | 304/306, 99.3% |

검증은 로컬 SDK가 준비된 macOS에서 수행했다. 전체 게이트에는 기존 코드의 비차단
스타일 경고와 외부 빌드 규칙의 폐기 예정 기능 안내가 남지만 종료 상태는 0이다.

## 실환경에서 확인할 것

계정·리전·ACM·DNS·허용 CIDR·GitHub 환경과 runner·Secret 입력을 준비한 뒤
[운영 절차](../ops/aws-deployment.md)로 ALB 생성·보안 그룹·target health·TLS·
NetworkPolicy를 검증한다. 로컬 nginx relay는 ALB의 헤더 전달을 재현하지만
AWS 컨트롤러·IAM·실제 네트워크 적용을 대신 검증하지 않는다.
