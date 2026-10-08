# 구현 계획

코드보다 먼저 쓰는 계획 문서. 파일 하나가 기능 하나에 대응한다.

| 문서 | 대상 |
| --- | --- |
| [dev-lifecycle.md](dev-lifecycle.md) | 버튼 하나로 DEV 올리기·내리기 — runner·Secret·배포·Cloudflare DNS |
| [aws-teardown.md](aws-teardown.md) | 비용 절감을 위한 서비스·bootstrap 수동 삭제와 잔존 데이터 정책 |
| [alb-deployment.md](alb-deployment.md) | ALB 진입점·신뢰 경계·배포 검증·교육자료 전환 |
| [aws-dev-prd-port.md](aws-dev-prd-port.md) | TripPilot 기반 DEV·PRD AWS 배포와 SceneTrip 교육자료 이식 |
| [scene-api-search-map.md](./scene-api-search-map.md) | 검색·지도 백엔드 API (MZ2AZ-149) |
| [scene-api-service-module.md](./scene-api-service-module.md) | 백엔드 서비스 모듈과 클러스터 배포 (MZ2AZ-181) |
| [mobile-native-search-tab.md](./mobile-native-search-tab.md) | 검색 탭 iOS · Android 네이티브 구현 (MZ2AZ-148) |
| [course-api.md](./course-api.md) | 경로여정(코스) 백엔드 API — 코스·아이템·찜·마켓 (MZ2AZ-199) |
| [poi.md](./poi.md) | POI(편의시설) 도입 — 음식·숙박·관광·교통 47만 건 |
| [navigation-next-leg.md](./navigation-next-leg.md) | 여행 중 길찾기 백엔드 이관 — 카카오를 서버가 부른다 (MZ2AZ-296) |
| [guide-endpoints.md](./guide-endpoints.md) | 가이드 챗봇 백엔드 창구 — scene-api 가 trip-guide 에이전트를 부른다 (MZ2AZ-319) |
| [guide-app.md](./guide-app.md) | 앱을 가이드 창구에 붙인다 — 챗봇 시트는 `POST /guide/chat`, 마법사는 `POST /guide/plan` (MZ2AZ-321, iOS) |
| [mobile-home-tab.md](./mobile-home-tab.md) | 홈 탭 — 4탭을 3탭으로, 홈이 첫 화면. 경로여정·마이페이지는 홈이 띄우는 덮개 (main 이식 2026-09-05) |
| [trip-mode.md](./trip-mode.md) | 여행 모드 — 코스 시작부터 스탬프까지, 편집 화면 안 길찾기(2단계) · main 이식 (MZ2AZ-299 · MZ2AZ-307) |
| [poi-card.md](./poi-card.md) | 편의시설 카드 — 사진·영업시간·평점을 네이버 장소에서 (데모 한정, ADR 0011) |
| [social-login.md](./social-login.md) | 구글·애플 소셜 로그인과 JWT — 계약·요청 계정 규칙·합치기·앱이 할 일 (MZ2AZ-329, ADR 0018) |
| [i18n-en-fallback.md](./i18n-en-fallback.md) | 응답 언어 폴백 — 요청 언어 → en → ko, 편의시설 제외 (MZ2AZ-344) |
| [analytics-events.md](./analytics-events.md) | 앱 분석 이벤트 — Firebase·GA4, 퍼널(설치→가입→조회→담기·찜→첫 코스 생성→여행 시작), 보내지 않는 것 (MZ2AZ-353) |
| [poi-i18n-image.md](./poi-i18n-image.md) | 편의시설 다국어·사진 — 번역은 옆 표, 분류 사전, 영문 주소는 행안부 영문도로명주소DB, 분기 갱신과 번호 변경 |
| [place-key-seed.md](./place-key-seed.md) | 촬영지 적재를 고유 키로 — 지우지 않고 갱신·추가, CSV 에서 빠지면 숨김 (MZ2AZ-361) |
| [review.md](./review.md) | 리뷰와 별점 — 촬영지·편의시설 상세, 한 테이블, 단순 평균·보정 평균, 사진첩에 리뷰 사진, 탈퇴해도 남김 (MZ2AZ-362) |
| [rate-limit.md](./rate-limit.md) | 요청 한도 — 계정별 제한, 유료 API(챗봇·길찾기) 하루 한도, 챗봇 멱등 키, 요금제별 설정 (MZ2AZ-334) |
| [review-app.md](./review-app.md) | 리뷰·별점·사진첩·닉네임 — 앱 화면. 한 벌의 리뷰 시트를 세 곳에서, PR 다섯으로 (MZ2AZ-363) |

## 언제 여기에 쓰는가

[CLAUDE.md §3](../../../CLAUDE.md) 의 조건 중 하나라도 걸리면 쓴다 — 모듈 둘 이상에
걸치거나, `contracts/` 를 바꾸거나, `MODULE.bazel` 에 의존성을 더하거나, 대략 100줄을
넘는 변경.

계획은 티켓이 끝나도 지우지 않는다. "왜 이렇게 만들었나" 를 답하는 것이 계획서의
두 번째 수명이다. 결정이 뒤집히면 문서를 고치지 말고 **뒤집힌 사실을 덧붙인다.**
오래 영향을 남기는 결정은 [ADR](../../architecture/adr/README.md) 로 승격한다.
