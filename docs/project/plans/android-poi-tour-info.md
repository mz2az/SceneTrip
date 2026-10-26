# Android 관광공사 편의시설 안내 (MZ2AZ-470)

iOS [#307](ios-poi-tour-info.md)의 이식. 기준은 `scene-api-v1.yaml` 1.21.0과
[poi-source.md §6-1](poi-source.md#6-1-결정과-실측-2026-10-15-권호)이다. 생성 Kotlin 클라이언트의
`PoiDetail.info`·`PoiInfo`·`PoiInfoText`, `Photo.thumbnailUrl`·`license`를 소비하며 계약·서버·생성 코드는 바꾸지 않는다.

## iOS와의 대응

| iOS | Android |
| --- | --- |
| `RouteTab/PoiInfoRules.swift` | `routetab/PoiInfoRules.kt` — 줄 6개(개요·이용 시간·쉬는 날·주차·대표 메뉴·입장료)와 필드별 언어 태그, 홈페이지는 `WebAddress`(HTTP(S))만 |
| `RouteTab/PoiInfoSection.swift` | `routetab/PoiInfoSection.kt` — 220dp 안에서 스크롤, 서버 줄바꿈 보존, 「홈페이지」 44dp 이상 |
| `RoutePlaceCard.details` + `Card.info` | `RoutePlaceCard`의 `RoutePlaceCardData.info` (편의시설 분기에서만 채움), 전화 줄 아래 |
| `PhotoGalleryRules.imageUrl/credit/credits/preservesFrame` | `ReviewRules.imageUrl/credit/credits/isProtected/preservesPhotoFrame` |
| `PhotoHero`·`CardPhotoStrip`·`PhotoViewer`의 Type3 처리 | `GalleryStrip`·`GalleryViewer` — Type3가 든 사진첩은 모서리를 깎지 않고 방문자 표식·쪽 번호·닫기·출처를 사진 밖으로 뺀다 |
| 문자열 `tr()` | 번역은 이미 `Translations.kt`에 있다(`just android-translations`). 언어를 인자로 받는 `tr(korean, lang)`만 더했다 |

## 규칙

- `info`와 각 필드의 누락·null·공백은 그 줄(영역)을 그리지 않는다. 영업 중 여부는 계산하지 않는다.
- 필드 `lang`이 앱 언어와 다르면 작은 언어 태그(한국어·영어·일본어·중국어 번체)를 붙인다.
- 지도 카드(`RoutePlaceCard`)·사진 격자·리뷰 사진 줄은 `thumbnailUrl`(웹 주소가 아니거나 없으면 `url`), 상세 대표와 확대는 `url`.
- `license`가 있으면 앱 언어의 「출처: 한국관광공사」 / “Source: Korea Tourism Organization”을 사진 밖에 적고 추가 `credit`을 이어 붙인다.
- `kogl_type3`는 aspect-fit(`ReviewRules.preservesPhotoFrame`)이며 자르기·필터·문구 덮기가 없다.

## 시험

`tests/PoiInfoTests.kt` — 규칙 순수 함수와 생성 클라이언트의 JSON 디코딩(`Serializer.moshi`). 앱 언어는 전역이라
규칙에 `Lang`을 인자로 넘겨 확인한다. 실기기·에뮬레이터 화면 확인(KO/EN, 큰 글자, 긴 개요 스크롤, 홈페이지 열기,
세로 Type3 사진)은 인계 후 할 일이다.
