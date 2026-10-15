# iOS 신고·차단·차단 목록·금칙어 안내·문의 연락처 (MZ2AZ-461)

서버(MZ2AZ-438, 계약 scene-api 1.20.0 태그 `moderation`)의 앱 쪽 중 **iOS** 몫이다. App Store 심사 지침 1.2 —
사용자가 글을 올리는 앱에는 **신고, 차단, 부적절한 글 거르기, 연락처 공개**가 앱 안에 있어야 한다. Android 는 다른 세션이 한다.
서버 계획은 [community-moderation.md](community-moderation.md).

## 1. 범위

| 항목 | 자리 | 창구 |
| --- | --- | --- |
| 신고 | 리뷰 한 줄(리뷰 시트), 여행후기 목록 줄·상세의 「⋯」 → 「신고」 → 이유 여섯 + 설명(선택) | `POST /reports` (비회원 가능, `X-Install-Id`) |
| 차단 | 같은 「⋯」 → 「이 사용자 차단」 → 확인 창 | `POST /me/blocks` (로그인 필요) |
| 차단 목록 | 마이페이지 「차단한 사용자」 → 줄마다 「차단 풀기」 | `GET /me/blocks`, `DELETE /me/blocks/{blockId}` |
| 문의 연락처 | 마이페이지 「문의하기」 — 주소를 글자로 보이고(복사 가능) 옆에 메일 열기 | 없음. 주소는 `LegalLinks.supportEmail` 한 곳 |
| 금칙어 안내 | 리뷰·여행후기·닉네임·코스 저장이 `400 TEXT_NOT_ALLOWED` 면 「쓸 수 없는 말이 들어 있어요. 고친 뒤 다시 저장해 주세요.」 | 각 저장 창구 |
| 사진에서 차단이 풀리는 틈 | 사진첩·상세 진입 전에 `AuthRefresher.refreshIfStale()` (권호 결정 2026-10-15) | `listPlacePhotos`·`listPoiPhotos`·`getPlace`·`getPoi` |

마켓 코스는 계약의 신고 대상(`review`·`post`)이 아니고 iOS 에 화면도 없다 — 하지 않는다.
사진 전체 화면(`PhotoPager`)은 사진과 「리뷰 보기」 만 있고 작성자 글·닉네임이 없다 — 메뉴를 두지 않고 「리뷰 보기」 로 간 리뷰 줄에서 신고한다.

## 2. 구조

- **API 는 생성 클라이언트**(`ModerationAPI`)만 부른다. 손으로 쓴 HTTP 는 없다. 화면은 `ModerationClient` 프로토콜을 거쳐
  시험에서 가짜로 바꿀 수 있다.
- `ModerationRules`(순수 함수) — 이유 순서와 문구, 오류 코드 → 결과(`ReportOutcome`·`BlockOutcome`), 설명 다듬기(500자),
  메뉴를 보일지(`isMine`·작성자 유무), `TEXT_NOT_ALLOWED` 판정과 문구. 서버 문장이 아니라 **code 로 가른다**(MZ2AZ-345,
  계약 `ApiError.message` 는 화면 문구가 아니다).
- `ModerationController`(ObservableObject) — 목록 화면이 하나 들고, 줄의 「⋯」 가 이것을 부른다. 시트·확인 창·알림을
  목록 화면 한 곳에 단다(지연 목록의 줄마다 시트를 달지 않는다).
- 비회원이 차단을 누르면 공용 로그인 화면(`auth.promptSignIn()` → 화면의 `.signInSheet()`)을 올린다.
- 자기 글(`isMine`)에는 메뉴 자체를 두지 않는다. 작성자가 탈퇴한 글(`author == nil`)에는 차단을 두지 않는다
  (`BLOCK_TARGET_HAS_NO_AUTHOR`). 그래도 `CANNOT_REPORT_OWN`·`CANNOT_BLOCK_SELF` 가 오면 알맞은 안내를 한다.

## 3. 차단한 뒤

서버가 이후 목록에서 그 사람의 글을 뺀다. 앱은 **그 글을 바로 지우고**(앱은 작성자 번호를 몰라 같은 사람의 다른 글은 모른다)
목록을 **다시 받는다** — 리뷰 시트는 리뷰·방문자 사진을 다시 받고 부른 화면에 `onChanged` 를 알린다. 여행후기는
`CommunityStore` 에서 그 글을 빼고 새로 받는다. 상세에서 차단했으면 상세를 닫는다(그 글은 이제 404).

- **나가 있던 받기는 무효로 한다** — 차단 전에 떠난 새로고침·더 보기 응답은 그 글을 담고 있다. `CommunityStore.drop` 이
  세대(`revision`)를 올려 늦은 응답을 버리고, `blocked(postId:)` 가 첫 쪽을 반드시 새로 받는다.
- **다른 화면의 사진도 비운다** — 검색 탭의 촬영지 상세는 탭을 옮겨도 살아 있다. 차단·풀기는 `ModerationEvents` 로
  알리고, 촬영지 상세·편의시설 카드·리뷰 시트가 `onModerationChange` 로 방문자 사진을 먼저 비운 뒤 다시 받는다.
- **차단 목록의 늦은 응답** — 받는 사이 푼 줄은 늦게 온 목록에서 뺀다(`BlockedUsersStore.unblockedLog`).
- **신고 중 세션이 풀리면** — 신고 시트가 떠 있는 동안(`ReportSheetPresence`) 밑의 화면은 로그인 화면을 올리지 않는다.
  신고 시트가 스스로 닫히고, 닫힌 뒤 밑의 화면이 올린다(`signInSheet` 의 「맨 위 화면에서만」 규칙).
- 홈 「커뮤니티 지금」 에도 같은 메뉴가 있다. 비회원의 차단은 루트(`RootTabs`)의 로그인 화면이 받는다.

## 4. 시험

`ModerationRulesTests` — 이유 여섯이 계약 순서·값과 같다, 오류 코드 → 결과, 설명 다듬기, 메뉴 보이기 규칙,
`TEXT_NOT_ALLOWED` 판정, 문의 주소 모양, 새 문구의 영어 번역. `ModerationControllerTests` — 가짜 클라이언트로 신고·차단의
흐름(비회원 차단은 로그인 안내, 성공하면 `onBlocked`), 차단 목록 받기·풀기. `CommunityStore` 의 차단 뒤 글 빼기.

## 5. 확인 (사람이)

- 남의 리뷰 → 「⋯」 → 신고 → 「접수됐어요」, 같은 글 다시 신고해도 같은 안내
- 남의 리뷰 작성자 차단 → 리뷰 목록·사진첩·후기 목록에서 그 사람 글이 사라짐 → 마이페이지 「차단한 사용자」 에서 풀기 → 다시 보임
- 리뷰·후기·닉네임·코스 이름에 비속어 → 안내 문구
- 마이페이지에 문의 메일
