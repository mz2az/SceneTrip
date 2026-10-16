# Android 위치기반서비스 이용 동의 (MZ2AZ-472)

iOS [ios-location-consent.md](ios-location-consent.md)(PR 커밋 `359c45c`)를 같은 문구·같은 순서로 옮긴다. 기준은 위치 약관 초안 제20~29조(별도 동의·철회·카카오 제공 알림)다.
시스템 위치 권한과 **별도로** 버전 동의를 저장한다. 기기 안의 파란 점·도착 판정·발자취는 서버로 나가지 않으므로 동의와 무관하다.
서버·계약·에이전트·iOS는 바꾸지 않는다.

## 범위와 흐름

- 기기 좌표를 **우리 서버로 보내는** 기능을 쓰는 순간 「위치기반서비스 이용 동의」 시트를 한 번 띄운다(AI 동의와 같은 `ConsentSheet`: 내용 높이 240~480dp, 세로로 쌓인 「동의하고 계속」/「취소」, 약관 링크 `https://scenetrip.io/terms#location-services`).
- 허용하면 시트가 사라진 뒤 대기 중 동작을 **한 번** 실행한다. 취소·바깥 누르기·끌어 내려 닫기는 모두 취소이며 아무것도 보내지 않거나 위치 없이 이어 간다.
- 마이페이지 「AI 기능 사용」 바로 아래 「위치기반서비스 이용」 토글(꼬리말 「끄면 위치를 쓰는 기능을 쓸 때 다시 물어봐요.」). 끄면 철회하고 이미 나간 위치 요청을 끊는다. 다시 켤 때도 시트를 확인한다. AI 동의와는 저장 키·철회가 서로 독립이다.
- 길 안내가 성공하면 사용자 요청 하나에 한 번 「길 안내를 위해 카카오에 현재 위치를 보냈어요.」 띠를 4초 띄운다.
- 동의 거부로 끝난 요청의 문구는 「위치기반서비스 이용에 동의한 뒤 다시 시도해 주세요.」(iOS `RouteGuideFailure.locationConsentRequired`·`RouteNavFailure.locationConsentRequired`).

## 구현 (iOS 대응)

| Android | iOS |
| --- | --- |
| `data/LocationConsent.kt` — `CoordinateSource`, `CoordinateSources`, `LocationConsentStore`, `LocationRequests`, `LocationConsentRequired`, `LocationConsentInterceptor`, `requestLocation`, `KakaoDirectionsNotice`, `LocationDirectionsConsent`, `LatestRequestQueue` | `Models/LocationConsent.swift`, `Models/RetryingSession.swift`의 위치 검사 |
| `data/AIConsent.kt` — `AIConsentGate.onCancel` 추가(저장소·게이트는 AI와 공용) | `Models/AIConsent.swift`, `locationConsentSheet(onCancel:)` |
| `ui/LocationConsentSheet.kt` — `LocationConsentCopy`, `LocationConsentSheet`, `LocationConsentSheetHost`, `KakaoDirectionsNoticeBanner` | `Models/LocationConsentSheet.swift` |
| `profiletab/ProfileAISettings.kt`(위치 토글 추가) | `ProfileTab/ProfileAISettings.swift` |
| `searchtab/MapRegionSource.kt`, `searchtab/NaverMap.kt`, `searchtab/SearchTabScreen.kt`, `data/SearchSession.kt`·`SearchClient.kt`·`SceneData.kt` | `SearchTab/MapRegionSource.swift`, `NaverMapView.swift`, `SearchTabOverlays.swift`, `Models/SceneData.swift` |
| `routetab/RouteEditorView.kt` (주변 편의시설·지도 범위·길 안내 시트·알림), `routetab/PoiClusterPins.kt` | `RouteEditorAmbient.swift`, `RouteMapView.swift`, `RouteMapLocate.swift`, `RouteMapTrip.swift`, `RouteEditorView.swift` |
| `routetab/RouteGuideSession.kt`, `routetab/RouteGuideSheet.kt` | `RouteGuide.swift`, `RouteGuideSession.swift`, `RouteGuideSheet.swift`, `RouteGuideSheetLimit.swift`, `RouteGuideTurn.swift` |
| `routetab/RouteWizardView.kt`, `data/RouteStore.kt` | `RouteWizardView.swift`, `RouteStore.swift` |
| `routetab/TripSession.kt` | `RouteTab/TripSession.swift` |
| `routetab/ExternalDirections.kt` | `RouteEditorTripLimit.swift`, `ExternalDirections.swift` |
| `data/NetworkFailure.kt`·`ApiFailure.kt`·`RetryInterceptor.kt`, `data/LegalLinks.kt`, `auth/AuthStore.kt`(인터셉터 등록) | `RouteGuideFailure.swift`, `RouteNavFailure.swift`, `LegalLinks.swift`, `RetryingSession.swift` |

