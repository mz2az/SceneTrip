# scenetrip-android

> 모듈 종류: `app` · 언어: `kotlin` · 경로: `apps/scenetrip-android`

## 목적

SceneTrip 의 Android 네이티브 앱. **작품검색 탭**(지도 + 바텀시트 + 검색)이 첫 화면
이었고, 그 다음 온보딩(스플래시 + 사용법 4장)과 홈(3탭 + 가운데 원형 홈 버튼)을
옮겼다 — 기준 문서는 각각
[검색 탭 네이티브 구현 계획](../../docs/project/plans/mobile-native-search-tab.md) §3,
[홈 탭 계획](../../docs/project/plans/mobile-home-tab.md)이다.
**iOS 와 같은 문서를 기준으로 삼는다.** 거기 없는 동작은 어느 쪽에서도 임의로 만들지
않는다 — 두 앱이 갈리는 것은 화면을 만들 때가 아니라 규칙이 한쪽에만 적혀 있을 때다.

## 화면은 Jetpack Compose 로 짓는다

**SwiftUI 와 같은 선언형이라 iOS 코드가 구조를 유지한 채 옮겨진다** — `@State` 가
`remember`, `VStack` 이 `Column` 이 된다. 뷰 + XML 로 가면 같은 화면에 RecyclerView ·
Adapter · ViewHolder · DiffUtil 이 붙어 코드가 두세 배가 되고, 그만큼 두 앱의 구조가
갈려 위의 "iOS 와 같은 문서를 기준으로" 를 지키기 어려워진다.

지도만 예외다. 네이버가 Compose 용 지도를 내놓지 않아 `MapView` 를 `AndroidView` 로
감싼다 (`searchtab/NaverMap.kt`) — iOS 가 `UIViewRepresentable` 로 하는 것과 같은 일이다.

컴파일러 플러그인이 필요하다. `//tools/bazel/kotlin:compose_compiler_plugin` 이며
**버전이 Kotlin 컴파일러와 같아야 한다.** 빠뜨리면 컴파일은 되는데 화면이 갱신되지
않는다 — 조용히 잘못 도는 쪽이라 찾기 어렵다.

## 지금 어디까지 됐나

