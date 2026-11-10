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
| RootTabs — 검색·홈·커뮤니티 + 코스·마이페이지 입구 | 구현됨. 탭바는 바닥에 떠 있는 알약(아래 「웹 시안 반영 P1·P2」) |
| 홈 — 「어디로 여행 가세요?」 사진 카드 · 지금 뜨는 작품 · 오늘의 성지 · 커뮤니티 지금 · 내 기록, 떠 있는 「여행 코스 짜기」 | 서버·기기 값을 사용. 「내 여행 이어가기」 카드는 없앴다(MZ2AZ-547) |
| 코스 — 편집·저장·공유·여행 안내·가이드 | 구현됨. 촬영지·POI·개인 핀을 구분하고 서버 항목 ID를 보존 |
| 장소·POI 리뷰 (MZ2AZ-476) | iOS와 같은 구성: 머리줄(「리뷰」+대상명)→요약(평균·별·점수 막대)→방문자 사진 줄→정렬 세그먼트→행(닉네임·「내 리뷰」·상대 날짜·별·고치기)→더 보기/「더 불러오지 못했어요 · 다시 시도」→쓰기 캡슐. 첫 쪽은 스피너/실패/목록 3상태, 빈 상태 두 줄, 로그인→닉네임→쓰기 이음. 작성은 별 선택·카드형 글 입력·고정 오류 띠·사진 칸 상태(올리는 중/멈춤/다시 시도/경고)와 iOS 실패 문구 8종, 내 리뷰는 종류 아이콘·「내 리뷰 고치기·지우기」·영어 편의시설 이름 조회 |
| 사진첩 | 공식·방문자 사진, 공공누리 원본 비율 보존, 검은 전체 화면·격자·출처·추가 페이지·리뷰 연결 |
| 리뷰 사진 업로드 | 최대 10장, JPEG 축소·회전·위치 메타데이터 제거, 실패한 사진 재시도 |
| 커뮤니티·내 글 | 서버 조회·게시·삭제·코스 사본, 사진 최대 8장, 게시 응답 소실 시 중복 전송 방지. 옛 기기 글은 비공개 읽기로 보존 |
| 주변 지도·일차 머무는 시간 | 편의시설을 각각 실제 좌표의 점으로(묶음 핀·구성원 목록 없음, MZ2AZ-403), 선택 해태 강조, 저장한 이동 시간과 머무는 시간 조정, 큰 글자 요약 줄바꿈 |
| 서버 인기도 기록 | 상세 열기·검색 확정의 생성 EventsApi 호출. 전용 클라이언트에서 인증 갱신·재시도 없이 전송 |
| Google 로그인·닉네임·프로필 | 로그인 안내, 닉네임 검증·수정, 계정 전환 시 늦은 응답 무시 |
| 마이페이지·홈·커뮤니티·로그인 정합 (MZ2AZ-480) | 로그아웃·탈퇴·세션 소실 때 `AccountDataReset`이 찜·코스·스탬프·「지도에 발자취 보기」를 먼저 비운다(옛 기기 찜을 새 계정에 올리지 않는다). 마이페이지는 「내 리뷰」를 커뮤니티 절 안에, 닉네임 옆 연필, 발자취 꼬리말, 당겨서 새로고침. 홈의 코스 카드는 받는 중/못 받음/정말 없음을 가르고 네 요청을 나란히 보낸다. 커뮤니티 탭 맨 위에 「이 기기에만 저장된 옛 글」 구역. 로그인 단추는 구글 지침 모양(공식 G, 흰 바탕), 닉네임·로그인 실패 문구는 code 기준. 날짜는 앱 언어 로케일 |
| 가이드 챗봇 오류·한도·대화 유지 (MZ2AZ-481) | `RouteGuideFailure`(iOS와 같은 14갈래·문구)와 `GuideLimit`(N분 뒤/내일/모름, 남은 양 줄), 「다시 시도」는 같은 멱등 키. 대화는 `GuideChat`(순수 규칙)+`RouteGuideSession.shared`가 코스별로 든다. 공용 오류 문구는 429·5xx·오프라인을 따로 말한다. 시험은 가짜 전송만 쓴다 |
| 가이드 창을 닫으면 자동 다시 보내기를 끊는다 (MZ2AZ-481 남은 몫) | iOS `setAttended`를 옮겼다. `RouteGuidePanel`이 접히거나 화면을 떠나면 `GuideChat.setAttended(false, panel)`로 알리고(창마다 제 값을 든다 — 마지막 창이 사라져야 닫힌 것), 다시 보내기·되묻기(자동 재시도·409 되묻기·429 대기)를 기다리는 턴이면 `ResendEvents.cancelCalls`가 그 멱등 키의 OkHttp 호출을 끊어 `RetryInterceptor`의 대기 반복이 더 보내지 않는다(코루틴만 끊으면 막힌 호출이 뒤에서 계속 돈다). 처음 보낸 요청은 끊지 않고, 창이 닫힌 채 다시 보내기 알림이 오면 그때 끊는다. 끊긴 턴은 `중단됨`으로 남고 대화는 그대로라, 같은 코스의 창을 다시 열어 「다시 시도」를 누르면 같은 키로 이어진다. 시험: `tests/GuideCloseTests.kt`(가짜 전송 + 가짜 서버 위의 실제 `RetryInterceptor`) |
| 비회원 「이 기기의 기록 지우기」 (MZ2AZ-471) | iOS #313 이식. 비회원 마이페이지(계정 절 자리, 가입자에게는 숨김)에 빨간 「이 기기의 기록 지우기」+꼬리말 「이 기기의 발자취도 함께 지워져요.」 → 확인 창 → `DELETE /v1/me`를 **토큰 없이 설치 UUID(`X-Install-Id`)만** 보낸다(`auth/GuestDataDeletion.kt` — 인증·재시도 계층을 거치지 않는 전용 OkHttp 전송, 204만 성공, 자동 반복 없음). 204에서만 장바구니·코스·스탬프·찜·발자취(`FootprintStore.forgetOwner`)를 `AccountDataReset`으로 비우고 계정 변경 신호를 올린다. 설치 UUID·로그인 상태는 그대로다. 가입한 설치본의 `401 SESSION_REQUIRED`는 「로그인한 뒤 … 회원 탈퇴」 안내, 그 밖의 실패는 데이터를 보존하고 실패 문구. 나가 있는 쓰기가 끝난 뒤 보내고, 도중에 로그인·계정 전환이 있으면 응답을 버리며, 실행 중 중복을 막는다(`GuestDataDeletion.run`). `CartStore`·`RouteStore`가 `AccountDataReset`을 듣고(약한 참조) 코스 목록은 받는 도중 지워지면 되살리지 않는다. 시험: `tests/GuestDataDeletionTests.kt` |
| 요청 재시도·사용량 | 읽기·안전한 쓰기만 제한 재시도, 유료 호출 중복 방지, 서버 한도·지도 앱 연결 |
| 안내 중 가이드 입구 | 배너 얼굴로 전환, 카드·서랍·핀 찍기 상태별 한 곳만 표시. 지도 앱 링크는 줄바꿈 |
| 신고·차단·문의 (MZ2AZ-461) | 남의 리뷰·여행후기·홈 「커뮤니티 지금」의 「⋯」 → 신고(이유 여섯 + 설명)·이 사용자 차단, 마이페이지 「차단한 사용자」·「문의하기」, 금칙어 안내. [계획](../../docs/project/plans/android-moderation.md) |
| 코스 상세 실패 보호·가이드 시작·주변 촬영지·첫 안내 (MZ2AZ-474) | 상세를 못 받은 코스는 편집 화면을 열지 않고 「다시 시도」, 저장은 막음. 지도 중심 기준 가이드 시작 화면, 모델 없는 「지도 주변 촬영지 보기」, 새 직접 짜기의 장바구니 첫 안내. [계획](../../docs/project/plans/android-first-course-guide.md) |
| 코스 지도 핀 확대·내 위치 정확도 (MZ2AZ-509) | iOS #326·#327 이식. 번호 핀·목록 행을 누르면 그 정지점으로 줌 14 확대(내 위치가 3 km 안일 때만 나와 함께, 멀면 그곳만), 카드를 닫으면 포커스를 풀고 일차 전체로 다시 맞춤(`NaverMap.focusOnStop`, `RouteStopFocus`). 내 위치는 `LocationFix` 규칙(10초 넘은 값·정확도 모름 버림, 더 정확하거나 조금 덜 정확해도 더 새 값으로 갈아탐, 여행 중 100 m 넘는 값 버림)으로 거른 값만 쓰고, 검색 지도는 계속 받아 점을 옮기며 다시 누르면 가진 최신 값으로 바로 반응, 한 번 읽기(마법사·코스 편집 첫 위치)는 3초 동안 가장 나은 값을 기다림. Android 12+ 「대략적 위치」만 허용이면 정확한 위치를 다시 요청. 코스 편집기를 열 때 일차를 맞추는 범위에는 내 자리를 **같은 지역일 때만**(`RouteGeometry.fitHere` — 한국 안·가장 가까운 정지점 100 km 안) 넣는다. 멀리 있는 자리(에뮬레이터 기본 캘리포니아)를 넣으면 지도가 세계 줌에 멈추고 핀이 안 보였다(MZ2AZ-511). 시험: `tests/LocationFixTests.kt` |
| 여행 중 화면 동기화 (MZ2AZ-478) | iOS 여행 화면 이식. 도착 「발바닥」 스탬프(`PawStampOverlay`, 1.6초)와 「N번 성지 도착!」·「N번 …로 가는 중」, 도착 알림 카드(지도 위)와 가는 중 안내 띠 분리, 머무름 힌트(「머무르면 도착 · N분」), 길찾기 오류 8종 문구와 재시도 단추 조건(`RouteNavFailure`), 한도(429) 안내와 카카오맵·네이버 지도 넘기기(`TripLimitNotice`, 복귀 안내), 경로선 도보 점선·대중교통 실선·지나온 구간 자르기(`RouteTrail`)·미리보기 점선, 정지점 핀(방문=발바닥·고른 곳/목적지=해태·담을까 보는 곳=빨간 해태)과 내 위치 파문, 번호 핀 카드(`RouteStopCard` 장면 설명·별점·「여기로 길찾기」, 편의시설 줄은 `RoutePlaceCard`), 저장 안 된 정지점은 먼저 저장하고 길찾기 시작, 영문 안내 언어 표시·「Line N」 지하철 판정·큰 글자 요약 줄바꿈, 행의 큰 발바닥 배지·발자취 단추 이름. 시험: `tests/TripScreenTests.kt` |
| 코스 편집기 정리 (MZ2AZ-482) | 저장된 코스의 아래 줄에서 「저장하고 닫기」를 없애고(저장은 머리줄 하나, 새 코스만 「코스 만들기」, MZ2AZ-368) 계획/안내 줄을 `TripPlanControls`·`TripControls`로 나눔, 장소 검색은 서버 조회(`RoutePlaceSearch` — 입력이 멎은 350ms 뒤 한 번, 앞 요청 취소, 40개씩 이어 받기, 고른 것은 지도에 미리보기 핀, MZ2AZ-372), 편의시설 묶음 핀·`PoiClusters` 제거하고 실제 좌표 핀(`PoiMarkerRules`, MZ2AZ-403), 마법사 문구(방문 수 각주)와 일정 생성 실패 문구(`RouteGuideFailure`), 큰 글자에서 상단바·하단 줄을 세로로 쌓고(MZ2AZ-388) 해태 칩이 바닥 카드 위로 비켜 섬(MZ2AZ-367). 시험: `tests/TripScreenTests.kt`·`tests/VisualParityRulesTests.kt` |
| AI 기능 동의 (MZ2AZ-468) | 첫 가이드 질문·첫 AI 코스 만들기 전 「AI 기능 사용 안내」 시트, 마이페이지 토글. [계획](../../docs/project/plans/android-ai-consent.md) |
| 위치기반서비스 이용 동의 (MZ2AZ-472) | 기기 좌표를 서버로 보내기 전 「위치기반서비스 이용 동의」 시트, 마이페이지 토글, 길 안내 성공 때 카카오 제공 알림. [계획](../../docs/project/plans/android-location-consent.md) |
| 담아 둔 항목의 숨김·폐업 안내 (MZ2AZ-458) | 장바구니(검색·마이페이지 같은 행)·내 코스·찜한 작품에 「더 이상 소개하지 않는 장소/작품이에요」·「폐업했어요」와 빼기. 상세의 새 담기·찜은 막고 409(`PLACE_DELISTED` 등)는 종류별 안내, `DUPLICATE_CART_ITEM`만 「이미 저장됨」. 숨김·폐업 행은 다음 목적지·거리·계획선·최적화·길찾기에서 제외. [계획](../../docs/project/plans/android-delisted-items.md) |
| 이용 통계 수집 (MZ2AZ-475) | 마이페이지 「이용 통계 수집」 토글(기본 켜짐, `SharedPreferences("scenetrip")`의 `scenetrip.analytics.collectionEnabled`). 끄면 `AppAnalytics`가 이벤트·사용자 속성을 입구에서 버리고 `setAnalyticsCollectionEnabled(false)`로 Firebase 자동 수집까지 끈다. 매니페스트 `firebase_analytics_collection_enabled=false` 위에서 시작 때 저장값을 적용한다. ATT는 iOS 전용이며 Meta 광고 측정은 아직 없다. [Meta·광고 ID 계획](../../docs/project/plans/android-meta-ads-ad-id.md) |
| 검색 탭 동작 (MZ2AZ-479) | 자동완성에서 고른 장소는 결과가 오면 바로 선택·확대(첫 쪽에 없으면 상세를 받아 연다). 담기(＋)는 줌을 두고 그 핀만 가운데로 이동. 「현 지도 내 성지 검색」은 열린 상세를 닫고 카메라를 건드리지 않으며 「486곳 중 200곳」 표기. 핀 이름표는 28자·132dp로 줄이고 같은 좌표 핀은 약 8m씩 벌림. 「내 위치」에 레이더 파문. 오류 화면은 오프라인·429·502~504 문구와 500의 traceId. 계정이 바뀌면 장바구니 사본을 비우고 늦은 응답은 버림. 장바구니 409는 `DUPLICATE_CART_ITEM`만 「이미 저장됨」(MZ2AZ-458)이고 담기·빼기 토스트는 검색 탭 하단에 2초 |
| 장면 사진 여러 장 (MZ2AZ-430) | iOS #275 이식. `scenes[].sceneImageUrls`를 서버 순서·반복 주소 그대로 쓰고(`ScenePhotoRules.photos`) 구형 `sceneImageUrl`·포스터로 대신하지 않는다. 카드·설명 시트는 첫 장과 「사진 1 / N」 알약(2장부터), 확대 화면은 확대 줄 아래에 장소 이름과 이전·다음 버튼(끝에서 비활성)과 「사진 i / N」. 쪽을 바꾸면 확대 100%·이미지·오류를 초기화하고 지나간 쪽의 늦은 응답은 버린다. 사진첩을 크게 열면 `view_photos`(`target_type`·`entry` = `detail`·`reviews`·`card`, 리뷰 행 사진은 기록하지 않음, MZ2AZ-476). 시험: `tests/ScenePhotoGalleryTests.kt` |
| 만료된 서명 사진 주소 다시 받기 (MZ2AZ-477) | 리뷰 시트·내 리뷰·여행후기(목록 대표 사진·상세)의 사진을 못 받으면 글·리뷰를 다시 읽어 새 주소로 바꾼다(`data/PhotoRenewal.kt`, iOS `PhotoRenewal`). 갱신은 화면마다 하나만 돌고 동시에 실패한 사진은 합류하며, 저절로 하는 것은 30초에 한 번·사진마다 한 번이다. 「다시 받기」는 간격과 상관없이 나간다. 리뷰·내 리뷰는 읽은 만큼 모든 쪽을 다시 받고 한 쪽이라도 못 받으면 목록을 그대로 둔다. 여행후기는 사진·본문 블록·출처·AI 표시를 같은 상세 응답으로 바꾸고 기기 옛 글은 서버에 묻지 않는다. 글·계정이 바뀐 뒤 늦게 온 응답은 버린다. 사진첩은 기존 `PhotoBook.renew`를 그대로 쓴다 |
| 관광공사 편의시설 안내·사진 출처 (MZ2AZ-470) | iOS #307 이식. 편의시설 카드의 `info`에 값이 있는 개요·이용 시간·쉬는 날·주차·대표 메뉴·입장료만 전화 줄 아래에 표시(`routetab/PoiInfoRules.kt`·`PoiInfoSection.kt`). `info`·필드의 누락·null은 숨기고 긴 안내는 220dp 안에서 스크롤, 서버 줄바꿈 보존, 영업 중 계산 없음. 필드 언어가 앱 언어와 다르면 작은 언어 태그. 「홈페이지」는 HTTP(S)만 브라우저로 연다. 사진은 지도 카드·격자·리뷰 줄이 `thumbnailUrl`(없으면 `url`), 상세·확대가 `url`. `license`가 있으면 「출처: 한국관광공사」 / “Source: Korea Tourism Organization”과 추가 credit을 사진 밖에 적고, `kogl_type3`는 전체 비율·모서리 없음·표식/쪽 번호/닫기를 사진 밖으로(`ReviewRules`, `GalleryStrip`·`GalleryViewer`). 시험: `tests/PoiInfoTests.kt`. [계획](../../docs/project/plans/android-poi-tour-info.md) |
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

