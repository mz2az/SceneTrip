# 응답 언어 폴백 — 요청 언어 → en → ko (MZ2AZ-344)

- **티켓**: 스토리 [MZ2AZ-344](https://mz2az.atlassian.net/browse/MZ2AZ-344) — 하위 [MZ2AZ-347](https://mz2az.atlassian.net/browse/MZ2AZ-347) 서버 · [MZ2AZ-345](https://mz2az.atlassian.net/browse/MZ2AZ-345) iOS · [MZ2AZ-346](https://mz2az.atlassian.net/browse/MZ2AZ-346) Android. 앱 다국어 [MZ2AZ-343](https://mz2az.atlassian.net/browse/MZ2AZ-343). 에픽 MZ2AZ-109 "데이터·AI"
- **작성일**: 2026-10-04
- **상태**: 계약 #120 (`Lang.default` → `en`). 서버 폴백 사슬은 MZ2AZ-347
- **계약**: `contracts/openapi/scene-api-v1.yaml` — 상단 「언어」, `AcceptLanguage`, `Lang`

---

## 1. 결정

| 항목 | 값 | 이유 |
| --- | --- | --- |
| 응답 언어 | 앱이 보낸 `Accept-Language` | 앱 UI 언어와 본문 언어가 같아야 한다. 폰 언어가 아니라 앱 언어 |
| 폴백 | 헤더 없음 · 미지원 값 · 번역 행 없음 → **`en`, 그다음 `ko`** | 외국인 대상 앱이다. 한국어를 못 읽는 사용자에게 영어가 낫다 |
| 편의시설 | `/pois`, `/pois/{poiId}`, 카드는 **언제나 `ko`** | 원본(공공데이터·네이버)이 한국어뿐이다 |
| 길찾기 안내문 | 그대로 — `ko` 면 `ko`, 그 외 `en` | 카카오가 두 언어만 준다 ([ADR 0010](../../architecture/adr/0010-server-does-not-translate-guidance.md)) |
| `ja` · `zh-Hant` | 계약 enum 에 있고 데이터는 없다 | 나중. 지금 보내면 `en`, 그다음 `ko` |

이전 결정([scene-api-search-map.md](./scene-api-search-map.md) §결정의 "응답 언어")은
"없으면 `ko`" 였다. 이 문서가 그것을 대신한다.

## 2. 서버가 언어를 정하는 세 곳

```
요청 ──> ① 헤더 해석 ──> ② 번역 행 고르기(SQL) ──> ③ Content-Language
```

1. **헤더 해석** — 헤더가 없으면 생성된 컨트롤러가 계약 기본값 `en` 을 넣는다.
   값이 있으면 `web/LanguageConfiguration` 의 변환기가 첫 태그·지역 제거로 읽고, 모르면 `EN`.
2. **번역 행 고르기** — Store 7곳(`Cart`·`Content`·`Course`·`Favorite`·`Market`·`Place`·`Suggestion`)이
   같은 꼴을 쓴다.

   ```sql
   WHERE x.lang IN (:lang, 'en', 'ko')
   ORDER BY x.id, (x.lang = :lang) DESC, (x.lang = 'en') DESC
   ```

   검색어 매칭(`SuggestionStore`)도 요청 언어·`en`·`ko`·`NULL`(라틴 별칭) 표기를 함께 본다.
   일본어 사용자가 `Goblin` 으로 찾을 수 있다.
3. **`Content-Language`** — 각 행이 실제로 나온 언어(`shown_lang`)를 들고 온다.
   `web/Responses.used` 가 정한다: 하나라도 요청 언어면 그것 → 아니고 `en` 이 있으면 `en` → 아니면 `ko`.
   빈 목록은 폴백한 것이 없으므로 요청 언어. 상세는 그 한 행의 언어를 그대로.

헤더는 값 하나라, 한 응답 안에서 작품 제목은 영어·장소 이름은 한국어처럼 섞일 수 있다.

## 3. 하지 않는 것

- 번역 데이터 채우기 — 시드 CSV 영어 칸(`title_en` 11/98, `place_name_en`·설명 0). 다음 과제
- `trip-guide` 에이전트의 CLI 기본 언어(`ko`). 서버가 부를 때는 언제나 언어를 넘긴다
- 편의시설 다국어

## 4. 검증

- 단위: 변환기(`fr`·빈 값 → `en`), `Responses.used` 세 갈래, 상세의 `Content-Language`
- 통합(DB): ko·en 이 있는 작품을 `ja` 로 → 영어·`en`, ko 만 있는 작품 → 한국어·`ko`
- 배포 뒤 dev 에 `ja` · `fr` · 헤더 없음으로 실제 요청
