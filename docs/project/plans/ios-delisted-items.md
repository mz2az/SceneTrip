# iOS 담아 둔 항목의 숨김·폐업 안내 (MZ2AZ-458)

기준은 [서버 계획](delisted-items.md), OpenAPI 1.20.0, `docs/api/errors.md`의 409 표다.
범위는 iOS 앱이며 서버·계약·생성 코드는 수정하지 않는다.

## 구현 순서

1. 생성 Swift 모델의 선택적 `delisted`·`closed`를 공통 표시 규칙으로 해석한다. 누락·false는 정상이다.
2. 장바구니·내 코스·찜한 작품에 안내와 빼기 버튼을 붙인다. 내용만 약하게 표시하고 안내·버튼은 읽기 쉽게 유지한다.
3. 상세의 새 담기·찜을 차단하되 기존 항목 빼기는 허용한다. 화면이 열린 사이 바뀐 상태는 409 코드로 안내한다.
4. 코스 저장 실패는 편집을 유지한다. 기존 행 ID는 그대로 보내며 상태 때문에 행을 삭제하지 않는다.
5. 한국어·영어 문구와 단위 시험, 모듈 README를 갱신한다.

## 이동 정책

숨긴 촬영지·폐업 편의시설은 자동 다음 목적지, 시작 안내, 행 사이·총 직선거리에서 건너뛴다.
직접 고른 행의 정보 카드는 열지만 길찾기는 제공하지 않는다. 지도 핀·행 번호·저장 ID는 유지한다.
기존 서버 이동 포함 시간은 해당 항목을 포함한 값이므로 표시하지 않고 유효한 곳의 머무는 시간만 표시한다.
상태를 서버에서 다시 받기 전까지는 새 숨김·폐업을 알 수 없다. 공개 후기·마켓 코스 사본은 서버 계획대로 범위 밖이다.
폐업 POI 상세는 서버가 404로 닫으므로 코스가 보관한 이름·분류·주소와 폐업 안내를 보여 준다.
409는 첫 실패의 종류만 알려 주므로 특정 행 이름은 지목하지 않고, 편집을 유지한 채 해당 종류를 빼도록 안내한다.

## 검증

단위 시험으로 누락/false/true·갈래별 안내, 상세 추가/제거 허용, 409 매핑과 중복 구별,
기존 행 ID 보존, 다음 목적지·거리·전부 제외·직접 길찾기 차단을 고정한다.
요청에 따라 이번 작업에서는 Bazel/just·네트워크·실제 길찾기를 실행하지 않는다.
변경 Swift 파일만 swiftformat으로 정리하고 diff를 검토한다. 빌드·시험 실행은 담당자의 `just check`로 확인한다.

## 리뷰 보완

1. AI `plan.move/revise`에서 기존 행의 숨김·폐업 상태도 ID·방문 상태와 함께 보존한다.
   편집 전후 안내·거리·최적화·다음 목적지 제외를 회귀 시험에 추가한다.
   같은 대상이 여러 일차에 있어도 종류·대상 ID별 상태를 모든 일치 행에 보존한다.
   행 ID·방문 상태는 기존·새 코스에서 각각 한 행일 때만 복사한다.
   `testGuideRevisionPreservesUnavailableTargetsAcrossDuplicateRows`로 기존·새 코스의 중복과
   중복 추가·축소, 같은 숫자의 촬영지·POI 구별을 고정한다.
2. 마이페이지 장바구니도 검색 장바구니와 같은 행·저장소를 써서 안내·약한 표시·제거를 제공한다.
3. 남은 곳이 숨김·폐업뿐이면 완료와 구별해 알리고 코스 시작·활성 상태 전환을 막는다.
   선택 일차의 시작 상태와 방문 가능한 곳이 섞인 경우를 시험한다.
4. 사용량 시험의 검색·재시도 경로를 `origin/main`과 비교하고, 고정 yield 횟수 대신
   실제 실패 상태를 기다리게 한다. 번역 번들 조회 수정은 별도 PR #307에 둔다.

사용량 실패의 정적 근거: `SceneData`의 검색·재시도 구현과 사용량 시험의 본문·가짜
요청 빌더는 `origin/main`과 같다. MZ2AZ-458은 `ApiFailure.message` 표시만 더했고,
문제의 시험은 `CartStore`를 생성·호출하지 않는다. 제공된 로그는 yield 100회 뒤
0.004초에 실패했다. 생성 클라이언트의 continuation과 `async let` 자식 작업이
끝났다는 신호가 아니므로 대기가 스케줄링에 의존한다. 제품 검색 코드는 바꾸지 않고
실제 `.failed` 발행을 XCTest expectation으로 최대 2초 기다리며 503도 확인한다.
실행 재현·수정 뒤 통과 확인은 이번 요청의 명령 제한으로 수행하지 않았다.

## 변경 파일

아래 경로는 이 문서를 제외하고 `apps/scenetrip-ios/` 기준이다.

| 위치 | 파일 |
| --- | --- |
| `src/Models/` | `UnavailableItem.swift`, `CartStore.swift`, `LikeStore.swift`, `SceneData.swift` |
| `src/SearchTab/` | `CartSheet.swift`, `PlaceDetailView.swift`, `ContentDetailView.swift`, `Rows.swift`, `SearchTabView.swift` |
| `src/ProfileTab/` | `ProfileSheets.swift`, `ProfileStamps.swift`, `ProfileTabView.swift` |
| `src/RouteTab/` | `RouteBridge.swift`, `RouteModels.swift`, `RouteDayEstimate.swift`, `RouteEditorList.swift`, `RouteEditorParts.swift`, `RouteEditorTrip.swift`, `RouteEditorView.swift`, `RouteGuideIdentity.swift`, `RouteMapTrip.swift`, `RouteMapView.swift`, `RouteNavControls.swift`, `RouteStopCard.swift`, `RouteWizardView.swift`, `TripSession.swift` |
| `tests/` | `UnavailableItemTests.swift`, `CartStoreTests.swift`, `UsageEventsTests.swift` |
| `resources/` | `ko.lproj/Localizable.strings`, `en.lproj/Localizable.strings` |
| 앱 루트 | `BUILD.bazel`, `README.md` |
| 문서 | `docs/project/plans/ios-delisted-items.md`(이 문서) |
