# iOS 시뮬레이터 실행 (MZ2AZ-386)

`just ios-run`은 Bazel의 `//apps/scenetrip-ios:bin` IPA를 빌드하고, 부팅한
기기에 덮어 설치한 뒤 앱을 다시 실행한다. 앱 삭제·데이터 초기화는 하지 않는다.
**켜진 기기가 없으면 하나를 켠다**(2026-10-10) — 앱이 깔린 시뮬레이터 중 가장 최근에 켰던 것(쓰던
로그인·코스가 거기 있다), 없으면 설치된 아이폰 중 이름 순 첫째. 부팅이 끝날 때까지 기다리고 Simulator 창을 연다.
네이버 지도 클라이언트 ID는 기존 `.env` 설정으로 빌드에 주입한다.

## 실행

다음을 실행한다. 명령은 앱 PID를 출력한 뒤 돌아온다.

```bash
just ios-run
```

기기가 여럿 켜져 있으면 UDID를 고른다. `SCENETRIP_IOS_DEVICE`로 고른 기기가 꺼져 있으면 켠다.
`-netFault`처럼 쉼표·세미콜론이 있는 값은 작은따옴표로 묶는다.

```bash
SCENETRIP_IOS_DEVICE='<부팅한 기기 UDID>' just ios-run -demoDrive 0 -netFault 'guide/chat:down:all,guide/plan:down:all,navigation/next-leg:down:all'
```

앱 실행 인자를 해석하거나 조합하지 않고 그대로 전달한다. 위 예시는 시뮬레이터
개발 빌드에서 가이드·길찾기 요청을 보내지 않고 실패 응답을 만드는 확인용 실행이다.
일반 실행은 `just ios-run`을 쓴다. 실제 기기 설치·서명은 `just ios-xcode` 범위다.

## 실패 진단

1. 여러 기기가 켜져 있거나 잘못된 UDID이면 설치 전에 중단한다 — 어느 것에 깔지 임의로 고르지 않는다.
   `SCENETRIP_IOS_DEVICE`를 지정한다. 켤 수 있는 아이폰 시뮬레이터가 하나도 없으면 Xcode에서 받는다.
2. Bazel 빌드 오류이면 설치·실행하지 않는다. 빌드 오류를 해결한 뒤 다시 실행한다.
3. 설치·실행 오류이면 해당 명령의 오류와 실패 상태가 전달된다. 임시 번들은
   성공·실패 모두 정리한다. 앱 데이터 삭제를 복구 방법으로 사용하지 않는다.

예전 rules_apple 4.5.3 실행기는 선택된 Xcode의
`Developer/Applications/Simulator.app`을 무조건 열었다. 현재 Xcode 27 설치에는
그 경로가 없어서 **빌드 성공 뒤 실행만 실패**했다. 새 실행 경로는 Xcode 선택이나
생성 실행기를 고치지 않고 `xcrun simctl`로 설치·실행하므로 GUI 앱 경로에 의존하지 않는다.

## 검증·되돌리기

```bash
just test //tools/scripts:ios_run_unit_test
just check
```

단위 시험은 임시 IPA와 가짜 Xcode 명령을 쓴다. 실제 기기·앱·DB를 바꾸지 않는다.
실제 설치 확인에서는 로그인, 기존 코스와 리뷰, 전달한 실행 인자가 유지되는지 본다.
코드 롤백은 MZ2AZ-386 PR을 되돌리지만 이전 GUI 경로 오류도 다시 생길 수 있다.
