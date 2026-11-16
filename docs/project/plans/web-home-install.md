# 웹 홈 화면 추가 단추와 권유 (MZ2AZ-589)

2026-11-17 사용자 결정. 간직하기(web-keep-flow.md) 밖에서도, 앱 안에서 늘 「홈 화면에 추가」 를 할 수 있게 하고 쓸모를 느낀 순간에 한 번씩 권한다.

## 1. 자리와 때

- 늘 있는 단추: 홈 머리줄(HomeHeader), 「마이맵」 알약과 톱니바퀴 사이의 36pt 동그라미 아이콘(`arrow.down.to.line`), 이름 「홈 화면에 추가」. 홈 화면 앱(`installed`)으로 열었으면 없다.
- 권유 띠: 화면 아래에 떠 있는 띠 「다음엔 홈 화면에서 바로 열어요」 + 「추가」 + 닫기(✕). 이런 때 한 번:
  - `course_created`: 이 브라우저에서 처음으로 코스를 새로 저장한 직후(`create_course` 와 같은 자리).
  - `return_visit`: 다시 온 사람의 새 세션(`return_visit` 이벤트가 나가는 때).
- 띄우지 않는 때: `installed`, 닫은 지 7일이 안 됨, 이번 세션에 이미 띄움, `course_created` 는 이 브라우저에서 이미 한 번 띄움.

## 2. 누르면(단추와 띠 같음, 환경은 web-keep-flow.md §2-1 `keepEnv`)

| env | 동작 |
| --- | --- |
| `installable` | 브라우저 설치 창. 결과 `install_accepted`, `install_dismissed` |
| `samsung_android` | 크롬 intent 로 넘김(MZ2AZ-588, Play Protect 회피) |
| `in_app_android`, `in_app_ios` | 바깥 브라우저로 넘김(`handoffHref`) |
| `android_menu`, `ios_safari`, `other` | 안내 시트: 그 브라우저의 메뉴 두 줄(간직하기와 같은 말) + 「알겠어요」 |

크롬은 사이트를 누르고 30초 넘게 머문 뒤에야 설치 창을 준다. 그 전에 누르면 안내 시트가 뜬다. 시트가 떠 있는 동안 설치 창이 오면 주 단추가 「알겠어요」 에서 「지금 설치」 로 바뀌고, 누르면 설치 창(`install_accepted`, `install_dismissed`)을 띄우고 시트를 닫는다(MZ2AZ-590).

넘기는 주소는 지금 장바구니(`handoffKeepUrl`)와 광고 꼬리표, 익명 id 를 싣는다.

## 3. 서비스 워커

크롬이 설치 창(`beforeinstallprompt`)을 확실히 주도록 `public/sw.js` 를 넣는다. 저장(캐시)은 하지 않는다. 페이지 이동 요청만 그대로 네트워크로 보낸다. 운영 빌드에서만 등록하고, `/sw.js` 는 `Cache-Control: no-cache`.

## 4. 분석 이벤트

| 이벤트 | 매개변수 | 언제 |
| --- | --- | --- |
| `add_to_home` | `env`, `action`, `where`, `trigger`(띠일 때) | `where` = `header`, `nudge`. `action` = `view`(띠가 보임), `tap`, `install_accepted`, `install_dismissed`, `open_browser`, `guide`, `got_it`, `dismiss`(띠 닫기) |
| `app_installed` | 없음 | 브라우저가 설치를 끝냄(`appinstalled`, 브라우저 메뉴로 설치한 것도 포함) |

홈 화면 앱으로 연 방문은 기존 `launch_mode = standalone` 으로 센다. GA4 맞춤 측정기준에 `env`, `where`, `trigger` 를 등록하고, PostHog 대시보드에 「홈 화면 추가」 칸을 더한다.
