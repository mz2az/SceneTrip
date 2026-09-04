# scenetrip-ios

> 모듈 종류: `app` · 언어: `swift` · 경로: `apps/scenetrip-ios`

## 목적

SceneTrip 의 iOS 네이티브 앱. 첫 화면인 **작품검색 탭**(지도 + 바텀시트 + 검색)을
만든다 — 화면 구조·검색 규칙의 기준은
[검색 탭 네이티브 구현 계획](../../docs/project/plans/mobile-native-search-tab.md) §3 이다.

현재 구현된 동선:

- 지도 핀은 **번호 박힌 그라데이션 핀**(파스텔 하늘→보라, 흰 배지에 컬러 번호)이고,
  시트의 장소 목록이 같은 배열을 같은 순서로 그리므로 행 번호가 곧 핀 번호다
  (`NaverMapView.PinImage` — 색은 `light`/`deep` 상수 두 줄로 바꾼다).
- 장소를 고르면(목록 행·핀) 카메라가 그 장소로 **확대**되고, 검색을 확정하면 결과
  도착 시점에 결과 전체 범위로 fit 된다 — 확정 즉시 fit 하면 직전 결과에 맞는다(실측).
- 작품을 고르면 **작품 상세**(포스터·출연·별칭·촬영지 목록)로 드릴다운한다. 검색
  상태는 건드리지 않으므로 뒤로 가면 고르기 전 화면 그대로다.
- 자동완성은 검색창 바로 아래 붙는 드롭다운이고, 뜨는 동안 지도는 스크림으로
  덮인다(밖을 누르면 닫힘). 첫 작품은 포스터 카드로(누르면 작품 상세로 직행),
  장소는 장소 절로, 전체를 연관 검색어 칩으로 배치한다. 별칭으로 걸리면
  (`matchedTerm`) 그 표기를 배지로 보여 준다. **장소 제안을 누르면 장소 탭으로
  넘어가 그 장소를 바로 선택·확대한다** — 검색어 커밋만 하고 작품 탭에 남아
  있으면 고른 것과 무관한 작품이 보인다.

Flutter 프로토타입(`~/workspace/mobile`, 저장소 밖)이 화면 동작의 대조군이다 —
코드는 옮기지 않고 규칙만 가져온다 (ADR 0002).

## 홈 탭 — 탭 셋과 첫 화면 (2026-09-01)

하단 탭은 **셋**이다 — 작품검색 · 가운데 동그란 **홈**(해태 얼굴) · 커뮤니티. 첫 화면은
홈이다. 목업은 [docs/product/canvas/home/](../../docs/product/canvas/home/), 계획은
[mobile-home-tab.md](../../docs/project/plans/mobile-home-tab.md).

경로여정과 마이페이지는 탭에서 내려왔지 없어지지 않았다 — 코드는 그대로고 홈이 전체
화면 덮개(`TabRouter.cover`)로 띄운다. 입구는 홈 안에 있다:

| 홈의 절 | 데이터 | 누르면 |
| --- | --- | --- |
| 인사 + 프로필 단추 | — | 마이페이지 |
| **내 여행 이어가기** (고정 카드) | 여행 중(없으면 최근) 코스 + 서버 `visitedAt` 로 센 스탬프 | 「이어서 길찾기」→ 진행 일차의 다음 미방문 정지점으로 길찾기 · 「코스 보기」→ 경로여정(편집) · 코스가 없으면 「코스 만들기」→ 경로여정 |
| 지금 뜨는 작품 | `ContentsAPI.listContents`, 촬영지 많은 순 | 작품 → 작품검색 탭의 상세(`TabRouter.pendingContentId`) · 「전체 보기」→ 작품검색 탭 |
| 오늘의 성지 | `PlacesAPI.listPlaces` 에서 연중 일수로 한 곳 — 하루 동안 같은 곳 | 「길찾기」→ 그 장소로 길찾기 |
| 여행자들의 코스 | `RouteStore.marketCourses` 둘 | 경로여정의 「둘러보기」(옛 코스마켓) |
| 커뮤니티 지금 | `CommunityStore.posts` 둘 | 커뮤니티 탭 |
| 내 기록 | `VisitStamp.collect` · `LikeStore` | 마이페이지 |

