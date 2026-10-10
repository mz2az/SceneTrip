# 출시 전 운영 보호 — 스냅샷 정리·장면 사진 버킷·사용자 사진 버킷·끊긴 배포 (MZ2AZ-364)

MZ2AZ-364 의 할 일 중 2·3·4 와, 2026-10-09 dev 올리기가 중간에 끊겨 정리에 손이 세 번 간 일(5)을 한다. 1(PRD 수동 삭제 막기)은
PRD 에 실제 사용자가 생기기 직전에 따로 한다(권호 2026-10-10).

## 0. 실측 (2026-10-10, 읽기만)

| 대상 | 지금 |
| --- | --- |
| dev 최종 스냅샷 | `scenetrip-dev-final-*` 4 개, 각 20 GB — 09-29 · 09-30 · 10-01 · 10-09(수동, 끊긴 올리기 정리 때) |
| 「올리기」 가 스냅샷을 복원하나 | **아니다** — 매번 빈 DB 로 시작한다(`docs/ops/aws-teardown.md`). 최종 스냅샷은 사람이 꺼내 쓰는 백업일 뿐이다 |
| 스냅샷 지우기 권한 | 배포 역할(`scenetrip-{env}-deploy`)에 `rds:DeleteDBSnapshot` 이 없다. 지우는 코드도 없다 |
| 장면 사진 버킷 | 실제 이름은 **`scenetrip-media-prod`**(티켓·review.md 의 `scene-media-prod` 는 오기). 104 장 253 MB, 공개 읽기(버킷 정책 `PublicReadForImages`), **버전 관리 꺼짐**, 코드 관리 없음(콘솔에서 만듦, 2026-08-28). 로컬·dev·prd 모두 시드의 `scene_image_url` 로 이 주소를 읽는다 |
| 사용자 사진 버킷 | bootstrap 에 있다 — `DeletionPolicy: Retain` 이라 스택을 지워도 남는다 ✅. 버전 관리 꺼짐. prd 는 아직 bootstrap 을 적용하지 않아 버킷이 없다 |
| bootstrap 삭제 | **고장** — 허용 목록(`bootstrap_delete.py` `expected_resources`)에 362 에서 더한 사진 버킷·정책·`MediaRole` 이 없어 「허용되지 않은 리소스」 로 멈춘다 |
| 끊긴 배포 | 잠금 파일(`.tflock`)을 치우는 길도, 상태 밖에 남은 자원을 찾는 길도 없다. 기록된 절차도 없다 |

## 1. 결정

| 할 일 | 결정 | 이유 |
| --- | --- | --- |
| 2. 스냅샷 정리 | **dev 만**, 내리기(retain)가 끝나면 `scenetrip-dev-final-*` 중 **최근 2 개만 남기고** 지운다. 개수는 설정(`--keep`) | 올리기가 복원하지 않으니 오래된 것은 쓸 일이 없다. 하나가 깨져도 하나가 남게 2 개 |
| 2. 권한 | 배포 역할에 `rds:DeleteDBSnapshot` 을 **`snapshot:scenetrip-dev-final-*` 에만** | prd 스택이 만든 배포 역할도 dev 최종 스냅샷밖에 못 지운다 — prd 스냅샷은 지울 수 없다. 조건문(`Fn::If`)을 권한 목록에 넣지 않아 템플릿을 읽는 점검이 그대로 돈다 |
| 3. 장면 사진 버킷 | 새 CloudFormation 스택 **`scenetrip-shared-media`** 로 가져온다(import). `DeletionPolicy: Retain`, **버전 관리 켬**(지운 것은 30 일 뒤 영구 삭제), 공개 읽기·암호화·소유권은 지금 그대로 | 환경(dev·prd) 스택에 넣으면 dev 를 지울 때 함께 휘말린다 — 모든 환경이 읽는 공용 자원이라 따로 둔다. 버전 관리는 실수로 지운 사진을 되살리는 유일한 길이다 |
| 4. 사용자 사진 버킷 | prd 에만 버전 관리(지운 것은 30 일 뒤 영구 삭제). bootstrap 삭제 허용 목록을 고친다 | 사용자가 올린 사진은 다시 만들 수 없다. dev 는 시험용이라 켜지 않는다(비용) |
| 5. 끊긴 배포 | 읽기 전용 점검 `just aws-drift <env>` — 잠금 파일, 상태 밖 EKS·RDS·NAT·VPC. 그리고 런북 | 10-09 에 손으로 찾은 것을 명령 하나로. 지우는 것은 사람이 런북을 보고 한다 |

## 2. 적용 순서 (클라우드를 바꾸는 것 — 권호 확인 뒤)

1. PR 머지.
2. bootstrap 바깥 정책(`scenetrip-bootstrap-scope`, 저장소 밖)에 추가가 필요 없다 — 배포 역할 정책은 bootstrap 이 만든다. `aws-bootstrap.yml` plan → apply(dev).
3. 장면 사진 버킷 가져오기: `just aws-shared-media`(미리 보기) → `just aws-shared-media --execute`(가져오기 뒤 버전 관리·수명 규칙·정책 적용까지 한 번에). 관리자 자격으로 노트북에서 — 이 스택은 GitHub 역할이 다루지 않는다.
4. 지금 쌓인 스냅샷 4 개 중 오래된 2 개(09-29·09-30) 정리 — 다음 내리기 때 자동으로 되지만, 지금 한 번 `just aws-snapshot-prune dev`(미리 보기)로 보고 `--execute` 로 지운다.

## 3. 하지 않는 것

- 1(PRD 수동 삭제 막기) — PRD 사용자 직전.
- 「올리기」 가 스냅샷을 복원하는 것 — dev 는 빈 DB 로 시작하는 것이 설계다(aws-teardown.md).
- 끊긴 배포를 자동으로 고치기 — 무엇을 지울지는 사람이 본다.