| | 상태 |
| --- | --- |
| 지도 · 첫 진입 카메라(남한 전체) | 됨 |
| 작품 / 장소 두 탭, 첫 화면 「인기 N」 표기 | 됨 |
| 카테고리 칩 — 목록과 지도를 **같이** 좁힌다 | 됨 |
| 검색 이어 받기·총수 | 서버 total 표시, 중복 행 제외, 분류별 남은 장소 조회, 실패 시 재시도 |
| 장바구니 담기(＋ → ✓) | 서버 계약 호출, 설치 UUID로 비회원 계정 식별 |
| 작품 찜(♡ → ♥) | 서버(`/favorites/contents`)가 정본, 기기에는 사본 (MZ2AZ-335) |
| 핀 번호 — 첫 화면 작품 탭에서만 민 핀 | 됨 |
| 자동완성 · 작품 드릴다운 · 지도 범위 검색 · 현위치 | 구현됨 |
| 온보딩 — 스플래시(핀 낙하 애니메이션) + 사용법 4장 | 됨. 버전 플래그라 내용이 바뀌면 이미 본 사람에게도 다시 보인다 |
| RootTabs — 검색·홈·커뮤니티 + 코스·마이페이지 입구 | 구현됨 |
| 홈 — 내 여행 이어가기 · 지금 뜨는 작품 · 오늘의 성지 · 커뮤니티 지금 · 내 기록 | 서버·기기 값을 사용 |
| 코스 — 편집·저장·공유·여행 안내·가이드 | 구현됨. 촬영지·POI·개인 핀을 구분하고 서버 항목 ID를 보존 |
| 장소·POI 리뷰 | 평점 요약·정렬·페이지, 작성·수정·삭제, 프로필의 내 리뷰 |
| 사진첩 | 공식·방문자 사진, 공공누리 원본 비율 보존, 검은 전체 화면·격자·출처·추가 페이지·리뷰 연결 |
| 리뷰 사진 업로드 | 최대 10장, JPEG 축소·회전·위치 메타데이터 제거, 실패한 사진 재시도 |
| 커뮤니티·내 글 | 서버 조회·게시·삭제·코스 사본, 사진 최대 8장, 게시 응답 소실 시 중복 전송 방지. 옛 기기 글은 비공개 읽기로 보존 |
| 주변 지도·일차 머무는 시간 | 편의시설을 각각 실제 좌표의 점으로(묶음 핀·구성원 목록 없음, MZ2AZ-403), 선택 해태 강조, 저장한 이동 시간과 머무는 시간 조정, 큰 글자 요약 줄바꿈 |
| 서버 인기도 기록 | 상세 열기·검색 확정의 생성 EventsApi 호출. 전용 클라이언트에서 인증 갱신·재시도 없이 전송 |
| Google 로그인·닉네임·프로필 | 로그인 안내, 닉네임 검증·수정, 계정 전환 시 늦은 응답 무시 |
| 요청 재시도·사용량 | 읽기·안전한 쓰기만 제한 재시도, 유료 호출 중복 방지, 서버 한도·지도 앱 연결 |
| 안내 중 가이드 입구 | 배너 얼굴로 전환, 카드·서랍·핀 찍기 상태별 한 곳만 표시. 지도 앱 링크는 줄바꿈 |
| 신고·차단·문의 (MZ2AZ-461) | 남의 리뷰·여행후기·홈 「커뮤니티 지금」의 「⋯」 → 신고(이유 여섯 + 설명)·이 사용자 차단, 마이페이지 「차단한 사용자」·「문의하기」, 금칙어 안내. [계획](../../docs/project/plans/android-moderation.md) |
| 코스 상세 실패 보호·가이드 시작·주변 촬영지·첫 안내 (MZ2AZ-474) | 상세를 못 받은 코스는 편집 화면을 열지 않고 「다시 시도」, 저장은 막음. 지도 중심 기준 가이드 시작 화면, 모델 없는 「지도 주변 촬영지 보기」, 새 직접 짜기의 장바구니 첫 안내. [계획](../../docs/project/plans/android-first-course-guide.md) |
| 코스 지도 핀 확대·내 위치 정확도 (MZ2AZ-509) | iOS #326·#327 이식. 번호 핀·목록 행을 누르면 그 정지점으로 줌 14 확대(내 위치가 3 km 안일 때만 나와 함께, 멀면 그곳만), 카드를 닫으면 포커스를 풀고 일차 전체로 다시 맞춤(`NaverMap.focusOnStop`, `RouteStopFocus`). 내 위치는 `LocationFix` 규칙(10초 넘은 값·정확도 모름 버림, 더 정확하거나 조금 덜 정확해도 더 새 값으로 갈아탐, 여행 중 100 m 넘는 값 버림)으로 거른 값만 쓰고, 검색 지도는 계속 받아 점을 옮기며 다시 누르면 가진 최신 값으로 바로 반응, 한 번 읽기(마법사·코스 편집 첫 위치)는 3초 동안 가장 나은 값을 기다림. Android 12+ 「대략적 위치」만 허용이면 정확한 위치를 다시 요청. 시험: `tests/LocationFixTests.kt` |
| 여행 중 화면 동기화 (MZ2AZ-478) | iOS 여행 화면 이식. 도착 「발바닥」 스탬프(`PawStampOverlay`, 1.6초)와 「N번 성지 도착!」·「N번 …로 가는 중」, 도착 알림 카드(지도 위)와 가는 중 안내 띠 분리, 머무름 힌트(「머무르면 도착 · N분」), 길찾기 오류 8종 문구와 재시도 단추 조건(`RouteNavFailure`), 한도(429) 안내와 카카오맵·네이버 지도 넘기기(`TripLimitNotice`, 복귀 안내), 경로선 도보 점선·대중교통 실선·지나온 구간 자르기(`RouteTrail`)·미리보기 점선, 정지점 핀(방문=발바닥·고른 곳/목적지=해태·담을까 보는 곳=빨간 해태)과 내 위치 파문, 번호 핀 카드(`RouteStopCard` 장면 설명·별점·「여기로 길찾기」, 편의시설 줄은 `RoutePlaceCard`), 저장 안 된 정지점은 먼저 저장하고 길찾기 시작, 영문 안내 언어 표시·「Line N」 지하철 판정·큰 글자 요약 줄바꿈, 행의 큰 발바닥 배지·발자취 단추 이름. 시험: `tests/TripScreenTests.kt` |
| 코스 편집기 정리 (MZ2AZ-482) | 저장된 코스의 아래 줄에서 「저장하고 닫기」를 없애고(저장은 머리줄 하나, 새 코스만 「코스 만들기」, MZ2AZ-368) 계획/안내 줄을 `TripPlanControls`·`TripControls`로 나눔, 장소 검색은 서버 조회(`RoutePlaceSearch` — 입력이 멎은 350ms 뒤 한 번, 앞 요청 취소, 40개씩 이어 받기, 고른 것은 지도에 미리보기 핀, MZ2AZ-372), 편의시설 묶음 핀·`PoiClusters` 제거하고 실제 좌표 핀(`PoiMarkerRules`, MZ2AZ-403), 마법사 문구(방문 수 각주)와 일정 생성 실패 문구(`RouteGuideFailure`), 큰 글자에서 상단바·하단 줄을 세로로 쌓고(MZ2AZ-388) 해태 칩이 바닥 카드 위로 비켜 섬(MZ2AZ-367). 시험: `tests/TripScreenTests.kt`·`tests/VisualParityRulesTests.kt` |
| AI 기능 동의 (MZ2AZ-468) | 첫 가이드 질문·첫 AI 코스 만들기 전 「AI 기능 사용 안내」 시트, 마이페이지 토글. [계획](../../docs/project/plans/android-ai-consent.md) |
| 위치기반서비스 이용 동의 (MZ2AZ-472) | 기기 좌표를 서버로 보내기 전 「위치기반서비스 이용 동의」 시트, 마이페이지 토글, 길 안내 성공 때 카카오 제공 알림. [계획](../../docs/project/plans/android-location-consent.md) |
| 담아 둔 항목의 숨김·폐업 안내 (MZ2AZ-458) | 장바구니(검색·마이페이지 같은 행)·내 코스·찜한 작품에 「더 이상 소개하지 않는 장소/작품이에요」·「폐업했어요」와 빼기. 상세의 새 담기·찜은 막고 409(`PLACE_DELISTED` 등)는 종류별 안내, `DUPLICATE_CART_ITEM`만 「이미 저장됨」. 숨김·폐업 행은 다음 목적지·거리·계획선·최적화·길찾기에서 제외. [계획](../../docs/project/plans/android-delisted-items.md) |
| 이용 통계 수집 (MZ2AZ-475) | 마이페이지 「이용 통계 수집」 토글(기본 켜짐, `SharedPreferences("scenetrip")`의 `scenetrip.analytics.collectionEnabled`). 끄면 `AppAnalytics`가 이벤트·사용자 속성을 입구에서 버리고 `setAnalyticsCollectionEnabled(false)`로 Firebase 자동 수집까지 끈다. 매니페스트 `firebase_analytics_collection_enabled=false` 위에서 시작 때 저장값을 적용한다. ATT는 iOS 전용이며 Meta 광고 측정은 아직 없다. [Meta·광고 ID 계획](../../docs/project/plans/android-meta-ads-ad-id.md) |
| 검색 탭 동작 (MZ2AZ-479) | 자동완성에서 고른 장소는 결과가 오면 바로 선택·확대(첫 쪽에 없으면 상세를 받아 연다). 담기(＋)는 줌을 두고 그 핀만 가운데로 이동. 「현 지도 내 성지 검색」은 열린 상세를 닫고 카메라를 건드리지 않으며 「486곳 중 200곳」 표기. 핀 이름표는 28자·132dp로 줄이고 같은 좌표 핀은 약 8m씩 벌림. 「내 위치」에 레이더 파문. 오류 화면은 오프라인·429·502~504 문구와 500의 traceId. 계정이 바뀌면 장바구니 사본을 비우고 늦은 응답은 버림. 장바구니 409는 `DUPLICATE_CART_ITEM`만 「이미 저장됨」(MZ2AZ-458)이고 담기·빼기 토스트는 검색 탭 하단에 2초 |
| 만료된 서명 사진 주소 다시 받기 (MZ2AZ-477) | 리뷰 시트·내 리뷰·여행후기(목록 대표 사진·상세)의 사진을 못 받으면 글·리뷰를 다시 읽어 새 주소로 바꾼다(`data/PhotoRenewal.kt`, iOS `PhotoRenewal`). 갱신은 화면마다 하나만 돌고 동시에 실패한 사진은 합류하며, 저절로 하는 것은 30초에 한 번·사진마다 한 번이다. 「다시 받기」는 간격과 상관없이 나간다. 리뷰·내 리뷰는 읽은 만큼 모든 쪽을 다시 받고 한 쪽이라도 못 받으면 목록을 그대로 둔다. 여행후기는 사진·본문 블록·출처·AI 표시를 같은 상세 응답으로 바꾸고 기기 옛 글은 서버에 묻지 않는다. 글·계정이 바뀐 뒤 늦게 온 응답은 버린다. 사진첩은 기존 `PhotoBook.renew`를 그대로 쓴다 |
| 영어 | iOS의 최신 영어 문구에서 변환. Android 전용 문구는 `TranslationExtras.kt` |
| 약관·정책·탈퇴·AI 안내 (MZ2AZ-455·452·439) | 아래 「약관 링크·탈퇴 문구·AI 답변 안내」 |