### 저장

`SharedPreferences("scenetrip")`의 정수 `scenetrip.locationConsentVersion`(iOS UserDefaults와 같은 키 이름·버전 1). 저장값이 현재 안내 버전과 **정확히 같을 때만** 허용이다.
`LocationConsentStore`는 AI 동의(MZ2AZ-468)가 만든 `VersionedConsentStore`를 키·버전만 바꿔 쓰는 하위 클래스이며, 시트 게이트는 `AIConsentGate`를 그대로 쓴다.
안내(시트 본문·약관)가 바뀌면 `LocationConsentStore.CURRENT_VERSION`을 올린다.

### 좌표 출처

같은 좌표값이라도 **기기에서 얻었는지**, **사용자가 지도·목적지를 골랐는지**는 다르다. 위치 동의는 앞의 경우에만 필요하다.

- 지도 범위: `MapRegionSource`가 카메라 이동 원인으로 기록한다. 손 제스처·컨트롤(`REASON_GESTURE`·`REASON_CONTROL`)은 사용자 선택, 위치 추적(`REASON_LOCATION`)은 기기.
  코드가 옮기는 카메라(`REASON_DEVELOPER`)·여백 변경(`REASON_CONTENT_PADDING`)은 출처를 바꾸지 않는다 — 옮기는 쪽이 옮기기 전에 직접 정한다
  (`fit`·`zoomTo`·`showWholeKorea`는 사용자 선택, `zoomToMyLocation`·내 위치가 들어간 `fit`·`centerOn(…, DEVICE)`·`fitTrip`은 기기). 처음 값은 사용자 선택(남한 전체).
- 호출 하나의 출처: 생성된 코틀린 클라이언트는 호출마다 헤더를 받지 못해 iOS의 내부 헤더(`X-SceneTrip-Coordinate-Source`) 대신 **같은 스레드에서 도는 동기 호출 동안** 스레드 값(`CoordinateSources.with`)으로 싣는다.
  `AuthInterceptor`·`RetryInterceptor`·동의 인터셉터가 모두 호출한 스레드에서 돌므로 인증 재전송·자동 재시도도 같은 출처를 본다. 표식이 없으면 **기기 위치로 취급한다**(iOS와 같다).
- 이어 받기·다시 시도는 최초 출처를 유지한다(`SearchSession.bboxSource`, `RouteGuideSession.Pending.source`).

### 요청 계층(핵심)

`LocationConsentInterceptor`는 `ApiClient.builder`에 `AuthInterceptor` → `RetryInterceptor` → `AIConsentInterceptor` → `LocationConsentInterceptor` 순으로 단다(`auth/AuthStore.kt` `start`).
동의 검사가 재시도 **안쪽**이라 인증 갱신 뒤 재전송과 자동 재시도도 매번 검사받는다. `LocationRequests.need`가 요청을 가른다.

| 요청 | 분류 | 동의 없을 때 |
| --- | --- | --- |
| `/navigation/*` | 필수 | `LocationConsentRequired`(전송 없음) |
| `GET /places`·`/pois`에 `bbox`·`lat`·`lng` + 기기 출처(표식 없음 포함) | 필수 | `LocationConsentRequired` |
| 같은 요청 + 사용자 선택 출처 | 해당 없음 | 그대로 나감 |
| `POST /guide/chat` 본문에 `latitude`·`longitude` + 기기 출처 | 필수 | `LocationConsentRequired` |
| `POST /guide/chat` 사용자 선택 좌표(지도·목적지)·좌표 없음 | 해당 없음 | AI 동의만 필요 |
| `POST /guide/plan` 본문에 좌표 | 선택 | `latitude`·`longitude`만 빼고 전송(다른 값의 서식은 그대로) |

