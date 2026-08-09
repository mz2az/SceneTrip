# scenetrip-ios

> 모듈 종류: `app` · 언어: `swift` · 경로: `apps/scenetrip-ios`

## 목적

SceneTrip 의 iOS 네이티브 앱. 첫 화면인 **작품검색 탭**(지도 + 바텀시트 + 검색)을
만든다 — 화면 구조·검색 규칙의 기준은
[검색 탭 네이티브 구현 계획](../../docs/project/plans/mobile-native-search-tab.md) §3 이다.
현재는 빈 화면을 띄우는 뼈대까지 있다 (MZ2AZ-160).

Flutter 프로토타입(`~/workspace/mobile`, 저장소 밖)이 화면 동작의 대조군이다 —
코드는 옮기지 않고 규칙만 가져온다 (ADR 0002).

## 인터페이스

| 항목 | 값 |
| --- | --- |
| 프로토콜 | 해당 없음 (클라이언트 앱) |
| 산출물 | `:bin` — 시뮬레이터/기기에 설치하는 .app 번들 |
| 계약 | `contracts/openapi/scene-api-v1.yaml` — swift5 생성 클라이언트로 소비한다 (생성 타깃은 아직 없음, 계획서 §5-5) |

## 의존성

| 의존 대상 | 이유 |
| --- | --- |
| `services/scene-api` | 직접 import 가 아니라 계약(`contracts/openapi/`)을 통해 |

## 빌드가 되는 조건

- **macOS + 정식 Xcode 만.** 모든 타깃에 `tags = ["ios"]` 가 달려 있고 `.bazelrc` 가
  리눅스에서만 그 태그를 걸러내므로 리눅스 러너는 이 모듈을 건너뛴다. **새 타깃을
  추가하면 `tags = ["ios"]` 도 함께 단다** — 상세와 실측 근거는 `BUILD.bazel` 머리말
  과 계획서 §5-3.
- `.swift` 파일이 있으므로 `just check` 가 `swiftformat`·`swiftlint` 를 요구한다 —
  없으면 게이트가 빨간불이다. `brew install swiftformat swiftlint`.
- 규칙 설정 파일(`.swiftlint.yml`·`.swiftformat`)은 아직 만들지 않았다 — 화면을 몇 개
  옮긴 뒤 실제로 나온 경고를 근거로 쓴다 (계획서 §6).

## 명령

```bash
just build-module apps/scenetrip-ios    # 빌드
just run //apps/scenetrip-ios:bin       # 시뮬레이터 실행
```

## 설정

| 환경변수 | 필수 | 기본값 | 용도 |
| --- | --- | --- | --- |

네이버 지도 클라이언트 ID 는 지도 SDK 연동(MZ2AZ-161) 때 빌드 시점 주입으로 붙는다 —
소스에 박지 않는다. 시크릿은 시크릿 매니저에서 온다.

## 운영

스토어 배포 없음 — MZ2AZ-148 의 완료 조건이 "팀원이 로컬에서 실행"까지다.