홈은 검색 탭처럼 **항상 살려 둔다**(opacity 전환) — 탭을 오갈 때마다 서버를 다시 부르지
않기 위해서다. 덮개를 닫고 돌아오면 다시 불러온다(코스·스탬프가 달라졌을 수 있다).
목업의 맨 윗줄 검색칸과 「지금 나에게서 N km」 는 넣지 않았다 — 검색은 검색 탭이, 위치
권한은 길찾기 화면이 맡는다.

확인용 뒷문 `-initialTab` 은 그대로다: `search`·`community` 는 탭, `route`·`profile` 은
홈 위의 덮개로 열린다. 인자가 없으면 홈이다(예전엔 검색).

## 여행 모드 — 편집 화면 안에서 길찾기·스탬프 (2026-09-03, 2단계)

계획은 [trip-mode.md](../../docs/project/plans/trip-mode.md) §8. 1단계의 별도 길찾기 창은
코스 여행에서 **더 안 쓴다** — 계획 화면(일차 탭·번호 핀·직선)과 여행이 갈라진 두 세계였다.
이제 **편집 화면의 지도가 곧 여행 지도**다.

```
[코스 시작] / 행의 「길찾기」 / 홈 「이어서 길찾기」          startTrip → TripSession.start
  → 이 지도에 현재 위치 → N번 실제 경로 (직선 계획선 위에)     RouteMapView.legs · tripHere
  → 위치를 계속 받는다 (앱 사용 중 · 25 m 마다)                 RouteLocator.track
  → 반경 100 m 안에 5분 머무르면 도착                            TripArrival (뒷문 -tripDwellSeconds 10)
  → 발바닥 스탬프 쾅 + 햅틱 → visitedAt                          PawStampOverlay · markVisited
       · N번 핀이 **발바닥 핀**으로 바뀌고 목록의 N번 줄은 흐려진다
       · 경로선은 지운다. **다음으로 넘기지 않는다** — 둘러볼 시간은 사람의 것이다
  → 아래 줄 「다음 · M번 ○○로 길찾기」를 사람이 누르면 그때 M번 경로
  → 다 돌면 「오늘 일정을 모두 돌았어요」
```

- 「여기 도착함」은 남겼다 — 머무를 시간이 없거나 GPS 가 튈 때의 탈출구. 저절로 찍힐 때와
  같은 길을 지난다(스탬프 → 도착 상태로 서기).
- 안내 띠(일정 시트 맨 위): 어디로 가는 중인지, 총 시간·환승·도보, 구간 조각(도보=회색·
  대중교통=초록), 머무름 힌트. 오른쪽 X 가 「안내 끝」.
- 번호 핀을 누르면 성지 카드(장면 설명)가 뜨고, 여행 중이면 「여기로 길찾기」가 있다.
- **발자취**(젤다 「영걸의 길」): `FootprintStore` — **기기 파일에만** 저장, 한국 안에서만, 25 m 마다 한
  점. 마이페이지 「발자취」 절에서 거리 확인·끄기·지우기. 시간 되감기는 계획서 §3.
- **안내 중엔 편의시설 점을 끈다**(칩 전부 꺼짐) — 경로선이 주인공. 안내가 끝나면 다시 켠다(2026-09-04).
- **발자취**: 안내 중 지나온 자리를 기기 파일에 남기고, 지도 오른쪽 위 신발 토글(또는 마이페이지
  「지도에 발자취 보기」)을 켜면 **황금색 반투명 발자국**(진행 방향으로 회전)이 찍힌다. 기록은
  토글과 무관하게 늘 남는다 — 꺼 두었다가 켜도 볼 수 있게.
