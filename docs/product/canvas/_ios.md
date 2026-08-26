# 아트보드가 지키는 값

앱에서 잰 것이다. 새 아트보드를 만들 때 여기서 베껴 쓴다 — 눈대중으로 정하면
캔버스에서 좋아 보이던 것이 앱에 옮겼을 때 어긋난다.

출처: `apps/scenetrip-android/.../ui/IOSTheme.kt` (iOS 스크린샷에서 실측한 값들)
와 `apps/scenetrip-ios/src/RouteTab/*.swift` 의 실제 수치.

## 색

| 이름 | 값 | 쓰는 곳 |
| --- | --- | --- |
| accent | `#0088FF` | 버튼·링크·선택된 것 |
| label | `#000000` | 본문 |
| secondaryLabel | `rgba(60,60,67,.60)` | 부제·설명 |
| tertiaryLabel | `rgba(60,60,67,.30)` | 흐린 것·거리 |
| separator | `rgba(60,60,67,.29)` | 구분선 |
| systemGray5 | `#E5E5EA` | 배지 바탕 |
| systemGray6 | `#F2F2F7` | 칩 바탕 |
| systemGroupedBackground | `#F2F2F7` | 화면 바탕 |
| pinLight → pinDeep | `#8FCCF7` → `#7A68ED` | 핀·번호 배지 그러데이션 |
| systemRed | `#FF383C` | 지우기·경고 |

## 글자 (iOS 텍스트 스타일 기본 크기)

| 이름 | 크기/굵기 |
| --- | --- |
| largeTitle | 34 / 700 |
| title3 | 20 / 600 |
| headline | 17 / 600 |
| body | 17 / 400 |
| subheadline | 15 / 400 (semibold 600) |
| footnote | 13 / 400 |
| caption | 12 / 400 |
| caption2 | 11 / 400 (heavy 900) |

글꼴: `-apple-system, "SF Pro Text", system-ui, sans-serif`

## 간격

- 화면 좌우 여백 **16**
- 라지타이틀·리스트 좌우 **14** (`IOS.gutter`)
- 캡슐 버튼 radius **100**
- 카드 radius **10~16**
- 번호 배지 **22×22**, 글자 caption2 heavy 흰색

## 규칙

- **가짜 상태바를 그리지 않는다.** 위 59px 는 비워 둔다 — 실기기에서 진짜
  상태바가 그 위에 그려진다.
- 아이콘은 **인라인 SVG**. 이모지·유니코드 기호(`↓` 등)를 아이콘 자리에 쓰지 않는다.
- 누르는 것은 **44px 이상**. (지금 앱에 이걸 어기는 자리가 있다 — 아래 참고)
- 형제 배치는 `display:flex` + `gap`. 마진으로 띄우지 않는다.
- 지도는 **네이버**다. 저작권 표기(`NAVER · OpenStreetMap`)를 지우지 않는다.

## 앱이 지금 어기고 있는 것 (고칠 후보)

| 자리 | 지금 | 소스 |
| --- | --- | --- |
| 일차 ＋/− 버튼 | **22pt** | `RouteEditorControls.swift` |
| 행 드래그 손잡이 | **14pt** | `RouteEditorParts.swift` |
| 「여기서 길 찾기」 줄 | ~15pt | 〃 |
| 「30분」 칩 | ~24pt | 〃 |

## 확정 사항 (어기면 안 된다)

8/11 회의 확정 — `00_기록/Sprint(권호작성)/(3주차)2026년 8월 11일 경로여정 목업 확정.md`

- **시작 시각은 없다.** 회의에서 가장 길게 다툰 끝에 뺐다. 시간축을 그리지 않는다.
- **계획 단계는 직선거리만.** 예상 소요 시간을 보여 주지 않는다.
- 날짜는 **선택**이다. 안 정해도 다음으로 넘어간다.
- 일차는 **＋/− 로** 늘리고 줄인다.
- 체류 시간 기본 **30분**.
- AI 가 짜 준다는 것이 드러나야 한다.
