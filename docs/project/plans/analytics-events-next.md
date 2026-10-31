# 앱 분석 이벤트 — 다음 버전 후보 (MZ2AZ-353 후속)

- **상태**: 설계만 있다. 코드는 없다(2026-10-30)
- **전제**: iOS 1.0.0 (4)는 심사 중이라 바이너리를 바꾸지 않는다. 이 문서의 항목은 **다음 버전(1.0.1 이후)** 에 넣는다. 웹은 출시 전이라 먼저 넣을 수 있다. 정본 이벤트 표는 [analytics-events.md](./analytics-events.md).
- **규칙**: 기존 이벤트의 이름은 바꾸지 않는다(이전 데이터와 끊긴다). 더하기만 한다. 매개변수 이름에 `origin` 을 쓰지 않는다. 이름·검색어 원문·좌표·글 본문은 보내지 않는다(analytics-events.md §4).

## 1. 왜 더 필요한가

지금 퍼널(설치 → 조회 → 담기·찜 → `create_course` → `start_trip` → `visit_stamp`)은 **끝까지 간 사람만** 센다. 빠진 사람이 왜 빠졌는지는 알 수 없다. 특히 KPI 인 `create_course` 는 저장한 사람만 세므로 「만들다 포기한 사람」이 보이지 않는다.

## 2. 후보

| 우선 | 이벤트 | 언제 | 매개변수 | 풀어주는 질문 |
| --- | --- | --- | --- | --- |
| 1 | `begin_course` | 새 코스 만들기를 시작했다(마법사 첫 화면 또는 빈 편집기가 열렸다). 저장된 코스를 여는 것은 세지 않는다 | `course_origin`(`ai`·`self`·`review` — `create_course` 와 같은 값) | `create_course` ÷ `begin_course` = 코스 만들기 완료율. 방식(AI·직접)별 이탈 |
| 2 | `search` 에 `result_count` 추가 | 검색어를 확정한 뒤 첫 응답이 왔다 | `result_count`(정수, 0 포함) | 결과 0건 검색 비율, 사전·후보에 없는 작품 찾기. 검색어 원문은 계속 보내지 않는다 |
| 3 | `course_fail` | 코스 저장·AI 일정 생성이 실패했다 | `stage`(`save`·`plan`), `reason`(`network`·`server`·`limit`·`other`) | 퍼널 이탈이 「싫어서」인지 「오류」인지 |
| 4 | `location_consent` | 앱 안 위치 동의 시트에서 고르거나 닫았다 | `result`(`allow`·`cancel`), `from`(`editor`·`search`·`trip`) | 위치를 쓰는 기능(편집기 지도·길찾기)에 접근 못 하는 비율. 위치 동의 시점 결정(Android 새 편집기 대 iOS 저장 코스)의 근거 |
| 5 | UTM / 설치 출처 | 광고를 켜기 직전 | Android: Play Install Referrer, iOS: App Store 캠페인 링크, 웹: URL 의 `utm_*` | 어떤 채널이 `create_course` 를 만드는가. analytics-events.md §7 「남은 것」과 같은 항목 |

- **1순위(`begin_course`)와 2순위(`search.result_count`)만 먼저** 넣는 것을 권한다. 3~5는 광고를 정하거나 이탈 원인을 찾을 때 더한다.
- 메타(Facebook) SDK 는 `save_place`·`create_course` 만 받는다. 위 후보는 메타로 보내지 않는다.

## 3. 구현 메모

- **호환**: `search` 는 매개변수 하나를 더하는 호환 변경이다. 옛 빌드는 `result_count` 가 없으므로 보고서에서 「(not set)」으로 보인다.
- **GA4 설정**: 새 매개변수(`course_origin` 은 이미 있음, `result_count`·`stage`·`reason`·`result`·`from`)는 GA4 콘솔에서 **맞춤 측정기준(사용자 정의 측정기준)으로 등록**해야 보고서에서 나눠 볼 수 있다(이벤트 범위 한도 약 50개). 쓸 것만 등록한다.
- **퍼널 보고서**: `begin_course` 가 들어가면 GA4 탐색 → 유입경로 탐색분석에서 `begin_course` → `create_course` → `start_trip` 단계를 만든다.
- **개인정보**: `from` 에 화면 이름만 쓰고 장소·좌표는 쓰지 않는다. 처리방침 수집 항목은 그대로다(앱 사용 기록).
- **세 곳 같이**: iOS·Android·웹이 같은 이름·같은 매개변수를 쓴다(analytics-events.md §3 의 원칙). iOS `AppEvent.swift`, Android `AppEvent.kt`, 웹 `apps/web/src/analytics/events.ts` 에 함께 넣고 단위 시험(`AppEventTests` 등)의 이름 표를 갱신한다.
- 호출 위치(마법사 첫 화면·빈 편집기 진입·검색 응답 처리)는 구현할 때 각 앱 소스에서 정한다 — 이 문서는 위치를 확정하지 않는다.
