# Android Meta 광고 측정과 광고 ID (MZ2AZ-475)

2026-10-16 현재 **Android에는 Meta SDK가 없다.** MZ2AZ-475는 마이페이지 「이용 통계 수집」 스위치만 옮겼고,
그 스위치가 제어하는 것은 Firebase Analytics뿐이다. 이 문서는 Android에서 Meta 광고 측정을 켜기로 결정했을 때
출시 전에 해야 할 일을 모아 둔 계획이다. `MODULE.bazel`·`apps/scenetrip-android/BUILD.bazel`에는 아무것도 추가하지 않았다.

iOS는 [meta-app-events.md](meta-app-events.md)(MZ2AZ-391)와 [ios-att-statistics.md](ios-att-statistics.md)(MZ2AZ-402)에서
Meta 측정을 ATT 허용·설정 파일·이용 통계 ON 세 조건이 모두 있을 때만 켠다.

## 지금 Android의 상태

- Firebase Analytics만 있다. 광고 식별자(AAID)는 읽지 않는다(`analytics/AppAnalytics.kt` 머리말).
- `AndroidManifest.xml`에 `com.google.android.gms.permission.AD_ID`가 없다. Firebase Analytics SDK 자체는 이 권한을
  매니페스트에 병합해 넣을 수 있으므로, **광고 ID를 쓰지 않는 동안에도 병합 결과를 출시 전에 확인한다**(아래 체크리스트).
- 이용 통계 OFF는 Firebase 자동 수집까지 끈다. Meta가 붙으면 같은 스위치가 Meta 초기화·이벤트도 막아야 한다.

## Meta를 붙일 때 필요한 것

1. **Meta SDK (Android).** Facebook Android SDK의 Core 제품만 쓴다(iOS와 같은 범위: 설치·활성화, `save_place`, `create_course`).
   버전은 `MODULE.bazel`의 Maven 목록에 정확히 고정하고 `just deps-update`로 잠금 파일을 갱신한다. 무거운 의존성 추가이므로
   CLAUDE.md §9에 따라 추가 전에 사람에게 먼저 묻는다.
2. **광고 ID 권한.** Android 13(API 33)+ 에서 `targetSdk`가 33 이상이면 광고 ID는
   `<uses-permission android:name="com.google.android.gms.permission.AD_ID" />`가 있어야 받을 수 있다. 없으면 SDK는 0으로 채운
   ID를 받는다. 지금 `targetSdkVersion`은 36이다. Meta 측정을 쓰려면 이 권한을 매니페스트에 직접 선언하고,
   쓰지 않기로 하면 `tools:node="remove"`로 지워 광고 ID를 확실히 끊는다 — 둘 중 하나를 의도적으로 고른다.
3. **설정 값.** Meta 앱 ID·클라이언트 토큰은 iOS의 `MetaService-Info.local.plist`처럼 커밋하지 않는 로컬 설정으로 둔다
   (`.env` → `manifest_values`). 값이 없으면 측정을 끈다.
4. **동의 모델.** Android에는 ATT가 없다. 제품 결정이 필요하다:
   - 이용 통계 스위치(기본 ON) 하나로 Firebase와 Meta를 함께 제어한다 — iOS와 같은 스위치지만 ATT 같은 시스템 동의창이 없다.
   - 또는 Meta만 별도 동의(첫 실행 시트·설정 토글)를 둔다.
   사용자가 시스템 설정에서 광고 ID를 삭제하거나 개인 맞춤 광고를 끄면(`limitAdTracking`) Meta 이벤트를 보내지 않는다.
   정책 결정은 정승길·정권호 몫이다.
5. **스위치 연결.** `UsageStatistics`의 적용 콜백(`AppAnalytics.setCollectionEnabled`)에 Meta 클라이언트의 활성화/비활성화를
   연결하고, `AppAnalytics.log` 입구의 게이트(이미 있음)를 Meta 수집기도 통과시킨다. iOS와 같이 SDK가 이미 넘겨받은 이벤트는
   취소할 수 없다는 한계를 문서에 남긴다.
6. **이벤트 범위.** 장소 ID·검색어·이메일·닉네임·좌표·회원 여부·사용자 속성은 Meta로 보내지 않는다(iOS 규칙 그대로).

## 출시 전에 할 일

- [ ] **개인정보 처리방침에 Android 광고 ID 문장 추가.** 방침 원문은 이 저장소 밖(`scenetrip.io/privacy`, 랜딩 프로젝트)에 있다.
  Meta 측정을 켜는 날 함께 반영한다. 한국어·영어 모두. 예시 문장(법무 확인 필요):
  - 한국어: 「Android 기기에서는 광고 캠페인의 설치·앱 내 행동(장소 저장, 코스 만들기) 측정을 위해 기기의 광고 ID를 Meta에 전달할 수 있으며,
    마이페이지의 ‘이용 통계 수집’을 끄거나 기기 설정에서 광고 ID를 삭제·재설정하면 전달하지 않습니다.」
  - English: "On Android, we may share your device advertising ID with Meta to measure ad-driven installs and in-app actions (saving places,
    creating courses). We do not share it if you turn off Usage statistics in My Page or delete or reset your advertising ID in device settings."
- [ ] **Google Play 데이터 보안(Data safety) 양식.** Meta를 붙이면 「기기 또는 기타 ID」(광고 ID)를 수집·공유하는 것으로 신고하고
  목적을 「광고 또는 마케팅」·「분석」으로 적는다. 앱 활동(장소 저장·코스 만들기)도 공유 항목에 해당하는지 확인한다.
  Firebase Analytics 신고(앱 활동·기기 ID)와 일치해야 한다. 광고 ID를 쓰지 않기로 하면 「광고 ID 사용 안 함」으로 선언하고
  `AD_ID` 권한을 제거한 APK를 올린다(Play Console의 광고 ID 선언은 매니페스트와 맞아야 한다).
- [ ] **매니페스트 병합 결과 확인.** 릴리스 APK에서 `aapt2 dump permissions`로 `AD_ID`가 의도대로 있거나 없는지 확인한다.
- [ ] **Meta 콘솔.** Android 패키지명 `com.mz2az.scenetrip`, 서명 키 해시를 등록한다. 계정은 태환님이 관리한다.
- [ ] **실기 검증.** 스위치 OFF에서 Meta 초기화·이벤트가 없는지, 광고 ID 삭제 상태에서 이벤트가 나가지 않는지, 이벤트 관리자 수신을
  확인한다. 확인 전에는 MZ2AZ-391 전체를 완료로 처리하지 않는다.
- [ ] 이 계획이 실행될 때 `apps/scenetrip-android/README.md`와 이 문서를 갱신한다.

## 이번 변경(MZ2AZ-475)에서 하지 않은 것

- Meta SDK·`AD_ID` 권한·`MODULE.bazel` 변경.
- 서버 인기도 기록(`UsageEvents`, `/events`)에는 스위치를 걸지 않았다. iOS도 Firebase·Meta만 막는다.