## 웹 시안 반영 P1·P2 — 떠 있는 탭바·홈 사진 카드 (2026-11-10, MZ2AZ-547)

기준은 iOS 와 같은 체크리스트 `docs/project/plans/ios-web-parity.md`(브랜치 `MZ2AZ-547-ios-web-parity`) T1–T3·H1–H6 이고,
치수·규칙은 iOS 구현(`FloatingTabBarRules` 등)과 같은 이름·값으로 옮겼다. 순수 규칙은 Compose 형을 쓰지 않아 JVM 시험이 선다
(`tests/WebParityTabBarHomeTests.kt`·`tests/WebParityTrendingTests.kt`).

| 파일 | 하는 일 |
| --- | --- |
| `RootTabs.kt` · `FloatingTabBarRules.kt` · `ui/TabBarParts.kt` | 화면 바닥에 떠 있는 알약(좌우 16 · 내비게이션 막대 위 8 · 높이 58). 세 탭 같은 폭, 고른 탭 뒤 보라 알약이 0.38초에 미끄러짐. 탭 내용은 알약 뒤까지 내려가고 `LocalTabBarClearance`(74 + 내비게이션 막대)만큼 바닥을 비운다 — 홈 목록·커뮤니티 목록·검색 서랍·담기 토스트 |
| `searchtab/BottomSheet.kt` · `BottomSheetRules.kt` | 검색 탭 서랍도 알약 뒤까지 내려간다. 접힘·중간 단은 알약 위에 보이는 몫으로 잰다(`clearance + (전체 − 상태줄 − clearance) × 비율`). 탭 밖(코스 편집)은 0 이라 예전과 같다 |
| `hometab/HomeCityCard.kt` · `HomeCityRules.kt` | 「어디로 여행 가세요?」 사진 카드(높이 250). 앱에 실린 도시 사진 8장(`res/drawable-nodpi/city_cover_*.jpg`, iOS 와 같은 파일)이 4초마다 겹쳐 바뀜, 애니메이션 제거 설정이면 첫 장 고정. 도시 칩은 `GET /places/cities` 서버 순서 최대 8 |
| `hometab/HomeTripFloat.kt` · `HomeTripFloatRules.kt` | 떠 있는 「여행 코스 짜기」. 코스 0개(목록 받은 뒤)면 바로 새 코스, 있으면 ✕ · ≡ · 「+ 새 코스」 · 내 코스(최대 5, 여행 중 먼저) 줄이 오른쪽부터 펼쳐짐. 여행 중이면 단추가 곧 그 코스 |
| `hometab/PinnedWorks.kt` · `TrendingRules.kt` · `TrendingWorksView.kt` | 「지금 뜨는 작품」 맨 앞에 케이팝 데몬 헌터스(32) 고정, 「전체 보기」 는 인기순 포스터 격자 덮개(`Cover.Trending`) |

