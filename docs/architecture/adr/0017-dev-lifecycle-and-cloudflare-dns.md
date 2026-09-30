---
number: 0017
title: 환경 올리기·내리기를 OIDC 전용 역할의 workflow로 묶고 DNS는 Cloudflare(DNS only)에 둔다
status: proposed
date: 2026-09-30
supersedes:
superseded-by:
amended-by:
---

# ADR 0017: 환경 올리기·내리기 workflow와 Cloudflare DNS

## 배경

2026-09-29 DEV를 처음 실제로 배포하고 삭제했다. EKS·NAT·RDS·ALB는 쓰지 않아도 시간
요금이 나가므로 DEV는 쓸 때만 올리는 편이 낫다. 수동으로 올리려면 runner EC2 켜기, 삭제
예약된 Secret 복원, 배포 workflow 실행과 대기, 새 ALB 주소로 DNS 갱신, runner 끄기를
매번 해야 했다. 내릴 때도 삭제 plan·destroy와 runner 전원을 다뤘다.

ALB는 올릴 때마다 새 주소를 받는다. 도메인 `scenetrip.io`의 DNS는 가비아에 있었고
가비아는 일반 계정에 레코드 API를 주지 않는다. 그래서 이 한 단계를 자동화할 수 없었다.

[ADR 0015](0015-aws-manual-environments.md)는 cloud 배포를 `workflow_dispatch`와 확인
레시피에서만 실행하고 push·PR·schedule로는 시작하지 않는다고 정했다.

## 결정

- 우리는 `dev-lifecycle.yml` workflow로 환경 올리기(`up`)와 내리기(`down`)를 버튼
  하나로 실행한다. 이 workflow는 기존 `aws-deploy.yml`·`aws-destroy.yml`을
  `workflow_dispatch`로 호출하고 결과를 기다린다. 기존 workflow는 바꾸지 않으며 그 안의
  main 사전검증·Environment 보호·동시 실행 그룹이 그대로 적용된다.
- AWS 권한은 GitHub OIDC로 받는 `scenetrip-<env>-lifecycle` 역할이다. bootstrap
  템플릿이 소유한다. 태그 `project=scenetrip`·`environment=<env>`·`role=github-runner`가
  모두 맞는 EC2의 시작·정지, `/scenetrip/<env>/*` Secret의 복원, 리전 한정 EC2·ALB 조회만
  허용한다. Secret 값은 읽지 못한다. 장기 액세스 키를 GitHub에 두지 않는다.
- `scenetrip.io`의 DNS는 Cloudflare가 담당한다. 도메인 등록은 가비아에 둔다.
  API 레코드(`api-<env>`)와 ACM 검증 레코드는 **DNS only**다. `up`은 배포가 끝나기 전에
  `scenetrip-<env>-alb` 보안 그룹을 쓰는 ALB를 찾아 CNAME을 바꾼다(TTL 60). 토큰은 존의 DNS
  편집 권한만 가지며 GitHub Environment secret `CLOUDFLARE_API_TOKEN`에 둔다.
- 이 workflow도 사람이 실행할 때만 동작한다. 예약 실행은 이 결정에 포함하지 않는다.

## 검토한 대안

| 선택지 | 채택하지 않은 이유 |
| --- | --- |
| 운영자 노트북의 `just` 레시피 | 노트북이 켜져 있어야 하고 예약으로 확장할 수 없다. Bazel AWS CLI가 macOS를 지원하지 않아 호스트 CLI에 의존하게 된다 |
| 관리자 IAM 키를 GitHub secret에 저장 | 만료 없는 계정 전체 권한이 저장소에 남는다. 런북의 "장기 키로 우회하지 않음"과 충돌한다 |
| 기존 배포 역할에 EC2 전원 권한 추가 | 배포 역할이 runner 자신을 끄는 권한을 갖게 되고 역할 경계가 섞인다 |
| DNS를 가비아에 두고 CNAME은 사람이 갱신 | 올릴 때마다 사람이 필요하다. 예약 실행을 막는다 |
| Route 53으로 DNS 이전 | 가능하다. Cloudflare는 무료이고 이미 랜딩페이지 이전을 끝냈다 |
| Cloudflare 프록시(주황 구름) 사용 | ALB가 보는 접속자가 Cloudflare가 되어 허용 CIDR·XFF 신뢰·TLS 종료 설계가 깨진다. 공개 서비스 방어는 별도 결정으로 다룬다 |

## 결과

**좋아지는 것**

- DEV를 버튼 하나로 올리고 내린다. 운영자 기기와 무관하다.
- runner EC2를 쓰는 동안에만 켠다.
- 기존 workflow와 배포기를 바꾸지 않아 이미 검증된 경계가 그대로다.

**나빠지는 것 / 감수하는 비용**

- 계정에 역할이 하나 늘고 bootstrap을 한 번 더 적용해야 한다.
- DNS 운영 주체가 Cloudflare로 바뀐다. `api-<env>`의 프록시를 켜면 접속이 끊긴다.
- 내릴 때마다 RDS가 새로 만들어져 데이터가 비워진다. 최종 스냅샷이 쌓인다.
- 배포·삭제가 도는 중에 runner를 끄면 Terraform 잠금이 남는다. workflow는 진행 중인
  run이 있으면 끄지 않지만, 사람이 콘솔에서 끄는 것은 막지 못한다.

**후속 작업**

- 예약 실행(ADR 0015의 schedule 금지와의 관계), UTC cron 시각.
- 올릴 때 스냅샷 복원 또는 데이터 적재 자동화.
- 스냅샷 정리 정책.
- 공개 시점의 앞단 방어: Cloudflare 프록시와 AWS WAF 중 선택.

## 검증

단위 시험이 호출 순서, runner 식별, Secret 복원 대상, DNS only 강제와 ALB 주소 형식,
배포 실패 시 중단, 삭제 1회 재시도, 진행 중 run이 있을 때 runner 유지를 확인한다. 템플릿
시험이 lifecycle 역할의 신뢰 조건과 권한 범위를 고정한다. 실환경에서 bootstrap 적용 뒤
`up`·`down`을 한 번씩 실행해 확인한다.