본문을 읽을 수 없거나(일회성 본문·깨진 JSON) 좌표를 뺄 수 없으면 보내지 않는다. 철회와 전송 시작의 원자성은 AI 동의와 같다: `begin(call)`이 **잠금 안에서 허용을 다시 확인하고 호출을 등록**하고,
`revoke()`가 같은 잠금 안에서 값을 0으로 쓴 뒤 등록된 호출을 끊는다(끊김은 동의 오류로 올린다). 좌표를 싣지 않는 요청은 등록하지 않는다.

### 길 안내(TripSession)

- 사용자 동작(시작·다음 목적지·다시 시도·종료)마다 `LocationDirectionsConsent.beginRequest()`로 새 요청 ID를 만든다. 한 요청에서 시트는 **한 번만** 열린다(최초 GPS 수신·위치 갱신마다 다시 묻지 않는다).
- 취소하면 안내 띠에 동의 안내(`failureCode = LOCATION_CONSENT_REQUIRED`)와 「다시 시도」가 남고, 다시 시도하면 새 요청이라 다시 묻는다. 허용하면 이어서 전송한다.
- 카카오 알림은 응답을 받은 직후 요청 ID 기준으로 한 번(`KakaoDirectionsNotice.succeeded`). 응답이 낡아 버려져도 위치는 이미 나갔으므로 센다. GPS 갱신·같은 요청의 중복 완료는 알림을 늘리지 않는다.
- 진행 중 목적지가 바뀌면 `LatestRequestQueue`가 중간 목적지를 건너뛰고 **최신 하나**를 기존 요청이 끝난 뒤 한 번 실행한다. 안내 종료·도착 뒤에는 보내지 않는다.

## 기기 좌표를 서버로 보내는 길과 처리

| 경로 | 호출 | 처리 |
| --- | --- | --- |
| 촬영지 지도 검색·이어 받기 | `SearchTabScreen.kt` `searchViewport` → `SceneData.searchInViewport` → `SearchSession.searchInViewport`·`loadNextPlaces` → `ServerSearchClient.places` → `GET /places?bbox` | GPS 출처 범위는 UI에서 시트 확인(`requestLocation`), 취소하면 상태도 바꾸지 않는다. 전송 계층이 철회·재시도에도 출처를 검사 |
| 주변 편의시설(경로 편집 지도) | `RouteEditorView.kt` `LaunchedEffect(viewport)` → `PoisApi.listPois` → `GET /pois?bbox&lat&lng` | 내 위치가 들어간 맞춤은 시트 확인 후 조회, 취소하면 조회 없음. 손으로 옮긴 범위는 묻지 않음. 길 안내 중 미동의 상태의 기기 출처 범위는 조회를 생략(안내 동의 시트와 겹침 방지) |
| AI 가이드 질문·재시도 | `RouteGuidePanel` → `RouteGuideSession.ask`·`retry` → `POST /guide/chat` | AI 동의 다음에 위치 동의(기기 좌표). 재시도는 최초 출처 보존. 취소하면 입력 유지·전송 없음 |
| AI 코스 만들기 | `RouteWizardView.createAiPlan` → `RouteStore.guideDraft` → `POST /guide/plan` | 출발점이 있으면 위치 동의 시트, 취소·미동의면 **좌표 없이** 생성. 저장소도 동의 없으면 좌표를 빼고, 전송 계층이 한 번 더 뺀다 |
| 길 안내 | `TripSession.fetchLeg` → `POST /navigation/next-leg` | 요청마다 동의 확인·카카오 알림. 전송 계층이 필수 차단 |
| 외부 지도 앱 넘기기 | `RouteEditorView.kt` `TripBanner` → `NavigationUsageNotice` | 현재 좌표를 주소에 싣지 않고 목적지만 넘긴다(출발지는 외부 앱이 정함) |

