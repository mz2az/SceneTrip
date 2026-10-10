# iOS 메타 광고 측정 (MZ2AZ-391)

## 범위와 현재 결정

Meta iOS SDK 18.1.1의 Core 제품만 추가한다. Firebase Analytics와 별도로
설치·활성화 및 `save_place`, `create_course`를 측정한다. 로그인·공유 SDK,
Android 구현, 광고 계정 변경은 이 변경에 포함하지 않는다.

ATT 정책·요청 화면은 [MZ2AZ-402](https://mz2az.atlassian.net/browse/MZ2AZ-402)로
분리했고, 사용자 요청으로 구현을 보류한다. 정책은 정승길·정권호가 결정한다.
결정 전에는 설정 파일이 있어도
측정을 기본 비활성화한다. 약관 동의는 ATT 허용을 대신하지 않는다.
활성화 설정과 시스템의 ATT `authorized`가 모두 있어야 SDK를 초기화한다.
거부·제한·미결정 상태에서는 메타로 이벤트를 보내지 않고 앱 기능은 유지한다.

## 구현

- SDK 버전은 Package.swift·Package.resolved에 고정하고 Bazel에서 빌드한다.
- 기본 Info.plist에서 자동 초기화·자동 이벤트·광고 식별자 수집을 끈다.
- 별도 로컬 설정 `MetaService-Info.local.plist`는 커밋하지 않는다. 앱 ID·클라이언트
  토큰·측정 활성화 여부를 읽으며, 파일이 없거나 잘못되면 측정을 끈다.
- Firebase 이벤트 입구에서 메타 수집기로 두 이벤트만 전달한다. 장소 ID,
  검색어, 이메일, 닉네임, 좌표, 회원 여부와 사용자 속성은 메타로 보내지 않는다.
- 포그라운드 진입과 각 이벤트 전 ATT 상태를 확인한다. 허용 전의 이벤트는
  보관하거나 동의 후 다시 보내지 않는다.
- 설치·활성화는 SDK 표준 동작을 사용한다. 추가 자동 행동 수집은 끈다.
- SDK privacy manifest가 앱 번들에 포함되는지 확인한다.

## 검증

SDK를 대신하는 가짜 수집기로 설정 없음·동의 없음·허용·동의 철회와
두 이벤트 외 차단을 테스트한다. Firebase 수집이 영향을 받지 않는지 확인한다.
`just check`, 시뮬레이터 기본 비활성화 실행, 앱 번들 privacy manifest 확인을
수행한다. 실제 광고 귀속·메타 이벤트 관리자 수신은 별도 검증 결과로 남긴다.

## 출시 전 남은 일

태환님이 iOS 개발자 계정을 관리한다. 계정 이메일과 메타 콘솔의 App Store ID는
다른 값이다. App Store Connect의 SceneTrip 앱 정보에 있는 숫자 Apple ID를
메타 콘솔에 등록해야 한다. ATT 요청 화면·한국어와 영어 목적 문구·App Store
개인정보 표시는 정책 결정에 맞춰 추가하고 검증한다. Android 측 완료와 실제
메타 수신까지 확인하기 전에는 MZ2AZ-391 전체를 완료로 처리하지 않는다.

## 근거

- [Meta SDK 18.1.1](https://github.com/facebook/facebook-ios-sdk/releases/tag/v18.1.1)
- [Apple 추적 동의 기준](https://developer.apple.com/app-store/user-privacy-and-data-use/)
- [App Store Connect 앱 정보](https://developer.apple.com/help/app-store-connect/reference/app-information/app-information/)
