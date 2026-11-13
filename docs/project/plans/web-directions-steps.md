# 웹 — 길찾기 안내를 외국인도 읽게 (MZ2AZ-566)

- **상태**: 구현 중(2026-11-13). dev 먼저, 사용자 확인 뒤 main.
- **왜**:
  - 11/11 멘토 지적: 안내 띠의 구간이 가로로 긴 한 줄이다. 어디서 어디까지 걷고 어디서 무엇을 타는지 알기 어렵다. 외국인은 버스 종류와 정류장 이름도 모른다.
  - 11/13 사용자 지적:
    - 지도는 모든 구간이 보라색이다. 대중교통 선 색은 안내 글자의 색과 맞아야 한다.
    - 도보는 보라색 길 위의 점선이 아니라 회색 점선이어야 한다.
    - 「내 위치」를 누르면 위치를 잡을 때까지 5 초쯤 아무 반응이 없는 것처럼 보인다.
- **범위**: 웹만 바꾼다. 서버와 계약(`RouteLeg`)은 그대로다. 외부 길찾기를 새로 부르지 않는다.

## 1. 구간의 색·표시 — 순수 함수 (`features/route/nav/navStyle.ts`)

테스트는 이 절과 §2·§3만 보고 쓴다.

### 1-1. `WALK_COLOR = '#7A69ED'`

도보 구간의 색이다. 지도 선과 단계 목록 둘 다 이 색을 쓴다. 앱 대표 보라다. 처음엔 회색(`#8A94A6`)이었는데 지도 위에서 눈에 안 띄었다(2026-11-13 사용자 지적).

### 1-2. `boardAlight(guidance: string): { from: string; to: string } | null`

안내문에서 탈 곳과 내릴 곳을 꺼낸다.

- 안내문의 **마지막** 괄호 안이 `A > B` 꼴이면 `{ from: A, to: B }` 이고, 앞뒤 공백은 뗀다.
  - 예: `'마을 종로02외 1대 (북촌한옥마을입구 > 가회동주민센터)'` → `{ from: '북촌한옥마을입구', to: '가회동주민센터' }`
  - 예: `'Line 1 (City Hall > Daebang)'` → `{ from: 'City Hall', to: 'Daebang' }`
- 괄호가 없고 안내문 전체가 `A > B` 꼴이어도 같다. 예: `'서울역 > 동대문'` → `{ from: '서울역', to: '동대문' }`.
- `>` 가 없거나 양쪽 중 하나가 비면 `null` 이다.
- 「마지막 괄호」는 **맨 바깥** 괄호 묶음 중 마지막이다(검증 뒤 추가). 정류장 이름 안의 괄호는 그 묶음의 일부다.
  - 예: `'마을 종로03 (서울역버스환승센터(소월로) > 이화장)'` → `{ from: '서울역버스환승센터(소월로)', to: '이화장' }`

### 1-3. `subwayLine(name: string | null | undefined): string | null`

노선 이름에서 짧은 표시를 고른다.

- 숫자 호선이면 숫자만 준다.
  - `'1호선'` → `'1'`, `'부산 1호선'` → `'1'`, `'Line 2'` → `'2'`, `'Busan Line 1'` → `'1'`, `'수도권 9호선'` → `'9'`
- 이름 있는 노선은 아래 표의 짧은 이름을 준다. 한국어와 영어 이름을 모두 알아본다.

| 노선 | 짧은 이름 | 알아보는 글자(포함) |
| --- | --- | --- |
| 경의중앙선 | `Gyeongui` | `경의`, `Gyeongui` |
| 공항철도 | `AREX` | `공항`, `AREX`, `Airport` |
| 신분당선 | `Shinbundang` | `신분당`, `Shinbundang` |
| 수인분당선 | `Suin-Bundang` | `수인`, `분당`, `Bundang` (`신분당`이 먼저) |
| 동해선 | `Donghae` | `동해`, `Donghae` |
| 부산김해경전철 | `BGL` | `김해`, `Gimhae` |

- 그 밖의 이름은 받은 그대로(앞뒤 공백 뗌) 준다. 빈 값은 `null` 이다.

