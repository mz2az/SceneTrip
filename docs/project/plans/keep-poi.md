# 편의시설(식당·쇼핑·명소·숙소)도 내 지도에 담기 — 계약 1.31.0 · 서버 · 웹

- **상태**: 구현 중(2026-11-14 밤). 서버는 main 에 넣어 DEV 서버에만 배포, 웹은 dev 브랜치 → dev.scenetrip.io. 사용자가 확인한 뒤 운영.
- **티켓**: [MZ2AZ-571](https://mz2az.atlassian.net/browse/MZ2AZ-571).
- **근거**: 2026-11-14 김상규 멘토 멘토링 — 「담아 놓는 게 우리가 가진 음식점 같은 것도 다」, 「다른 것도 더 추가해 볼까요? 에서 음식점이라든가 다른 작품이라든가」. 사용자: 「이게 메인」.
  [담기 중심 흐름](web-keep-flow.md)은 촬영지만 담는다. 장바구니 API 가 `placeId` 만 받기 때문이다. 코스는 이미 `poiId` 를 받는다(1.8.0).
- **여러 모듈**: `contracts/` → `services/scene-api` → `apps/scenetrip-web`. iOS·Android·agents 는 건드리지 않는다.

## 1. 계약 1.31.0 (더하기만 — 옛 앱이 깨지지 않게)

옛 iOS 앱(1.0.0)은 `CartItem.placeId` 가 반드시 있다고 읽는다. 그래서 편의시설을 `items` 에 섞지 않고 **따로 준다**.

### 1-1. `GET /cart` — `Cart` 에 두 칸을 더한다

```yaml
Cart:
  required: [items, totalCount]   # pois·poiCount 는 필수로 두지 않는다(아래)
  properties:
    items: CartItem[]        # 그대로 — 촬영지만
    totalCount: integer      # 그대로 — 촬영지 수
    pois: CartPoi[]          # 담은 편의시설, 담은 순서(오래된 것부터). 없으면 빈 배열
    poiCount: integer        # pois 길이
```

`pois`·`poiCount` 는 **필수 칸이 아니다**. 서버는 언제나 채워 보내지만, 계약에서 필수로 두면 (1) 이 계약으로 만든 앱 클라이언트가 옛 서버의 응답을 읽지 못하고 (2) iOS·Android 코드가 `Cart` 를 직접 만드는 자리(시험 포함)가 모두 깨진다 — 2026-11-14 `just check` 에서 iOS 시험 네 곳이 실제로 깨졌다. 응답에 칸을 더할 때는 필수로 두지 않는다.

### 1-2. `CartPoi`

`PoiSummary` 의 모양을 그대로 물려받고(`allOf`) 두 칸을 더한다. `id` 가 편의시설 id 다.

| 칸 | 뜻 |
| --- | --- |
| (PoiSummary 의 모든 칸) | `id`·`name`·`displayName`·`nameRoman`·`category`·`categoryLabel`·`categoryGroup`·`address`·`displayAddress`·`latitude`·`longitude`·`placeId`. 언어 규칙도 `GET /pois` 와 같다. `distanceMeters`·`spreadRank` 는 없다 |
| `addedAt` (필수, date-time) | 담은 때 |
| `closed` (boolean) | 그 편의시설이 **폐업 표시**됐다(`poi.closed_at`). 담아 둔 것은 남기고 알리기만 한다 — 촬영지의 `delisted` 와 같은 생각. 폐업이 아니면 `false` |

### 1-3. `POST /cart/pois` — 편의시설 담기

- 요청 `CartPoiCreate`: `{ poiId: int64 (필수) }`. 머리말은 `POST /cart/items` 와 같다(`Accept-Language`, `X-Install-Id`).
- `201` — 본문 `CartPoi`.
  - **촬영지와 같은 곳인 편의시설**(`PoiSummary.placeId` 가 있다)이면 서버가 **촬영지로** 담는다(코스의 `poiId` 규칙과 같다). 응답은 `201` 이고 본문의 `placeId` 가 채워져 있다. 그 항목은 다음 `GET /cart` 에서 `pois` 가 아니라 `items` 에 나온다. 그 촬영지가 이미 담겨 있었으면 `409 DUPLICATE_CART_ITEM`.
- `400` — 본문이 틀렸다(`poiId` 없음·숫자 아님).
- `404 POI_NOT_FOUND` — 없는 편의시설이거나 폐업한 편의시설(새로 담을 수 없다).
- `409 DUPLICATE_CART_ITEM` — 이미 담았다.
- `401`·`500` 은 다른 장바구니 경로와 같다.

### 1-4. `DELETE /cart/pois/{poiId}` — 빼기

- `204` 지웠다. `404` 담겨 있지 않았다. `400`·`401`·`500` 은 `DELETE /cart/items/{placeId}` 와 같다.
- 폐업한 편의시설도 뺄 수 있다.

### 1-5. `GET /pois` — `categories` 로 세부 종류 거르기

- 새 쿼리 `categories`: 쉼표로 나눈 **세부 종류의 한국어 원본**(`PoiSummary.category` 값 — 예 `쇼핑`, `관광지,문화시설`). 있으면 그 종류만 돌려준다. `categoryGroup` 과 함께 쓰면 둘 다 맞는 것만. 최대 10개, 빈 조각은 무시, 하나도 남지 않으면 `400`.
- 왜: 쇼핑은 갈래(`categoryGroup`)가 아니라 명소 갈래(`sight`) 안의 세부 종류 `쇼핑` 이다(관광공사 자료). 화면에서 「쇼핑」 칩을 내려면 서버가 걸러 줘야 `limit` 안에 쇼핑이 다 찬다.
- `total` 도 거른 뒤의 수다. `sort=spread` 의 칸 순위는 거른 것 안에서 매긴다.

### 1-5b. `PoiSummary.imageUrl` 과 `GET /pois` 의 `hasImage` ([랜딩 계획](web-ad-landing.md) §5)

- `PoiSummary.imageUrl`(nullable): 우리가 가진 사진(`poi_image`)의 첫 장(정렬 순서의 첫 줄 — 상세가 주는 사진의 첫 장과 같다). 없으면 null. `GET /pois`·`GET /pois/{poiId}`·`CartPoi`(`GET /cart` 의 `pois`, `POST /cart/pois` 응답)에 모두 온다.
- `hasImage=true`: 사진이 있는 것만. 목록 SQL 의 모든 갈래와 개수 SQL 에 건다(`categories` 와 같은 자리). `false`·없음은 거르지 않는다.

### 1-6. 그대로인 것

- `POST /cart/items`·`DELETE /cart/items/{placeId}`·`CartItem` 은 바꾸지 않는다.
- 코스에 넣기는 이미 있는 `CourseItemInput.poiId` 를 쓴다.

## 2. 서버 (`services/scene-api`)

- **표** `saved_poi` (Flyway 다음 번호): `user_id UUID` → `app_user(id)` (`saved_place` 와 같은 주체·같은 삭제 규칙), `poi_id BIGINT` → `poi(id) ON DELETE CASCADE`, `created_at TIMESTAMPTZ DEFAULT now()`, 기본키 `(user_id, poi_id)`, 조회용 인덱스 `(user_id, created_at)`.
- **담기**: 중복은 기본키 위반을 잡아 409 로(「조회 뒤 넣기」 로 하지 않는다 — `CartStore.add` 와 같은 이유).
  - 폐업(`closed_at` 있음)·없음 → 404 `POI_NOT_FOUND`.
  - 촬영지와 이어진 편의시설(`place_poi_link`, 숨긴 촬영지의 연결은 쓰지 않는다 — `PoiStore` 가 `placeId` 를 정하는 규칙 그대로)이면 `saved_place` 에 넣는다. 그 촬영지가 목록에서 빠졌으면(숨김) 연결이 없는 것이므로 편의시설로 담긴다.
- **목록**: `GET /cart` 가 `pois` 를 함께 준다. 이름·주소·분류의 언어는 `PoiStore` 가 `GET /pois` 에 쓰는 것과 **같은 코드**를 쓴다(두 벌로 쓰지 않는다). 폐업한 것도 거르지 않고 `closed: true`.
  - `Content-Language` 는 지금 규칙 그대로(촬영지 기준)다.
- **계정 잇기·지우기**: 비회원이 로그인하면 `saved_place` 를 옮기는 곳(`AccountLinkStore`)에서 `saved_poi` 도 같은 규칙으로 옮긴다(이미 있는 것은 건너뛴다). 비회원 데이터 지우기·탈퇴는 `app_user` 삭제를 따라간다 — `saved_place` 가 하는 방식을 확인해 똑같이.
- **`categories`**: `PoiStore` 의 목록 질의에 조건 하나.
- **분석 뷰**: `analytics` 스키마에 담은 편의시설 수를 보이는 뷰는 이번에 넣지 않는다(다음).
- 시험: `@WebMvcTest`(상태 코드·본문), 통합(실제 PostgreSQL — 담기·중복·폐업·촬영지 연결·계정 잇기·`categories`), 그리고 **Flyway 를 켠 채 스프링 컨텍스트를 실제 DB 로 띄워 `GET /cart` 가 `pois` 를 주는지** 한 건(CLAUDE.md §3 「스프링 시작 + DB」 틈).

## 3. 웹 (`apps/scenetrip-web`)

### 3-1. 장바구니 스토어 (`state/cart.ts`)

```ts
export type CartPoi = components['schemas']['CartPoi']
interface CartState { …지금 그대로; pois: readonly CartPoi[]; poiIds: ReadonlySet<number> }
cartActions.addPoi(poi: { id: number; placeId?: number | null }): Promise<'saved' | 'already' | 'failed'>
cartActions.removePoi(poiId: number): Promise<void>
```

- `refresh()` 가 `pois`·`poiIds` 도 채운다. 서버가 `pois` 를 주지 않으면(옛 서버) 빈 것으로 본다.
- `addPoi`: `placeId` 가 있으면 **촬영지 담기**(`add(placeId)`)로 보낸다. 없으면 `POST /cart/pois`.
  - 201 → `'saved'`, `poiIds` 에 즉시 넣고 `refresh()`. 응답의 `placeId` 가 채워져 있으면(서버가 촬영지로 담았다) `placeIds` 에 넣는다.
  - 409 `DUPLICATE_CART_ITEM` → `'already'`. 그 밖 → `'failed'`.
  - 알림: 담김 `say('내 지도에 담았어요', 'Saved to your map')`, 이미 `say('이미 담은 곳이에요', 'Already on your map')`, 실패 `say('담지 못했어요. 잠시 후 다시 시도해 주세요', "Couldn't save. Try again in a moment.")`.
  - 분석: 담기면 `webEvent.savePoi(poiId, categoryGroup)`.
- `removePoi`: 204 → 목록에서 빼고 알림 `say('내 지도에서 뺐어요', 'Removed from your map')`. 실패 → `say('빼지 못했어요. 잠시 후 다시 시도해 주세요', "Couldn't remove. Try again in a moment.")`.

### 3-2. 규칙 (순수 함수 — `features/keep/keepPoiRules.ts`)

```ts
export type NearbyKind = 'food' | 'shopping' | 'sight' | 'stay'
export const NEARBY_KINDS: readonly NearbyKind[]   // 이 순서: food, shopping, sight, stay
export function nearbyQuery(kind: NearbyKind): { categoryGroup: 'food' | 'sight' | 'stay'; categories?: string }
export function nearbyBbox(points: readonly { latitude: number; longitude: number }[], padMeters?: number): string | null
export function myMapCount(placeCount: number, poiCount: number): number
export function parsePoisParam(search: string): number[]
export function keepUrl(origin: string, contentIds: readonly number[], poiIds?: readonly number[]): string
```

- `nearbyQuery`: `food` → `{ categoryGroup: 'food' }`, `shopping` → `{ categoryGroup: 'sight', categories: '쇼핑' }`, `sight` → `{ categoryGroup: 'sight', categories: '관광지,문화시설' }`, `stay` → `{ categoryGroup: 'stay' }`.
- `nearbyBbox`: 좌표들의 최소·최대를 `padMeters`(기본 600)만큼 넓힌 `minLng,minLat,maxLng,maxLat`(소수 5자리). 위도 1도 = 111,320m, 경도 1도 = 111,320m × cos(가운데 위도). 좌표가 없으면 `null`.
- `myMapCount`: 둘의 합.
- `parsePoisParam('?pois=7,9')` → `[7, 9]`. `parseKeepParam` 과 같은 규칙(양의 정수·중복 제거 뒤)이되 **최대 30개**.
- `keepUrl`: 지금 것에 `pois` 를 더한다. `keepUrl(o, [32], [7, 9])` → `o + '/my-map?keep=32&pois=7,9'`. 작품이 없고 편의시설만 있으면 `o + '/my-map?pois=7,9'`. 둘 다 없으면 `o + '/my-map'`. 편의시설은 30개까지. (기존 두 인자 호출의 결과는 그대로다.)

### 3-3. 내 지도 (`/my-map`) — 주변에서 더 담기

- 지도 위에 칩 줄: 「식당·카페 / 쇼핑 / 명소 / 숙소」(`say('식당·카페','Food & cafés')`, `say('쇼핑','Shopping')`, `say('명소','Sights')`, `say('숙소','Stays')`). 하나만 켜진다. 다시 누르면 꺼진다.
- 칩을 켜면 **담은 곳들 둘레**(`nearbyBbox`)의 그 종류 편의시설을 `GET /pois`(`sort=spread`, 지금 지도 배율의 `spreadZoom`, `limit` 60)로 받아 지도에 점으로 찍는다. 지도를 옮기면 보이는 영역 기준으로 다시 받는다(코스 편집 화면의 주변 편의시설과 같은 부품·규칙 — `features/route/poi/*`, `useAmbientPois`).
- 점을 누르면 아래에서 정보 카드(코스 편집의 `PlaceCard` 와 같은 카드)가 뜨고, 주 단추가 `say('내 지도에 담기', 'Save to my map')` / 담겨 있으면 `say('담음 · 빼기', 'Saved · Remove')`.
- 담은 편의시설은 **다른 색 핀**으로 지도에 남고(칩을 꺼도), 목록 맨 아래에 묶음 `say('주변에서 담은 곳', 'Saved nearby')` 로 나온다 — 줄마다 분류 아이콘·이름(`displayName ?? name`)·분류 이름·주소, 누르면 그 카드. 폐업한 것은 흐리게 + `say('문을 닫았어요', 'Closed')`.
- 머리줄과 아래 단추의 N 은 촬영지 + 편의시설(`myMapCount`).
- 담은 것이 편의시설뿐이어도 내 지도는 빈 화면이 아니다.
- 상태: 받는 중(칩에 돌아가는 표시), 그 둘레에 없음(`say('이 둘레에는 없어요. 지도를 옮겨 보세요', 'None around here. Try moving the map.')`), 실패(`say('불러오지 못했어요', "Couldn't load")` + 다시 누르면 다시 받는다).
- 분석: 칩 켬 `my_map_nearby(kind)`, 카드 엶 `view_poi`(기존), 담기 `save_poi`.

### 3-4. 담은 뒤 시트 ① — 주변도 담기

- 「다른 작품도 담아 볼까요?」 포스터 줄 아래에 한 줄을 더한다: `say('주변 식당·쇼핑도 담아 볼까요?', 'Add places to eat and shop nearby?')` 와 칩 넷(§3-3 과 같은 이름).
- 칩을 누르면 시트를 닫고 내 지도를 그 칩이 켜진 채로 연다(`keep_nearby(kind)`).

### 3-5. 계획으로 — 편의시설도 같이

- 「이 N곳으로 계획 짜기」 로 연 「담은 곳으로」 마법사는 담은 편의시설도 고를 수 있는 줄로 보이고 전부 골라진 채 시작한다. 코스에는 `poiId` 로 들어간다(촬영지와 같은 곳인 편의시설은 애초에 촬영지로 담겼다).
- 폐업한 편의시설은 고를 수 없다.

### 3-6. 브라우저로 넘기기

- 넘기는 주소에 담은 편의시설을 싣는다(`keepUrl(…, poiIds)`).
- `?pois=` 로 들어오면 `KeepArrival` 이 하나씩 `addPoi` 로 담는다(이미 담긴 것·없는 것은 건너뛴다). `keep_arrive` 의 `count` 는 그대로 작품 수, 새 매개변수 `poi_count` 에 담긴 편의시설 수.

### 3-7. 홈 카드

- 「내 지도」 카드의 수는 촬영지 + 편의시설. 담은 것이 편의시설뿐이어도 카드가 뜬다. 칩에 `say('주변', 'Nearby')` + 수가 붙는다.

## 4. 분석 이벤트 (웹)

| 이벤트 | 함수 | 매개변수 |
| --- | --- | --- |
| `save_poi` | `webEvent.savePoi(poiId, group)` | `poi_id`, `category_group` = `food`·`stay`·`sight`·`transit` |
| `my_map_nearby` | `webEvent.myMapNearby(kind)` | `kind` = `food`·`shopping`·`sight`·`stay` — 내 지도에서 칩을 켰다 |
| `keep_nearby` | `webEvent.keepNearby(kind)` | 같은 `kind` — 담은 뒤 시트에서 칩을 눌렀다 |
| `keep_arrive` (있음) | `webEvent.keepArrive(count, poiCount = 0)` | `count`, `poi_count` |

## 5. 순서와 배포

1. 계약 → `just gen`.
2. 서버: 시험(명세만 보고) → 구현 → `just test`·`just test-integration` → `just check` → PR(main) → CI → 머지 → **DEV 서버에만** 배포(`aws-deploy.yml`, environment=dev). 운영 서버는 사용자 확인 뒤.
3. 웹: 시험 → 구현 → 독립 검증(서버 쓰기는 가짜 응답으로) → `just check` → PR(dev) → CI → 머지 → dev.scenetrip.io 확인.
4. DEV 서버에는 시험 데이터를 쓰지 않는다. 배포 확인은 쓰지 않는 호출로만 한다: `GET /cart` 에 `pois: []`, `POST /cart/pois` 의 400·404, `DELETE /cart/pois/{없는 id}` 의 404, `GET /pois?categories=쇼핑`.

## 6. 하지 않는 것

- iOS·Android 반영, `analytics` 뷰, 편의시설 묶음 담기(한 번에 여러 곳)는 다음.
- 담을 수 있는 수의 상한은 두지 않는다.

## 7. 웹의 이음매 (시험과 구현이 같이 보는 약속)

- 주변 칩의 상태는 `features/keep/keepStore.ts` 의 `keepStore` 에 `nearby: NearbyKind | null`(처음 값 `null`)로 둔다. `keepActions.setNearby(kind | null)`. 내 지도가 이것을 읽고, 담은 뒤 시트의 칩은 `setNearby(kind)` → 시트 닫기 → `router.openMyMap()` 순서로 부른다. 내 지도 덮개가 닫히면 `null` 로 돌린다.
- 주변 편의시설은 `api.GET('/pois', { params: { query: { bbox, categoryGroup, categories?, sort: 'spread', spreadZoom, limit: 60 }, header: … } })` 로 받는다(`@/api/client` 의 `api`).
- 담기·빼기는 `cartActions.addPoi`·`cartActions.removePoi` 만 지난다.
- 내 지도의 주변 칩 줄은 `features/keep/NearbyChips.tsx` 의 표시 전용 부품이다.

  ```ts
  export function NearbyChips(props: { value: NearbyKind | null; loading?: boolean; onChange: (kind: NearbyKind | null) => void }): JSX.Element
  ```

  칩 넷을 `NEARBY_KINDS` 순서로 그리고, 켜진 칩은 `aria-pressed="true"`. 켜진 칩을 누르면 `onChange(null)`, 다른 칩을 누르면 `onChange(그 kind)`.
- 담은 편의시설 줄은 `features/keep/SavedPoiRow.tsx` 의 표시 전용 부품이다.

  ```ts
  export function SavedPoiRow(props: { poi: CartPoi; onOpen: () => void; onRemove: () => void }): JSX.Element
  ```

  제목은 `displayName ?? name`, 아래 줄에 `categoryLabel ?? category` 와 `displayAddress ?? address`. `closed` 면 `say('문을 닫았어요', 'Closed')` 를 보이고 줄을 눌러도 `onOpen` 을 부르지 않는다. 빼기 단추(`aria-label` = `say('빼기', 'Remove')`)는 언제나 `onRemove`.
- 홈 카드 `MyMapCard` 에 선택 prop `pois?: readonly CartPoi[]` 를 더한다. 수는 `items.length + pois.length`, 편의시설이 있으면 칩 `say('주변 N', 'Nearby N')` 가 작품 칩들 뒤에 붙는다(작품 칩 최대 3개 규칙은 그대로, 주변 칩은 그와 별개로 늘 보인다). 둘 다 비면 `null`.

## 8. 세부 (시험을 쓴 뒤 정한 것)

- `keepUrl` 은 `keepRules.ts` 의 것을 세 번째 인자로 넓히고, `keepPoiRules.ts` 가 그것을 다시 내보낸다(두 벌로 두지 않는다).
- `nearbyBbox` 의 숫자는 `toFixed(5)` 다.
- `nearbyBbox` 에 넣는 점은 담은 촬영지와 담은 편의시설의 좌표 전부다.
- `removePoi`: 404(이미 없음)는 실패가 아니다 — 목록에서 빼고 「뺐어요」 알림을 띄운다.
- `keep_arrive` 의 `poi_count` 는 `addPoi` 가 `'saved'` 를 돌려준 수다(`'already'`·`'failed'` 는 세지 않는다). 편의시설은 하나씩 차례로 담는다.
- `?pois=` 만 있어도 `?keep=` 과 똑같이 한다: 담은 뒤 주소를 `/my-map`(쿼리 없음)으로 바꾸고, 앱 안 브라우저가 아니고 무엇이든 하나라도 담겼으면 시트를 ② 에서 연다.
- 내 지도 덮개가 닫히면(언마운트 포함) `keepStore.nearby` 를 `null` 로 돌린다. `nearby` 가 미리 켜진 채 열릴 때는 `my_map_nearby` 를 보내지 않는다(시트의 `keep_nearby` 가 이미 셌다) — 내 지도에서 사용자가 칩을 눌러 켤 때만 보낸다.
- 주변 받기가 실패하면 실패 문구 옆에 `say('다시 시도', 'Try again')` 단추를 둔다.
- 홈 카드의 주변 칩 글자는 한 덩어리다(`주변 2`, `Nearby 2`).
- 「담은 곳으로」 마법사(`ProtoSavedPlan`)는 담은 편의시설을 촬영지 줄 아래에 같은 모양의 줄로 보인다(이름 `displayName ?? name`, 분류 이름). 폐업한 것은 줄을 흐리게 하고 고를 수 없다. 코스 저장 때 편의시설은 `poiId` 로 보낸다.

## 9. 검증 뒤 바꾼 것 (2026-11-14 밤, 웹 독립 검증 지적 — 앞 절과 다르면 이 절이 이긴다)

1. **알림이 보여야 한다.** 장바구니 알림(`cartStore.toast`)을 그리는 곳이 작품검색 탭에만 있어 내 지도 덮개 아래에서 보이지 않았다. 알림을 **앱 맨 위 한 곳**(`features/keep/CartToast.tsx`, `RootTabs` 에 붙인다)에서 그리고, 작품검색 탭의 것은 없앤다. 보이는 시간 2.4초, 새 알림이 오면 바꿔 끼운다, 화면이 바뀌어도 남은 시간 동안 보인다. `role="status"`. 아래 고정 단추·탭바와 겹치지 않는 자리(화면 위쪽, 머리줄 바로 아래).
2. **이름은 읽을 수 있게.** 편의시설의 제목은 `displayName ?? name`, 그 아래(또는 옆) 작은 글자로 `nameRoman` — `displayName` 이 없고 화면 언어가 한국어가 아닐 때만. 담은 목록(`SavedPoiRow`), 마법사 줄, 「더 담기」 칸 모두. 정보 카드와 같은 규칙이다.
3. **계획의 날 나누기.** 마법사가 지역을 주소의 첫 낱말로 묶는 것을 그만두고, **좌표로** 가까운 곳끼리 묶는다(촬영지·편의시설을 구분하지 않는다). 날 수만큼 묶음을 만들고, 묶음 안 순서는 가까운 곳 잇기. 좌표 없는 항목은 마지막 날 끝에.
4. **담는 중에는 다시 보내지 않는다.** 같은 편의시설의 `addPoi`·`removePoi` 가 진행 중이면 두 번째 누름은 무시한다(카드 단추·칸 모두 눌리지 않는 모양).
5. **폐업한 곳은 셈에서 뺀다.** 내 지도·홈 카드·「이 N곳으로」 의 N 은 폐업한 편의시설과 목록에서 빠진 촬영지를 뺀 수다(목록·핀에는 흐리게 남는다).
6. **누르는 크기.** 주변 칩과 「다시 시도」 는 높이 44px 이상(칩의 보이는 캡슐은 그대로 두고 눌리는 영역만 넓혀도 된다 — 코스 편집 화면의 칩도 같이 넓어지는 것은 괜찮다).
7. **촬영지로 담긴 편의시설의 알림·이벤트.** `addPoi` 가 촬영지로 보낼 때도 알림은 §3-1 의 새 문구(`내 지도에 담았어요`)이고, 이벤트는 `save_place`(기존)만 — `save_poi` 는 보내지 않는다.
8. **넘어올 때 한꺼번에 받기.** `KeepArrival` 은 장바구니를 먼저 받은 뒤(이미 담긴 것은 건너뛰기 위해) 하나씩 담고, `refresh` 와 알림은 끝에 **한 번만**.