`SceneData`가 생성된 계약 클라이언트로 서버 검색·상세·자동완성을 호출한다.

화면 구현은 iOS 에서 확정된 순서(§3-1 화면 구조 → §3-2 검색 범위 → §3-3 자동완성 →
§3-5 칩 → §3-6 오류 화면)를 그대로 따라간다. 온보딩·홈은 iOS 소스와
[홈 탭 계획](../../docs/project/plans/mobile-home-tab.md)을 그대로 따랐다.

`apps/scenetrip-ios`가 동작 대조군이다.
[실화면 대조 계획](../../docs/project/plans/android-visual-e2e-parity.md)과
[실행 중인 검증 기록](../../docs/qa/android-visual-e2e-parity.md)에 최신 반영·미검증 경로를 남긴다.
[최신 iOS 기능 반영 계획](../../docs/project/plans/android-ios-parity.md)과
[검증 기록](../../docs/qa/android-ios-parity-verification.md)에 범위와 확인 결과를 남긴다.
가상 GPS는 앱 기능 대신 Android 에뮬레이터의 위치 주입으로 확인한다.

## 인터페이스

2026-10-13 동기화는 [계획](../../docs/project/plans/android-ios-sync-2026-10.md)과
[검증 기록](../../docs/qa/android-ios-sync-2026-10.md)을 따른다. 화면 모드는 시스템·라이트·다크를
선택해 즉시 반영하며 앱 재시작 뒤에도 유지한다. 상세 포스터는 제작사 공식 주소로,
실제 씬 사진은 별도 확대 화면으로 연결한다. 홈 작품은 서버 인기순 첫20개이며,
방문은 서버 저장이 성공한 뒤에만 완료한다. 기기 옛 후기 찾기와 발자취 기록 동의를 제공한다.

