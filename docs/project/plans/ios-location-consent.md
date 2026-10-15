# iOS 위치기반서비스 이용 동의 (MZ2AZ-472)

## 범위와 근거

위치 약관 초안 제20~29조(별도 동의·철회·카카오 제공 알림)를 기준으로 iOS만 변경한다.
시스템 위치 권한과 별도로 버전 동의를 저장한다. 기기 안의 파란 점·방문 판정·발자취는 유지한다.
서버·계약 변경과 서버 확인기록 삭제는 이 작업 범위 밖이다.

## 구현 순서

1. 기존 AI 동의 저장·시트 대기 흐름을 공통화하고 위치 동의 저장소를 추가한다.
2. AI 질문/재시도, AI 코스 생성, 여행 길찾기와 옛 길찾기에 동의 시트를 연결한다.
3. 전송 계층에서 필수 좌표 요청을 차단하고 선택 좌표는 동의가 없으면 제거한다.
4. 마이페이지 철회 토글과 KO/EN 안내, 길찾기 성공마다 사용자 요청 단위 알림을 추가한다.
5. 주입 저장소·가짜 HTTP 세션으로 허용/거절/철회/버전/재시도/알림 중복을 검증한다.

## 리뷰 보완 계획

1. 지도 이동 출처를 추적한다. GPS 이동·내 위치를 포함한 자동 맞춤은 기기 위치로,
   손으로 이동·확대하거나 검색 결과를 선택한 영역은 사용자 선택으로 기록한다.
2. 촬영지 bbox·편의시설 조회와 AI 질문에 출처를 전달한다. 기기 위치일 때만 UI 동의를
   요구하고, 전송 계층은 철회·재시도에도 같은 출처를 검사한다. 내부 표식은 전송 전에 제거한다.
3. 질문의 재시도는 최초 좌표 출처를 유지한다. AI 동의 정책·저장 키는 유지한다.
4. 진행 중 목적지 변경은 최신 요청 하나로 대체해 기존 요청 종료 후 실행한다.
5. 미동의·취소·철회·사용자 지도 이동과 지연된 가짜 길안내 응답 회귀 시험을 추가한다.

## 좌표 감사

- `guide/chat`: 기기 위치 또는 지도 중심·목적지. 기기에서 유래한 좌표일 때만 위치 동의를 요구한다.
  AI 기능 동의는 모든 질문에 그대로 적용하며 재시도는 최초 좌표 출처를 유지한다.
- `guide/plan`: 기기 위치가 선택값. 미동의 시 `latitude`/`longitude` 없이 생성한다.
- `navigation/next-leg`: `TripSession`과 옛 `RouteNavControls` 모두 기기 좌표. UI와 전송 계층에서 차단한다.
- `places` bbox 및 `pois` bbox/lat/lng: 지도 이동 출처를 함께 보존한다. GPS 이동·자동 추적·
  내 위치를 포함한 자동 맞춤은 동의 대상이다. 손으로 이동·확대하거나 검색 결과를 고르면 사용자 선택이다.
- 외부 지도 넘기기: 앱이 기기 좌표를 URL에 첨부하지 않고 목적지만 넘긴다. 출발지는 외부 앱에서 정한다.

## 검증과 남은 확인

네트워크·실제 navigation 호출·Bazel/just·커밋은 사용자 요청에 따라 실행하지 않는다.
변경 Swift만 swiftformat으로 정리하고 BUILD srcs를 정렬한다. 공식 검증은 사용자가 실행하는 `just check`다.
실기기에서 시트 전환·접근성·영문 표시를 확인해야 한다. 약관 게시 상태는 네트워크 없이 확인하지 않는다.

## 구현 후 좌표 경로 보고

