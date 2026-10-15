# iOS 서명·스토어 제출 준비

- `SceneTrip-device.entitlements`: 김태환님의 개발자 팀이 발급한 프로파일로 서명할 때
  사용하는 권한 파일. `AppIdentifierPrefix`는 서명 프로파일에서 공급한다. 프로파일은
  `com.mz2az.scenetrip`의 Sign in with Apple 권한과 키체인 그룹을 허용해야 한다.
- 기존 `../SceneTrip.entitlements`는 프로파일 없는 시뮬레이터 빌드용이다. 실기기용
  `ios_application`을 구성할 때 `entitlements = "deploy/SceneTrip-device.entitlements"`로
  선택하고, 개인 프로파일·인증서·서버 Apple 개인 키를 저장소에 넣지 않는다.

현재 `:bin`과 생성 Xcode 프로젝트는 시뮬레이터용이다. 실기기 패키지는 아래의
`:ipa`와 `just ios-package`를 사용한다. 권한 파일만으로 TestFlight·앱 심사가
준비되는 것은 아니며 실제 개발자 팀의 배포 프로파일과 서명 키가 필요하다.

앱의 `PrivacyInfo.xcprivacy`는 자체 `UserDefaults` 사용 이유 `CA92.1`을 선언한다.
최종 수집 항목과 App Store 개인정보 표시에는 서버 데이터 흐름(MZ2AZ-418)과 SDK별
매니페스트를 함께 검토해야 한다. ATT 정책(MZ2AZ-402)이 결정될 때까지 Meta 측정은
기존대로 꺼 둔다. 한국어·영어 위치 권한 설명은 `InfoPlist.strings`에 포함된다.

제출 전 남은 조건(MZ2AZ-416):

- 개발자 팀·번들 capability·서명 프로파일·App Store Connect 앱과 숫자 Apple ID.
- Apple 로그인 키가 설정된 DEV/PRD, HTTPS API와 공개 약관·개인정보처리방침 URL.
- 서명된 실제 기기의 로그인·로그아웃·재로그인·탈퇴와 사진 게시·삭제 검증.
- 제출 버전/빌드 번호, 스크린샷·설명·지원 URL·개인정보 표시, TestFlight 검증.

Apple 근거: [개인정보 매니페스트](https://developer.apple.com/documentation/BundleResources/privacy-manifest-files),
[필수 사유 API](https://developer.apple.com/documentation/bundleresources/app-privacy-configuration/nsprivacyaccessedapitypes/nsprivacyaccessedapitypereasons),
[위치 권한 설명](https://developer.apple.com/documentation/bundleresources/information-property-list/nslocationwheninuseusagedescription).

## 배포용 패키지 (MZ2AZ-440)

Apple 로그인 구현과 Apple의 앱 배포 서명은 별개다. 시뮬레이터용 `:bin`은 그대로이며,
수동 대상 `:ipa`가 실기기 권한 파일과 명시한 배포 프로파일을 사용한다.
`just ios-package`는 arm64·최적화·운영 주소 `https://api.scenetrip.io/v1`로 빌드한다.

태환님 개발자 팀에서 `com.mz2az.scenetrip`의 Sign in with Apple을 활성화하고
Apple Distribution 인증서(개인 키 포함)를 서명할 Mac에 준비한다. 개인 명의 가입의
App Store Connect 초대만으로 개발자 포털 인증서 접근 권한이 생기지는 않는다.
암호를 공유하지 말고 권한 또는 담당자 측 빌드로 진행한다.

프로파일은 저장소 밖에 보관한다. `.env` 또는 현재 셸에서 아래 값을 공급한다.

- `SCENETRIP_APPLE_TEAM_ID`: 실제 개발자 팀 식별자.
- `SCENETRIP_IOS_SIGNING_IDENTITY`: Mac 키체인의 Apple Distribution 인증서 **이름**(`Apple Distribution: <이름> (<팀ID>)`). SHA-1 을 넣으면 rules_apple 이 `No signing identity found` 로 실패한다(MZ2AZ-460).
- `NAVER_MAP_CLIENT_ID`: 지도 클라이언트 ID.
- `SCENETRIP_IOS_DEVICE_UDID`: Ad Hoc 검증 시 등록된 iPhone/iPad 식별자.

Firebase 운영 설정 `resources/GoogleService-Info.plist`도 필요하다. 실제 인증서/프로파일/
Firebase 설정의 앱·프로젝트가 맞는지는 담당자가 확인한다.

```sh
just ios-release-check /path/to/profile.mobileprovision app-store --team TEAMID
just ios-package /path/to/profile.mobileprovision app-store 1.0.0 1
# iPad에 직접 설치할 패키지: UDID를 등록한 Ad Hoc 프로파일 필요
just ios-package /path/to/adhoc.mobileprovision ad-hoc 1.0.0 2
```

버전과 빌드 번호는 예시이며 실제 제출 번호를 정해 입력한다. 번호를 자동 증가시키지 않는다.
프로파일 검사는 만료·팀·정확한 App ID·Apple 로그인·키체인·배포 종류를 확인한다.
`security cms -D`로 지정 파일만 해독하며 계정 토큰이나 키체인 암호를 조회하지 않는다.
프로파일 사전 검사 통과는 인증서 개인 키 보유·실제 서명 성공을 보장하지 않는다.
실제 패키지 생성에서는 Apple codesign이 해당 키의 사용 허용을 요청할 수 있다.

임시 프로파일은 빌드 후 제거한다. Bazel 캐시에 프로파일이 남을 수 있으므로 로컬 개발
캐시도 개인 자료로 취급한다. `ipa.ipa`는 이 작업트리의 `bazel-bin/apps/scenetrip-ios/`에
생기며 업로드/설치/계정 로그인은 자동 수행하지 않는다. 다른 Bazel 작업과 동시에 실행하지 않는다.
실패 시 프로파일을 바꿔 임의로 우회하지 말고 출력된 조건을 확인한다.

## iPad와 iPhone 검증 분담

현재 `families = ["iphone"]`, 최소 OS 17.0이다. iPad의 iPhone 호환 모드로 로그인,
로그아웃, 사진 선택·업로드, 후기 작성/수정/삭제, 코스 저장을 검증할 수 있다.
TestFlight 또는 등록 UDID용 Ad Hoc 패키지로 설치하며, 시뮬레이터 IPA는 설치할 수 없다.
iPad 네이티브 레이아웃 지원을 추가한 것은 아니다. iPhone 화면·실제 이동/GPS·외부 지도
전환은 태환님 기기에서 별도로 확인한다. 모델별 위치 기능 차이도 확인한다.

서명 성공 이후 순서: 기기 기능 검증 → 수집 항목·SDK 매니페스트·암호화 설문 검토 →
App Store Connect 앱/버전/지원 URL/스크린샷 준비 → TestFlight 업로드·처리 확인 → 테스터 초대.
암호화 답변은 사용하는 라이브러리를 확인하고 정하며 자동으로 면제라고 선언하지 않는다.

근거: [Apple 프로파일](https://developer.apple.com/help/account/provisioning-profiles/create-an-app-store-provisioning-profile),
[등록 기기 배포](https://developer.apple.com/documentation/xcode/distributing-your-app-to-registered-devices),
[개인 계정 사용자 초대](https://developer.apple.com/help/app-store-connect/manage-your-team/add-and-edit-users/).
