# Android 신고·차단·차단 목록·금칙어 안내·문의 연락처 (MZ2AZ-461)

서버(MZ2AZ-438, 계약 scene-api 1.20.0 태그 `moderation`)의 앱 쪽 중 **Android** 몫이다. iOS 몫은
[ios-moderation.md](ios-moderation.md)(PR #299)이고, **Android 는 그 구현을 문구·동작 그대로 옮긴다.** App Store 심사 지침 1.2 —
사용자가 글을 올리는 앱에는 신고, 차단, 부적절한 글 거르기, 연락처 공개가 앱 안에 있어야 한다. 서버 계획은
[community-moderation.md](community-moderation.md).

## 1. 범위와 iOS 대응

| 항목 | Android 자리 | iOS 원본 |
| --- | --- | --- |
| 규칙·결과 해석·문구 | `data/Moderation.kt` `ModerationRules` | `Models/Moderation.swift` `ModerationRules` |
| 신고·차단 창구 | `ModerationClient` / `ModerationServerClient` (생성 클라이언트 `ModerationApi`만 부른다) | `ModerationClient` / `ModerationServerClient` |
| 「⋯」 메뉴 | `reviews/ModerationViews.kt` `ModerationMenu` + `ui/IOSMenu.kt` | `Reviews/ModerationViews.swift` `ModerationMenu` |
| 신고 시트·차단 확인 창·안내 | `ModerationViews.kt` `ModerationSheets` · `ReportSheet` | `ModerationSheets` · `ReportSheet` |
| 리뷰 한 줄 | `reviews/ReviewsSheet.kt` `ReviewRow(moderation=)` | `Reviews/ReviewRow.swift` |
| 여행후기 목록 줄·상세 작성자 줄 | `communitytab/CommunityList.kt` · `CommunityPostView.kt` | `CommunityTabView` · `CommunityPostView` |
| 홈 「커뮤니티 지금」 | `hometab/HomeFeed.kt` · `HomeTabView.kt` | `HomeFeed.swift` · `HomeTabView.swift` |
| 차단 목록 | `profiletab/BlockedUsersView.kt` + `BlockedUsersStore` | `ProfileTab/BlockedUsersView.swift` |
| 문의하기 | `profiletab/BlockedUsersView.kt` `SupportContactRow` (주소는 `ModerationRules.SUPPORT_EMAIL` 한 곳) | `SupportContactRow` · `LegalLinks.supportEmail` |
| 금칙어 안내 | `NetworkFailure`(리뷰·닉네임) · `CommunityRules.failureText`(후기) · `ApiFailure`(코스) | `ReviewComposeView` · `NicknameView` · `CommunityRules` · `ApiFailure` |
| 영어 문구 | `data/Translations.kt` — iOS `en.lproj` 의 해당 34개를 그대로 | `Localizable.strings` |

마켓 코스는 계약의 신고 대상(`review`·`post`)이 아니고 Android 에도 화면이 없다. 사진 전체 화면(`GalleryViewer`)은 사진과
「리뷰 보기」 만 있다 — 메뉴를 두지 않고 「리뷰 보기」 로 간 리뷰 줄에서 신고한다.

## 2. 구조

- **API 는 생성 클라이언트만** 부른다(`ModerationApi.createReport`·`createBlock`·`listMyBlocks`·`deleteBlock`). 손으로 쓴 HTTP 는 없다.
  화면과 시험은 `ModerationClient` 인터페이스를 거친다.
- 오류는 **서버 문장이 아니라 code 로 가른다**(`NetworkFailure.of(error).code`). 신고: `CANNOT_REPORT_OWN`·`REPORT_TARGET_NOT_FOUND`·
  `RATE_LIMITED`(또는 429). 차단: `SIGN_IN_REQUIRED`(401)·`CANNOT_BLOCK_SELF`·`BLOCK_TARGET_HAS_NO_AUTHOR`·`BLOCK_TARGET_NOT_FOUND`.
- `ModerationController`(`StateFlow<ModerationUi>`) — 목록 화면 하나가 `rememberModerationController()` 로 들고, 줄의 「⋯」 가 이것을
  부른다. 시트·확인 창·알림은 화면 한 곳의 `ModerationSheets` 에 단다(지연 목록의 줄마다 달지 않는다).
- 비회원이 차단을 누르면 확인 창 대신 앱 맨 위의 로그인 시트(`AuthStore.promptSignIn()`)를 올린다. 신고는 비회원도 된다(`X-Install-Id`).
- 자기 글(`isMine`)과 서버에 없는 기기 옛 글에는 메뉴를 두지 않는다. 작성자가 탈퇴한 글(`author == null`)에는 「이 사용자 차단」 을 두지 않는다.
- 신고 중에 세션이 풀려 로그인 시트가 올라오면 신고 시트가 스스로 닫힌다(iOS `ReportSheetPresence` 와 같은 결과).

## 3. 차단한 뒤

- **그 글을 바로 지우고 목록을 새로 받는다.** 리뷰는 `ReviewListStore.drop`, 여행후기는 `CommunitySession.drop` → `invalidateAndRefresh`.
  상세에서 차단했으면 상세를 닫는다(그 글은 이제 404).
- **나가 있던 받기는 무효로 한다(세대 수).** `ReviewListStore` 와 `CommunitySession` 이 세대를 세어, 차단 전에 떠난 응답이 늦게 와도 그 글을
  되살리지 않는다. 피드와 내 글은 세대를 따로 세어 차단이 내 글 받기를 끊지 않는다. 차단 뒤 첫 쪽은 반드시 새로 받는다.
- **앱 전체 신호** — `ModerationEvents.shared.version`(StateFlow). 차단·풀기가 성공하면 오른다. 촬영지 상세(`PlaceDetailView`)·편의시설 카드
  (`RoutePlaceCard`)·리뷰 시트(`ReviewsSheet`)는 이 값이 바뀌면 방문자 사진을 먼저 비운 뒤(`ReviewRules.withoutReviewPhotos`) 상세를 다시
  받는다. `GalleryStrip` 은 신호가 바뀌면 사진 묶음(`PhotoBook`)을 새로 만들어, 옛 묶음에 나가 있던 「다음 쪽」 응답이 새 묶음에 섞이지 않는다.
- **차단 목록의 늦은 응답** — 받는 사이 푼 줄은 늦게 온 목록에서 뺀다(`BlockedUsersStore.unblockedLog`). 풀면 여행후기 목록을 다시 받는다.
- 홈 「커뮤니티 지금」 에도 같은 메뉴가 있다. 비회원의 차단은 앱 맨 위 로그인 시트가 받는다.
- 화면이 닫혀도 목록 새로고침이 끝나도록 `CommunityStore.blockedInBackground`·`invalidateAndRefreshInBackground` 는 스토어의 코루틴 범위에서 돈다
  (iOS 는 전역 `Task`).

## 4. 만료된 토큰과 사진 (iOS `AuthRefresher.refreshIfStale()` 결정)

누구나 읽는 창구는 만료된 토큰에 401 을 주지 않고 비회원으로 답한다(서버 `CurrentAccount.signedInOrNull`). 그러면 갱신할 계기가 없어
차단한 사람의 리뷰·사진·후기가 다시 섞인다. Android 는 이미 `AuthInterceptor` 가 `/reviews` 읽기 앞에서 만료 여부를 보고 있었고, 이번에
`AuthRules.refreshesBeforeRead(method, path)` 로 iOS 가 `refreshIfStale()` 을 거치는 곳과 같게 넓혔다 — 리뷰 목록·내 리뷰, 촬영지·편의시설
상세(`/places/{id}`·`/pois/{id}`)와 사진첩(`…/photos`), 여행후기 목록·상세·내 글(`/posts`·`/posts/{id}`·`/me/posts`). 화면마다 부르지 않고
요청 가로채기 한 곳에서 처리하므로 빠진 호출 지점이 없다.

## 5. 시험

`tests/ModerationTests.kt`(JUnit 5, `just test //apps/scenetrip-android:unit_test`) — iOS `ModerationTests` 와 같은 순서:
이유 여섯이 계약 순서·값과 같다, 오류 코드 → 결과, 설명 다듬기(500자·서로게이트 쌍), 메뉴 보이기 규칙, `TEXT_NOT_ALLOWED` 가 리뷰·닉네임·후기·코스
저장 문구로 나온다, 가짜 클라이언트로 신고·차단 흐름(비회원 차단은 로그인 안내, 성공하면 신호), 차단 목록 받기·풀기·늦은 응답,
`ReviewListStore`·`CommunitySession` 의 차단 뒤 빼기와 늦은 응답 무시, 방문자 사진 비우기, 만료 토큰을 살필 창구 목록, 문의 주소.

**iOS 시험 중 Android 에 짝이 없는 것** — 번역 누락 시험(`testNewWordingIsTranslated`): 번역표(`TRANSLATIONS_EN`)가 `internal` 이라 시험 모듈에서 볼
수 없다. 문구는 iOS `en.lproj` 에서 가져오므로 `just android-translations` 가 근거다. `ReportSheetPresence` 시험: 같은 클래스가 없다(로그인 시트가
Dialog 창이라 위에 겹쳐 뜬다). `PhotoGallery.forgetReviewPhotos` 시험: 같은 클래스 대신 `ReviewRules.withoutReviewPhotos` 를 시험한다.

## 6. 확인 (사람이)

- 남의 리뷰 → 「⋯」 → 신고 → 「접수됐어요」, 같은 글 다시 신고해도 같은 안내. 내 리뷰에는 「⋯」 가 없다.
- 남의 리뷰 작성자 차단 → 리뷰 목록·사진첩·후기 목록에서 그 사람 글이 사라짐 → 마이페이지 「차단한 사용자」 에서 풀기 → 다시 보임
- 비회원에서 「이 사용자 차단」 → 로그인 시트
- 리뷰·후기·닉네임·코스 이름에 비속어 → 안내 문구
- 마이페이지의 문의하기(주소 길게 눌러 복사, 「메일 쓰기」 로 메일 앱 열기)
- 영어로 바꿔 위 문구가 모두 영어인지
- TalkBack: 「⋯」 가 「신고·차단 메뉴」(영어 「Report or block」)로 읽히는지
