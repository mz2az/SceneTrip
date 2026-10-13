# PRD 출시 — 서버 올리기부터 스토어 제출까지

- **상태**: 계획(2026-10-12, 권호). 문서·코드에 흩어진 「PRD 전」·「출시 전」 항목을 한 곳에 모은 것이다
- **티켓**: MZ2AZ-417(PRD 서버, 권호) · MZ2AZ-418(처리방침·약관·구글 동의 화면, 권호) · MZ2AZ-415(iOS 애플 로그인, 승길) ·
  MZ2AZ-416(스토어 제출 준비, 승길) · MZ2AZ-317(Bedrock IAM, 담당 미정) · MZ2AZ-402(ATT) · MZ2AZ-364(1 번)
- **관련**: [ADR 0024](../../architecture/adr/0024-prd-same-account-and-public.md)(같은 계정·전체 공개) ·
  [remote-data-load.md](remote-data-load.md) · [ops-protection.md](ops-protection.md) · [social-login.md](social-login.md) ·
  [meta-app-events.md](meta-app-events.md) · [aws-deployment.md](../../ops/aws-deployment.md)

## 0. 막는 것 두 가지

1. **Apple 인증 설정·실기기 검증이 남았다.** iOS 네이티브 Apple 로그인은 MZ2AZ-415 / PR #235로
   main에 들어왔다. 10/12 로컬 서버는 Apple 개인 키·토큰 암호화 키가 없어 `/auth/apple`이 501을
   반환한다. 개발자 팀의 `.p8` 공급·DEV 설정·실기기 가입/재로그인/탈퇴를 확인해야 한다.
2. **구글 동의 화면이 「테스트」 다** — 등록한 테스트 사용자(최대 100 명)만 가입한다. 「프로덕션」 전환에는 구글 심사가 있고
   **개인정보 처리방침 URL** 이 필요한데 처리방침이 아직 없다.

둘 다 오래 걸려 1 단계와 **나란히** 시작한다.

## 1. PRD 서버 올리기 — 권호(코드)·사람(콘솔)

| 할 일 | 상태 | 근거 |
| --- | --- | --- |
| 공개 설정 세 곳 — Terraform `ingress_public`, 배포기의 「PRD 공개 거부」, Helm `gateway.public` dev 전용 | 안 함 | ADR 0024 · `network.tf:7` · `deploy.py:167` · `config.yaml:8` |
| 애플 로그인 키(.p8)를 **PRD 필수**로 — 지금은 선택이라 PRD 가 애플 로그인 꺼진 채 뜬다 | 안 함 | `tools/aws/config.py:33-41` · `application.yaml:181` |
| AI 에이전트의 Bedrock 호출을 고정 키 대신 IAM 역할(Pod Identity)로 — 「배포 전 필수」 | 안 함, 담당 미정 | MZ2AZ-317 · `bedrock-migration.md:102` · `config.py:17-18` |
| GitHub Environment `prd`(검토자·main 만), runner `scenetrip-prd` | 확인 필요 | aws-deployment.md §1 |
| `api.scenetrip.io` ACM 인증서·Cloudflare DNS(DNS only) | 안 함 | aws-port-verification.md:76 |
| bootstrap prd apply(배포 역할·사용자 사진 버킷 — PRD 는 버전 관리) | 안 함 | ops-protection.md §0 |
| 비밀값 — 카카오·Bedrock·애플 .p8 (JWT·DB 는 배포가 만든다) | 안 함 | aws-deployment.md §6 |
| 배포 → 데이터 적재(`data-load`, 검토자 승인) → `just aws-verify prd` | 안 함 | remote-data-load.md |
| 카카오 「허용 IP」 에 PRD 송신 IP(NAT) | 안 함 | ADR 0009:80 |
| 모니터링·알람·비용 상한 | 바깥 감시 알람 2 개 걸림(2026-10-13), PRD SigNoz 는 아직 | [observability-aws.md](observability-aws.md) §6-3 |

RDS 삭제 보호·Multi-AZ·백업 14 일은 Terraform 이 PRD 에 이미 켠다(`database.tf:49-53`).

## 2. 스토어 제출 — 정승길(앱)·사람

| 할 일 | 담당 | 상태 |
| --- | --- | --- |
| **iOS 애플 로그인 화면**(서버 `POST /auth/apple` 은 있음) | 승길 | PR #235로 main 반영. `.p8`·서명·실기기 인증 검증은 남음(MZ2AZ-415) |
| 실기기 서명 — entitlements 의 임시 팀 ID `SIM0000000` → `$(AppIdentifierPrefix)`, 버전·빌드 번호 | 승길 | 안 함 |
| PRD 주소로 빌드(`just mobile-build-cloud`), TestFlight·Play 내부 테스트 | 승길 | 안 함 |
| Play 앱 서명 키 SHA-1 을 Android OAuth 클라이언트로 등록 | 사람(구글 콘솔) | 안 함 |
| 구글 동의 화면 「프로덕션」 전환·심사 | 사람 | 안 함 — 처리방침 URL 필요 |
| App Store 개인정보 라벨, Play 데이터 보안 양식, 스크린샷, 계정 삭제 웹 주소(Play) | 사람 | 안 함 |
| ATT(추적 동의) 결정 — 메타 측정을 켤지 | 승길·사람 | MZ2AZ-402 보류 |
| 개인정보 매니페스트(`PrivacyInfo.xcprivacy`) | 승길 | 없음 |

앱 안 계정 삭제(애플 연결 끊기 포함)는 서버·두 앱 모두 끝났다.

## 3. 법·정책 — 사람

| 할 일 | 상태 |
| --- | --- |
| **개인정보 처리방침** — 분석(Firebase), 이용 기록(조회·검색어, popularity.md §8), 챗봇(질문이 모델 제공자로 간다, 처리 기록은 원문 없이), 발자취(기기에만), 탈퇴 뒤 리뷰는 글쓴이 없이 남음 | 문서 없음 |
| 이용약관 — 올린 사진이 서비스에 보인다는 조항, 리뷰 문구(법률 멘토) | 안 함 |
| 장면 사진 저작권 — ADR 0007 미결. 새 CSV 는 장면 사진이 전부 비어 있다 | 미결 |
| TourAPI 사진(Type3)의 상업 이용·가공 여부, 운영 계정 전환 | 확인 필요 |
| 네이버 링크용 비공식 장소 검색 — 「정식 출시 전 재검토」(ADR 0021) | 재검토 |

## 4. 실사용자 직전

- PRD 수동 삭제 경로 막기 — MZ2AZ-364 의 1 번
- 샌드박스 계정의 수명·정리 정책(회사) — ADR 0024
- 데이터 키 확정 — PRD 에 올린 뒤로는 `title_key`·`place_key` 를 바꾸지 않는다(원격 `seed-reset` 은 사용자 데이터를 지운다)

## 5. 데이터

- 장면 사진 — CSV `scene_image_url` 을 채우면 된다(공개 https). 데이터 담당자 확인
- 편의시설 사진(#184) — TourAPI 수집이 끝나면 다시 올린다

## 6. 순서

1. **지금 나란히**: iOS 애플 로그인(승길) · 개인정보 처리방침(사람) · 1 단계 코드(공개 설정·.p8 필수·Bedrock IAM)
2. dev 에서 실제로 — 올리기·자동 적재·앱 확인(remote-data-load.md §5)
3. PRD 콘솔 준비(Environment·runner·인증서·DNS) → bootstrap → 비밀값 → 배포 → 적재 → 검증
4. 처리방침 URL → 구글 동의 화면 프로덕션 → 스토어 제출
5. 실사용자 직전 항목(§4)