iOS431의 main 반영 뒤 작품 포스터는 홈·목록·자동완성·상세의 고정 칸을 가운데 정렬로 채운다.
원본 비율이 다르면 가장자리가 잘리며, 작품 상세의 포스터는 높이176dp·가로 전체로 표시한다.
장소/실제 씬 사진의 원래 비율과 제작사 링크의 검증은 유지한다.
자동완성 장소 행의 아랫줄은 `placeType` 표시말(`data/PlaceType.kt` 표)과 서버가 현지화한 `contentTitle` 을 ` · ` 로 이어 적는다(MZ2AZ-450, `SuggestionDetail`). 표에 없는 유형은 빼고, 둘 다 없으면 `subtitle`(주소)을 쓴다. 작품·인물 행은 그대로다.
닉네임 정하기·변경은 작성자 미리보기·글자 수·지우기·스크롤 본문·하단 저장/취소를 제공한다.
큰 글자·키보드는 큰 시트로 열고, 저장 중 입력·닫기를 잠그며 계정 전환 뒤 응답은 UI에도 적용하지 않는다.
[Android431 계획](../../docs/project/plans/android-home-profile-polish.md)의 별도 모의 응답 UI는
`just android-nickname-qa edit ko light`로 확인한다. `welcome`, `busy`, `stale`, `failure`와
`en`, `dark`를 조합할 수 있다. 제품 계정과 API 쓰기를 실행하지 않는다.

커뮤니티 여행후기는 첫 사진 하나를 대표로, 본문은 계약1.13의 bodyBlocks 순서로 표시한다.
작성 시 사진별 문단 위치·설명·출처·AI 표시를 정하고 상단 미리보기로 확인한다.
사진 삭제 뒤 남은 사진의 UUID에 연결된 배치를 유지하며, 기존 글은 읽기 시에만 문단별로 배치한다.
원래 비율의 사진을 눌러100~400% 확대·이동하며 실패한 사진은 다시 받을 수 있다.
블록 배치까지 같은지 확인해야 불확실한 게시를 성공으로 처리한다.
`just android-community-blog-qa compose ko light`는 별도 QA 설치본의 합성 초안만 사용한다.
read/legacy/retry/pending/failed/portrait 및 en/dark 조합과 검증 범위는
[Android 블로그 검증](../../docs/qa/android-community-blog.md)을 따른다.
사진 시험 서버를 유지한 뒤 `just android-community-blog-qa-test`로 세로 대표 사진이 제목을
덮지 않는 실제 픽셀 회귀를 검사한다. 이 시험은 기기 글자 배율을 원래 값으로 복원한다.