| 경로 | 좌표 구성·호출 | 동의 처리 |
| --- | --- | --- |
| AI 가이드 질문·재시도 | `RouteEditorGuide.swift:26`·`RouteNavView.swift:153` → `RouteGuideSheet.swift:386`·`RouteGuideSheetLimit.swift:9` → `RouteGuide.swift:144` | 기기 좌표일 때 위치 동의. 지도·목적지 질문은 AI 동의만 필요. 재시도는 최초 출처 보존 |
| AI 코스 생성 | `RouteWizardView.swift:199` → `RouteStore.swift:341`·`:345` | 취소하면 위치 없이 생성. 저장소와 전송 계층에서도 선택 좌표 제거 |
| 여행 길안내 | `TripSession.swift:279`·`:297` | 요청별 동의. 성공 시 사용자 요청 ID로 알림 중복 방지. 진행 중 목적지 변경은 최신 요청으로 대체해 기존 요청 종료 후 실행 |
| 옛 길안내 화면 | `RouteNavControls.swift:61`·`:67` | 같은 요청별 동의·알림 정책 |
| 촬영지 지도 검색·이어 받기 | `NaverMapView.swift:456`·`MapRegionSource.swift:7` → `SearchTabOverlays.swift:129` → `SceneData.swift:64`·`:235` | GPS 유래 영역은 시트에서 허용한 뒤 전송. 취소하면 요청하지 않음. 이어 받기도 최초 출처 보존 |
| 주변 편의시설 | `RouteMapView.swift:525`·`RouteMapLocate.swift:68`·`RouteMapTrip.swift:129` → `RouteEditorAmbient.swift:104` → `RouteGuide.swift:187` | GPS·내 위치 포함 자동 맞춤은 동의 대상. 여행 시작 시 길안내 동의와 겹치는 편의시설 조회는 생략하고 경로 맞춤 뒤 재조회 |
| 외부 지도 넘기기 | `RouteEditorTripLimit.swift:98` | 현재 좌표를 제거하고 목적지만 전달 |

공통 방어는 `LocationConsent.swift:44`·`:76`, 전송 연결은 `RetryingSession.swift:227`·`:294`다.
내부 헤더는 `RetryingSession.swift:305`에서 제거한다.
출처가 없는 지도 좌표·질문은 기기 위치로 취급한다. 앱 내부 헤더로 출처를 전달하고 실제
HTTP 전송 직전에 제거한다. 인증 재전송·자동 재시도에서도 원래 출처를 확인한다.
코스의 선택 좌표는 제거하며 철회와 실제 전송 시작을 같은 잠금으로 묶는다.
현재 두 길안내 경로 모두 자동 재탐색 폴링을 하지 않는다. GPS 갱신·동일 요청의 중복 완료는
알림을 추가하지 않고, 수동 재시도·다음 목적지는 새 요청으로 센다.

## 검증 결과와 불확실성

- `LocationConsentTests` 9개 추가: 취소·철회·버전, 필수 좌표 차단/허용, 선택 좌표 제거,
  전송 직전 철회, 알림 중복·겹친 성공, 자동 재전송 차단, UserDefaults 독립 저장, 가짜 여행 요청.
- `LegalLinksTests`에 위치 약관 주소·영문 안내·지정 본문 검증을 추가했다.
- 기존 재시도·길안내 한도 시험에는 허용된 주입 저장소를 명시했다. 실제 navigation 호출은 하지 않았다.
- 변경 Swift의 swiftformat 검사, BUILD의 캐시된 buildifier 검사, KO/EN strings의 plutil 검사,
  diff 공백 검사를 수행했다. SwiftLint는 종료 0이며 기존 대형 파일 등에서 경고가 남는다.
- 단위 테스트·빌드·`just check`는 사용자 요청으로 실행하지 않았다. 통과를 확인한 상태는 아니다.
- 실기기 시트 전환·배너·접근성과 약관 URL 게시는 확인하지 않았다.
- 약관 제24조의 제공일시 표시는 티켓에 지정된 배너 문구에 없으므로 별도로 추가하지 않았다.
  부분 동의·서버 확인기록 삭제·실제 좌표 파기 여부는 이 iOS 변경만으로 보장하거나 검증하지 않는다.

## 리뷰 보완 검증

`LocationSourceTests`에 추가한 시험:

