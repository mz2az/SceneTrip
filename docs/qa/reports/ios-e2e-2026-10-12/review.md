# iOS 전체 사용자흐름 E2E 준비·읽기전용 대조

소스: `6fa9df2dac15868b35c7ae7276f50d4fe114fe81` (로컬 origin/main 객체). 네트워크 fetch나앱/DB 변경을하지않았다.

체크리스트 70개. 이번 live E2E 실행 0개·PASS 0개. 부모의 Orca/CoreSimulator 접근차단으로 모든동선 BLOCKED_NOT_RUN. 코드확인이나 과거QA를 E2E성공으로 보지않는다.

## 발견사항

### CODE-262 — 작품 상세의 담기에서 작품 출처 누락

코드 경로 확정·실 E2E 미재현。 onSave→save→cart.add(placeId:)에 sourceContentId가 없어 default nil을 DTO에 전달

근거: `apps/scenetrip-ios/src/SearchTab/SearchTabView.swift:297`, `apps/scenetrip-ios/src/SearchTab/SearchTabView.swift:380`, `apps/scenetrip-ios/src/Models/CartStore.swift:58`, `contracts/openapi/scene-api-v1.yaml:4060`

### CODE-363 — 기존 로그인 상태 재시작에서 최초 닉네임 요청 연결 없음

코드상 누락 소견·실 E2E 미재현。 start→loadMe는 me만 갱신. askNicknameIfNeeded는 Google로그인 성공 한곳. refresh는 session.user를 버리고 토큰만 저장。 전체 src 검색에서 RootTabs78·Profile258·RouteTab59·Reviews75는 askingNickname을 관찰해 시트를 표시할뿐이며 Reviews86~109는 닉네임 종료 뒤 리뷰작성 이어가기만 수행한다. 추가 nicknameConfirmed 관찰로 질문을 켜는 경로는 찾지못했다.

근거: `apps/scenetrip-ios/src/Auth/AuthStore.swift:32`, `apps/scenetrip-ios/src/Auth/AuthStore.swift:77`, `apps/scenetrip-ios/src/Auth/AuthStore.swift:219`, `apps/scenetrip-ios/src/Auth/AuthTokens.swift:85`, `apps/scenetrip-ios/src/Auth/AuthStore.swift:267`, `apps/scenetrip-ios/src/Reviews/ReviewsSheet.swift:86`, `apps/scenetrip-ios/src/RootTabs.swift:78`

### DOC-PLAN — 일정생성 비용없음 서술은 현재 모델설정과 다름

문서·실설정불일치·실 E2E 미재현。 iOS README와 OpenAPI는 모델없음/비용없음. agents config/llm_planner.json enabled=true이며 최신README는 AI일정 모델호출을 설명

근거: `apps/scenetrip-ios/README.md:98`, `contracts/openapi/scene-api-v1.yaml:2307`, `agents/trip-guide/config/llm_planner.json:4`, `agents/trip-guide/README.md:47`

### DOC-NICKNAME — 닉네임 저장 미확인이라는 README 서술이 과거실기정정과 모순

이미 알려진 문서불일치。 최신 README는 서버362전이라실기미확인. 과거10/7지라댓글은로컬닉네임저장·중복확인으로정정

근거: `apps/scenetrip-ios/README.md:106`

### SCOPE-PRODUCER — 포스터 제작사 링크는 현재 계약에 필드없음

계약선행·실 E2E 미재현。 origin/main 계약 producer/producerUrl 없음, 포스터는RemoteImage; MZ393선행대기

근거: `apps/scenetrip-ios/src/SearchTab/ContentDetailView.swift:76`, `contracts/openapi/scene-api-v1.yaml`

### SCOPE-APPLE — Apple 로그인은 현재 사용자 버튼 동선없음

출시범위 확인 필요·새 E2E 버그 아님。 SignInView는 Google버튼과나중에만제공; 기존README도Apple은자리만

근거: `apps/scenetrip-ios/src/Auth/SignInView.swift:39`

### SCOPE-HOME — 홈 인기순 전환은 아직별도후속

의도된후속·버그판정아님。 shelf는placeCount순·동일수server순;408계측과혼동안함