사진의 두 손가락 입력 시험은 제품 데이터와 분리한다. 별도 터미널에서
`just android-photo-qa-server`를 유지한 뒤 `just android-photo-qa-test`를 실행한다.
수동 화면 확인은 `just android-photo-qa ok`, `failure`, `missing`으로 나눈다.
시험 APK(`com.mz2az.scenetrip.qa`)는 제품 앱·계정·자료를 변경하지 않으며,
실제 공개 데이터의 씬 연결 부족이나 실기기 검증을 대신하지 않는다.

로컬 MinIO 사진은 서명 URL의 Host·query를 치환하지 않고 이미지 DNS에서만
에뮬레이터 호스트에 연결한다. 이미지 요청에는 API 인증 헤더를 넣지 않는다.

코스 마법사와 새 코스 편집은 시스템 글자 크기를 바꿔도 답변·이름·선택 일차·장소를 유지한다.
코스 지도는 실제 지도 높이와 편집 패널 높이로 카메라 여백을 계산하며 번호 핀의 상단 그림자도 고려한다.
`just android-run`으로 최신 제품 APK를 설치한 뒤 `just android-route-font-qa-test`로
한국어 화면의 글자 배율1.0/1.6 회귀를 확인한다. 기존 편집 초안이 없는 시험 기기에서만 실행한다.
이 시험은 제품 앱의 저장하지 않은 초안을 만들고 버린 뒤 원래 글자 배율을 복원하며,
유료 가이드·서버 코스 저장을 호출하지 않는다. 회전·프로세스 종료 복원 시험은 별도다.

신고·차단(MZ2AZ-461)은 iOS PR #299 를 문구·동작 그대로 옮겼다. 「⋯」 는 남의 리뷰·여행후기 목록 줄과 상세 작성자 줄·홈 「커뮤니티 지금」에만
있고(내 글·기기 옛 글에는 없다), 「이 사용자 차단」 은 가입한 사람만(비회원은 로그인 시트)·탈퇴한 작성자에게는 두지 않는다. 차단하면 그 글을 바로
빼고 나가 있던 목록 응답을 세대 수로 버린 뒤 첫 쪽을 새로 받는다. `ModerationEvents`(앱 전체 신호)가 오르면 이미 열려 있는 촬영지 상세·편의시설 카드·
리뷰 시트가 방문자 사진을 먼저 비우고 다시 받는다. 마이페이지 「차단한 사용자」(로그인 때만)는 목록·「차단 풀기」, 「문의하기」는
`support@scenetrip.io` 를 글자로 보이고(길게 눌러 복사) 「메일 쓰기」로 메일 앱을 연다. `400 TEXT_NOT_ALLOWED` 는 리뷰·여행후기·닉네임·코스 저장 모두
「쓸 수 없는 말이 들어 있어요. 고친 뒤 다시 저장해 주세요.」 로 안내한다. 누구나 읽는 창구(촬영지·편의시설 상세, 사진첩, 여행후기)도 부르기 전에
만료된 액세스 토큰을 갱신해 차단한 사람의 글이 다시 섞이지 않는다(`AuthRules.refreshesBeforeRead`). 시험은 `tests/ModerationTests.kt`.

코스 상세 실패 보호(MZ2AZ-474, iOS MZ2AZ-444 몫)는 목록 요약으로 편집 화면을 열지 않는다. 코스 목록·홈의 「내 여행 이어가기」가 상세를 받아
올 때(`routetab/RouteCourseOpening.kt`) 실패하면 편집 화면 대신 「코스를 불러오지 못했어요」+「다시 시도」를 목록 위에 보이고, 상세를 받지
못한 서버 코스(`RouteCourse.canSave`가 거짓)는 저장 단추가 눌리지 않을 뿐 아니라 저장 경로(`data/RouteCourseSession.kt`)도 요청을 보내지 않아
빈 일차로 서버 코스를 덮어쓰지 못한다. 저장 오류(`saveFailure`)는 목록·재료 조회 오류(`failure`)와 나뉜다. 가이드 창은 위치가 없어도 열리며
기준은 기기 위치 또는 지금 보이는 지도 중심(`routetab/RouteGuideAnchor.kt`, 둘 다 있으면 전환 단추)이고, 시작 화면(`RouteGuideStart.kt`)에
추천 질문 넷·「지도 주변 촬영지 보기」·「코스 추천받기 (AI)」·장바구니 미리보기가 있다. 「지도 주변 촬영지 보기」(`RouteNearbySheet.kt`)는
생성된 `GET /places`(bbox 약 3km, 40개)만 부르고 AI 와 상태를 나눈다. 「내 위치로」는 기존 기기 위치를 쓰며 위치 동의는 MZ2AZ-472 몫이다
(`TODO(MZ2AZ-472)`). 서버에 없는 AI 아닌 빈 코스는 기기당 한 번 장바구니 버튼에 링과 문구(`RouteCartHint.kt`)를 보이고, 문구는 장바구니
조회가 끝난 뒤 정해 TalkBack 이 한 번 읽으며 「애니메이션 제거」에서는 링이 움직이지 않는다. 시험은 `tests/CourseDetailSafetyTests.kt`·
`tests/FirstCourseGuideTests.kt`.