- `testGPSRegionAsksBeforeSearchAndCancelDoesNotSend`
- `testGPSRegionAsksAgainAfterRevokeAndResultSelectionResetsSource`
- `testUnmarkedCoordinateRequestsDefaultToDeviceLocation`
- `testGPSViewportTransportBlocksWithoutConsentAndAfterRevoke`
- `testRevocationJustBeforeViewportResumeBlocksTransport`
- `testMapAndDestinationGuideRequestsNeedOnlyAIConsent`
- `testGuideAnchorRetainsGPSDerivedMapSource`
- `testGuideRetryKeepsOriginalSourceAndCoordinates`

그 밖의 회귀 시험:

- `GuideChatRequestBuildTests.testMapGuideSourceSurvivesBuilderAndRetry`와
  `testViewportPaginationKeepsGPSCoordinateSource`: 생성 클라이언트가 만드는 요청의 출처 보존.
- `TripSessionRequestTests.testLatestDestinationRunsAfterDelayedOldResponseWithoutGPSUpdate`와
  `testEndingTripDoesNotRunQueuedDestinationAfterDelayedFailure`: 지연된 가짜 응답으로 최신 목적지
  실행·중간 목적지 생략·종료 뒤 요청 방지를 검증한다.
- 빌드·단위 시험은 실행하지 않는다. 시트 중첩·제스처 전환은 실기기 확인이 남는다.

리뷰 보완에서 직접 수정한 파일(기존 미커밋 위치 동의 변경 위에 적용):

- `apps/scenetrip-ios/src/Models/LocationConsent.swift`: 좌표 출처·조건부 동의·전송 게이트
- `apps/scenetrip-ios/src/Models/RetryingSession.swift`: 전송 직전 내부 출처 헤더 제거
- `apps/scenetrip-ios/src/Models/SceneData.swift`: 지도 검색·이어 받기의 출처 전달
- `apps/scenetrip-ios/src/SearchTab/MapRegionSource.swift`: 지도 카메라 이동 원인 추적
- `apps/scenetrip-ios/src/SearchTab/NaverMapView.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/SearchTab/SearchTabView.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/SearchTab/SearchTabOverlays.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/RouteMapView.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/RouteMapLocate.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/RouteMapTrip.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/RouteMapAmbient.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/RouteEditorView.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/RouteEditorAmbient.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/RouteEditorGuide.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/RouteGuideAnchor.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/RouteGuideSheet.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/RouteGuideSheetLimit.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/RouteNavView.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/RouteGuideTurn.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/RouteGuideSession.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/RouteGuide.swift`: 지도·가이드 좌표 출처와 동의 연결
- `apps/scenetrip-ios/src/RouteTab/TripSession.swift`: 진행 중 변경된 최신 목적지 요청 실행
- `apps/scenetrip-ios/tests/GuideChatRequestBuildTests.swift`: 회귀 시험·가짜 전송 서명 보완
- `apps/scenetrip-ios/tests/LocationSourceTests.swift`: 회귀 시험·가짜 전송 서명 보완
- `apps/scenetrip-ios/tests/TripSessionRequestTests.swift`: 회귀 시험·가짜 전송 서명 보완
- `apps/scenetrip-ios/tests/LocationConsentTests.swift`: 회귀 시험·가짜 전송 서명 보완
- `apps/scenetrip-ios/tests/RouteGuideLimitTests.swift`: 회귀 시험·가짜 전송 서명 보완
- `apps/scenetrip-ios/tests/RouteGuideTurnTests.swift`: 회귀 시험·가짜 전송 서명 보완
- `apps/scenetrip-ios/tests/SearchUsageTests.swift`: 회귀 시험·가짜 전송 서명 보완
- `apps/scenetrip-ios/BUILD.bazel`: 새 소스·시험과 SDK 의존성 등록, srcs·제외 목록 정렬
- `apps/scenetrip-ios/README.md`: 지도 출처·AI 비위치 질문·목적지 대기 동작 안내
- `docs/project/plans/ios-location-consent.md`: 보완 계획·좌표 경로·회귀 시험·검증 기록

리뷰 보완 정적 검사: 변경 Swift 29개만 SwiftFormat 적용, SwiftLint 종료 0(경고 49건),
캐시된 buildifier 검사와 diff 공백 검사 통과. 빌드·단위 시험·실제 navigation 요청은 실행하지 않았다.