- **반투명 유리의 흐림(blur)은 없다.** Compose 에서 뒤 내용을 흐리게 하려면 API 31+ 의 `RenderEffect` 로 탭 내용을 따로 그려야 해서,
  바탕 88% 불투명 + 얇은 테두리로 대신한다. 도시 칩도 흐림 대신 옅은 어둠 + 흰 18% 바탕이다.
- **새 코스의 입구는 갈고리 하나다** — 홈의 도시 칩과 「+ 새 코스」 는 `TabRouter.startNewCourse(city)` 를 부른다. 받는 곳은 P3(아래).
- 화면 확인용 뒷문: `-e initialTab trending`(전체 보기 덮개), `--ez homeFloatOpen true`(떠 있는 줄을 펼친 채로).

## 웹 시안 반영 P3 — 새 코스 시트·도시로 여행 짜기·작품/담은 곳 (2026-11-10, MZ2AZ-547)

체크리스트 F1–F2·Q1–Q5·R1–R6·S1·A1–A4. iOS `src/NewCourse/*` 를 같은 이름으로 `newcourse/` 에 옮겼다. 순수 규칙은
`newcourse/NewCourseRules.kt` 하나에 있고 JVM 시험이 선다(`tests/NewCourseRulesTests.kt`·`SavedPlanRulesTests.kt`·`NewCourseEntryTests.kt`).
예전 「AI 로 짜기 / 직접 짜기」 갈림길과 질문 마법사(`RouteWizardView.kt`)는 없앴다 — 편집 화면이 쓰는 초안 상태만 `routetab/RouteEditorDraft.kt` 로 남겼다.