홈의 원형 버튼은 탭 행 위로 나온 부분도 터치할 수 있다. 전체 보기는 이전 상세·검색 조건을 지워
인기 작품 목록을 열고, 일반 검색 탭 선택은 이전 상태를 보존한다. `just android-home-qa-test`는
기존 초안이 없는 한국어 시험 기기에서 공개 작품 진입·홈 원 위쪽 터치·전체 보기·홈 복귀를 검사한다.

### 약관 링크·탈퇴 문구·AI 답변 안내

iOS PR #296(MZ2AZ-455)·#305(MZ2AZ-452)·#294(MZ2AZ-439)를 같은 한국어·영어 문구로 옮겼다.

- **약관 링크** — 주소와 문구는 `data/LegalLinks.kt` 한 곳에 있다(`https://scenetrip.io/privacy`, `/terms`).
  마이페이지의 「약관 및 정책」 섹션(개인정보 처리방침·이용약관)은 비회원에게도 보이고 「계정」 바로 위에 있다.
  로그인 시트(`auth/SignInView.kt`)는 Google 단추 아래에 「계속하면 이용약관과 개인정보 처리방침에
  동의하는 것으로 봅니다.」와 두 링크를 둔다. 로그인 시트는 앱 전체에 하나라 모든 로그인 입구가 같은 안내를 본다.
  링크는 앱의 다른 외부 페이지처럼 외부 브라우저로 열고(`ui/ExternalLink.kt`), 누르는 자리는 높이 48dp 이상이다.
  한 줄에 안 들어가면 링크를 위아래로 쌓는다. 시트가 반쯤 높이라 내용이 스크롤된다.
- **탈퇴 확인** — 「…작성한 리뷰·여행후기와 사진이 모두 지워지고 되돌릴 수 없어요.」(`AccountDeletionCopy`).
  작성자가 없는 옛 글의 「탈퇴한 사용자」 표시는 그대로다.
- **AI 답변 안내** — 가이드 창 입력창 아래 회색 한 줄(`routetab/GuideAiNotice.kt`). 글자 배율 1.6 이상에서는 앞
  문장만 보이고 글자 배율을 1.65로 묶으며, 화면 낭독기는 늘 전체 문장을 읽는다.
- 영어 문구는 iOS 번역표에서 `just android-translations`로 가져왔다. iOS 표에서 빠졌지만 Android 주변 지도가 아직
  쓰는 문구 셋은 `TranslationExtras.kt`로 옮겼다. 시험은 `tests/LegalAndAiNoticeTests.kt`.

### AI 기능 동의 (MZ2AZ-468)

iOS의 AI 동의([`ios-ai-consent.md`](../../docs/project/plans/ios-ai-consent.md))를 같은 문구로 옮겼다. 첫 가이드 질문(「다시 시도」·예시 질문 포함)과 마법사의
「AI 로 일정 짜기」 전에 시트 한 장(`ui/AIConsentSheet.kt`)을 띄우고, 허용하면 두 기능이 함께 열린다. 취소·닫기는 입력한 질문과
마법사 선택값을 그대로 둔다. 마이페이지 「AI 기능 사용」 토글을 끄면 철회하고 다음에 다시 묻는다. 동의는 `SharedPreferences`
`scenetrip.aiConsentVersion` 정수(현재 안내 버전과 같을 때만 허용)이고, 저장·철회·게이트는 위치 동의(MZ2AZ-472)가 키만 바꿔 쓰도록
`VersionedConsentStore`/`AIConsentGate`로 일반화했다(`data/AIConsent.kt`).

