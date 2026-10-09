# iOS 시뮬레이터 실행 호환성 (MZ2AZ-386)

## 문제와 범위

`just ios-run`의 Bazel 빌드는 성공하지만 rules_apple 4.5.3의 실행기가 선택된
Xcode 경로 아래 `Developer/Applications/Simulator.app`을 열다가 실패했다.
현재 Xcode 27 설치에는 그 경로가 없고 `simctl`과 이미 부팅한 기기는 정상이다.
생성 실행기에는 GUI 열기를 건너뛰는 옵션이 없다.

앱 빌드 체계와 Xcode 선택은 유지하고 설치·실행만 저장소의 스크립트로 옮긴다.
Simulator GUI를 새로 열거나 기기를 만들지 않는다. 이미 부팅한 기기가 없거나
여럿이라 모호하면 설치 전에 실패한다. `SCENETRIP_IOS_DEVICE`로 UDID를 지정한다.

## 구현 순서

1. 가짜 `xcrun`과 임시 IPA로 기기 확인, 설치·실행 순서,
   실행 인자의 공백 보존, 삭제 없는 재설치, 오류 전달을 검증하는 단위 시험을 쓴다.
2. `just ios-run` 레시피가 Bazel로 같은 `:bin` IPA를 빌드하고 키는 기존 환경변수로
   주입한다. 새 `tools/scripts/ios-run.sh`는 `simctl getenv`로 부팅한 기기를 확인한다.
3. 임시 폴더에 번들을 풀어 `simctl install`로 덮어 설치한다. 앱 데이터 삭제나
   `uninstall`은 하지 않는다. `launch --terminate-running-process`로 다시 실행하며
   `just ios-run`에 넘긴 인자를 배열 그대로 전달한다.
4. 실패하면 뒤 단계를 실행하지 않고 임시 폴더를 정리한다. 단위 시험·`just check`
   뒤 지정 기기에서 실행해 로그인·기존 코스가 유지되는지 확인한다.

## 검증과 제한

단위 시험은 실제 Xcode·시뮬레이터·앱·DB에 닿지 않는다. 기기 부팅과 GUI 표시를
자동으로 하지 않으므로 처음 실행할 때는 사용자가 기기를 먼저 부팅해야 한다.
실행기는 앱 로그를 붙잡지 않고 PID를 출력한 뒤 돌아온다. 실기기 서명은 기존
`just ios-xcode` 범위다. 앱 내부 기능·서버·계약은 변경하지 않는다.