| 파일 | 하는 일 |
| --- | --- |
| `NewCourseSheet.kt` | 「새 여행 코스」 시트(높이 380·회색 바탕): ① 도시로 시작 ② 작품으로 [AI] ③ 담은 곳으로(「담아 둔 N곳을 날마다 나눠 넣어요」/0곳이면 「빈 코스에서 하나씩 넣어요」). 홈 떠 있는 줄 「+ 새 코스」(`TabRouter.newCourseSheet`)와 코스 목록 머리의 「+ 새 코스」(P7)가 연다 |
| `CityQuestionView.kt` | 전체 덮개 질문: Q1 도시(사진 머리 min(46%,390)·최소 300, 도시 칸 2열×8, 받는 중 회색 칸, 실패 「다시 시도」 + 15초 뒤 자동 한 번) → Q2 며칠(당일치기~3박 4일) → Q3 「알아서 짜 주세요」/「직접 고를게요」. 홈 도시 칩은 Q2 부터이고 그때 뒤로는 닫기 |
| `CityPlanPreview.kt` | R1–R6: `GET /places?city=`(200개) 를 **앱 안에서** 잇는다(인기 1위부터 가까운 곳, 하루 5곳). 카드 16:9·번호·↻ 바꾸기·✕ 빼기, **꾹 0.3초 → 들림(1.02·그림자·햅틱), 8dp 넘게 먼저 움직이면 스크롤** — 라이브러리 없이 `pointerInput`+`awaitEachGesture`+`withTimeoutOrNull`, 손가락은 화면 기준으로 잰다. 「이 코스로 만들기」 → `POST`+`PUT /courses`(madeByAI) → `PUT /me/trip` → `TabRouter.courseCreated`(방금 만든 코스 표시 + 6초 안내 띠 + 계획 화면) |
| `CityPickView.kt` | S1 직접 고르기: 도시 촬영지 30곳, [＋ 담기]/[✓ 담음] 은 진짜 보관함. 「N곳으로 코스 만들기」 는 Q2 의 며칠로 고르게 나눠(`splitDays`) 「{도시} 여행」 으로 저장, R5 와 같은 길 |
| `WorksPlanView.kt` · `NewCoursePlanParts.kt` | A1 일정(기간 칩 3열·「떠나는 날」 펼치는 달력·「날짜 지우기」)·다니는 속도 → A2 포스터 3열(찜한 작품 미리 고름, 5개 넘으면 경고) → AI 동의·위치 동의 → `POST /guide/plan` → A3 짜는 중(포스터 부채꼴·요약 칩·0/3.5/8초 세 줄·그만두기) → 저장 전 초안 편집(isNew) |
| `SavedPlanView.kt` | A4: 일정 + 「넣을 곳」(사진 겹침·「담아 둔 곳 N곳」) → 보관함 전부를 시·도 묶음 → 가까운 순으로 잇고 날마다 고르게 나눈 초안 편집 |
| `NewCourseData.kt` | `CityPlacesStore`(도시 목록·도시 촬영지를 앱에 하나로 들고 있음, 언어가 바뀌면 다시 받음) · `TripMemoryStore`(정한 여행 Q4: 켤 때 `GET /me/trip` — 서버가 이기고, 서버에 없고 기기에만 있으면 `PUT`; 바꿀 때 `PUT`, 비회원도 설치 id) |