**요청 계층에서도 막는다.** `AIConsentInterceptor`가 `AuthInterceptor` → `RetryInterceptor` 다음에 붙어 `POST /guide/chat`·`/guide/plan`의
매 전송(인증 갱신 뒤 재전송·자동 재시도 포함)을 검사하고, 동의가 없으면 `AIConsentRequired`로 끝낸다. 허용 확인과 호출 등록이
철회와 같은 잠금이라 첫 검사 뒤 철회돼도 시작되지 않고, 나가 있는 호출은 철회 때 끊긴다. 시험은 `tests/AIConsentTests.kt`.

### 위치기반서비스 이용 동의 (MZ2AZ-472)

iOS의 위치 동의([`ios-location-consent.md`](../../docs/project/plans/ios-location-consent.md))를 같은 문구로 옮겼다. **기기 좌표를 우리 서버로 보내는 순간**에만 묻는다 — GPS(내 위치)에서 온 지도 범위로 촬영지를 찾을 때
(`현 지도 내 성지 검색`), 같은 범위로 코스 편집 지도의 주변 편의시설을 받을 때, 가이드 질문(AI 동의 다음), 출발점이 있는 AI 코스 만들기, 길 안내 요청. 손으로 옮기거나 검색 결과를 고른 지도 범위,
파란 점·도착 판정·발자취처럼 기기 안에서만 쓰는 위치는 묻지 않는다. 시트는 AI 안내와 같은 `ConsentSheet`(`ui/LocationConsentSheet.kt`)이고 약관 링크는 `LegalLinks.LOCATION_SERVICES`
(`https://scenetrip.io/terms#location-services`)다. 취소하면 보내지 않거나(촬영지 검색·편의시설·가이드·길 안내) 위치 없이 이어 간다(AI 코스 만들기). 마이페이지 「위치기반서비스 이용」 토글은
「AI 기능 사용」 바로 아래이고 서로 독립으로 철회한다. 동의는 `SharedPreferences` 정수 `scenetrip.locationConsentVersion`(현재 안내 버전과 같을 때만 허용)이다.

**요청 계층에서도 막는다.** `LocationConsentInterceptor`가 `AIConsentInterceptor` 바로 뒤에 붙어(재시도 안쪽) `/navigation/*`, 기기 출처의 `GET /places`·`/pois` 지도 좌표, 좌표를 실은 `POST /guide/chat`을 동의 없이는
보내지 않고(`LocationConsentRequired`), `POST /guide/plan`은 `latitude`·`longitude`만 빼고 보낸다. 좌표 출처(기기 / 사용자가 고른 지도·목적지)는 호출 스레드 값(`CoordinateSources.with`)으로 싣고
표식이 없으면 기기로 본다. 철회는 전송 시작과 같은 잠금이고 나가 있는 위치 요청을 끊는다. 길 안내는 요청마다 동의를 확인하고, 성공하면 사용자 요청 하나에 한 번
「길 안내를 위해 카카오에 현재 위치를 보냈어요.」 띠를 4초 띄운다. 외부 지도 링크에는 현재 좌표를 싣지 않는다(목적지만). 시험은 `tests/LocationConsentTests.kt`.

| 항목 | 값 |
| --- | --- |
| 프로토콜 | 해당 없음 (클라이언트 앱) |
| 산출물 | `:bin` — 기기/에뮬레이터에 설치하는 APK |
| 계약 | `contracts/openapi/scene-api-v1.yaml` — `//contracts/openapi:scene_api_kotlin_lib` 로 소비한다 |

## 의존성

| 의존 대상 | 이유 |
| --- | --- |
| `services/scene-api` | 직접 import 가 아니라 계약(`contracts/openapi/`)을 통해 |
| `@maven_android//:com_naver_maps_map_sdk` | 지도. 버전은 iOS 와 같은 3.23.3 |
| Jetpack Compose (`@maven_android//:androidx_compose_*`) | 화면. 버전 못은 `MODULE.bazel` 에 있다 |

계약 클라이언트는 Bazel이 생성·컴파일한다. 과거 enum 기본값 생성 오류는 생성기
7.24.0으로 해결됐으며, 현재 `data/SceneData.kt`가 그 클라이언트를 사용한다.

## 빌드가 되는 조건

- **Android SDK 가 필요하다.** Bazel 이 받아오지 못하는 두 가지 중 하나다(다른 하나는
  Xcode). 설치 절차는
  [온보딩 문서](../../docs/engineering/onboarding.md)의 "Android SDK 설치" 절에 있고,
  빠졌는지는 `just doctor` 가 본다.