- **시뮬레이터에서는 가상 GPS 가 기본으로 켜진다**(`DemoDrive`, `-demoDrive` 인자 없어도) —
  어느 코스든 「길찾기」를 누르면 경로선을 따라 도보 48 m/s(대중교통 구간은 96 m/s)로 핀 12 m 앞까지 간다. 앱을 켤 때와 코스 화면을 나갈 때 서울시청으로 되돌아간다. 머무름도 시뮬레이터는 5초(실기기 5분). 도착 뒤 「다음 · N번으로」를 누르면 그 자리에서 이어 걷는다. 끄려면
  `-demoDrive 0`. 실기기는 진짜 GPS.
- 홈의 「오늘의 성지」(코스 없음)만 예전 길찾기 창(`RouteNavView`)을 쓴다.
- 시뮬레이터에서 보기: `xcrun simctl location booted set <위도>,<경도>` 로 첫 성지 옆에 서고,
  `-tripDwellSeconds 10` 으로 켰으면 10초 뒤 스탬프가 찍힌다. 다음은 단추를 눌러야 간다.

### 데모 주행 — 중간발표 영상 (계획서 §7)

```sh
just ios-run                      # 앱 설치
just ios-run "iPhone 17"          # 시뮬레이터가 두 대 떠 있으면 기종을 집어 준다
just ios-demo                     # 끝까지, 각 성지 5초 머무름, 도보 48 m/s · 대중교통 96 m/s
just ios-demo 3 8 40              # 3번까지만(영상), 8초 머무름, 40 m/s
just ios-demo 1 5 12 <UDID>       # 두 대일 때는 UDID 로 (booted 는 모호하다)
```

`-demoCourse 1 -navStop 1 -demoDrive N -tripDwellSeconds S -footprintOn 1` 로 앱을 띄운다.
저장소의 데모 코스(`resources/demo/demo-course.json`, 「도깨비 · 선재 2박 3일」 15곳, 장소 id 는 시드 v3)가 이
설치본에 없으면 서버에 만들어 여행 중으로 바꾸고, 1번 성지 길찾기를 연다. 그다음은 **앱 안의
가상 GPS**(`DemoDrive`)가 경로선을 따라 12 m/s 로 걸어가 반경 안에 서고, 머무름이 차면 발바닥
스탬프 → 핀이 발바닥으로. 다음 성지는 「다음 · N번으로」를 눌러야 간다(2026-09-03). 진짜 위치
서비스는 쓰지 않는다. 실행 인자가
없으면 이 코드는 아무 것도 하지 않는다 — 실제 사용자에게는 없는 기능이다.

## 다른 맥에서 돌리기 — 백엔드 담당용 (MZ2AZ-288)

