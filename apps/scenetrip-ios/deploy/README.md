# iOS 서명·스토어 제출 준비

- `SceneTrip-device.entitlements`: 김태환님의 개발자 팀이 발급한 프로파일로 서명할 때
  사용하는 권한 파일. `AppIdentifierPrefix`는 서명 프로파일에서 공급한다. 프로파일은
  `com.mz2az.scenetrip`의 Sign in with Apple 권한과 키체인 그룹을 허용해야 한다.
- 기존 `../SceneTrip.entitlements`는 프로파일 없는 시뮬레이터 빌드용이다. 실기기용
  `ios_application`을 구성할 때 `entitlements = "deploy/SceneTrip-device.entitlements"`로
  선택하고, 개인 프로파일·인증서·서버 Apple 개인 키를 저장소에 넣지 않는다.

현재 `:bin`과 생성 Xcode 프로젝트는 시뮬레이터용이다. 이 파일이 있는 것만으로
실기기 빌드·TestFlight·앱 심사가 준비된 것은 아니다. 프로파일과 device 대상 설정은
[모듈 README의 실기기 절차](../README.md#2-buildbazel-에서-device-를-켠다)를 따른다.

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