- 방금 만든 코스(`TabRouter.freshCourseId`)는 계획 화면이 편집 상태로 열고, 저장 없이 나가면 묻고 지운다(P4 E16, 아래).
- `TabRouter` 는 Compose 상태를 들어 JVM 시험에서 만들 수 없다(`android.os.Looper`) — 입구 판단만 `NewCourseEntryRules` 로 떼어 시험한다.
- 화면 확인용 뒷문(iOS 와 같은 이름): `-e newCourse sheet|city|works|saved`, `-e cityFlow 부산 --ei cityDays 2 -e cityHow plan|pick`,
  `-e worksStep works`, `--ez cityAutoSave true`(짜자마자 저장 — 서버에 코스가 생긴다).

## 웹 시안 반영 P4 — 계획 화면 보기/편집·지도 켜고 끄기·＋ 메뉴 (2026-11-10, MZ2AZ-547)

체크리스트 E1–E17. iOS `RouteEditorRules`·`RouteEditorModes`·`CourseAddViews`·`RouteNumberPin` 을 옮겼다. 순수 규칙은
`routetab/RouteEditorRules.kt` 하나에 있고 JVM 시험이 선다(`tests/RouteEditorRulesTests.kt` 60개 — iOS 시험 56개 + 번호 핀·여는 중 덮개).

| 파일 | 하는 일 |
| --- | --- |
| `RouteEditorRules.kt` | 처음 상태(저장된 코스는 보기, 새 초안·방금 만든 코스는 편집)·「취소」 결정 다섯 갈래·저장 뒤·편집 도구/아래 줄을 보이나·「내 위치」 높이(편집 62·보기 10) · 카메라 규칙(`refits`·`moves`: 일차 바꾸기·장소 넣기·처음 펼치기만 맞춤, 끌기·빼기·최적화·서랍 높이·다시 펼치기·카드 닫기는 그대로, 고른 것을 놓기만 하면 옮기지 않음) · 「지도」 알약 · 보관함 고르기(고른 순서) · 넣기 규칙 · 번호 핀 치수 · 여는 중 덮개 |
| `RouteEditorModes.kt` | 보기 머리줄(‹ · 이름 · 「편집」) · 「지도」/「목록만」 알약(왼쪽 아래 16) · 줄 사진 ⓘ 로 여는 장소 상세(전체 화면) · 확인용 뒷문 `RouteEditorDebug` |
| `CourseAddViews.kt` | 떠 있는 ＋(해태 위, 편집 중만) → 「지도에서 추가」(검색 탭을 덮개로 하나 더 — `SearchTabScreen(embedded = true)` 는 홈 쪽지 `TabRouter.pending*` 를 읽지도 지우지도 않는다, 장소 상세 맨 위 「＋ 코스에 추가」/「이미 코스에 있어요」/「✓ N일차에 넣었어요」 → 일차 고르기) · 「보관함에서 추가」(큰 시트, 고른 순서 번호, 「N곳 선택 완료」 → 「몇 일차에 넣을까요?」) |
| `RouteNumberPin.kt` | 코스 번호 핀 — 보라 단색 동그라미 + 흰 테두리 + 꼬리, 30×38dp, 꼬리 끝 (15, 37) |

- 계획 화면 기본은 **목록 전체**(지도 접힘), 여행 중이면 펼친 채. 처음 펼칠 때만 서랍이 자리를 잡은 뒤 코스 전체에 맞춘다. 줄을 누르거나 핀 찍기를 켜면 펼친다.
- 동작 줄은 동선 최적화 · 핀 찍기 둘뿐. 「검색」(`PlaceSearchOverlay`)·「장바구니」 단추와 장바구니 첫 안내(`RouteCartHint*`)는 없앴다(iOS 와 같다).
  가이드 창의 「장바구니 장소 보기」 는 예전 장바구니 시트를 그대로 연다.
- 보기에서는 일차 ＋/−·동작 줄·끌기·밀어서 빼기·출발/도착 고정·편의시설 칩·＋ 가 없다. 저장된 코스를 고치는 동안은 아래 「코스 시작」 줄을 숨긴다.
  보기 중에 가이드가 코스를 바꾸면(담기·빼기·초안) 편집으로 넘어간다.
- 「취소」/뒤로: 새 초안은 닫기(바뀌었으면 「바꾼 내용을 버릴까요?」), 저장된 코스는 편집 전으로 되돌려 보기로(바뀌었으면 묻고),
  방금 만든 코스는 「저장하지 않고 나갈까요? / 방금 만든 코스가 지워져요.」 → 「나가기」 면 코스 목록(`RouteTabView`)의 범위에서 `DELETE`.
  저장된 코스의 「저장」 은 닫지 않고 보기로 돌아간다.
- 코스를 여는 동안(상세 받기) 목록을 덮개와 스피너로 덮고, 실패 띠는 다시 여는 중이 아닐 때만 보인다(E17).
- 확인용 뒷문(iOS 와 같은 이름): `-e editorMode edit`, `--ez editorMap true`, `-e editorAdd menu|saved|map`,
  `--el openCourseId N --ez freshCourse true`(그 코스를 방금 만든 코스로 연다 — 나가기를 누르면 서버에서 지워진다).

## 웹 시안 반영 P5 — 지도 편의시설 칩·지도 검색 (2026-11-10, MZ2AZ-547)

체크리스트 M1–M7 에 같은 날의 고침(웹 72f87416, iOS `MZ2AZ-547-ios-map-search-fix`)을 얹은 최종 동작이다. 순수 규칙은 `routetab/MapPoiRules.kt`·
`routetab/MapSearchRules.kt` 에 있고 JVM 시험이 선다(`tests/MapPoiRulesTests.kt`·`tests/MapSearchRulesTests.kt` 75개 — iOS 시험을 옮기고 몇 개 더했다).

