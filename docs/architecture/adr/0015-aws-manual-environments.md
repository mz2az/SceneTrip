---
number: 0015
title: DEV·PRD는 SceneTrip 배포 단위를 Bazel 이미지와 수동 AWS 배포로 연결한다
status: accepted
date: 2026-09-19
amends: [0003, 0013]
amended-by: [0016]
---

# ADR 0015: DEV·PRD 수동 AWS 배포

> 외부 진입점은 [ADR 0016](0016-alb-http-ingress.md)에서 ALB로 확정했다.
> 이 문서의 환경 격리·수동 배포·DB 권한 결정은 유지한다.

## 배경

TripPilot PR #579·#645의 배포 체계와 교육자료를 SceneTrip에 적용한다.
SceneTrip은 네이티브 앱·Spring API·Python agent·PostGIS 구조이므로 TripPilot의
웹 프런트엔드·Redis·임베딩 구성을 복사할 수 없다. 원격 인프라는 미구현이고
Python과 OCI Bazel 타깃도 비어 있었다.

## 결정

- EKS Auto Mode, RDS PostgreSQL 17, ECR, Secrets Manager를 사용한다.
  DEV·PRD는 동일 선언의 환경 값으로 나눈다. 자세한 형상은
  [배포 아키텍처](../aws-dev-prd.md)를 정본으로 삼는다.
- cloud 배포는 `workflow_dispatch`와 확인 레시피에서만 실행한다. PR·push·merge는
  AWS 배포를 시작하지 않는다. 향후 자동 승격은 운영 검증 후 별도 결정한다.
- Python과 OCI 의존성을 활성화하고 서비스뿐 아니라 **서버로 운영되는 agent**에도
  `:image`·`:push`를 허용한다. 모바일 앱에는 계속 허용하지 않는다.
- 배포 대상 이미지는 Bazel에서 생성하고 commit SHA와 digest로 식별한다.
  DB 마이그레이션은 API와 같은 소스의 독립 이미지·Job으로 실행한다.
- RDS master, migration, runtime 계정을 분리한다. 비밀값은 Terraform state와
  이미지에 넣지 않는다. DB의 공간 확장·한글 검색 로케일을 검증한다.
- 초기 진입점은 CIDR로 제한한다. 설치 UUID는 로그인 인증이 아니므로 현재 API를
  일반에 무제한 공개하지 않는다. 운영 공개에는 인증·사용자 권한 검증이 선행한다.
- 에이전트는 메모리 대화 상태 때문에 1 replica로 배포한다. 무중단·세션 복구를
  제공한다고 설명하지 않는다. 공용 세션 저장소는 필요할 때 별도 결정한다.
- 앱 주소는 빌드 입력으로 설정한다. 클라우드 주소는 HTTPS `/v1`만 허용하고
  잘못된 주소는 빌드 단계에서 거부한다. 키를 앱에 넣지 않는다.

## 대안과 비용

| 대안 | 채택하지 않은 이유 |
| --- | --- |
| TripPilot 파일 그대로 복사 | 배포 단위·DB 확장·빌드 시스템이 다르고 작동하지 않음 |
| 단일 EC2/Docker Compose | 초기 비용은 낮을 수 있으나 현재 Kubernetes 운영 흐름과 다름 |
| 모든 클라우드 작업을 Terraform 하나로 | 최초 state/OIDC 신뢰를 만드는 순환과 비밀값 state 노출 위험 |
| PRD도 매 push 자동 배포 | 아직 공개 인증·실환경 복원 검증이 없고 비용·DB 변경 검토 필요 |
| agent replica를 PRD에서 2로 증가 | 공유 세션 없이 대화가 요청마다 갈라짐 |
| 현재 설치 UUID를 JWT로 감싸기만 함 | 로그인·신원 검증을 새로 만들지 않으므로 인증 문제가 해결되지 않음 |

EKS·NAT·RDS·로드밸런서는 유휴 상태에서도 비용이 발생한다. PRD 다중 AZ는 비용과
장애 격리를 교환한다. 별도 GitHub 배포 runner와 환경 보호 설정도 운영 책임이다.

## 기존 결정과의 관계

ADR 0003의 Bazel 기반 Java 빌드는 유지하며, 후속으로 남겼던 OCI 이미지를 구현한다.
ADR 0013의 Python 책임 경계는 유지하며, 당시 아직 없던 컨테이너·Bazel 진입점을
추가한다. 기존 ADR 본문을 현재 사실인 것처럼 재작성하지 않고 보정 링크를 남긴다.

## 검증

입력·권한·DB Job·배포 실패 순서·agent HTTP 경계·모바일 URL 검증은 Bazel 테스트로,
Terraform은 fmt·validate·mock provider 테스트로 확인한다. 교육자료는 소스 해시·링크·
탐색·브라우저로 확인한다. 실제 AWS plan/apply, DNS/TLS, 네트워크 차단과 DB 복원은
계정 입력이 마련된 뒤 [운영 가이드](../../ops/aws-deployment.md) 절차로 확인한다.