### 1-3-1. `badgeInk(color: string): string` (검증 뒤 추가)

배지 글자색이다. `#RRGGBB` 의 밝기(0.299R+0.587G+0.114B, 0~1)가 0.66 보다 크면 `'#1c1c1e'`, 아니면 `'#fff'` 다. 그 밖의 형식이면 `'#fff'` 다.

- 수인분당 `#FABE00` → 어두운 글자, 수도권 1호선 `#0052A4` → 흰 글자

### 1-4. `legStyle(p): { color: string; badge: string | null; kind: string | null }`

`p` 는 `{ mode: LegMode; vehicleType?: string | null; vehicleName?: string | null; at?: { latitude: number; longitude: number } | null }` 이다. `at` 은 구간 첫 좌표다.

**도보**: `{ color: WALK_COLOR, badge: null, kind: null }`.

**지하철**:

- `badge = subwayLine(vehicleName)`.
- `kind` 는 `tr('지하철')` 이다(영어 `Subway`).
- 색은 지역을 보고 고른다.
  - **부산권**: `at` 이 위도 34.8~35.6, 경도 128.7~129.4 안이다.

    | 1 | 2 | 3 | 4 | 동해선 | 부산김해경전철 |
    | --- | --- | --- | --- | --- | --- |
    | `#F06A00` | `#81BF48` | `#BB8C00` | `#217DCB` | `#0054A6` | `#8652A1` |

  - **부산권이 아니면**(at 이 없을 때도) 수도권 색이다.

    | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 |
    | --- | --- | --- | --- | --- | --- | --- | --- | --- |
    | `#0052A4` | `#00A84D` | `#EF7C1C` | `#00A5DE` | `#996CAC` | `#CD7C2F` | `#747F00` | `#E6186C` | `#BDB092` |

    | 경의중앙 | 공항철도 | 신분당 | 수인분당 |
    | --- | --- | --- | --- |
    | `#77C4A3` | `#0090D2` | `#D4003B` | `#FABE00` |

  - 표에 없으면 `SUBWAY_FALLBACK = '#3A6FD8'` 이다.

**버스**: `badge = vehicleName`(공백 뗌, 비면 `null`). `vehicleType` 에 아래 글자가 들어 있으면 그 색과 종류다. 위에서부터 먼저 맞는 것을 쓴다.

| 글자 | 색 | `kind` (한국어 열쇠 → 영어) |
| --- | --- | --- |
| `간선` | `#3D5BAB` | `간선버스` → `Blue bus (trunk)` |
| `지선` | `#5BB025` | `지선버스` → `Green bus (branch)` |
| `마을` | `#5BB025` | `마을버스` → `Village bus` |
| `광역` | `#E6312E` | `광역버스` → `Red bus (express)` |
| `직행` 또는 `급행` | `#E6312E` | `급행버스` → `Express bus` |
| `순환` | `#F2B70A` | `순환버스` → `Circular bus` |
| `공항` | `#00A0E9` | `공항버스` → `Airport bus` |
| 그 밖 | `#3D7DCA` | `버스` → `Bus` |

갈래(`legModeFrom`)는 `vehicleType` 에 `급행`·`공항` 이 들어 있어도 버스로 본다(검증 뒤 추가 — 전에는 회색 「그 밖의 탈것」이었다).

**그 밖의 탈것(`transit`)**: `{ color: '#6B7280', badge: vehicleName(공백 뗌, 비면 null), kind: null }`.

## 2. 화면 구간(`Leg`)에 더하는 칸 (`navModels.ts`)

`legFromContract` 는 지금 칸에 더해 아래 칸을 채운다.

- `color`, `badge`, `kind`: `legStyle` 의 결과다. `at` 은 `path.coordinates[0]` 이며 `[경도, 위도]` 순서다.
- `from`, `to`:
  - 탈것은 `boardAlight(guidance)` 의 값이다. 없으면 둘 다 `null`.
  - 도보는 둘 다 `null`.
- `stops`: `stopCount ?? null`.

`makeLeg` 의 새 칸 기본값:

- `color` 는 도보면 `WALK_COLOR`, 아니면 `SUBWAY_FALLBACK`.
- 나머지는 `null`.