| 파일 | 하는 일 |
| --- | --- |
| `MapPoiRules.kt` | 지도 범위(`bbox`)·칩 상태(처음 다 꺼짐, 켤 때만 받기, 하나만 켜면 `categoryGroup`)·서버 요청(`sort=spread`·`spreadZoom=floor(zoom)`·`limit=200`)·상태 알약·골고루 찍기(72dp 칸마다 1등은 원래 점, 나머지는 작은 점 9/16) |
| `MapSearchRules.kt` | 검색어(2글자, 0.35초, 100자)·목록 줄·지도에 찍을 검색 점·고른 곳을 보이는 지도 가운데로(가린 높이의 반, 카드는 적어도 300dp, 확대 ≥16)·결과 지도 상태(서랍 42%↔78%, 「다시 찾기」) |
| `MapSearchModels.kt` | 검색 목록·결과 지도의 받기(`GET /pois?q` 만, 늦은 답은 취소) |
| `MapPoiBar.kt`·`MapSearchPanel.kt`·`MapSearchResults.kt` | 칩 줄(🔍 · 전체 · 음식점 · 명소 · 숙소 · 교통)과 알약 · 검색칸과 목록 · 엔터 뒤 결과 지도(검색어 알약·서랍) |
| `MapPoiMarkers.kt` | 골고루 점(받은 목록이 바뀔 때만 만들고, 확대하면 그림만 바꾼다)·검색 점·검색에서 고른 해태·고른 곳으로 카메라 옮기기 |
| `RouteEditorMapPoi.kt` | 계획 화면의 칩·검색 상태 묶음(`EditorMapPoi`)·지도 맨 위 자리(`EditorMapTop`)·확인용 뒷문 `MapPoiDebug` |

- 칩 줄은 **편집 중 + 지도 펼침 + 여행 안내 아님** 에서만 지도 맨 위에 뜬다. 보기로 돌아가거나 여행을 시작하면 다 끄고 검색도 닫는다.
- 받기는 칩을 켤 때와 「↻ 이 지역에서 다시 찾기」 를 누를 때뿐이다. 예전의 「지도가 멈출 때마다 · 줌 13 이상」 조회는 없앴다. 지도를 옮기면 점은 그대로 두고 알약만 뜬다.
  기기 위치로 맞춘 범위면 위치 동의를 먼저 묻는다(칩·🔍 둘 다).
- 일정 서랍의 칩 자리에는 「AI 장소」 칩만 남았다.
- 🔍 는 가게·장소만 찾는다(촬영지 탭·사진 핀은 없다). 목록이 떠 있는 동안 찾은 곳을 지도에 점으로 찍고, 새 결과가 올 때까지 앞의 점을 둔다.
  고르면 정보 카드가 뜨고 지도는 시트·서랍·카드가 가리지 않은 곳의 가운데로 간다. 카드를 닫아도 지도는 그대로다.
- 엔터 → 결과 지도: 일정 서랍·「지도」 알약·＋·해태는 비키고 결과 서랍(42%, 손잡이로 78%)이 그 자리를 쓴다. 결과에 맞춘 뒤 사람이 지도를 옮기면 「다시 찾기」.
- 확인용 뒷문(iOS 와 같은 이름, `-e editorMode edit --ez editorMap true` 와 함께): `-e editorPoi all|food|sight|stay|transit`,
  `-e editorSearch 글자`(검색칸을 그 글자로 연다), `-e editorResults 글자`(이 지역 밖까지 찾은 결과 지도를 열고 첫 결과를 고른다).

## 웹 시안 반영 P6 — 처음 안내 말풍선 (2026-11-10, MZ2AZ-547)

체크리스트 C1–C5(지도 검색 안내 M8 포함)를 iOS `Coach/` 그대로 옮겼다. 단추 하나만 밝게 남기고 나머지를 어둡게 덮은 뒤
말풍선이 그 단추를 가리킨다. 순수 규칙은 `coach/CoachRules.kt` 에 있고 JVM 시험이 선다(`tests/CoachRulesTests.kt` 46개 — iOS 시험을 옮기고 영어 문구 확인을 더했다).

| 안내 | 언제 (기다림) | 가리키는 것 |
| --- | --- | --- |
| 홈 `home-plan-trip` | 여행 중 아님 · 떠 있는 줄 접힘 · 홈이 다른 탭·덮개·시트에 가려지지 않음 (0.9초) | 「여행 코스 짜기」 |
| 새 코스 `fork-options` | 「새 여행 코스」 시트가 뜨면 (0.55초) | 도시로 시작 · 작품으로 · 담은 곳으로 |
| 계획 화면 `editor-first` | 편집 중 · 새 코스나 방금 만든 코스 · 묻는 창 없음 (1.2초) | 「지도」 알약 · 해태 · ＋ · 만들기/저장 |
| 지도 검색 `map-search` | 칩 줄이 보이고 검색칸·결과 지도가 닫힘 (0.7초) | 🔍 · 「전체」 칩 |

- 한 번에 하나만 뜬다. 끝까지 보거나 「건너뛰기」·뒤로 가기를 하면 그 안내는 다시 안 뜬다(설정 파일 `scenetrip` 의 `scenetrip.coach.seen`).
  화면을 떠나거나 조건이 풀리면 접기만 하고 다음에 다시 띄운다. 덮개를 눌러도 「다음」이다.
- **창이 여럿이다.** 시트·확인창은 앱 창 위에 따로 뜨는 대화창이라, 앱 창에 그린 덮개는 시트를 못 덮는다. 그래서 대화창마다
  「층」을 올리고(`DisableDialogDim` 이 알린다, 시트는 `CoachLayer`), 덮개는 **가리킬 단추가 든 층**에 그린다. 그 위에 다른 대화창이
  떠 있으면 「가려짐」으로 보고 숨긴 채 기다린다 — 이 동안은 「4초 동안 단추가 안 보이면 그 단계를 건너뛴다」 에 세지 않는다.
- 단추 자리는 안내가 떠 있는 동안 매 화면 다시 잰다(시트가 올라오거나 해태가 비켜 서도 따라간다). 애니메이션 제거(`ui/ReduceMotion.kt`)면 나타남·옮김이 움직이지 않는다.
- 확인용 뒷문(iOS `-coachReset 1`·`-coachStep 2` 와 짝): `--ez coachReset true`(본 기록을 지우고 처음부터), `--ei coachStep 2`(둘째 단계부터).

## 웹 시안 반영 P7 — 코스 목록 「내 여행」 (2026-11-10, MZ2AZ-547)

