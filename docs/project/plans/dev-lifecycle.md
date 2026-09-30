# DEV 올리기·내리기 워크플로

작성일: 2026-09-30. 사용자 요청에 따른 후속 계획이며 연결된 JIRA 티켓은 없다.
결정은 [ADR 0017](../../architecture/adr/0017-dev-lifecycle-and-cloudflare-dns.md)에 남긴다.

## 배경

2026-09-29 첫 DEV 배포와 삭제를 수동으로 진행했다. 한 번 올리는 데 운영자가 할 일은
runner EC2 켜기, 삭제 예약된 Secret 복원, 배포 workflow 실행과 대기, 새 ALB 주소로 DNS
갱신, runner 끄기였다. 내릴 때는 삭제 plan·destroy와 runner 켜고 끄기였다. 비용 때문에
DEV를 자주 내리고 올리려면 이 과정을 버튼 하나로 묶어야 한다. 운영자의 노트북에
묶이지 않도록 GitHub Actions에서 실행한다.

DNS는 같은 날 가비아에서 Cloudflare로 옮겼다. 가비아는 일반 계정에 레코드 API를 주지
않아 ALB 주소 갱신을 자동화할 수 없었다.

## 범위

- 새 workflow `dev-lifecycle.yml`: `workflow_dispatch`만. `action`은 `up`·`down`.
  예약 실행(`schedule`)은 이번 범위가 아니다(ADR 0015와의 관계·데이터 복원을 먼저 정한다).
- 기존 `aws-deploy.yml`·`aws-destroy.yml`은 바꾸지 않는다. 새 workflow는 이들을
  `workflow_dispatch`로 호출하고 끝날 때까지 기다린다. 입력 검증·main 사전검증·Environment
  보호·동시 실행 그룹은 그대로 적용된다.
- AWS 권한은 OIDC로 받는 새 역할 `scenetrip-<env>-lifecycle`. bootstrap 템플릿이 소유한다.
  장기 액세스 키를 GitHub에 두지 않는다.
- Cloudflare 토큰은 GitHub `dev` Environment secret `CLOUDFLARE_API_TOKEN`. 권한은
  `scenetrip.io` 존의 DNS 편집만.

## 흐름

`up`

1. runner EC2(`project=scenetrip`, `environment=<env>`, `role=github-runner` 태그)를 켠다.
2. `/scenetrip/<env>/` 아래 삭제 예약된 Secret을 복원한다. 완전히 삭제된 경우는 복원하지
   않는다. 그 경우 배포가 빈 Secret에서 멈추며 운영자가 값을 넣는다.
3. `aws-deploy.yml`을 `operation=apply`로 호출한다.
4. 기다리는 동안 `scenetrip-<env>-alb` 보안 그룹을 쓰는 ALB가 생기면 Cloudflare의
   `api-<env>` CNAME을 그 주소로 바꾼다(DNS only, TTL 60). 배포기의 마지막 HTTPS 검증이
   새 주소를 보도록 배포가 끝나기 전에 바꾼다.
5. 배포 run이 성공해야 성공이다.

`down`

1. runner EC2를 켠다.
2. `aws-destroy.yml`을 `scope=service`, `operation=plan`으로 호출하고 성공을 확인한다.
3. 같은 입력의 `operation=destroy`를 확인 문자열과 함께 호출한다. 실패하면 런북대로
   같은 입력으로 한 번 재시도한다.

공통: 마지막 단계에서 runner EC2를 끈다. 단 배포·삭제 run이 아직 대기·진행 중이면 끄지
않는다. 진행 중에 끄면 Terraform 잠금이 남는다(2026-09-29에 실제로 겪었다).

## 권한

`scenetrip-<env>-lifecycle` 역할은 아래만 가진다. 세션은 최대 4시간.

| 동작 | 범위 |
| --- | --- |
| EC2 시작·정지 | 위 세 태그가 모두 맞는 인스턴스 |
| EC2·보안 그룹·ALB 조회 | 리전 한정 읽기 |
| Secret 복원·조회 | `/scenetrip/<env>/*` |
| Secret 목록 | 이름만(리소스 단위 제한 불가) |

## 검증

- 단위 시험: 호출 순서, runner 식별 실패, Secret 복원 대상 제한, DNS only 강제·ALB 주소
  형식 검증, 배포 실패 시 중단, 삭제 재시도 1회, 진행 중 run이 있으면 runner를 끄지 않음.
- 템플릿 시험: lifecycle 역할의 신뢰 조건과 권한 범위.
- bootstrap 삭제 시험: 새 역할을 스택 리소스로 인정.
- 실환경: bootstrap apply로 역할 생성 뒤 `up`→`verify`→`down`을 한 번씩 실행.

## 남은 결정

- 예약 실행 여부와 시간(UTC 기준 cron).
- 내릴 때마다 RDS가 비워지는 문제: 스냅샷 복원 또는 데이터 적재 자동화.
- 스냅샷 누적 정리 정책.
