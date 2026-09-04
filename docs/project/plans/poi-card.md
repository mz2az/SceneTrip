# 편의시설 카드 — 사진·영업시간·평점 (바깥 출처)

- **에픽**: [MZ2AZ-107](https://mz2az.atlassian.net/browse/MZ2AZ-107) "코스"
- **작성일**: 2026-09-05
- **상태**: 구현 완료(§8). 앱 교체와 데모 전 채우기가 남았다
- **결정**: [ADR 0011](../../architecture/adr/0011-naver-place-unofficial-for-demo.md) — 비공식, 데모 한정
- **실측**: 볼트 「2026-09-03 네이버 매칭 거리 실측」

---

## 1. 무엇을 만드는가

경로 탭에서 편의시설 핀을 누르면 뜨는 카드. 우리 자료([poi.md](./poi.md))에 없는
사진·영업시간·별점·리뷰 수를 네이버 장소에서 가져온다. 프로토타입 `apps/navi_proto` 의
`/api/place-card` 를 서버로 옮긴 것이다.

## 2. 프로토타입이 하던 것 — 두 층

```
A  매칭   「이 가게가 네이버에 있나」   공식 지역검색(API HUB). 24 시간 배치. id 를 못 준다
B  상세   id → 사진·평점·영업시간       비공식 GraphQL(검색) + summary(상세). 폰 브라우저 위장
```

A 는 안 옮긴다 — B 의 검색이 id·좌표·이름을 다 주어 A 가 할 일이 없다. 챗봇 순위(베이지안)도
안 옮긴다. 옮기는 것은 B 와, A·B 가 같이 쓰던 **판정 규칙 `match_ok`** 다.

## 3. 결정된 것

| 결정 | 근거 |
| --- | --- |
| 핀은 우리 표, 카드는 누를 때 그 한 곳만 | 30 곳을 미리 조사하면 10 초(프로토타입의 8 초 예산). 누른 곳 하나는 0.35 초 |
| 여럿 조회는 「있는 것만 즉시 + 없는 것은 뒤에서」 | 목록 화면(미래)이 30 개 사진을 기다릴 수 없다. `pending` + `retryAfterSeconds` |
| 결과는 표 `poi_naver`, 못 찾은 것도 저장 | 다시 묻지 않는다. 「못 받음」(타임아웃·차단)은 저장하지 않아 다음에 다시 묻는다 |
| 판정 규칙은 프로토타입 그대로, 좌표는 건물 좌표 | 건물 좌표로 재니 명소 중앙값 33 m — 계단이 그대로 맞고 입구 좌표 컬럼이 필요 없다 |
| 시군구 재검색 | 결과의 30% 가 같은 이름의 다른 동네 지점. 「이름 지역 시군구」로 한 번 더 → +6~11p |
| 일꾼 하나, 초당 3 건, 막히면 쉬고 세 번이면 내림 | 한 IP 에서 두드리는 것이라 스스로 속도를 죈다 |
| 못 찾아도 200 | 「없다」도 답. 404 는 POI 자체가 없을 때뿐. 여럿에서는 없는 id 도 그 자리에 `found:false` |
| 재확인 안 함 | 데모. `checked_at`·`rule_version` 만 두어 나중에 TTL 을 붙일 때 계약이 안 바뀐다 |

## 4. 흐름

```
앱  GET /pois/{id}/card                       앱  GET /pois/cards?ids=1,…,30
      │                                              │
PoisController → PoiCardService.card            PoiCardService.cards
      │  표에 있나 → 즉시                              │  있는 것 → 카드 / 없는 것 → pending + 줄
      │  없으면 PoiCardFetcher.fetch (지금)             │  retryAfterSeconds = 줄 × 최근 평균
      │                                              ▼
      │                                        PoiCardFiller (일꾼 1)  줄에서 꺼내 fetch → 저장
      ▼
PoiCardFetcher.fetch
  NaverPlaceClient.search(이름) → NaverMatcher.pick → (헛돌면 search(이름 시군구)) → detail → NaverCard
      │
PoiNaverStore.save (UPSERT, RETURNING)
```

## 5. 표 — `poi_naver` (V14)

`poi_id` 가 기본키, 행 하나 = 한 POI 를 찾아본 결과 전부. 상태 셋 — 행 없음(안 물어봄·못 받음),
`found=false`+`why`, `found=true`+상세. `CHECK (found = (naver_id IS NOT NULL))`. `rule_version`
현재 판만 조회. 자세한 것은 마이그레이션 주석.

## 6. 계약

`GET /pois/{poiId}/card` → `PoiCard`, `GET /pois/cards?ids=` → `PoiCardBatch`. 명세 본문에 앱 규칙
(pending 이 있으면 힌트만큼 자고 pending 만 다시, 최대 3 회)이 적혀 있다.

## 7. 앱에 생기는 일

`RouteGuide.card(for:)` 가 `127.0.0.1:8899/api/place-card` 대신 생성 클라이언트의 `getPoiCard` 를
부른다. `Card` 구조체의 칸은 `PoiCard` 와 이름만 다르다(`review_count` → `reviewCount`).
`tmap_name`·`limited` 는 없어졌다.

## 8. 구현 순서

| 순서 | 내용 | 상태 |
| --- | --- | --- |
| 1 | 계약 | ✅ |
| 2 | V14 `poi_naver` · `PoiNaverStore` | ✅ |
| 3 | `NaverMatcher` — 규칙 표 19 케이스 | ✅ |
| 4 | `NaverPlaceClient` — 가짜 서버 10 케이스 | ✅ |
| 5 | `PoiCardFetcher` · `PoiCardService` · 컨트롤러 | ✅ |
| 6 | `just poi-card-smoke` — 실제 네이버, 10 번 안쪽 | ✅ 2026-09-04 |
| 7 | `PoiCardFiller` — 줄·일꾼·힌트·휴식 | ✅ |
| 8 | 문서 · ADR 0011 | ✅ |
| 9 | 앱 교체 | 남음 |
| 10 | 데모 전 채우기 `just poi-card-warm <bbox>` | 남음 |
| 11 | 승길의 `naver_match.jsonl` 재활용 — 찾음+id 항목만 상세를 채운다 | 파일을 받아야 |

## 9. 실측 (2026-09-04, kind, 강남역 2 km)

```
단건 처음        530 ms   (검색 0.22 + 상세 0.10 + 시군구 재검색 때론 한 번 더)
단건 표에서        5 ms
여럿 처음         18 ms   (출처 안 부름)
2 초 뒤 다시      pending 4 → 0
다섯 곳 중        찾음 2 (포490 사진 3·리뷰 2,459 / 강남타로카페) · 없음 3 (후보 0 · 6 km · 20 km)
```

## 10. 열어 둔 것

| 항목 | 왜 지금 정하지 않는가 |
| --- | --- |
| 3 글자 명소(경복궁·창덕궁·남산)의 부분 일치 | 짧은 이름 가드가 명소 매칭률을 얼마나 깎는지 실측 뒤에 |
| 재확인 주기(TTL) | 데모엔 없음. `checked_at` 이 있어 한 줄로 붙는다 |
| 여럿 조회 뒤 앱이 다시 묻는 방식 | 지금 목록 화면이 없다. 명세에 규칙만 적어 뒀다 |
| 대체 출처 | ADR 0011 의 조건 셋 중 하나가 오면 |

## 11. 변경 이력

| 날짜 | 내용 |
| --- | --- |
| 2026-09-05 | 최초 작성. 구현 8 단계 완료 후 정리 |
