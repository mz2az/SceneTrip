# 앱 업데이트 안내 — 최소·최신 버전과 강제/권장 업데이트 창

- **작성일**: 2026-11-09
- **티켓**: MZ2AZ-546
- **상태**: 구현 — 계획 확인(2026-11-09), 사용자 결정: iOS 만(Android 는 내지 않음), 앱 코드도 이 작업에서

---

## 1. 왜

스토어에 새 버전을 내도 옛 앱 사용자에게 알릴 방법이 없다. 서버 응답을 바꿀 때 옛 앱을 막을 수도 없다. 지금 서버·iOS·Android 어디에도
버전을 확인하는 코드가 없다(2026-11-09 저장소 검색). 사용자 결정: **강제 업데이트 포함**.

## 2. 모양

```
앱 켤 때·다시 앞으로 올 때 → GET /app/versions (로그인 없이)
  설치 버전 < minimum → 닫을 수 없는 창 「업데이트해야 계속 쓸 수 있어요」 [업데이트]
  설치 버전 < latest  → 「새 버전이 나왔어요」 [나중에] [업데이트] — 그 latest 에 한 번만(나중에를 누르면 다음 latest 까지 묻지 않음)
  [업데이트] → 그 플랫폼 스토어의 앱 페이지
  창구 실패(오프라인·옛 서버) → 아무것도 띄우지 않는다(업데이트 확인 때문에 앱을 막지 않는다)
```

## 3. 계약 (1.27.0 — 더하기만)

| 창구 | 하는 일 |
| --- | --- |
| `GET /app/versions` | `{ ios: AppVersionRule, android: AppVersionRule }`. 인증 없음, 캐시 가능(`Cache-Control: max-age=300`) |

- `AppVersionRule { minimum, latest, storeUrl? }` — 버전은 `1.2.3` 처럼 점으로 나눈 숫자 셋(앞에서부터 숫자로 비교). `minimum ≤ latest`.
  `storeUrl` 이 없으면 앱이 자기 스토어 주소를 쓴다(아직 스토어 번호가 없는 플랫폼).
- 비교 규칙은 계약에 적고 iOS·Android 가 같은 시험 사례로 확인한다(예: `1.10.0 > 1.9.9`, `1.2 = 1.2.0`).

## 4. 서버

- 값은 `application.yaml` 의 `scenetrip.app-versions.{ios,android}.{minimum,latest,store-url}` — 환경마다 덮어쓸 수 있다(Spring 설정).
  새 앱을 내면 이 숫자를 고치고 배포한다. DB 는 쓰지 않는다.
- 시작할 때 형식(`숫자.숫자.숫자`)과 `minimum ≤ latest` 를 검사해 틀리면 뜨지 않는다 — 잘못된 숫자로 모든 사용자를 막는 사고를 막는다.
- 처음 값: 두 플랫폼 모두 `minimum = latest = 지금 스토어 버전` → 아무 창도 뜨지 않는다. 강제는 필요할 때 숫자를 올려 켠다.

## 5. 앱

| | iOS (`apps/scenetrip-ios`) | Android (`apps/scenetrip-android`) |
| --- | --- | --- |
| 지금 버전 | `CFBundleShortVersionString` | `versionName`(BUILD.bazel `ANDROID_VERSION_NAME`) |
| 언제 확인 | 켤 때 + `scenePhase == .active` | 켤 때 + `onResume` |
| 창 | 강제는 닫을 수 없는 전체 덮개, 권장은 알림창 | 같다 |
| 「나중에」 기억 | UserDefaults 에 본 latest | SharedPreferences 에 본 latest |
| 스토어 | `storeUrl` 또는 `itms-apps://apps.apple.com/app/id<번호>` | `storeUrl` 또는 `market://details?id=com.mz2az.scenetrip` |

- **앱 코드 담당 확인 필요** — 두 앱 모두 여러 사람이 고쳐 온 모듈이다. 정해지기 전에는 계약·서버까지만 한다.
- iOS 앱스토어 번호(`id…`)가 아직 정해지지 않았으면 `storeUrl` 을 비워 두고 출시 때 서버 설정에 넣는다.

## 6. 시험

| 갈래 | 무엇 |
| --- | --- |
| 서버 단위 | 창구 200·본문 모양, 설정 형식 검사(틀린 형식·minimum > latest 면 시작 실패) |
| 앱 단위 | 버전 비교 사례(같음·작음·큼·자릿수 다름·`1.10.0` 대 `1.9.9`), 강제/권장/없음 판정, 「나중에」 기억 |
| 실제 요청 | `just update scene-api` 뒤 curl. 앱은 설정 숫자를 올려 강제·권장 창이 뜨는지 시뮬레이터·에뮬레이터로 |

## 7. 남는 것

- 웹은 이미 「새 버전이 나왔어요 — 새로고침」 이 있다(번들 비교, MZ2AZ-544). 이 창구를 쓰지 않는다.

## 8. 구현하며 정한 것 (2026-11-09)

- **iOS 만**: 서버 설정에 `ios` 만 둔다. `android` 는 응답에서 빠지고, 계약에는 자리만 있다.
- **처음 값**: App Store 에 낸 판이 1.0.0(빌드 4)이라 `minimum = latest = 1.0.0` — 아무 창도 뜨지 않는다.
- **이미 낸 1.0.0 에는 이 코드가 없다.** 창은 이 코드가 든 다음 판부터 뜬다. 그래서 1.0.0 사용자를 강제로 막을 수는 없다.
- **개발용 빌드는 확인하지 않는다**: 개발 빌드의 `Info.plist` 는 0.1.0 이고 출시 판만 패키징 때 버전을 넣는다. 0.1.0 이면 건너뛴다.
- **스토어 주소**: App Store 번호 6823022207(2026-11-09 사용자). 서버 `store-url` 은 `https://apps.apple.com/app/id6823022207`, 앱의 예비 주소는 `itms-apps://apps.apple.com/app/id6823022207`.
- **게이트웨이**: DEV·PRD nginx 가 `/v1/app` 을 넘기도록 `platform/helm/scenetrip/templates/config.yaml` 에 더했다(빠뜨리면 운영에서만 404 — `tools/aws` 시험이 잡았다).
