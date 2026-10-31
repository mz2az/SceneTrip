# apps/scenetrip-android/deploy — Play Store 배포

Google Play 에 올리는 **앱 번들(AAB)** 을 만드는 방법이다. 스토어 업로드 자체는 이 저장소가 하지 않는다 — 콘솔에서 사람이 올린다.

| 파일 | 내용 |
| --- | --- |
| `bundle_config.pb.json` | 번들을 나누는 방식. **언어로 나누지 않는다** — 앱 안에서 한국어·영어를 고르는데, 구글이 기기 언어 밖의 문구를 빼면 영어를 골랐을 때 한국어 문구로 보인다 |

## 올리기까지

1. **업로드 키스토어를 만든다 (처음 한 번).**
   `just android-upload-keystore` — 비밀번호와 이름을 직접 입력한다. 기본 위치는 `~/.scenetrip/scenetrip-upload.jks` 이다.
   **저장소에 넣지 않고, 파일과 비밀번호를 따로 백업한다.** 잃어버리면 Play Console 에서 업로드 키 재설정을 요청해야 한다.
2. **`.env` 에 경로와 비밀번호를 적는다.** `ANDROID_UPLOAD_KEYSTORE`, `ANDROID_UPLOAD_STORE_PASSWORD`(별칭이 `upload` 가 아니면
   `ANDROID_UPLOAD_KEY_ALIAS`, 키 비밀번호가 따로 있으면 `ANDROID_UPLOAD_KEY_PASSWORD`). `.env` 는 커밋하지 않는다.
3. **번들을 짓고 서명한다.**
   `just android-bundle https://api.scenetrip.io/v1` → 저장소 루트에 `scenetrip-android-release.aab` (`ANDROID_BUNDLE_OUT` 으로 바꾼다).
   `*.aab` 는 커밋하지 않는다.
4. **Play Console → 테스트 및 출시 → 내부 테스트 → 새 버전 만들기** 에 올린다.

## 버전

Play 는 올릴 때마다 **`versionCode` 가 커져야 한다**. 새 번들을 올리기 전에 `BUILD.bazel` 의 `ANDROID_VERSION_CODE` 를 올린다.
사람이 보는 이름은 `ANDROID_VERSION_NAME`(iOS 1.0.0 과 맞춘다).

## 첫 업로드 뒤에 할 일

Play 앱 서명이 켜지면 사용자 기기에는 **구글이 다시 서명한 키**로 서명된 앱이 깔린다. 그 지문은 Play Console →
**설정 → 앱 무결성 → 앱 서명 → 앱 서명 키 인증서** 의 SHA-1 이다.

- **구글 로그인:** 그 SHA-1 로 Google Cloud 의 **Android OAuth 클라이언트**(패키지 `com.mz2az.scenetrip`)를 하나 더 등록해야 한다.
  등록하지 않으면 스토어로 받은 앱에서 로그인이 안 된다. 클라이언트 하나에 SHA-1 하나다. 서버·앱 코드는 바꾸지 않는다
  (`docs/project/plans/social-login.md` §9).
- **Firebase 등 API 키에 앱 제한(패키지+SHA-1)을 걸었다면** 같은 지문을 추가한다.

## 알아 둘 것

- 이 번들의 네이티브 라이브러리는 **arm64-v8a 뿐**이다(네이버 지도 SDK 를 이렇게 받는다). 32비트 전용 기기·x86 에뮬레이터에서는
  설치되지 않는다.
- 서명하지 않은 번들(`bazel-bin/.../bundle_unsigned.aab`)은 스토어가 받지 않는다. 항상 `just android-bundle` 로 만든다.
