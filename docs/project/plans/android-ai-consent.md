# Android AI 기능 동의 (MZ2AZ-468)

iOS [ios-ai-consent.md](ios-ai-consent.md)를 같은 문구·같은 순서로 옮긴다. 기준은 iOS main(PR #312의
내용에 맞춘 시트 높이·세로로 쌓인 단추와, 동의 저장소를 AI·위치가 함께 쓰도록 일반화한 `VersionedConsentStore`)이다.

## 범위와 흐름

- 첫 가이드 질문 직전과 첫 AI 코스 만들기(마법사 마지막 「AI 로 일정 짜기」 → `POST /guide/plan`) 직전에
  「AI 기능 사용 안내」 시트를 한 번 띄운다. 앱 시작·가이드 열기·마법사 진입만으로는 띄우지 않고 요청도 보내지 않는다.
- 본문은 iOS와 한 글자까지 같다. `LegalLinks.PRIVACY` 링크(외부 브라우저), 단추 「허용하고 계속」 / 「취소」.
- 허용하면 두 기능을 함께 쓸 수 있다(동의 하나). 허용 뒤 시트가 사라지면 대기 중 동작을 **한 번** 실행한다.
  취소·바깥 누르기·끌어 내려 닫기는 모두 취소이며 입력한 질문과 마법사 선택값을 그대로 둔다.
- 가이드 창의 「다시 시도」와 예시 질문도 같은 문을 지난다. 예시 질문은 입력창에 먼저 채워지므로 취소해도 남는다(iOS 동일).
- 마이페이지의 「도움」 아래 「AI 기능 사용」 토글(꼬리말 「끄면 AI 가이드와 AI 코스 만들기를 쓸 때 다시 물어봐요.」).
  끄면 동의를 철회하고, 다시 켤 때도 같은 안내를 확인한다(안내를 취소하면 켜지지 않는다).
- 동의 거부로 끝난 요청의 문구는 「AI 기능 사용을 허용한 뒤 다시 시도해 주세요.」(iOS `RouteGuideFailure.consentRequired`).

## 구현 (iOS 대응)

| Android | iOS |
| --- | --- |
| `data/AIConsent.kt` — `AIConsentStorage`, `SharedPrefsConsentStorage`, `VersionedConsentStore`, `AIConsentStore`, `AIConsentRequired`, `AIConsentInterceptor`, `AIConsentGate` | `Models/AIConsent.swift`, `Models/RetryingSession.swift`의 동의 검사 |
| `ui/AIConsentSheet.kt` — `ConsentSheet`, `AIConsentSheet`, `AIConsentSheetHost`, 문구 `AIConsentCopy` | `Models/AIConsentSheet.swift` |
| `profiletab/ProfileAISettings.kt` (+ `ProfileTabView`에 배치) | `ProfileTab/ProfileAISettings.swift` |
| `routetab/RouteGuideSheet.kt`, `routetab/RouteWizardView.kt`의 게이트 | `RouteGuideSheet.swift`, `RouteGuideSheetLimit.swift`, `RouteWizardView.swift` |
| `data/NetworkFailure.kt`, `data/ApiFailure.kt`의 동의 문구 | `RouteTab/RouteGuideFailure.swift` |

- **저장**: `SharedPreferences("scenetrip")`의 정수 `scenetrip.aiConsentVersion`(iOS UserDefaults와 같은 키 이름·버전 1).
  저장값이 현재 안내 버전과 **정확히 같을 때만** 허용이다. 안내가 바뀌면 `AIConsentStore.CURRENT_VERSION`을 올린다.
- **재사용 구조**: 저장·철회·전송 잠금·화면 게이트는 `VersionedConsentStore`/`AIConsentGate`에 있고 AI 동의는 키와 버전만 정한
  하위 클래스다. 위치기반서비스 동의(MZ2AZ-472)는 같은 방식으로 하위 클래스 하나와 토글 하나를 더하면 된다.
- **요청 계층(핵심)**: `AIConsentInterceptor`는 `ApiClient.builder`에 `AuthInterceptor` → `RetryInterceptor` →
  `AIConsentInterceptor` 순으로 단다(`auth/AuthStore.kt` `start`). 동의 검사가 재시도 **안쪽**이라 인증 갱신 뒤
  재전송과 자동 재시도도 매번 검사받는다. `POST …/guide/chat`·`…/guide/plan`만 대상이고 미동의는 `AIConsentRequired`
  (`IOException`)로 끝나며 다음 인터셉터(실제 전송)를 부르지 않는다. `RetryInterceptor`는 이 오류를 되풀이하지 않는다.
- **철회와 원자성**: iOS는 전송 시작(`task.resume()`)이 비동기라 저장소 잠금 안에서 시작한다. OkHttp의 `proceed`는 응답이
  올 때까지 막혀 잠금을 쥔 채 보낼 수 없으므로, `begin(call)`이 **잠금 안에서 허용을 다시 확인하고 호출을 등록**하고,
  `revoke()`가 같은 잠금 안에서 값을 0으로 쓴 뒤 등록된 호출을 끊는다(끊김은 동의 오류로 올린다). 그래서 첫 검사 뒤
  철회되면 시작이 막히고, 이미 나간 호출도 철회가 끝나면 취소된다.
- **시트 높이**: iOS `ConsentSheet`와 같이 내용 높이를 재서 240~480dp로 맞추고(처음엔 반쯤), 더 크면 스크롤하거나 위로
  끌어 크게 연다. 단추는 세로로 쌓인 전체 폭(허용 캡슐 위, 취소 회색 글자 아래, 취소 높이 44dp 이상).
- **문구**: 한국어 원문을 열쇠로 `Translations.kt`(iOS 번역표에서 `just android-translations`로 생성)의 영어를 쓴다.
  새 문구는 이미 iOS 표에 있어 `TranslationExtras.kt`는 바꾸지 않았다.
- 서버·계약·에이전트·iOS는 바꾸지 않는다. 새 소스는 `BUILD.bazel`의 `src/**/*.kt` glob에 들고, 시험은 `unit_test_lib`에 올렸다.

## iOS와 다른 점

- 마이페이지에 이용 통계 설정이 아직 없어, 토글 자리는 「도움」 바로 아래·「약관 및 정책」 위다(iOS는 통계 설정 아래).
- 위치기반서비스 동의·위치 좌표 제거·`LocationConsentRequired`는 이번 범위가 아니다(MZ2AZ-472).
- 철회가 이미 나간 AI 요청을 **끊는다**(iOS는 시작 전 철회만 막는다). 화면에는 동의 안내 문구가 뜬다.

## 검증

- `just test //apps/scenetrip-android:unit_test` — `tests/AIConsentTests.kt`(iOS `AIConsentTests` 대응 + 요청 계층).
  가짜 저장소와 끝 인터셉터(전송 대신 횟수만 센다)만 쓰며 `/guide/*`를 실제로 부르지 않는다.
  미동의 차단(채팅·플랜), 허용 후 한 번 진행과 두 기능 공유, 취소·닫기의 입력 보존, 설정 철회 후 재확인,
  버전 변경, 다른 API 불변, 저장값 복원, 허용 후 철회 시 대기 동작 미실행, 재시도 대기 중 철회, 첫 검사 뒤 철회,
  요청 중 철회, 오류 문구, 한·영 문구, 다른 동의의 독립 철회를 검사한다.
- 에뮬레이터 확인(사용자): 한·영·큰 글자에서 시트 본문·링크·단추, 질문/마법사 취소 후 입력 유지, 마이페이지 토글과
  재실행 후 저장 상태. 실제 AI API 호출은 금지.
