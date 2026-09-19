# SceneTrip 교육 자료

현재 네이티브 앱·Java API·Python 에이전트·PostgreSQL 구조를 기준으로
로컬 kind 실습에서 AWS DEV·PRD 배포까지 학습한다. 기준일: 2026-09-20.

| 자료 | 용도 | 권장 시간 |
| --- | --- | --- |
| [AWS 상세 강의](aws-eks-course.html) | 정의·필요성·비용·대안·배포·복구 자습 | 강의 100분 + 실습 80분 |
| [AWS 발표 자료](aws-eks-presentation.html) | 장별 핵심과 강사 노트 | 약 90분 |
| [로컬 Kubernetes·관측성 상세 실습](k8s-observability-course.html) | 현재 명령·포트·DB·SigNoz 실습 | 약 2~3시간 |
| [로컬 발표 자료](k8s-observability-class.html) | 장별 핵심·강사 노트·상세 실습 링크 | 실습 진행용 |
| [소스 기반 구성도](diagrams/README.md) | DEV·PRD 형상, HCL 참조, 출처 해시 | 구현 대조용 |

```bash
just education-course    # AWS 상세 강의
just education-slides    # AWS 발표 자료
just slides              # 로컬 Kubernetes·관측성 발표
```

## 과정의 범위

VPC·서브넷·라우팅·IGW·NAT·Security Group, IAM·OIDC·STS, S3 state와 잠금,
EKS Auto Mode·EC2·ECR·ALB·ACM·Route 53, RDS PostgreSQL·PostGIS·pg_trgm,
Secrets Manager·KMS, CloudWatch와 SigNoz를 정의하고 필요성·비용 요인·대안으로 연결한다.
고정 가격이나 무중단 운영을 약속하지 않는다.

AWS 과정은 35장이다. ALB 선택과 NLB 비교, Auto Mode Ingress의 생성 관계,
nginx를 유지하는 이유와 X-Forwarded-For 신뢰 경계를 별도 장으로 다룬다.
ALB는 HTTPS를 종료하고 gateway Pod IP로 전달한다. ClusterIP Service는 Ingress의
논리적 대상 참조이며, ALB 이후 내부 연결은 HTTP다.

DEV는 2 AZ·NAT 1개·Single-AZ DB, PRD는 3 AZ·AZ별 NAT·Multi-AZ DB다.
trip-guide의 메모리 세션은 두 환경 모두 단일 replica·재시작 시 소실 제약이 있다.
모바일 앱은 EKS에 배포하지 않는다. 설치 UUID는 인증 수단이 아니므로 초기 원격 환경은
허용 CIDR 제한과 등록 판정을 유지한다.

- [AWS DEV·PRD 아키텍처](../architecture/aws-dev-prd.md)
- [AWS 서비스 정의·필요성·대안](../architecture/aws-services.md)
- [AWS 배포 운영 절차](../ops/aws-deployment.md)
- [적용 계획과 원본 PR 범위](../project/plans/aws-dev-prd-port.md)
- [ALB 전환 계획](../project/plans/alb-deployment.md)

## 탐색과 배포 형식

발표 HTML에는 CSS와 JavaScript가 내장되어 네트워크 없이 열린다. 이전·다음 버튼,
←/→·PageUp/PageDown·Space/Shift+Space·Home/End, 선택 상자, 목차, 장별 hash 링크,
강사 노트, 읽기 모드, 전체 화면, 인쇄를 지원한다. 입력창·버튼·링크 조작에는 발표
단축키를 적용하지 않는다. 전체 화면 미지원 브라우저는 버튼을 비활성화한다.
JavaScript가 꺼져 있어도 전체 슬라이드를 읽을 수 있다. 인쇄는 숨겨진 슬라이드도 포함한다.

## 수정과 검증

HTML을 직접 수정하지 않고 [생성기](../../tools/education/README.md)의 본문·스타일·탐색
정본을 수정한다. 생성된 문서는 리뷰와 오프라인 배포를 위해 커밋한다.

```bash
just test //tools/education:unit_test
just education-generate
just education-check
```

`education-check`는 구현 근거 링크·환경 입력·소스 해시·생성물의 변경 누락을 확인한다.
실제 AWS 생성이나 클라우드 연결 성공을 증명하지 않는다. 발표 전 브라우저에서
글자 잘림·탐색·인쇄도 확인한다.

원본 [TripPilot #645](https://github.com/ASM-TripPilot/trippilot/pull/645)의 교육 전달 방식과
탐색을 가져오고 SceneTrip의 제품·환경·명령·실패 사례로 내용을 다시 작성했다.
중복 요청된 #579는 한 번 적용했으며 TripPilot의 Redis·임베딩 서버 형상은 복제하지 않았다.