체크리스트 L1–L4 를 iOS `RouteTab/CourseListRules.swift`·`CourseListRow.swift` 그대로 옮겼다(L5·E17 여는 중 덮개는 P4 에서 끝).
순수 규칙은 `routetab/CourseListRules.kt`, 그리기는 `routetab/CourseListRow.kt`·`RouteTabView.kt` 이고 JVM 시험이 선다(`tests/CourseListRulesTests.kt`).

| 자리 | 모양 |
| --- | --- |
| 머리 | ✕ · 「내 여행」 · 보라 「+ 새 코스」(「새 여행 코스」 세 갈래 시트). 고르는 중이면 [취소] · 「N개 선택」(0개면 「코스 선택」) · [전체 선택 / 선택 해제] |
| 묶음 | 「여행 중」·「다가오는 여행」 — 작은 제목 옆 개수, 흰 칸(모서리 12). 코스가 없으면 「아직 코스가 없어요」 |
| 줄 | 사진 60 · 이름 16 · 「N일차 · N박 N일 · N곳」(일차는 여행 중에만) · 작품 최대 2(보라 12) · 오른쪽 「⋯」 |
| 지우기 | 「⋯」 → 「삭제」 → 「「…」을 지울까요?」 → `DELETE`. **밀어서 지우기는 뺐다**(아무도 못 찾았다) |
| 여러 개 | 줄을 꾹 0.2초(8dp 넘게 움직이면 스크롤) → 체크 동그라미·고른 줄 보라 13% → 아래 빨간 「N개 삭제」 → 「코스 N개를 지울까요? / 되돌릴 수 없습니다.」 → 하나씩 차례로 `DELETE`. 뒤로 가기는 고르기만 끈다 |

- 사진은 목록 요약의 `thumbnailUrl` 이 먼저, 없으면 상세의 첫 사진 있는 장소. 작품은 상세의 **앞 세 곳만** 보고 장소마다 첫 작품을 겹치지 않게 모은다
  (코스 수 × 장소 수로 호출이 불지 않게). 상세는 줄마다 한 번만 조용히 받고(실패해도 띠를 띄우지 않는다), 편집 화면에서 돌아오면 다시 받는다.
- Compose 의 `combinedClickable` 꾹 누르기는 0.4초 이상이라 웹·iOS 의 0.2초와 달라 직접 잰다(`courseRowGestures`).
- 확인용 뒷문(iOS `-courseList menu|pick|confirm` 과 짝): `-e initialTab route -e courseList menu`(첫 줄 「⋯」 메뉴), `pick`(앞 두 줄 고름), `confirm`(그 확인 창). **지우지는 않는다.**

## 웹 시안 반영 P9 — 검색 탭 보관함 핀·촬영지 길찾기 (2026-11-10, MZ2AZ-547)

체크리스트 G1–G3 을 iOS `SearchTab/SearchMapPins.swift`·`PlaceDirections.swift` 그대로 옮겼다. 순수 규칙은 `searchtab/SearchMapPins.kt`·
`searchtab/PlaceDirections.kt`(+ `RouteNumberPinSpec.scale`)이고 JVM 시험이 선다(`tests/SearchMapPinsTests.kt`·`tests/PlaceDirectionsTests.kt`).

- **G1 첫 화면 지도에는 보관함에 담은 곳만** 꽂는다 — 번호는 보관함 순서, 좌표 없는 곳·목록에서 빠진 곳은 뺀다. 작품을 고르면 그 촬영지,
  검색하면 검색 결과(목록 행 번호 = 핀 번호)다. 핀은 계획 화면과 같은 보라 번호 핀(`RouteNumberPin`, 30×38). **열어 둔 장소의 핀은
  그림을 한 번만 1.2배**로 굽고 이름표를 줌과 상관없이 핀 위·다른 핀보다 위에 둔다. 목록에 없는 장소면 번호 없는 핀(흰 점)을 하나 더 꽂는다.
  예전 물방울 핀(`PinImage`)은 더 쓰지 않는다.
- **G2 「보관함」** — 검색창 오른쪽 장바구니 그림이 보라 가방(읽기 이름 「보관함」)이 되고, 그 시트 제목이 「보관함」·「보관함 N곳」이다.
  다른 화면(마이페이지·계획 화면의 담은 곳 시트)의 글은 그대로 둔다.
- **G3 촬영지 상세 「길찾기」** 가 주 단추(보라)다. 누르면 그 장소 하나로 된 1일 코스 「{장소} 가는 길」(50자 넘으면 이름 끝을 「…」로)을 만들고
  (`origin=place_directions`) 여행 중으로 바꾼 뒤 경로여정을 열어 바로 안내를 켠다(`TabRouter.pendingTripStart` — 계획 화면이 읽는다).
  비회원이면 로그인 시트를 「로그인하면 바로 길안내를 시작해요」 와 함께 먼저 띄우고, 로그인되면 같은 장소로 이어 간다. 같은 장소의
  「가는 길」(이 기능이 만든 것 → 목록의 여행 중·1일·1곳·같은 제목, 속을 읽어 확인)이 있으면 새로 만들지 않고 그것을 연다. 누르는 동안 다시
  누르면 버리고, 화면을 떠나면 기다리던 로그인이 끝나도 이어 가지 않는다. 담기·네이버 지도는 아래 줄 보조 단추가 된다. 코스 편집 위에
  띄운 검색 탭에서는 「길찾기」를 그리지 않는다(그때 주 단추는 「코스에 추가」).
- `RouteStore.setRunning` 이 성공 여부를 돌려준다(여행 중으로 못 바꾸면 안내를 켜지 않는다). `AuthStore.promptSignIn(reason)` 이 시트에 이유 한 줄을 싣는다.
## 웹 시안 반영 P8 — 커뮤니티 게시판·검색·좋아요·댓글·블로그형 글쓰기 (2026-11-10, MZ2AZ-547)

체크리스트 K1–K11 을 iOS `CommunityTab/`(P8) 그대로 옮겼다. 순수 규칙은 `data/CommunityFeedRules.kt`(게시판·검색·다음 쪽·빈 화면·수),
`data/CommunityDraft.kt`(블로그형 초안), `data/PostSocial.kt`(좋아요·댓글 상태)에 있고 JVM 시험이 선다(`tests/CommunityFeedRulesParityTests.kt`·`CommunityDraftParityTests.kt`·`PostSocialParityTests.kt`·`CommunityFeedSessionTests.kt` 100개).

