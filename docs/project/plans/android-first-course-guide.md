# Android 코스 상세 실패 보호·가이드 시작 화면·지도 주변 촬영지·첫 안내 (MZ2AZ-474)

iOS MZ2AZ-444(PR #311, 가이드 시작 화면은 PR #281)의 **Android** 몫이다. 기준 문서는 [ios-first-course-guide.md](ios-first-course-guide.md)이고,
**Android 는 그 구현을 문구·동작 그대로 옮긴다.** 근거 격차는 2026-10-16 Android 패리티 격차 보고서의 B1·C3~C7이다.
위치기반서비스 동의(MZ2AZ-472)는 Android 에 아직 없다 — 이 작업은 그것을 만들지 않고, 닿는 자리에 `TODO(MZ2AZ-472)`를 남긴다.

## 1. 범위와 iOS 대응

| 순서 | 항목 | Android 자리 | iOS 원본 |
| --- | --- | --- | --- |
| 1 | 상세 조회 실패 시 편집 화면을 열지 않는다 | `routetab/RouteCourseOpening.kt` · `routetab/RouteTabView.kt` | `RouteCourseOpening.swift` · `RouteTabView.swift` |
| 1 | 상세를 못 받은 서버 코스는 저장하지 않는다 | `routetab/RouteModels.kt` `RouteCourse.canSave/detailLoaded` · `data/RouteCourseSession.kt` · `routetab/RouteEditorView.kt` | `RouteModels.swift` · `RouteStore.swift` · `RouteEditorHeader.swift` |
| 1 | 저장 오류와 조회 오류를 나눈다 | `data/RouteCourseSession.kt` `failure`/`saveFailure` · `data/RouteStore.kt` · `data/ApiFailure.kt` `courseSaveMessage` | `RouteStore.swift` `failure`/`saveFailure` · `UnavailableItem.swift` |
| 2 | 가이드 시작 화면(질문 넷·주변·AI 마법사·장바구니) | `routetab/RouteGuideStart.kt` · `routetab/RouteGuideSheet.kt` | `RouteGuideStart.swift` · `RouteGuideSheet.swift` |
| 2 | 지도 중심 기준(GPS 없으면 지도 중심, 전환 단추) | `routetab/RouteGuideAnchor.kt` · `routetab/RouteEditorView.kt` | `RouteGuideAnchor.swift` · `RouteEditorGuide.swift` |
| 2 | 가이드 → AI 마법사로 나가기 | `data/TabRouter.kt` `pendingPlanner` · `RouteEditorView.leaveForPlanner` · `RouteTabView.openPlannerIfAsked` · `data/UsageViews.kt` `GuideUsageNotice` | `RouteEditorView.leaveForPlanner` · `RouteGuideSheetLimit.swift` |
| 3 | 「지도 주변 촬영지 보기」 모델 없는 목록 | `routetab/RouteNearbySearch.kt` · `routetab/RouteNearbySheet.kt` | `RouteNearbySearch.swift` · `RouteNearbySheet.swift` |
| 4 | 빈 코스 첫 안내(장바구니 링 + 한 번만 뜨는 문구) | `routetab/RouteCartHint.kt` · `routetab/RouteCartHintViews.kt` · `ui/ReduceMotion.kt` · `data/CartSession.kt` `loadState` | `RouteCartHint.swift` · `CartStore.swift` `loadState` |

## 2. 상세 조회 실패 → 데이터 손실 방지 (B1)

**문제.** 목록 줄을 누르거나 홈에서 코스를 넘겨 받을 때, 상세 조회가 실패하면 요약(`CourseSummary`, 일차 속이 빈 코스)으로 편집 화면이
열렸다. 사용자가 「저장」을 누르면 `PUT /courses/{id}`가 빈 일차로 나가 서버 코스의 장소가 전부 지워질 수 있었다.

**고친 것.**

- `RouteCourseOpening`이 열기를 맡는다. 상세를 받은 사본만 `editing`에 오르고, 실패하면 `failedCourse`에 남는다. 목록은 그 위에
  「코스를 불러오지 못했어요」와 「다시 시도」를 보인다. 다시 시도는 실패한 코스를 다시 조회하며, 목록·재료 조회는 이 오류를 지우지 않는다.
  같은 열기가 진행 중이면 두 번째는 무시한다. 상세 대신 요약이 돌아와도(서버 코스인데 `detailLoaded`가 아님) 열지 않는다.
- `RouteCourse.detailLoaded`(상세 응답으로 만든 사본인가)와 `canSave`(`serverId == null || detailLoaded`). `RouteBridge.course(detail)`만
  `detailLoaded = true`다. 가이드가 일정을 갈아 끼울 때(`plan.revise` 등)는 원래 사본의 값을 이어받는다.
- **저장 경로에도 같은 막이 있다.** `RouteCourseSession.save`는 `canSave`가 아니면 **요청을 보내지 않고** `saveFailure =
  ApiFailure.courseDetailNotLoaded`만 남긴다. 편집 화면의 「저장」·「저장하고 닫기」 단추도 `canSave`가 아니면 눌리지 않고, 알림은
  「코스를 불러오지 못했어요」(`ApiFailure.courseSaveMessage`)다. 일부러 모든 장소를 뺀 코스는 상세를 받은 사본이라 그대로 저장된다.
- **저장 오류와 조회 오류를 나눈다.** `failure`(조회·쓰기 호출의 마지막 실패)와 `saveFailure`(편집 화면의 저장 실패)를 따로 든다.
  목록·재료 조회 실패로 편집 화면이 「저장하지 못했습니다」를 띄우지 않고, 저장이 성공한 뒤 목록 재조회가 실패해도 저장 실패로 보이지 않는다.
- 홈에서 넘어온 코스(`TabRouter.pendingCourseId`)는 같은 열기를 쓴다. 목록이 늦게 와도 오는 대로 다시 시도한다(효과가 다시 돌아도
  이미 시작한 조회가 끊기지 않게 화면의 코루틴 범위에서 연다).

**구조.** `RouteStore`는 안드로이드 컨텍스트가 필요해 단위 시험을 못 하므로, 상세 조회·저장·실패 상태를 `RouteCourseSession`(순수,
`CourseServer` 인터페이스)으로 떼었다 — `CartStore`/`CartSession`과 같은 모양이다. Compose 상태는 들지 않는다(JVM 시험에 Looper 가 없다).
화면은 값이 바뀔 때마다 받는 콜백으로 자기 상태를 갱신한다.

## 3. 가이드 시작 화면과 지도 기준 (C3·C4·C7)

- 대화창은 위치가 없어도 열린다(예전의 「현재 위치를 알 수 없습니다」 막힘 화면은 없앴다). 기준은 `RouteGuideAnchor.resolve` — 기기 위치가
  있으면 그것, 없거나 사용자가 바꾸면 지금 보이는 지도 중심(카메라가 멈춘 중심). 둘 다 있을 때만 「지도 중심 주변」/「내 위치 주변」 전환
  단추가 뜬다. 입력줄 위 한 줄이 기준을 말한다(「현재 지도 중심 주변을 찾아 드립니다.」 / 「지도에 표시된 내 위치 주변을 찾아 드립니다.」),
  기준이 아직 없으면 「지도의 위치를 확인하고 있어요. 질문을 미리 적어 두세요.」이고 보내기는 잠긴다.
- `RouteGuideStart`: 「무엇을 도와드릴까요?」, 「지도 주변 촬영지 보기」, 기준 전환, 「코스 추천받기 (AI)」(+ 초안이라는 안내), 추천 질문 넷
  (대화가 비었을 때만, 보낼 수 있을 때만 눌림), 장바구니 미리보기(최대 셋)와 「장바구니 장소 보기」. 열기만 해서는 모델을 부르지 않는다.
  대화가 있어도 장바구니·주변 입구는 유지한다.
- 「코스 추천받기 (AI)」와 한도 안내의 「AI 로 짜기 열기」는 편집 화면을 「취소」와 같은 길로 닫고(바꾼 것이 있으면 버릴지 묻는다)
  `TabRouter.pendingPlanner`를 남긴다. 편집 화면·마법사가 다 닫히면 코스 목록이 새 AI 마법사를 띄운다. 「계속 편집」을 고르면 표시를 거둔다.
  한도 안내(`GuideUsageNotice`)는 한도에 걸려 있을 때만 「새 일정은 「AI 로 짜기」에서도 만들 수 있어요」를 붙인다(무료라고 약속하지 않는다).

## 4. 지도 주변 촬영지 (C5)

- `RouteNearbySearch`는 AI 세션과 독립이다. 생성된 `GET /places`를 `bbox`(누른 시점의 지도 중심에서 남북·동서 약 3km, `minLng,minLat,maxLng,maxLat`)와
  `limit=40`으로만 부른다. 늦게 온 옛 응답은 세대 번호로 버리고, 실패는 가이드 오류와 따로 보이며 「다시 시도」로 AI 없이 다시 찾는다.
- `RouteNearbySheet`: 「지도 중심 주변」/「내 위치로」 전환, 목록(이름·작품·주소)과 「담기」/「담김」(담긴 곳은 눌리지 않는다, 낭독 문구
  「%s 코스에 담기」/「%s · 코스에 담김」), 빈 결과·오류·로딩. 담으면 현재 일차에 들어간다.
- **「내 위치로」(MZ2AZ-472 대기).** iOS 는 이 단추 앞에서 위치기반서비스 동의를 확인하고 거절·철회 시 지도 기준만 남긴다. Android 는
  동의 기능이 아직 없으므로 편집 화면이 열릴 때 한 번 읽어 둔 기존 기기 위치를 그대로 쓴다(없으면 단추가 눌리지 않고 「내 위치를 사용할 수 없어
  지도 중심으로 찾아요.」). 동의가 들어오면 `RouteNearbySheet`의 이 단추 앞과 `RouteGuideAnchor`의 좌표 출처에 붙인다(`TODO(MZ2AZ-472)`).

## 5. 직접 짜기 첫 안내 (C6)

- 대상: 서버에 없고(`serverId == null`), AI 초안이 아니고, 담은 장소가 없는 코스 — 표시 방식이 아니라 실제 상태로 판단한다. 기기당 한 번
  (`scenetrip.manualCourseCartHintSeen`).
- 편집 화면에 들어서면 장바구니 버튼의 링을 바로 켠다. **문구·낭독·기록·2.5초 노출은 장바구니 조회(`CartLoadState`)가 끝난 뒤** 함께
  시작한다. 성공했고 비었으면 「장소를 담으면 여기서 코스에 넣을 수 있어요」, 담긴 장소가 있거나 조회가 실패했으면(빈 배열은 증거가 아니다)
  「담아둔 장소로 코스를 시작해 보세요」. 장바구니 단추를 누르면 거둔다. 닫기 버튼(44dp)이 있고, 문구가 뜰 때 TalkBack 이 한 번 읽는다.
- 링은 2.5초 동안 1.0→1.12배, 투명도 1→0.3으로 번진다. 시스템 「애니메이션 제거」(애니메이터 배율 0)에서는 같은 시간 동안 고정 테두리만 보인다.

## 6. 시험

JVM 단위 시험 `tests/CourseDetailSafetyTests.kt`(11개)와 `tests/FirstCourseGuideTests.kt`(22개), 모두 `:unit_test`에 등록했다.

- 상세 실패: 실패 후 편집 미진입·요약 저장 거부(서버 쓰기 0건)·재시도 성공 뒤 진입과 장소 보존, 이전 실패 없이도 요약 저장 거부, 일부러 비운 코스는
  저장, 다른 상세 조회가 옛 요약 사본을 풀어 주지 않음, 요약이 상세로 돌아와도 열지 않음, 열기 중 두 번째 무시, 조회 오류가 저장 오류로 번지지
  않음, 새 코스 저장과 채우기 실패 시 껍데기 되돌리기, 영어 문구.
- 가이드 기준·주변 조회: 지도 중심 기준 좌표와 bbox 중심, 극지 범위 제한, 가짜 조회 한 번과 상태 순서, 실패 후 AI 없이 재시도, 늦은 옛 응답
  무시, 잘못된 좌표는 서버를 부르지 않음, 장바구니 조회 상태(미조회/성공/실패, 실패해도 목록 유지).
- 첫 안내: 대상 조건과 한 번만, 재생성 뒤에도 기록 유지, 느린 비어 있지 않은/빈/실패 조회에서 응답 전에는 링만 켜지고 문구·낭독·기록·노출 시간이
  시작되지 않음(가짜 대기로 2500ms 확인), 닫으면 기록 안 함, 화면을 떠나면 링 거둠, 이미 켜져 있으면 두 번째 무시. KO/EN 문구 표.
- 한계: 편집 화면·시트의 Compose 그림(링 애니메이션, 시트 배치)은 단위 시험으로 검증하지 못한다. 에뮬레이터 확인은 아래 7.

## 7. 사용자가 에뮬레이터로 확인할 것 (이 작업은 에뮬레이터를 쓰지 않았다)

1. 서버를 끈 채(또는 상세만 실패하게) 코스 목록의 줄을 누르면 편집 화면이 열리지 않고 「코스를 불러오지 못했어요」+「다시 시도」가 목록 위에 뜬다.
   서버를 되살리고 「다시 시도」를 누르면 편집 화면이 열리고 장소가 보인다. 홈의 「내 여행 이어가기」로 넘어온 경우도 같다.
2. 새 직접 짜기 코스(처음 한 번만): 장바구니 버튼에 링이 바로 켜지고, 장바구니 조회가 끝나면 문구가 2.5초 뜬다. 다시 열면 뜨지 않는다.
   TalkBack 을 켜면 문구가 한 번 읽히고, 「애니메이션 제거」를 켜면 링이 번지지 않고 고정이다.
3. 가이드(해태) 창: 위치 권한을 끈 채에도 열리고 지도 중심 기준으로 안내한다. 「지도 주변 촬영지 보기」→ 목록·담기·「내 위치로」.
   「코스 추천받기 (AI)」→ 편집 화면이 닫히고(바꾼 게 있으면 버릴지 묻는다) AI 마법사가 열린다. 이때 유료 `/guide/*` 호출은 하지 않는다 —
   추천 질문 단추는 누르지 않는다.

## 8. iOS 와 다른 점

- 「내 위치로」: 위치기반서비스 동의 확인이 없다(MZ2AZ-472 대기, 위 4).
- 좌표 출처(`device`/`userSelected`)를 `RouteGuideAnchor`에 싣지 않는다(같은 이유).
- 누르는 자리는 모두 높이 44dp 이상이다(iOS bordered 단추보다 조금 높다).
- 여행 상태 변경(`setRunning`) 실패는 아직 `saveFailure`로 올리지 않는다 — iOS 는 올리지만 Android 편집 화면은 그 알림을 보이지 않아
  화면 동작까지 함께 손봐야 한다(별도 작업).
- 「버릴까요?」 창은 `onDismiss`를 단추 동작보다 먼저 부르므로, 마법사 표시(`pendingPlanner`)는 `PlannerExit`가 의도를 들고 있다가 「버리기」에서만 올린다(「계속 편집」·바깥 누름은 표시가 남지 않는다).