프론트는 전부 main 에 있다. 이 절의 목적은 **백엔드 담당이 앱을 직접 돌려
「어떤 API 가 비어 있는지」를 화면으로 보는 것**이다 — 빈 자리의 명세는 볼트
문서 [백엔드 부탁 목록](https://github.com/kyjii900098/mz2az/blob/main/01_Raw/Project/%EB%B0%B1%EC%97%94%EB%93%9C%20%EB%B6%80%ED%83%81%20%EB%AA%A9%EB%A1%9D%20%E2%80%94%20%EB%A1%9C%EC%BB%AC%20%EC%9E%84%EC%8B%9C%20%EA%B5%AC%ED%98%84%20%EC%A0%95%EB%A6%AC.md)
에 정리돼 있다.

### 절차

1. **준비물** — macOS + 정식 Xcode(시뮬레이터 포함), `brew install just bazelisk`.
   (`swiftformat`·`swiftlint` 는 `just check` 를 돌릴 때만 필요하다.)
2. **키** — 저장소 루트에 `.env` 를 만든다. **값은 팀 채널로 승길에게 받는다 —
   깃에 올리지 않는다.**
   ```
   NAVER_MAP_CLIENT_ID=<네이버 지도 키>
   KAKAO_REST_KEY=<카카오 REST 키>
   ```
   AI 코스 플래너가 부를 LLM 은 여기가 아니라 **`apps/navi_proto/.env` 의
   `LLM_URL`·`LLM_MODEL`·`LLM_API_KEY`** 에서 온다(챗봇 서버와 같은 파일 —
   `just ios-run` 이 빌드 때 읽어 넣는다). 로컬 MLX·Ollama·DeepSeek 등 각자 것을
   쓸 수 있고, 바꾸면 `just ios-run` 을 다시 돌린다. 비어 있으면 로컬 `:8900`.
3. **백엔드** — 앱은 `http://localhost:8081/v1` 을 부른다. 로컬 클러스터를
   띄워 둔다(`just cluster-up`, MZ2AZ-157 구성 그대로).
4. **실행** — `just ios-run`.

### 되는 것 / 안 되는 것

| 화면 | 상태 |
| --- | --- |
| 작품검색·코스 목록·편집·마켓·길찾기·커뮤니티·마이페이지 | **된다** — 실서버(8081) + 카카오 직접 호출 |
| 챗봇(여행 가이드)·주변 편의시설 점 | **안 된다** — 승길 맥의 프로토타입 서버(:8899)에 붙어 있다. 이 구멍이 곧 MZ2AZ-283·284·285 다 |
| 찜·커뮤니티 글·방문 스탬프 일부 | 기기(UserDefaults) 저장 — 맥마다 따로 논다 |

### 함정

| 증상 | 처방 |
| --- | --- |
| 코드를 받았는데 화면이 옛것이다 | `xcrun simctl uninstall booted com.mz2az.scenetrip` 후 다시 `just ios-run` — 같은 번들 id 덮어쓰기가 간헐적으로 반영되지 않는다(실측) |
| 길찾기가 「현재 위치를 찾는 중」에서 멈춘다 | 시뮬레이터에 가짜 위치가 없다. `xcrun simctl location booted set 37.5663,126.9779` (서울시청) |
| 백엔드는 떠 있는데 8081 이 connection refused | 시뮬레이터가 `localhost` 를 `::1`(IPv6) 로 먼저 푸는데 포트가 IPv4 전용일 때 난다 — IPv6→IPv4 프록시를 하나 띄우면 된다(실측) |
| 지도가 회색이다 / `Authorize Error` | `.env` 의 네이버 키가 비었거나, `just ios-run` 이 아닌 방법으로 지었다 |

## 첫 실행 — 스플래시와 사용법

앱을 열면 `AppRoot` 가 **스플래시 → (첫 실행이면) 사용법 넉 장 → 앱** 순서를 든다.
진짜 앱은 처음부터 밑에 깔려 있고 스플래시는 그 위를 덮는다 — 1.9초 동안 지도 SDK
인증과 첫 서버 호출이 끝나므로 덮개가 걷힐 때 화면이 이미 그려져 있다.

마스코트는 **해태**다(피노→진도(MZ2AZ-286)→해태(MZ2AZ-289), 2026-08-28).
정본은 일러스트 컷(`resources/Images.xcassets/haetae-*`)이고, 앱은 포즈별
그림을 갈아끼우며 소품(반짝이·말풍선)과 모션(숨쉬기·갸웃)만 코드로 얹는다 —
`PinoMascot.swift` 머리말 참고. 코드 안 타입 이름(`Pino*`)은 첫 마스코트의
이름이 남은 것이다.

사용법 넉 장은 **영어가 본문**이고 한국어는 흐린 보조줄이다. 본체 UI 는 아직 한국어라
넷째 장을 넘기면 말이 바뀐다 — 별도 일감이다.

다시 보려면 **마이페이지 → 사용법 다시 보기**. 본 적 있는지는 `UserDefaults` 의
`scenetrip.onboarding.seenVersion` 에 판 번호로 남는다.

자세한 것은 [docs/product/prd/onboarding.md](../../docs/product/prd/onboarding.md).

## 경로여정(코스) 탭

`src/RouteTab/` 은 2026-08-11 스프린트 회의에서 확정된 화면이다. **서버에 저장된다** —
`RouteStore` 가 `CoursesAPI`(코스)·`PlacesAPI`(장소)·`ContentsAPI`(작품)를 실제로 부른다.
목 데이터로 돌던 것은 2026-08-22 전후로 걷어냈고, 반경 POI 목도 2026-08-27 에 지웠다 —
편의시설은 이제 챗봇(`RouteGuide`)이 찾아 준다. 우리 서버의 `/pois` 가 서면
(MZ2AZ-283·284) 그쪽으로 갈아탄다.

| 파일 | 하는 일 |
| --- | --- |
| `RouteTabView.swift` | 첫 화면 — 코스가 없으면 「AI 로 짜기 / 직접 짜기」 갈림길, 있으면 목록 + 스와이프 삭제 + 「코스 추가하기」 |
| `RouteWizardView.swift` | 질문 흐름 — 기간 → 날짜(선택) → 작품 → 빡빡/널널 → 요약 |
| `RoutePlanner.swift` | AI 가 코스를 짠다 — 로컬 LLM(`127.0.0.1:8900`)에게 「이 후보 중 어느 것을」만 맡기고, 개수·순서는 코드가 지킨다 |
| `RouteEditorView.swift` · `RouteEditorControls.swift` · `RouteEditorParts.swift` | 편집 화면 — 일차 ＋/−, 드래그 정렬, 체류 시간, 동선 최적화(출발·도착 고정 선택), 장소 검색·장바구니·핀 찍기 |
| `RouteEditorTrip.swift` · `TripSession.swift` · `RouteMapTrip.swift` · `TripMode.swift` | 여행 안내 — 편집 화면 안의 안내 띠·아래 줄·스탬프(`RouteEditorTrip`), 목적지·경로·머무름·데모 주행 상태(`TripSession`), 지도의 파문·경로선·안내 카메라(`RouteMapTrip`), 반경·머무름 판정(`TripMode`) |
| `RouteSearchSheet.swift` | 편집 화면 안에서 바로 장소를 찾아 담는 시트 — 장바구니를 거치지 않는다 |
| `RouteGeometry`(`RouteModels.swift` 안) | 동선 최적화 — 최근접 이웃·2-opt·완전탐색(≤8곳) 세 방법 중 가장 짧은 것. 출발·도착 고정은 각각 선택이다 |
| `RouteMapView.swift` | 코스용 지도 — 번호 핀, 계획 단계는 **직선**만(예상 시간 표시 안 함) |
| `KakaoTransit.swift` | 여행 중 「길찾기」 — 카카오 대중교통·도보(2026-08-24 도보도 카카오로 통일). **MVP1 동안 앱이 직접 부른다**, 백엔드 자리는 MZ2AZ-233 |
| `RouteNavView.swift` · `RouteNavMapView.swift` · `RouteNavModels.swift` · `RoutePoiTone.swift` | 예전 길찾기 창 — 이제 홈 「오늘의 성지」(코스 없음)에만 쓴다. `RouteNavModels` 의 `RouteLeg`·`RouteNavResult` 는 편집 지도도 쓴다 |
| `RouteBridge.swift` | 계약 타입(`CourseDetail` 등) ↔ 화면 타입(`RouteCourse` 등) 번역. 화면이 계약 타입을 직접 만지지 않는다 |
| `RouteMarketView.swift` | 「인기 코스」 — 이름·정렬 기준 미확정. 목록은 여전히 지어낸 것이나 **속 장소는 서버의 진짜 장소**라 담기가 실제로 동작한다 |
| `RouteModels.swift` · `RouteStore.swift` · `RouteMockData.swift` | 값 타입, 서버 연동 상태, (검색 탭 장바구니 예시 등) 남은 목 데이터 |

회의에서 확정돼 코드가 지키고 있는 것:

- **시작 시각을 묻지 않는다.** 8/10 목업에는 있었으나 삭제 확정됐다.
- **날짜는 선택이다.** 안 고르고 넘어갈 수 있고, 종료일은 일차 수에서 계산한다.
- **작품은 찜한 것이 먼저, 나머지는 인기도순.**
- **계획 단계에서는 길찾기 API 를 부르지 않는다.** 지도는 직선으로만 잇고 거리(km)만
  보여 준다 — **예상 소요 시간은 표시하지 않는다.**
- **AI 결과를 바로 저장하지 않는다.** 편집 화면을 거쳐 「코스 만들기」를 눌러야 남는다.
- **AI 가 고르는 개수는 하루 상한 × 일수를 정확히 지킨다.** 빡빡 5곳/일 · 널널 3곳/일 —
  모델이 규칙을 못 지켜도 코드가 자른다(2026-08-24 이전 버그: 2곳만 골라도 통과해
  5일 코스에 하루 1곳씩만 채워진 적이 있다).

아직 정해지지 않아 **일부러 비워 둔 것**:

- 「빡빡하게 / 널널하게」의 하루 개수(5/3)는 정해졌으나 **일차 안에서의 순서·묶는 규칙**은
  동선 최적화(직선거리)에 맡겨 두고 있다.
- 「인기 코스」의 이름·정렬 기준, 그리고 진짜 코스 공유 API(MZ2AZ-232, 아직 백엔드 티켓
  없음 — 2026-08-24 결정: 당장은 만들지 않는다).
- 편의시설 조회는 프로토타입 서버를 빌려 쓴다. `/pois` 계약·구현(MZ2AZ-283·284)이 서면 갈아탄다.

## 최근 작업 로그

경로여정 탭은 여러 세션에 걸쳐 만들어졌다. 아래는 날짜별 맥락 기록이고,
**지금은 전부 커밋돼 있다**(PR #48~#55 로 main 머지, 2026-08-27~28) — 정본은
`git log --oneline` 이다.

**2026-08-22 ~ 08-23 — 서버 연동과 실제 길찾기**

- `RouteStore` 를 서버 연동으로 바꿨다 — 코스 CRUD, 실제 장소·작품 목록.
- 코스 생성이 두 단계(`POST` → `PUT`)로 나뉘는 계약에서, 뒤 단계가 실패하면 **앞서 만든
  빈 코스를 되돌린다**(안 그러면 저장을 다시 누를 때마다 빈 코스가 쌓인다 — 실측: 3번
  눌러 3개).
- `KakaoTransit.swift`·`TmapWalk.swift`(현재는 걷어냄, 아래 참고)로 실제 경로를 받기
  시작했다 — 그전까지는 화면에 있던 것이 좌표만 있고 API 를 안 부르는 데모였다
  ("직선을 긋고 있었다").
- `RoutePlanner.swift` 를 만들어 로컬 LLM 이 AI 코스를 짜게 했다. 개수 버그(하루 상한을
  못 지킴)를 프롬프트와 최소 통과선(70%) 두 군데를 고쳐 잡았다.
- `InstallIdentity.swift` 를 `CartStore` 밖으로 뗐다 — 코스 API 도 같은 설치 식별자가
  필요해서다 (MZ2AZ-261).

**2026-08-23 — 마스코트 피노와 첫 실행 온보딩**

- 앱 마스코트 **피노(PINO)** 와 스플래시·사용법 넉 장을 만들었다. 별도 문서:
  [docs/product/prd/onboarding.md](../../docs/product/prd/onboarding.md).
- 디자인 캔버스를 먼저 만들고(웹) 그대로 SwiftUI 로 옮겼다 — `src/Onboarding/`.
- 시뮬레이터로 실제 화면을 보며 레이아웃 버그 여럿을 고쳤다(꼬리·소품 겹침, 앞발이
  몸에서 떨어져 보임, 스플래시 낙하 애니메이션이 안 보이던 문제 등) — 자세한 것은
  `PinoMascot.swift`·`SplashView.swift` 안 주석.

**2026-08-24 — 코스 관리 다듬기, 카카오 통일, Jira 정리**

- 코스 목록 삭제가 **스와이프뿐이라 아무도 못 찾았다**(사용자 지적). 글자 붙은 삭제
  버튼 + 삭제 확인 대화상자로 바꾸고, 「여행 중」 코스도 지울 수 있게 했다(전에는
  그 섹션에 삭제가 아예 없었다).
- 「내 코스로 담기」가 **말없이 실패했다** — 마켓 목 코스의 장소 id(201~214)가 서버에
  없어 `PUT` 이 500 을 내고 롤백돼 아무것도 안 남았는데 버튼은 「담았습니다」로
  바뀌었다. 마켓 코스를 서버의 진짜 장소로 다시 짓고, 성공했을 때만 표시가 바뀌게
  고쳤다(`RouteStore.buildPopular` · `RouteMarketView`).
- 편집 화면에 **장소 검색**(`RouteSearchSheet.swift`)을 추가했다 — 장바구니를 거치지
  않고 바로 찾아 지금 보는 일차에 담는다(사용자 요청).
- 동선 최적화의 **출발·도착 고정을 각각 선택**으로 바꿨다 — 전에는 첫 장소를 무조건
  고정했는데, 현재 위치에서 출발하는 사람에게는 맞지 않는 가정이었다
  (`RouteGeometry.optimized(pinStart:pinEnd:)`, 테스트 4개 추가로 총 11개).
- 반경 POI(뚝섬역 등)가 **위치와 무관하게 늘 같은 자리**에 찍히는 문제를 확인했다 —
  `RouteNavMock.pois` 가 지어낸 고정값이라서다. 코드는 그대로 두고(고칠 방법이 API
  없이는 없다), 필요한 API 두 건(`/pois` 계약과 구현)을 찾아 정권호에게
  배당했다. 그 티켓들은 8/27 지라 재편 때 MZ2AZ-283·284 로 다시 만들어졌다.
- 길찾기 백엔드 티켓(MZ2AZ-233)도 정권호에게 배당하고, 8/11 판의 "T맵 종량제" 서술이
  낡았음을 댓글로 남겼다.
- **도보 엔진을 T맵에서 카카오로 통일했다** — `TmapWalk.swift` 삭제, `KakaoTransit.swift`
  가 도보까지 부른다. 근거·트레이드오프는 위 §「경로여정(코스) 탭」과
  [course-api.md §7](../../docs/project/plans/course-api.md) 에 있다. `tmap_app_key`
  빌드 정의도 함께 걷어냈다(`BUILD.bazel`·`.bazelrc`·`tools/just/bazel.just`).

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
just ios-run                            # 시뮬레이터에 띄운다
just ios-xcode                          # Xcode 로 연다 (코드 탐색·디버깅·실기기)
```

`just ios-xcode` 는 `SceneTrip.xcodeproj` 를 만들고 연다. **그 파일은 생성물이라
커밋하지 않는다** — 사람마다 서명 설정과 기기 목록이 달라 서로 덮어쓴다. 정본은
`BUILD.bazel` 의 `xcodeproj` 타깃이다.

프로젝트를 열어도 **실제 빌드는 그 안에서 Bazel 이 한다.** 빌드 경로가 둘로 갈라지지
않는다 (ADR 0001 제1법칙).

> **네이버 지도 키 주의** — Xcode 가 부르는 빌드에는 `just ios-run` 의 `--define` 이
> 닿지 않는다. `.bazelrc.user` 에 아래 한 줄을 넣어야 지도가 뜬다. 그 파일은
> gitignore 대상이라 키가 저장소에 들어가지 않는다.
>
> ```
> build --define=naver_client_id=<발급받은 키>
> build --define=kakao_rest_key=<카카오 REST 키>
> build --define=llm_url=http://127.0.0.1:8900
> build --define=llm_model=<apps/navi_proto/.env 의 LLM_MODEL>
> build --define=llm_api_key=
> ```

## 실기기에 올리기

**시뮬레이터가 아니라 진짜 아이폰에서 보려면** 애플 서명이 필요하다. 서명 파일은
사람마다·기기마다 다르고 무료 애플 ID 로 만들면 **7일마다 새로 발급된다.** 저장소에
넣을 수 있는 물건이 아니라서 **기본으로 켜 두지 않았다** — 켜 두면 아이폰이 없는
팀원의 빌드까지 깨진다.

아이폰을 가진 사람이 자기 기계에서 한 번 켜면 된다.

### 1. 애플 ID 를 Xcode 에 등록한다

`Xcode → Settings → Accounts → +` 에서 애플 ID 를 넣는다. **유료 개발자 계정이 아니어도
된다.** 무료 계정으로도 자기 기기에는 설치할 수 있다.

### 2. BUILD.bazel 에서 device 를 켠다

`apps/scenetrip-ios/BUILD.bazel` 의 `xcodeproj` 타깃에서 한 줄을 고친다.

```python
target_environments = (["simulator"],)
# ↓
target_environments = (["device", "simulator"],)
```

그리고 `ios_application` 에 서명을 붙인다. `local_provisioning_profile` 이 **내 기계에
깔린 프로파일을 찾아 준다** — 경로를 손으로 적지 않아도 된다.

```python
load("@rules_apple//apple:apple.bzl", "local_provisioning_profile")
load("@rules_xcodeproj//xcodeproj:defs.bzl", "xcode_provisioning_profile")

local_provisioning_profile(
    name="local_profile",
    profile_name="iOS Team Provisioning Profile: com.mz2az.scenetrip",
    tags=["ios", "manual"],
)

xcode_provisioning_profile(
    name="provisioning_profile",
    managed_by_xcode=True,  # 서명은 Xcode 가 한다
    provisioning_profile=":local_profile",
    tags=["ios", "manual"],
)
```

`ios_application(name = "bin", ...)` 에 `provisioning_profile = ":provisioning_profile"`
를 더한다.

> **이 변경은 커밋하지 않는다.** 프로파일 이름이 사람마다 다르다. 팀 전원이 아이폰으로
> 확인하게 되면 그때 `--define` 으로 이름을 받는 형태로 정리한다.

### 3. Xcode 에서 실행한다

```
just ios-xcode
→ 아이폰을 USB 로 연결 (한 번 연결하면 이후 같은 와이파이에서 무선으로도 된다)
→ Xcode 상단에서 기기를 내 아이폰으로 선택 → Run
→ 아이폰: 설정 → 일반 → VPN 및 기기 관리 → 개발자 앱 신뢰
```

### 알아 둘 것

| 항목 | 내용 |
| --- | --- |
| iOS 버전 | 이 앱은 **iOS 17 이상**이다 (`minimum_os_version`) |
| 무료 계정 만료 | **7일 뒤 앱이 안 열린다.** 다시 설치하면 된다 |
| 무료 계정 앱 수 | 기기당 3개까지 |
| 남에게 보내기 | 무료 계정으로는 **불가능**하다. 링크로 배포하려면 유료 개발자 계정($99/년)과 TestFlight 이 필요하다 |

`Daily Todo.md` 의 「개발자 등록」이 그 유료 계정 얘기다. 멘토·심사위원에게 앱을
돌려야 하는 시점이 오면 그때 필요하다.

## 설정

| 환경변수 | 필수 | 기본값 | 용도 |
| --- | --- | --- | --- |

네이버 지도 클라이언트 ID 는 지도 SDK 연동(MZ2AZ-161) 때 빌드 시점 주입으로 붙는다 —
소스에 박지 않는다. 시크릿은 시크릿 매니저에서 온다.

## 운영

스토어 배포 없음 — MZ2AZ-148 의 완료 조건이 "팀원이 로컬에서 실행"까지다.
