# Android 담아 둔 항목의 숨김·폐업 안내 (MZ2AZ-458)

기준은 [서버 계획](delisted-items.md), [iOS 구현 계획](ios-delisted-items.md)(PR #309), OpenAPI 1.20.0,
`docs/api/errors.md`의 409 표다. 범위는 Android 앱이며 서버·계약·생성 코드·iOS 앱은 수정하지 않는다.
Android는 iOS를 그대로 따른다 — 문구·규칙·동선이 같아야 하고, 갈리는 곳은 아래 「iOS와 다른 점」에만 적는다.

## 구현 순서

1. 생성 Kotlin 모델의 선택적 `delisted`·`closed`를 `UnavailableItem`(`data/UnavailableItem.kt`)으로 해석한다.
   누락·false는 정상이다. 문구는 「더 이상 소개하지 않는 장소예요」·「…작품이에요」·「폐업했어요」.
2. 장바구니(검색·마이페이지가 같은 `CartItemRow`)·내 코스(마이페이지 요약·편집 화면)·찜한 작품에 안내와 빼기를 붙인다.
   제목·사진만 약하게(알파 0.65) 보이고 안내와 「빼기」는 또렷하게 둔다. 누르는 자리는 48dp 이상이다.
3. 상세에서 새 담기·찜을 막고 기존 항목 빼기는 허용한다. 촬영지 상세는 `PlaceDetail.delisted`, 작품 상세는
   `ContentDetail.delisted`(없으면 목록 요약)를 본다. 작품 상세에 iOS와 같은 찜 단추를 더했다.
4. 409는 코드로 가른다. 장바구니 `DUPLICATE_CART_ITEM`만 「이미 담긴 상태」이고 `PLACE_DELISTED`는 안내만 하며 담긴 것으로
   표시하지 않는다(MZ2AZ-479 겹침, 아래 B2). 코스 저장 실패는 편집을 유지하고 기존 행 ID를 그대로 보낸다.
5. 한국어·영어 문구와 단위 시험, 모듈 README를 갱신한다.

## 이동 정책 (iOS와 같다)

숨긴 촬영지·폐업 편의시설은 자동 다음 목적지, 시작 안내, 행 사이·총 직선거리, 계획선, 동선 최적화, 머무는 시간 합에서
건너뛴다. 행 번호·지도 핀·저장 ID는 유지한다. 직접 누른 행은 정보 카드를 열되 길찾기는 제공하지 않는다
(`RouteStop.canNavigate`, `TripSession.start/advance`가 거부한다). 서버가 센 이동 포함 시간은 그 행을 포함한 값이라
숨김·폐업 행이 있으면 보이지 않고 유효한 곳의 머무는 시간만 보인다. 남은 곳이 숨김·폐업뿐이면 「코스 완료」·시작 단추
대신 「갈 수 있는 곳이 없어요. 목록에서 빠지거나 폐업한 곳만 남았어요.」를 보이고 코스를 시작하지 않는다.
폐업 편의시설 상세는 서버가 404로 닫으므로 카드는 상세를 부르지 않고 코스가 보관한 이름·분류·주소와 폐업 안내를 보인다.
AI 일정 수정(`plan.draft/revise/move`)은 `RouteGuidePlan.revising`으로 모았고, 숨김·폐업 상태를 같은 대상의 모든 행에
이어받는다(같은 대상이 여러 일차에 있어도, 이어받을 기존 행이 모자라도).

## B2 — 장바구니 409 (MZ2AZ-479 겹침)

`CartSession.add`는 모든 409를 「이미 저장된 장소」로 보고 담긴 것으로 표시했다. 이제 `DUPLICATE_CART_ITEM`만 그렇게 다루고,
`PLACE_DELISTED`는 「목록에서 빠진 장소라 담을 수 없어요.」를 알리고 담긴 것으로 표시하지 않는다. 그 밖의 409는 일반
실패 문구다. 담기 결과 알림은 Android 검색 탭에 아직 그려지지 않던 것이라 `CartToast`(2초)를 더했다.

## iOS와 다른 점

| 항목 | iOS | Android | 이유 |
| --- | --- | --- | --- |
| 찜 목록의 원천 | `LikeStore.works`(`/favorites/contents`) | 같게 맞췄다 — `LikeStore.works`·`likedWorks` | 기존 Android는 작품 목록(`GET /contents`, 100편)에서 찾아 숨긴 작품이 목록에서 사라졌다 |
| AI 수정 행 대응 | 대상 ID가 유일할 때만 서버 번호·방문 상태를 이어받음 | 기존 방식(앞에서부터 하나씩) 유지, 숨김·폐업은 모든 일치 행에 | 이 작업은 상태 보존만 다룬다 |
| 시작 불가 안내 | 완료 안내 자리의 글 | 하단 줄 위의 글, 다음 단추 자리의 글 | Android 편집 화면 구조가 다르다 |
| 정보 카드 | `RouteStopCard`에 안내 | `RoutePlaceCard(unavailable)`에 안내, 폐업은 상세 생략 | Android는 카드가 하나다 |
| 홈 「이어서 길찾기」 | 남은 곳이 숨김·폐업뿐이면 첫 방문 가능 행으로 대신 시작 | 아무것도 하지 않음(`pendingTripStart`를 소비하고 코스를 시작하지 않음) | 「갈 수 있는 곳이 없어요」 규칙과 맞춘 의도적 차이 |
| AI 수정의 행 ID·방문 상태 | 대상이 유일할 때만 이어받음 | 앞에서부터 첫 일치 행과 짝지어 이어받음(기존 방식) | 후속으로 정책을 맞춘다 |
| 이동 거부 시험 | `TripSession` 직접 | `canNavigate` 규칙으로 시험 | `TripSession`이 Android 컨텍스트를 요구한다 |

## 검증

- 단위 시험 `tests/UnavailableItemTests.kt`가 iOS `UnavailableItemTests`·`CartStoreTests`의 항목을 옮겼다: 누락/false/true
  디코딩, 갈래별 안내, 상세 추가/제거 허용, 409 매핑과 중복 구별, 기존 행 ID 보존과 저장 거절 때 편집 유지, 다음 목적지·거리·
  전부 제외·시작 거부, 이동 포함 시간 무효화, 동선 최적화, AI 수정의 숨김 상태와 중복 행 보존, 한·영 문구, 숨김 작품 올리기 재시도 금지.
- `just android-translations`를 실행했다(iOS 표에 이미 있는 문구라 변경 없음). Android 전용 문구는 추가하지 않았다.
- `just check`를 실행한다. 에뮬레이터·실제 길찾기(`/navigation/*`)·가이드(`/guide/*`) 호출은 하지 않았다.

## 변경 파일

아래는 `apps/scenetrip-android/` 기준이다.

| 위치 | 파일 |
| --- | --- |
| `src/.../data/` | `UnavailableItem.kt`(새), `ApiFailure.kt`, `CartSession.kt`, `LikeStore.kt`, `RouteStore.kt` |
| `src/.../ui/` | `UnavailableItemViews.kt`(새) |
| `src/.../searchtab/` | `CartSheet.kt`(`CartItemRow` 공개), `CartToast.kt`(새), `DetailViews.kt`, `Rows.kt`, `SearchTabScreen.kt` |
| `src/.../profiletab/` | `ProfileSheets.kt`, `ProfileTabView.kt` |
| `src/.../routetab/` | `RouteAvailability.kt`(새), `RouteModels.kt`, `RouteBridge.kt`, `RouteDayEstimate.kt`, `RouteGuidePlan.kt`, `RouteEditorView.kt`, `RouteEditorParts.kt`, `RoutePlaceCard.kt`, `RouteWizardView.kt`, `TripSession.kt` |
| `tests/` | `UnavailableItemTests.kt`(새) |
| 앱 루트 | `BUILD.bazel`, `README.md` |
| 문서 | `docs/project/plans/android-delisted-items.md`(이 문서) |
