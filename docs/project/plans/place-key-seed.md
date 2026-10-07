# 촬영지 적재를 고유 키로 — 지우고 다시 넣기를 그만둔다

상태: 진행 (2026-10-07). 관련: 리뷰(place·POI 상세의 별점·리뷰) 설계, MZ2AZ-323(성지 시드).

## 1. 문제

`just seed`(`services/scene-api/seed/candidates.sql`)의 첫 동작이
`TRUNCATE place, content, person RESTART IDENTITY CASCADE` 다. CASCADE 라서 촬영지·작품을 가리키는
**사용자 데이터가 같이 지워진다** — 코스(`course_item`)·찜
(`saved_place`·`saved_content`)·마켓(`market_course_item`·`market_course_content`). 리뷰를 만들면 리뷰도 지워진다.

지우고 다시 넣는 이유는 CSV 에 촬영지·작품의 고유 번호가 없어서였다. 맨 앞 `id` 는 「작품 × 촬영지」 한
줄의 번호이고, 촬영지는 네이버 URL(없으면 이름+주소)로 묶어 왔다. DB 는 「CSV 의 이 줄이 DB 의 몇 번
촬영지인지」 알 수 없으니 통째로 갈았다. 로컬에서만 돌게 막아 두어 드러나지 않았지만, 서버에 사용자
데이터가 쌓이면 갱신 한 번에 날아간다.

## 2. 결정

**CSV 에 바뀌지 않는 키 두 칸을 둔다 — `content_key`(작품)·`place_key`(촬영지).** 적재는 그 키로
「있으면 갱신, 없으면 추가, CSV 에서 빠지면 숨김」 이다. 지우지 않는다.

키 규칙(수집 담당자에게 주는 규칙):

1. 같은 작품·같은 촬영지는 어느 줄에서나 같은 키를 쓴다.
2. 한 번 정한 키는 바꾸지 않는다 — 이름·주소·좌표가 바뀌어도.
3. 지운 키는 다시 쓰지 않는다. 새 것은 마지막 번호 다음.
4. 모양은 자유다(`C0001`·`P0001`). 비어 있으면 적재가 멈춘다.

### 2-1. 무엇을 갱신하고 무엇을 다시 넣나

사용자 데이터가 가리키는 것만 번호를 지킨다. 나머지는 적재가 소유하므로 지금처럼 다시 넣는다.

| 표 | 사용자 데이터가 가리키나 | 적재 방식 |
| --- | --- | --- |
| `place` · `content` | 예(장바구니·코스·찜·마켓) | **키로 UPSERT** — 번호(id) 유지. CSV 에서 빠지면 `hidden_at` |
| `place_i18n` · `place_alias` · `place_image` | 아니오 | CSV 에 있는 촬영지만 지우고 다시 |
| `content_i18n` · `content_alias` | 아니오 | CSV 에 있는 작품만 지우고 다시 |
| `place_content` · `place_content_i18n` | 아니오 | CSV 에 있는 촬영지의 것만 지우고 다시 |
| `person` · `person_i18n` · `content_cast` | 아니오 | 통째로 다시(지금과 같다) |

숨긴 촬영지·작품의 딸린 행(이름·사진·장면)은 그대로 둔다 — 상세 화면이 계속 그려져야 해서다.

### 2-2. 숨김의 뜻

`hidden_at` 이 찬 촬영지·작품은 **찾아서 들어가는 길에서만 빠진다** — 지도·목록·검색 제안·작품의 촬영지
목록·장소의 작품 목록·장소 수 세기. **상세(`/places/{id}`·`/contents/{id}`)와 사용자의 장바구니·코스·
찜·마켓에는 계속 나온다** — 저장해 둔 것이 사라지면 안 된다. 다시 CSV 에 나오면 `hidden_at` 이 비워진다.
POI 의 폐업 표시(`closed_at`, poi-i18n-image.md §8)와 같은 생각이다.

### 2-3. 처음 한 번 — 키가 없는 기존 행에 키를 붙인다

마이그레이션 직후 DB 의 촬영지·작품에는 키가 없다. 그대로 적재하면 기존 행은 「CSV 에 없음」 으로 숨겨지고
새 행이 생겨, 장바구니·코스가 숨긴 옛 행에 붙은 채 남는다. 그래서 적재는 **키가 빈 행을 옛 규칙으로 찾아
키를 붙인 뒤** UPSERT 한다 — 촬영지는 네이버 URL, 없으면 한국어 이름+주소(옛 묶음 규칙과 같다), 작품은
한국어 제목. 키가 이미 찬 행은 건드리지 않으므로 몇 번 돌려도 같다.

## 3. 바꾸는 것

| 곳 | 무엇 |
| --- | --- |
| `V20__place_content_key.sql` | `place.place_key`·`content.content_key`(UNIQUE), 둘 다 `hidden_at` |
| `seed/candidates.sql` | TRUNCATE 를 없애고 §2-1 대로. 키가 빈 줄·같은 키의 엇갈림은 멈춘다 |
| `seed/candidates.csv` | `content_key`(title 앞)·`place_key`(place_name 앞) 두 칸. 지금 98 행은 옛 묶음 규칙대로 채웠다 |
| `tools/scripts/seed.sh` | 「지우고 다시 채운다」 경고를 바꾼다 |
| `PlaceStore` · `ContentStore` · `SuggestionStore` · `FavoriteStore` | §2-2 의 숨김 거르기 |
| `contracts/openapi/scene-api-v1.yaml` | 설명만 — 숨긴 촬영지·작품은 목록·검색에 없고 상세는 나온다. 칸은 그대로 |

## 4. 하지 않는 것

- 인물에는 키를 두지 않는다. 사용자 데이터가 가리키지 않아 통째로 다시 넣어도 된다.
- 서버(EKS)에서의 적재 경로는 이 문서 밖이다. 이 변경으로 「서버에서 돌려도 사용자 데이터를 지우지
  않는다」 는 전제가 생긴다.
- 새 CSV(다른 담당자가 만드는 중)의 칸 구성은 아직 모른다. 받으면 `seed_staging` 칸 목록만 맞춘다 — 키
  로직은 칸 구성과 무관하다.