| 항목 | 화면에서 보이는 것 | 코드 |
| --- | --- | --- |
| 머리·게시판 (K1·K3) | 왼쪽 「커뮤니티」 · 🔍 · 보라 「✎ 글쓰기」 알약, 밑에 글자 탭 「여행후기 · 자유게시판」(밑줄·구분선 없음). 서버가 `board=review｜free` 로 거른다 | `communitytab/CommunityTabScreen.kt` |
| 검색 (K2) | 🔍 → 제목 줄이 검색칸 「제목·내용으로 찾기」. 두 글자부터 0.35초 멈추면 `GET /posts?board&q`, 없으면 「「q」 에 맞는 글이 없어요」. 탭을 떠나면 지운다 | `CommunityFeedRules.searchTerm`·`CommunityStore.resetFilterInBackground` |
| 끊김 없는 목록 (K4) | 끝에서 다섯 줄 안이 보이면 다음 쪽(`cursor=nextCursor`, 없으면 offset). 「더 보기」 없음(「내가 쓴 글」 시트는 그대로) | `CommunityList.kt`·`CommunitySession.refresh` |
| 목록 줄 (K5) | 작성자·날짜가 맨 아래, 사진 밑 「👍 N 💬 M」(누른 글은 보라 엄지, 사진 없으면 작성자 줄 끝, 수가 없으면 안 그림) | `CommunityPostCounts` |
| 새 화면 (K6) | 글 읽기·글쓰기는 오른쪽에서 밀려 들어와 **탭바까지 덮는다**. 글 화면 머리 「‹ 여행후기」, ✕ 없음, 하드웨어 뒤로 = 뒤로 | `CommunityNav.kt`(탭바 위 `RootTabs` 에 그린다) |
| 사진 중복 (K7) | 글 화면·미리보기 맨 위 대표 사진을 따로 그리지 않는다. 블록이 가리키지 않는 사진은 끝에 붙고, 같은 사진은 한 번만 | `CommunityArticle.readingBlocks` |
| 좋아요 (K8) | 글 끝 가운데 「👍 좋아요 N」. 누르면 바로 보라(실패하면 되돌림), 비로그인은 로그인 안내. `PUT/DELETE /posts/{id}/like` | `PostSocialModel.toggleLike` |
| 댓글 (K9) | 「댓글 N」, 오래된 순. 내 댓글 「지우기」, 남의 댓글 「⋯」 신고·차단(`ReportTargetType.comment`, 차단 뒤 다시 받기). 입력칸은 글 끝에 붙는다(500자) | `CommunityPostSocialViews.kt` |
| 글쓰기 (K10) | 머리 「글쓰기」 + 게시판 칩(처음 값은 보던 게시판, `TripPostCreate.board`). 비로그인도 화면은 열리고 사진 넣기·올리기에서 로그인 | `CommunityComposeView.kt` |
| 블로그형 본문 (K11) | 글 조각과 사진이 쓴 순서대로. 「사진 넣기 N/8」 은 커서 자리에서 문단을 나눠 넣는다. 사진은 4:3 · 「대표」 · ✕ · 설명 · 「출처·AI 표시 더하기」, 0.3초 꾹 눌러 위아래로 옮긴다(시스템 길게 누르기보다 짧아 직접 잰다), 첫 사진 = 대표 | `CommunityArticleEditor.kt` |

- 저장 형식은 그대로다 — 초안 목록에서 본문(`body`)과 사진마다 「몇 번째 문단 뒤」를 계산해 `bodyBlocks` 를 만든다. 사진 칸 순서는 본문에 놓인 순서로 맞춘다(`ReviewDraft.reorder`).
  예전의 「사진 배치」 고르기(`CommunityPlacementEditor.kt`)와 위쪽 사진 줄은 없앴다.
- 글 화면에서 바뀐 좋아요·댓글 수는 목록·내 글의 같은 줄에도 맞춘다(`CommunityStore.updateSocial`). 올린 글은 보던 게시판·검색 없음일 때만 목록 맨 앞에 넣는다.
- 확인용 뒷문(iOS 와 같은 이름, `-e initialTab community` 와 함께): `-e communityBoard free`, `-e communitySearch 글자`, `-e communityOpen post|compose`.

## 인터페이스

2026-10-13 동기화는 [계획](../../docs/project/plans/android-ios-sync-2026-10.md)과
[검증 기록](../../docs/qa/android-ios-sync-2026-10.md)을 따른다. 화면은 **라이트로 고정**한다(iOS
`.preferredColorScheme(.light)` — MZ2AZ-419의 다크 모드 철회, 2026-10-14). 시스템이 다크여도, 예전에 다크를 골라 둔 기기여도
밝은 화면이며 마이페이지에 화면 모드 선택은 없다. 사진 확대의 검정 감상 배경은 라이트 디자인의 일부라 그대로다. 상세 포스터는 제작사 공식 주소로,
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
`en`을 조합할 수 있다. 제품 계정과 API 쓰기를 실행하지 않는다.

커뮤니티 여행후기는 첫 사진 하나를 대표로, 본문은 계약1.13의 bodyBlocks 순서로 표시한다.
작성 시 사진별 문단 위치·설명·출처·AI 표시를 정하고 상단 미리보기로 확인한다.
사진 삭제 뒤 남은 사진의 UUID에 연결된 배치를 유지하며, 기존 글은 읽기 시에만 문단별로 배치한다.
원래 비율의 사진을 눌러100~400% 확대·이동하며 실패한 사진은 다시 받을 수 있다.
블록 배치까지 같은지 확인해야 불확실한 게시를 성공으로 처리한다.
`just android-community-blog-qa compose ko light`는 별도 QA 설치본의 합성 초안만 사용한다.
read/legacy/retry/pending/failed/portrait 및 en 조합과 검증 범위는
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

스토어 배포는 이 계획의 범위 밖이었다 (MZ2AZ-148 완료 조건은 "로컬 실행"까지).
Play Console 에 올리는 **앱 번들(AAB)** 은 `just android-bundle <API 주소>` 로 짓고 업로드 키로 서명한다
(`:bundle` 타깃). 업로드 키 만들기·`.env` 설정·버전 올리기·첫 업로드 뒤 구글 로그인 지문 등록은
[`deploy/README.md`](deploy/README.md) 에 있다. `deploy/` 는 k8s 매니페스트가 아니다 (AGENTS.md §3).