지금 칸(`title`·`detail`)과 지금 시험은 그대로 둔다.

`navChips` 가 이어진 도보를 합친 칩:

- `color = WALK_COLOR`
- `badge`·`kind`·`from`·`to`·`stops` 는 `null`

## 3. 내 위치 찾는 중 (`features/route/map/locateStatus.ts`)

`LocateStatus` 에 `'seeking'` 을 더한다. `locateStatus` 의 규칙:

| 상태 | 결과 |
| --- | --- |
| 내 위치가 꺼져 있다 | `'idle'` |
| 권한이 거부됐다 | `'denied'` |
| 위치를 잡았다 | `'idle'` |
| 못 잡고 `LOCATE_WAIT_MS`(8 초) 이상 지났다 | `'waiting'` |
| 못 잡고 8 초 전이다 | `'seeking'` (전에는 `'idle'`이었다) |

화면:

- 「내 위치」 단추는 `seeking`·`waiting` 동안 둘레에 시계 방향으로 도는 호를 그린다(`aria-busy`). 화면을 가리지 않는다.
- 위쪽 알림(「현재 위치를 찾는 중입니다」)은 지금처럼 `waiting`·`denied` 에서만 뜬다.
- 움직임 줄이기 설정(`prefers-reduced-motion`)이면 호를 돌리지 않고 천천히 깜박인다.

## 4. 안내 띠의 단계 목록 (`TripBanner.tsx`)

가로로 미는 칩 줄(`LegChips`) 대신 세로 단계 목록(`LegSteps`)을 쓴다. 단계는 `navChips(result)` 의 순서이고, 맨 끝에 「도착」 줄이 하나 더 붙는다.

```
 (🚶)  10분 걷기 · 651 m
  ┊    Oncheonjang 까지
 (1)   [1] 지하철 · Line 1
  ┃    탈 곳 Oncheonjang
  ┃    내릴 곳 Jwacheon · 9 정거장 · 21분
 (🚶)  6분 걷기 · 420 m
  ┊
 (⚑)  도착 · Maechukji Village
```

- 왼쪽 원과 세로줄은 그 구간의 `color` 다. 도보 세로줄은 점선, 탈것 세로줄은 실선이다. 지도 선과 같은 색이다.
- 탈것 줄의 배지(`badge`)는 색 바탕에 `badgeInk` 글자다. 그 옆에 `kind` 를 적는다. 배지가 없으면 `kind`(없으면 `title`)를 한 줄로 적는다. 배지는 넓이 140px 에서 말줄임한다.
- 「탈 곳」·「내릴 곳」은 `from`·`to` 가 있을 때만 적는다. 없으면 지금처럼 `detail` 을 한 줄 적는다.
- 걷기 줄의 「X 까지」는 다음 탈것의 `from` 이다. 다음 탈것이 없거나 `from` 이 없으면 적지 않는다.
- 계단이 있으면(`hasStairs`) 계단 아이콘을 붙인다.

## 5. 지도 선 (`mapOverlay.ts` `drawTrip`)

- **도보**: `WALK_COLOR` 둥근 점선이다. 굵기 8, 대시 `'0.1 13'`. 흰 바탕선은 깔지 않는다(네이버 점선은 굵기마다 점 간격이 달라 어긋난다).
- **탈것**: 흰 테두리(굵기 13)를 먼저 깔고, 그 위에 `leg.color` 실선(굵기 9)을 그린다.
- 목적지까지 마지막 몇십 m 회색 점선과 「내 자리 → 다음 곳」 미리보기는 그대로 둔다.

## 6. 하지 않는 것

- 단계를 눌러 지도를 그 구간으로 확대하는 것은 다음 차례다.
- 로그인 없이 길찾기는 하지 않는다. 서버가 가입자만 받는다(비용).
- 검색 탭의 「내 위치」 단추는 이번에 바꾸지 않는다. 날아가는 단추라 결과가 바로 보인다.
- 정류장 이름 로마자 변환은 하지 않는다. 영어 안내(`guidanceLang = en`)는 제공자가 이미 로마자로 준다.
