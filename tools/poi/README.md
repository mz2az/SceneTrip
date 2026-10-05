# tools/poi — 편의시설 적재 파일 준비

`just seed-poi` 가 넣을 POI 파일(JSON Lines)을 적재 전에 다듬는 도구. 계획과 실측은
[poi-i18n-image.md](../../docs/project/plans/poi-i18n-image.md) §6-3·§11.

| 파일 | 하는 일 |
| --- | --- |
| `en.py` | `just poi-en` — 줄마다 공식 영문 주소(`addr_en`·`addr_en_source`), 확실할 때만의 영어 이름(`name_en`·`name_en_source`), 로마자 읽기(`name_roman`)를 덧붙인다 |
| `names.py` | 로마자 표기법(소리 바뀜 포함)과 영어 이름 규칙 — 브랜드 사전 → 한식 800 → 흔한 낱말, 확실하지 않으면 없음(계획 §12) |
| `data/` | 이름 사전 셋 — 출처는 `data/README.md` |
| `addresses.py` | 한국어 주소 문장 쪼개기, 영문도로명주소DB 한 줄 → 공식 영문 주소 조립(순수 함수) |
| `juso_api.py` | 영문주소 검색 API — 받는 규칙과 캐시 |
| `tests/` | 단위 시험(`just test //tools/poi:unit_test`) — 진짜 API·파일은 쓰지 않는다 |

```bash
just poi-en ~/Downloads/SceneTrip_POI_20260907/out \
                 ~/Downloads/202608_영문주소DB_전체분.zip \
                 ~/Downloads/'소상공인시장진흥공단_상가(상권)정보_20260630.zip' \
                 ~/Downloads/SceneTrip_POI_20260907/out-en      # 괄호가 든 경로는 따옴표로
just seed-poi ~/Downloads/SceneTrip_POI_20260907/out-en/poi_*.jsonl
```

입력 셋은 모두 저장소 밖이다 — 영문도로명주소DB 는 business.juso.go.kr 에서 신청·본인인증 뒤 받고(월 단위),
상가정보는 공공데이터포털에서 받는다(분기). 남는 주소는 환경변수 `JUSO_ENG_API_KEY` 가 있을 때만 API 로
채우며, 결과는 결과 폴더의 `juso-api-cache.json` 에 남아 다시 돌릴 때 부르지 않는다. 키는 저장소에 두지 않는다.
