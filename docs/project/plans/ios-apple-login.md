# iOS 애플 로그인 — MZ2AZ-415

기존 구글 로그인 시트에 Apple 기본 로그인 버튼을 추가하고, 이미 구현된
`POST /auth/apple`(MZ2AZ-337)로 인증한다. 계약·서버·Android는 변경하지 않는다.
정본은 [소셜 로그인 계획](social-login.md), [ADR 0018](../../architecture/adr/0018-social-login-with-jwt.md),
[출시 계획](prd-launch.md)이다.

## 인증과 화면

- `ASAuthorizationAppleIDButton`을 구글 버튼과 같은 너비·50pt 높이로 표시한다.
  밝은 화면은 검은 버튼, 어두운 화면은 흰 버튼을 사용하고 Apple이 제공한 글자·로고를 유지한다.
- 시도마다 보안 난수 32바이트를 생성한다. Apple 요청에는 원문의 SHA-256 소문자 16진수를,
  생성 API 클라이언트에는 원문 nonce와 identityToken·authorizationCode를 보낸다.
- 첫 로그인에만 받을 수 있는 givenName·familyName은 그때 함께 보내고, 이름이 없는 재로그인도 허용한다.
  토큰 검증과 인가 코드 교환은 서버가 담당한다. 앱은 Apple 토큰을 저장하거나 로그에 남기지 않는다.
- 비회원 쓰기가 완료된 뒤 서버 로그인을 부른다. 성공 시 구글과 같은 토큰 저장·계정 전환·닉네임·
  데이터 다시 읽기 흐름을 사용하고 분석의 로그인 방식만 `apple`로 기록한다.
- 인증 중에는 두 로그인 버튼과 시트 닫기를 잠근다. Apple 창 취소는 조용히 끝나고 다시 시도할 수 있다.
  서버가 501로 Apple 미설정을 알리면 세션을 만들지 않고 안내와 함께 Apple 버튼을 숨긴다(기존 계약).
  인증 거절·일시 장애에서는 버튼을 유지해 새 인증 코드로 다시 시도할 수 있다.
- 기존 entitlement 파일에 `com.apple.developer.applesignin = [Default]`를 추가한다.
  실기기는 김태환님의 유료 개발자 팀에서 같은 번들 ID의 capability와 프로파일을 갖춰야 한다.

## 확인

1. SHA-256 고정값·난수 길이/중복·토큰/코드 누락·잘못된 UTF-8·이름 없는 재로그인을 단위 검사한다.
2. 가짜 인증/교환 창구로 쓰기 완료 전 로그인 금지, 전달 필드, 취소·서버 거절을 확인한다.
3. `just test //apps/scenetrip-ios:unit_test`, `just check`를 실행한다.
4. 시뮬레이터에서 버튼 표시·기본 Apple 인증 창·취소 후 재시도·작은 화면 및 다크 모드를 확인한다.
5. 실제 가입·로그아웃·재로그인·탈퇴는 DEV와 서명된 실기기에서 확인하고,
   실행한 검사와 아직 실행하지 않은 검사를 지라에 구분해 남긴다.

## 공식 자료

- [Apple 인증 구현](https://developer.apple.com/documentation/authenticationservices/implementing-user-authentication-with-sign-in-with-apple)
- [Apple 버튼 표시](https://developer.apple.com/documentation/signinwithapple/displaying-sign-in-with-apple-buttons-in-your-app)
- [nonce](https://developer.apple.com/documentation/authenticationservices/asauthorizationopenidrequest/nonce)

PR을 되돌리면 기존 구글 로그인만 남는다. DB 마이그레이션과 기존 사용자 데이터 변경은 없다.
