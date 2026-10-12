# Bedrock 을 고정 키 대신 IAM 역할로 — trip-guide 의 Pod Identity (MZ2AZ-317)

- **상태**: 계획(2026-10-12, 권호 — 태환님께 알림). 「배포 전 필수」
- **관련**: [prd-launch.md](prd-launch.md) §1 · [bedrock-migration.md](../../../agents/trip-guide/docs/design/bedrock-migration.md) ·
  scene-api 의 사진 버킷 역할(`MediaRole`, review.md §13) · ADR 0013

## 1. 왜

trip-guide 는 Bedrock(OpenAI 호환 주소, us-west-2)을 **IAM 사용자의 영구 액세스 키**로 서명해 부른다
(`model_client.py`, 배포 비밀값 `/scenetrip/<env>/trip-guide` 의 `AWS_ACCESS_KEY_ID`·`AWS_SECRET_ACCESS_KEY`). 새면 누가 지우기 전까지
Bedrock 을 부를 수 있고 비용이 우리 계정에 청구된다. AWS 안에서 도는 워크로드는 **역할의 임시 자격 증명**을 쓴다 — scene-api 가
사진 버킷에 이미 그렇게 한다(EKS Pod Identity).

## 2. 실측 (2026-10-12, 읽기만)

| 대상 | 지금 |
| --- | --- |
| 서명 | `sigv4_headers` 가 직접 서명한다(표준 라이브러리만). 세션 토큰(`AWS_SESSION_TOKEN`)도 이미 받는다 |
| 키 읽기 | 환경변수 두 개만. 없으면 시작할 때 멈춘다 |
| 파드 | trip-guide 는 ServiceAccount 가 없다(기본). scene-api 만 `scene-api` SA + Pod Identity(`eks.tf` `scene_api_media`) |
| **네트워크 정책** | **scene-api·trip-guide 모두 노드의 Pod Identity 에이전트(`169.254.170.23:80`)로 나가는 길이 없다** — 기본 차단에 443·5432 만 연다. 그래서 scene-api 의 사진 버킷 역할도 DEV 에서 임시 키를 못 받을 것이다(사진은 DEV 에서 실제로 확인한 적이 없다) |
| 배포 역할 | `iam:PassRole` 이 `-media` 역할까지만 |

## 3. 결정

| 항목 | 결정 |
| --- | --- |
| 역할 | bootstrap 에 **`scenetrip-<env>-bedrock`** — 신뢰는 `pods.eks.amazonaws.com`(이 계정·이 클러스터만), 권한은 `bedrock:InvokeModel`·`InvokeModelWithResponseStream` 을 **`config/model.json` 의 모델 하나**(us-west-2 `openai.gpt-oss-120b-1:0`)에만 |
| 연결 | `eks.tf` 에 `trip_guide_bedrock` Pod Identity 연결 — 네임스페이스 `scenetrip`, SA **`trip-guide`**(Helm 이 만든다) |
| 네트워크 | 정책 scene-api·trip-guide 에 `169.254.170.23/32` TCP 80 을 더한다 — Pod Identity 에이전트(노드의 링크 로컬 주소). 다른 곳은 그대로 |
| 에이전트 코드 | 자격 증명을 **두 곳에서**: ① Pod Identity 환경변수(`AWS_CONTAINER_CREDENTIALS_FULL_URI`·`AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE`)가 있으면 그 주소에서 임시 키를 받는다 — 만료 5 분 전에 다시 받는다 ② 없으면 지금처럼 환경변수 키(로컬). 표준 라이브러리만(SDK 를 들이지 않는다) |
| 비밀값 | 배포가 trip-guide 비밀값의 AWS 키를 **파드에 넣지 않는다**. 비밀값 키는 선택으로 — 확인 뒤 사람이 IAM 사용자 키를 비활성·삭제 |
| 로컬 | 그대로 — `.env` 의 키(`just guide-up`) |
| 배포 역할 크기 | 역할을 하나 더하자 배포 역할의 인라인 정책이 IAM 한도(10,240 자)를 95 자 넘었다(시험이 잡음). 읽기 전용 `ReadClusterRoles` 를 이 환경의 역할(`role/scenetrip-<env>-*`)로 묶어 10,159 자. **`iam:PassRole` 은 그대로 하나씩** 적는다 — 넘길 수 있는 역할을 넓히지 않는다 |

## 4. 순서

1. 이 문서 → 코드 PR(에이전트·bootstrap·Terraform·Helm·배포기·시험) — 태환님 리뷰
2. **dev bootstrap apply**(역할·PassRole) — 확인받고
3. dev 올리기 → 챗봇 한 턴 + 리뷰 사진 올리기(네트워크 정책 수정 확인) — 원격 적재 확인과 함께
4. ~~확인되면 Bedrock 용 IAM 사용자 키 비활성 → 삭제~~ — **2026-10-12 DEV 실측: 챗봇이 역할로 Bedrock 을 불렀다**(CloudTrail
   `ChatCompletions`, AssumedRole `…trip-guide`, 오류 없음). 그런데 IAM 사용자 `bedrock-api-user`(권한 `AmazonBedrockFullAccess`)의 키는
   **로컬 개발(`just guide-up`)이 쓴다** — 지우지 않는다. 권한을 모델 하나로 줄이거나 로컬을 SSO 로 바꾸는 것은 선택(권호: 지금은 두기)
5. trip-guide 용 Secrets Manager 비밀값을 없앤다 — Terraform·배포기·되살리기 목록에서. DEV 의 옛 값(`DEEPSEEK_API_KEY`)이 남아 있어
   올리기가 「키가 허용 목록과 다르다」 로 멈췄다(2026-10-12)