- 경로는 `.env` 의 `ANDROID_HOME` 에서 오고 **버전은 `MODULE.bazel` 이 고정한다**
  (api_level 36 · build-tools 36.1.0). 경로를 저장소에 박지 않는 이유는 brew 로 깐
  사람과 Android Studio 로 깐 사람이 다르기 때문이다.
- 기존 SDK의 command-line tools가 있으면 `just android-sdk-install <SDK 경로>`로
  고정된 플랫폼·빌드 도구만 설치한다. 라이선스 동의가 없으면 자동 승인하지 않는다.
- **iOS 와 달리 태그로 걸러 내지 않는다.** Android 는 리눅스에서 지어지므로 기존
  ubuntu `verify` 잡이 그대로 검사한다. `tags = ["ios"]` 같은 것을 붙이면 오히려 검사
  범위에서 빠진다 — 계획서 §5-2.
- Kotlin 포맷은 Bazel에 고정된 `//:ktlint`로 검사한다. 별도 호스트 설치는 필요 없다.

## 명령

```bash
just build-module apps/scenetrip-android    # 빌드
just android-run                            # 에뮬레이터에 띄운다
just test //apps/scenetrip-android:unit_test  # 순수 규칙·HTTP 재시도 회귀 테스트
just android-translations                   # iOS 영어 문구 변경 반영
just android-fmt                            # Android 소스 포맷
```

`just android-run` 은 AVD 가 없으면 만들고, 에뮬레이터가 꺼져 있으면 부팅을 기다린
뒤 설치·실행한다. 끝나도 에뮬레이터는 살아 있어서, 다시 부르면 빌드·설치만 한다.
Android Studio를 실행할 필요는 없다. 창 없는 실행 환경에서 프로세스가 자동 정리되면
별도 터미널의 `just android-emulator`로 에뮬레이터를 유지한 뒤 `just android-run`을 실행한다.
`just android-device <인자...>`는 기기 상태·탭·위치 주입에,
`just android-screenshot <파일>`·`just android-ui`는 화면 검증에 사용한다.

`just ios-run` 이 `bazel run` 한 줄인 것과 달리 스크립트를 거친다 — `android_binary`
는 APK 만 내놓을 뿐 설치·실행을 하지 않기 때문이다. 이유는
`tools/scripts/android-run.sh` 머리말에 적혀 있다.

**에뮬레이터는 별도 패키지다.** 빌드에 필요한 platform·build-tools 만으로는 뜨지
않는다. 없으면 스크립트가 받을 명령을 알려주고 멈춘다 (약 1.5GB, onboarding.md 참고).

## 설정

| 환경변수 | 필수 | 기본값 | 용도 |
| --- | --- | --- | --- |
| `ANDROID_HOME` | 예 | 없음 | Android SDK 경로. `.env` 에 적는다 |

네이버 지도 클라이언트 ID 는 지도 SDK 연동 때 빌드 시점 주입으로 붙는다 — 소스에
박지 않는다. iOS 는 `.env` → `--define` → 생성 파일 경로를 쓴다(`apps/scenetrip-ios`
참고). Android 도 같은 방식을 따른다.

API 주소의 기본값은 에뮬레이터에서 호스트를 가리키는 `http://10.0.2.2:8081/v1`이다.
DEV·PRD는 환경의 실제 HTTPS 주소를 명시한다. 아래 도메인은 예시다.

```bash
just mobile-build-cloud android https://api.example.com/v1
```

Bazel이 `scenetrip_api_base_url` 값을 검증해 `ApiConfiguration.kt`를 생성한다.
HTTP 원격 주소·인증정보·query·fragment·잘못된 포트·`/v1` 이외 경로는 빌드 단계에서
거절하므로 URL 오류로 앱이 시작 중 종료되지 않는다. 빈 값은 일반 로컬 빌드에서만
기존 기본값을 사용한다. API 주소는 공개 설정이며 모델 키·DB 자격 증명을 넣지 않는다.
명령은 APK를 빌드하며 기기 설치·서명·Play Store 배포는 수행하지 않는다.

## 운영

스토어 배포는 이 계획의 범위 밖이다 (MZ2AZ-148 완료 조건은 "로컬 실행"까지).
`deploy/` 는 서명·fastlane 설정이 생길 때 채운다 — k8s 매니페스트가 아니다
(AGENTS.md §3).