근거: `apps/scenetrip-ios/src/HomeTab/HomeTabModel.swift:157`

## 전체 동선 체크리스트

| ID | 영역 | 실행할 동선 | 기대 | 필요한 fixture/외부조건 | 상태 |
| --- | --- | --- | --- | --- | --- |
| entry | 첫 실행·언어·온보딩 | 새 설치→KO/EN 선택→4장 이전/다음/건너뛰기/완료→재실행 | 완료 한번 저장, 기존 설치는 자료를 보존하고 Home으로 | 새 설치용 별도 iOS 기기·미로그인 fixture | BLOCKED_NOT_RUN |
| entry-small | 온보딩 작은 화면·큰글자 | 작은 iPhone·XXXL에서 첫/마지막 페이지와 하단 버튼 | 본문 스크롤·44pt 버튼·페이지 범위 유지 | 작은 iPhone/iOS17 및 접근성 설정 | BLOCKED_NOT_RUN |
| home | 홈 진입·새로고침·자료없음 | 홈 코스/작품/오늘성지/내기록→세부→뒤로; empty/offline fixture | 기존 개수·내용 일치, 실패가 빈자료 성공처럼 보이지 않음 | 기존 코스·찜·스탬프 및 빈 계정 | BLOCKED_NOT_RUN |
| home-sort | 홈 작품 표시순 | 촬영지 수가 다른 작품과 동일수 작품 카드순서 대조 | 현재 구현은 촬영지수순, 같은수는 서버순; 인기순 후속과 구분 | 작품100편이상·placeCount가 다른 fixture | BLOCKED_NOT_RUN |
| search | 검색·자동완성·상세·뒤로 | 작품/배우/별칭/장소 제안·확정→상세→뒤로→검색삭제 | 선택 갈래에 맞는 탭, 늦은응답 무시, 뒤로 이전검색 유지 | KO/EN 별칭과 배우검색 fixture | BLOCKED_NOT_RUN |
| search-empty | 검색0건·오류·경합 | 0건검색→실패주입→재시도→짧은시간 다른검색 | 0건/오류 분리, 최신 요청결과만 화면에 반영 | 개발NetFault 응답주입·연속검색 | BLOCKED_NOT_RUN |
| paging | 작품·촬영지 페이지·칩 | 128작품 끝까지/486장소·분류칩·전국영역목록 | 서버total, 페이지중복없음, 한글유형/코드모두 분류 | 최소128작품·486visible places·다양한 유형 | BLOCKED_NOT_RUN |
| map | 지도·핀·목록·현위치 | 번호핀/목록 선택→fit→viewport검색→내위치/한국으로 | 목록번호와 핀 일치, viewport검색은 현재카메라 유지 | 위치권한 허용/거부·한국안/밖 기기위치 | BLOCKED_NOT_RUN |
| scene-photo | 촬영지 장면이미지 | 실sceneImageUrl/없음/잘못된주소/실패 장면 카드·확대 | 장면사진만 표시, 포스터 대체없음, 제목설명 유지 | 스틸있는/없는/실패하는 PlaceDetail | BLOCKED_NOT_RUN |
| official-photo | 관광공사 사진·출처 | 대표사진/지도카드/격자/확대에서 가로·세로 원본 | 비율유지, credit 원문 사진밖, 업로드캐시와 혼용없음 | 공식가로·세로사진·긴credit | BLOCKED_NOT_RUN |
| producer-link | 포스터 제작사 링크 | 작품·장면의 포스터탭으로 제작사URL 열기 | 393 계약 선행 없으므로 현재 구현완료로 판정불가 | 제작사URL API 계약과 valid/invalid fixture | BLOCKED_NOT_RUN |
| naver-link | 네이버 장소링크 | 네이버주소 있음/없음/불허호스트 POI·place 카드 | 유효 https 네이버만 버튼, 없는주소 검색대체없음 | 링크있는/없는/잘못된 응답 | BLOCKED_NOT_RUN |
| cart | 장바구니 기본 | 조회→신규담기→중복→빼기→앱재실행 | 서버와 행/저장아이콘 일치, installId 전달 | 별도 QA 설치계정·미저장장소 | BLOCKED_NOT_RUN |
| cart-source | 작품에서 담은 출처 | 작품 상세의 촬영지 +→장바구니 대표작품→코스로 담기 | sourceContentId=선택작품ID; 현재 코드미전달 발견 | 미저장촬영지와 해당작품 | BLOCKED_NOT_RUN |
| cart-delete-fail | 삭제실패·성공후GET실패 | 최초DELETE404/503/timeout·성공DELETE후조회실패 | 실패엔 행보존, 성공엔 행제거, 거짓성공없음 | 개발NetFault·기존자기QA행 | BLOCKED_NOT_RUN |
| favorites | 작품찜·내목록 | 작품 하트→마이페이지목록→재실행→해제 | 첫페이지밖 찜도 보임, 서버값과 개수일치 | 128작품중 후반ID 포함 찜fixture | BLOCKED_NOT_RUN |
| course-crud | 코스 CRUD | 빈코스생성→제목/날짜/일차→저장→재열기→삭제 | 성공후에만완료, 실패중초안보존·부분생성롤백 | 독립QA코스·인증된/비회원설치 | BLOCKED_NOT_RUN |
| course-edit | 코스 편집·검색·순서·취소 | Gyeongbok검색→장바구니담기→중복/삭제/드래그/최적화→취소 | 전체서버검색·중복정확·변경없는열기취소는확인안함 | 검색앞200밖장소·드래그가능UI도구 | BLOCKED_NOT_RUN |
| course-kind | 촬영지/POI/개인핀 왕복 | 각종류담기→저장→번호핀카드→항목하나삭제재저장 | 정확히하나ID, 종류보존, 개인핀은장소조회기록없음 | place/미연결POI/customPin 혼합QA코스 | BLOCKED_NOT_RUN |
| course-poi-link | 연결POI·지도숨김 | 몽테드연결POI 탭·담기·동일촬영지중복·핀숨김 | placeId대표, 촬영지핀이그려진경우에만POI숨김 | 277819↔45같은 실제연결 fixture | BLOCKED_NOT_RUN |
| course-time | 일차시간·체류 | 서버totalMinutes→체류+15→순서변경→원복→재저장 | 약/이동포함 표시;체류차이만반영;경로변경시옛어림무효 | 서버travelMinutes/totalMinutes 있는코스 | BLOCKED_NOT_RUN |
| poi-cluster | POI 클러스터·필터 | 음식/숙소/명소·줌별묶음→확대→동일좌표목록→개별카드 | 받은30건한도표시, 선택핀유지, course핀은따로 | POI밀집지역·동일좌표두POI | BLOCKED_NOT_RUN |
| poi-i18n | POI 영어·한국어 | 브랜드영문있음/없음→카드/코스행/지도캡션 KO↔EN | title/displayName·읽는법·영문주소·분류일치,원본저장 | 브랜드+동네가게+영문주소결측 fixture | BLOCKED_NOT_RUN |
| trip-start | 여행 시작·종료·다음날 | 저장된QA코스 시작→홈이어가기→종료→다음일차 | 여행상태와 서버저장 일치, 저장전 길찾기 차단 | 자기QA코스·네비요청차단응답주입 | BLOCKED_NOT_RUN |
| trip-nav | 유료 길찾기·도착·스탬프 | 현재위치→next-leg→경로→100m체류→다음구간→재개 | 서버경로/안내언어·한번방문·다음은사용자탭 | 실물GPS/QA코스/서버스탬프; 실제 유료창구 호출금지 | BLOCKED_NOT_RUN |
| trip-limit | 길찾기 한도·외부앱 | 429 주입→카카오웹/앱·네이버앱→복귀→도착처리 | 한도중재요청없음, 지도앱단추/돌아온안내 읽힘 | 실제iPhone에지도앱설치/미설치·response fixture | BLOCKED_NOT_RUN |
| wizard-ui | 일정 마법사 입력 | 기간/출발일/작품/빡빡·널널 선택→요약→취소 | 선택값유지, 방문수를고정약속하지않음 | 자료있는코스/empty·KOEN | BLOCKED_NOT_RUN |
| wizard-response | AI 일정생성 응답·실패 | 계약POSTguide/plan fixture→초안→편집→저장/취소 | 실제model 호출없이 응답표시검증;중복/유형/일차검증 | 전체JSON응답/503/401;현재 enabled=true로 실제호출금지 | BLOCKED_NOT_RUN |
| guide-entry | 가이드 입구·시트·닫기 | 홈/코스/안내 입구→드래그→시트닫기→재열기 | 안내·버튼안덮음,큰글자는말풍선접기 | KOEN·작은폰·XXXL | BLOCKED_NOT_RUN |
| guide-chat | 가이드 실제대화·도구 | 여행질문/날씨/DB검색/금융주제거절→추천담기/일정 | 최신Bedrock변경후가드/도구/일정전체왕복은미실행 | 인증·Bedrock/IAM/네트워크·유료실호출 금지 | BLOCKED_NOT_RUN |
| guide-retry | 챗봇 멱등·한도·취소 | 주입409/429/503/down→자동/버튼재시도→닫기 | 동일키/유한횟수·한도잠금·새메시지새키·닫으면대기취소 | 개발NetFault·멱등지원header fixture | BLOCKED_NOT_RUN |
| review-read | 리뷰·별점·분포·정렬 | place/poi카드별점→리뷰·세정렬·쪽받기·익명사용자 | 서버정렬total·막대·내리뷰·수정표시 일치 | 25건이상·평점분산·익명/내/남 리뷰 | BLOCKED_NOT_RUN |
| review-write | 리뷰 CRUD·내리뷰 | 가입계정별점/본문선택/사진→저장→수정→내리뷰→삭제 | 한대상하나,2000자,서버성공후목록평점갱신 | 독립QA계정과리뷰없는place/poi | BLOCKED_NOT_RUN |
| review-photo | 사진선택·제거·실패·재시도 | 10장선택/11장시도·PNG/HEIC/JPEG→429401503/PUT403→재시도 | 2048JPEG/EXIF제거;완료전저장차단;storagePUT는Auth없음 | 시스템PhotosPickerfixture·EXIFGPS사진·저장소주입 | BLOCKED_NOT_RUN |
| gallery | 사진첩20장초과·만료URL | 공식20+방문자5→끝쪽/격자/리뷰로→주소만료→재받기 | offset맞음/중복없음/같은key캐시/한번자동갱신 | 공식+리뷰25장이상·서명주소만료 fixture | BLOCKED_NOT_RUN |
| community-read | 커뮤니티 공개목록·내글 | 목록→페이징→전문→사진·코스더보기·내글 | 닉네임과본문/사진/순서·전체수 서버와일치 | 8장사진·여러일차·빈목록·탈퇴작성자 | BLOCKED_NOT_RUN |
| community-write | 커뮤니티 글·사진·코스 | 제목본문·사진0~8·코스선택→게시→재실행→내글삭제 | 성공만완료,개인핀제외수고지,원본코스그대로 | 독립QA계정·place/poi/pin혼합코스 | BLOCKED_NOT_RUN |
| community-loss | 게시응답유실·중복방지 | POST응답유실→게시여부확인→사진없는초안/계정변경 | 동일초안재POST없음,원래글을성공오인안함 | 서버저장은성공응답만유실fixture·0/1/2일치 | BLOCKED_NOT_RUN |
| community-copy | 후기코스담기·옛글 | 후기course사본→내코스→왕복·기기옛글읽기 | place/poi보존·개인핀공개제외,옛자료읽기전용 | 서버post-course+legacy기기JSON | BLOCKED_NOT_RUN |
| login | Google 로그인·취소·병합 | 비회원장바구니/찜/코스→Google→닉네임→서버자료 | cancel은조용히,진행중쓰기뒤계정병합,성공후재조회 | 별도GoogleQA계정/브라우저동의·oauthnetwork | BLOCKED_NOT_RUN |
| nickname | 닉네임 최초/재실행/중복 | 신규로그인/기존세션재실행·skip·마이페이지변경 | 미확정일때계정별한번묻기·NFC규칙·중복안내 | nicknameConfirmed=false·asked기록없음·taken fixture | BLOCKED_NOT_RUN |
| auth-expiry | 토큰만료·세션소실 | 401expired→refreshonce→retry·refresh거절·오프라인 | 불필요signout없음/거절시세션초기화/초안유지 | expired access·valid/invalid refresh 별도QA계정 | BLOCKED_NOT_RUN |
| signout-delete | 로그아웃·탈퇴·자료정책 | 자기QA계정로그아웃·탈퇴→비회원재시작 | 발자취/동의초기화·리뷰익명안내,인증자료남지않음 | 삭제가능한전용계정·기기로그인 fixture | BLOCKED_NOT_RUN |
| footprints | 발자취 동의·보기·지우기 | 기본OFF→안내동의→25m 이동→보기OFF→전체삭제 | 기기에만·한국안·로그아웃시삭제·동의초기화 | 별도기기/전용QA이동점; navigation실호출없이 fixture | BLOCKED_NOT_RUN |
| profile | 마이페이지 모든입구 | 닉네임/내코스/찜/장바구니/스탬프/내글/내리뷰/도움/릴스 | 개수/항목일치·릴스는준비중·마켓진입없음 | 각자료있는/빈계정 | BLOCKED_NOT_RUN |
| appearance | KOEN·다크·큰글자·회전 | 세탭/시트/코스/카드/리뷰/후기·시스템picker 재실행 | 잘림/겹침/버튼비활성 혼동없음·대비/voiceover이름 | iOS17/18/26·소형기기·XXXL·VoiceOver | BLOCKED_NOT_RUN |
| usage | 이용기록·인기도반영 | content/place/poi상세·personalpin·typing/search0/1·재열기 | daily중복제거·선택탭total·실패무재시도·민감원문외부미전송 | 서버V28·QA설치·이벤트읽기전용조회 | BLOCKED_NOT_RUN |
| analytics | Firebase/Meta·ATT | GA4허용이벤트·기본MetaOFF·실ATT허용/철회 | OFF일때SDK초기화/전송없음·정책미정ATT창없음 | 콘솔디버그확인·실iPhone·391/402정책 | BLOCKED_NOT_RUN |
| offline | 앱전체 네트워크실패 | 조회502503504/down·POST/PUT/DELETE·앱백그라운드복귀 | 횟수상한·유료창구자동재호출없음·문구노출·초안보존 | 응답주입·오프라인·foreground/background | BLOCKED_NOT_RUN |
| apple-login | Apple 로그인 | Apple 계정로그인→닉네임/자료병합 | 현재SignInView는Google버튼만·Apple은기능자리아직 | SignInWithApple entitlement/개발계정/구현선행 | BLOCKED_NOT_RUN |
| entry-restore | 온보딩 완료한 기존 설치 재실행 | 기존자료있는앱종료→재실행 | 온보딩재노출없음·로그인/코스/찜보존 | 기존설치기기 | BLOCKED_NOT_RUN |
| search-actor | 배우 자동완성 선택 | 배우이름검색→person제안선택→작품선택 | 장소탭아닌작품탭·matchedTerm과작품일치 | 배우별칭fixture | BLOCKED_NOT_RUN |
| map-denied | 위치권한 거부 후 설정 복귀 | 내위치→거부안내→설정→허용→복귀 | 지도상태유지·내위치재시도·안내단추읽힘 | 권한거부된별도기기 | BLOCKED_NOT_RUN |
| course-unsaved | 저장 전 편집 버리기 | 코스제목/항목변경→취소→계속편집→취소버리기 | 질문은실제변경에만·버리면원서버코스보존 | 읽기와수정용QA코스 | BLOCKED_NOT_RUN |
| course-day-move | 코스 일차와 항목 이동 | 2박3일→항목일차이동→빈일차추가/삭제→재열기 | dayNo/sortOrder·이동시간무효화·항목수일치 | 여러일차QA코스·드래그가능도구 | BLOCKED_NOT_RUN |
| course-optimize | 동선 최적화 고정 시작/끝 | 여러장소선택→출발고정/도착고정조합→최적화취소 | 핀과행순서일치·고정조건유지·초안만변경 | 4곳이상QA코스 | BLOCKED_NOT_RUN |
| course-pin-edit | 개인핀 좌표/이름/체류 | 지도를길게눌러개인핀→이름/주소수정→저장왕복 | 개인핀은place/poi와분리·좌표/이름보존 | longpress가능UI·QA코스 | BLOCKED_NOT_RUN |
| trip-return | 외부지도 앱 복귀 후 수동도착 | 한도fixture에서외부지도→복귀→여기도착함→다음 | 여행계속·스탬프중복없음·한도중새유료호출없음 | 실iPhone+지도앱·주입응답·QA코스 | BLOCKED_NOT_RUN |
| guide-add | 가이드 추천 장소 담기·빼기 | fixture추천촬영지/미연결POI/연결POI ⊕→기존항목중복→빼기 | placeId/poiId각각정확·연결POI는place대표 | 모델호출없는GuideChatResponse fixture | BLOCKED_NOT_RUN |
| guide-edit | 가이드 일정 수정 응답 | fixture로POI/개인핀포함현재일정→수정제안→수락/취소 | 모르는ID/잘못된결과검증·기존kind와개인핀유지 | GuideCourseDraft형식과수정fixture | BLOCKED_NOT_RUN |
| review-guest | 비회원 리뷰쓰기 로그인 이어가기 | 비회원리뷰쓰기→로그인→닉네임skip→리뷰쓰기 | 시트겹침없음·원subject유지·닉네임뒤작성창 | OAuthQA계정·nickname미확정 | BLOCKED_NOT_RUN |
| review-discard | 리뷰 초안 취소와 보존 | 별점/글/사진변경→닫기·취소→계속쓰기/버리기 | dirty이면묻기·버리기전서버리뷰안변경 | 자기QA리뷰·이미지fixture | BLOCKED_NOT_RUN |
| gallery-card | 지도 카드 방문자사진에서 리뷰 연결 | 코스place/poi카드사진줄→사진크게→방문자리뷰 | 올바른subject/reviewId·닫고원카드복귀 | 리뷰사진있는place와poi | BLOCKED_NOT_RUN |
| post-discard | 후기 초안 취소와 재개 | 제목본문사진코스선택→취소계속쓰기→버리기 | 게시전서버글없음·업로드중취소정지·기존글보존 | 독립QA계정/사진fixture | BLOCKED_NOT_RUN |
| post-delete-fail | 내 후기 삭제 실패 | 자기QA글삭제확인→401503timeout→새로고침 | 실패에글남음·삭제성공으로표시않음 | 자기QA글·NetFault응답 | BLOCKED_NOT_RUN |
| nickname-duplicate | 닉네임 규칙·중복·건너뛰기 | 1자/17자/한글NFC/예약자동이름/남의이름→skip | 규칙안내·taken구분·skip은계정별한번 | 독립QA사용자/중복사용닉네임 | BLOCKED_NOT_RUN |
| auth-background | 인증 중 앱 배경/복귀 | 로그인/refresh중background→복귀·동시여러요청 | 진행중쓰기와계정병합순서·refresh중복없음 | OAuth가능기기·만료fixture | BLOCKED_NOT_RUN |
| language-restart | 언어 변경 후 시스템 UI | KO→EN변경→picker/지도→앱종료재실행 | 앱문구즉시·시스템언어는다음실행·네이버라벨변경 | PhotosPicker·Location설정·별도기기 | BLOCKED_NOT_RUN |
| usage-dedup | 다시 열기·자동완성 계측 제외 | 작품/장소동일일재열기→typing만→personalpin카드 | daily조회한건·typing검색없음·personalpin이벤트없음 | V28 QA설치·DB읽기증거 | BLOCKED_NOT_RUN |
| meta-revoke | 추적 동의 철회 | 실ATT허용과로컬ON설정fixture→철회→foreground/이벤트 | 철회뒤이벤트차단·동의전기록재전송없음 | 실기기/정책승인후별도측정fixture | BLOCKED_NOT_RUN |

## 실행원칙

다른 Android세션·공유DB·서비스상태를 건드리지 않는다. 권한상승/우회/타세션실행요청을하지않는다. 향후 E2E는전용fixture로 필요한동선만좁혀실행하고, 코드수정없이 스크린샷·AX·서버/DB읽기증거를연결한다. 기존638단위시험이나CI초록은 이번라이브동선검증을대체하지않는다.