그 밖의 호출(작품·장소 상세·검색어 조회·홈·코스 저장·방문 저장 등)은 기기 좌표를 싣지 않는다. 안내 중 발자취·도착 판정·파란 점은 기기 안에서만 처리한다.
가이드 대화 맥락(`GuideContext`)의 코스 정류장 좌표는 코스에 담긴 장소의 좌표(사용자 선택)이고, `walkedKilometers`·`targetMeters`는 거리 값이다.

## iOS와 다른 점

- iOS는 요청 헤더로 좌표 출처를 실어 전송 직전에 떼지만, Android는 생성 클라이언트가 호출마다 헤더를 받지 않아 호출 스레드의 값으로 싣는다.
- 철회가 이미 나간 위치 요청을 **끊는다**(iOS는 시작 전 철회만 막는다) — AI 동의(MZ2AZ-468)와 같은 방식이다. 화면에는 동의 안내 문구가 뜬다.
- 마이페이지 토글 자리는 「AI 기능 사용」 바로 아래다(Android에는 이용 통계 설정이 없다).
- 외부 지도 링크는 출발지가 없으면 카카오맵 웹의 목적지 링크만 쓰고 네이버 지도 단추는 세우지 않는다(iOS `ExternalDirections.offers`와 같다). 이전 Android는 현재 좌표를 출발지로 실었다.
- Android는 길 안내가 자동 재탐색을 하지 않는다(iOS와 같다). 위치 갱신이 첫 요청을 이어 부르는 경우는 같은 사용자 요청의 일부로 센다.
- 지도 주변·`RouteGuideAnchor`(MZ2AZ-474, 다른 워크트리)는 main에 아직 없어 이번 범위에서 제외했다. 합쳐질 때 `GET /places?bbox`를 `CoordinateSources.with(출처)`로 감싸고
  UI에서 `requestLocation`을 거치지 않으면, 출처 표식이 없는 호출은 기기 위치로 취급되어 전송 계층이 동의 없이 막는다.

## 검증과 남은 확인

- `just test //apps/scenetrip-android:unit_test` — `tests/LocationConsentTests.kt`(iOS `LocationConsentTests`·`LocationSourceTests` 대응), `tests/SearchParityTests.kt`의 출처 보존.
  가짜 저장소와 끝 인터셉터(전송 대신 요청을 기록한다)만 쓰며 `/navigation/*`·`/guide/*`를 실제로 부르지 않는다.
  취소·철회·버전, 필수 좌표 차단/허용, 선택 좌표 제거(서식 보존)와 읽을 수 없는 본문, 지도 범위 출처별 분기, 첫 검사 뒤 철회, 요청 중 철회 취소, 자동 재시도 대기 중 철회, 출처 표식의 중첩·스레드 격리,
  카카오 알림 한 번·겹친 요청, 길 안내 동의 한 번·취소·재시도·철회 흐름, 최신 목적지 대기열, 한·영 문구와 약관 링크, 외부 지도 링크, 카메라 이유별 출처를 검사한다.
- `TripSession`은 `Context`·`LocationManager`에 묶여 단위 시험이 닿지 않는다. 동의 확인·알림·대기열은 순수 클래스로 분리해 시험했고 `TripSession` 연결은 코드 검토와 실기기 확인에 맡긴다.
- 에뮬레이터 확인(사용자): 시트 문구·링크·단추(한·영·큰 글자), 내 위치 → 「현 지도 내 성지 검색」에서 시트·취소 시 목록 불변, 손으로 옮긴 뒤 검색은 시트 없음,
  코스 편집 화면 진입 직후 주변 편의시설 조회 전 시트, 가이드 질문의 시트 순서(AI → 위치), AI 코스 만들기 취소 시 위치 없이 생성, 길 안내 시작 시 시트·취소 후 「다시 시도」·성공 시 띠 4초,
  마이페이지 토글 끄기 후 재질문. 실제 `/navigation/*`·`/guide/*` 호출은 금지.
- 약관 게시 상태(`#location-services` 앵커)는 네트워크 없이 확인하지 않았다. 부분 동의·서버 확인기록 삭제·실제 좌표 파기 여부는 이 앱 변경만으로 보장하거나 검증하지 않는다.
