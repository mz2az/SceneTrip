import Foundation

/// 앱이 기록하는 사용 이벤트 (MZ2AZ-353). **이름과 매개변수의 정본은 `docs/project/plans/analytics-events.md`** —
/// 이 파일과 Android 쪽이 그 표를 따른다. 고치면 문서와 두 앱을 같이 고친다.
///
/// ## 무엇을 재려는가
///
/// 퍼널: 설치 → 가입 → 정보 조회 → **씬 담기·찜** → **첫 코스 생성**(핵심 지표) → 여행 시작 → 방문 스탬프.
/// 광고를 집행하기 전에 「들어온 사람이 무엇을 하고 다시 오는가」를 알아야 한다(2026-10-01 마케팅 멘토링).
///
/// ## 보내지 않는 것
///
/// 이름·이메일·좌표·글 본문·검색어 원문은 보내지 않는다. 작품·장소·코스는 **우리 DB 의 id** 로만 적는다.
enum AppEvent: Equatable {
    // MARK: 들어옴

    /// 언어를 골랐다(첫 실행·마이페이지).
    case selectLanguage(String)
    case tutorialBegin
    case tutorialComplete
    /// 가입 — 이 구글 계정으로 처음 로그인했다.
    case signUp(method: String)
    case login(method: String)
    case logout
    case deleteAccount
    /// 닉네임을 정했다·건너뛰었다(MZ2AZ-363). **닉네임 자체는 보내지 않는다.**
    case setNickname(skipped: Bool)

    // MARK: 정보 조회

    /// 화면을 봤다. 탭과 덮개 단위(`search`·`home`·`community`·`courses`·`profile`).
    case screenView(String)
    /// 검색했다. **검색어는 보내지 않는다** — 길이와 갈래만.
    case search(termLength: Int, kind: String)
    case viewTitle(contentId: Int64)
    case viewPlace(placeId: Int64)
    /// 리뷰 시트를 열었다(MZ2AZ-363). `target_type` 은 `place`·`poi`.
    case viewReviews(targetType: String)
    /// 리뷰를 썼다·고쳤다(MZ2AZ-363). **글과 닉네임은 보내지 않는다.**
    case writeReview(targetType: String, rating: Int, photoCount: Int, hasBody: Bool, edited: Bool)
    case deleteReview(targetType: String)
    /// 사진첩을 크게 열었다(MZ2AZ-363). `entry` 는 연 자리 — `detail`·`reviews`·`card`. **사진 주소는 보내지 않는다.**
    case viewPhotos(targetType: String, entry: String)

    // MARK: 담기·찜 (의미 있는 첫 행동)

    case likeTitle(contentId: Int64, liked: Bool)
    case savePlace(placeId: Int64)

    // MARK: 코스 (핵심 지표)

    /// AI 일정 초안을 받았다(아직 저장 전).
    case generatePlan(dayCount: Int, titleCount: Int)
    /// **코스를 만들어 저장했다.** `origin` 은 `ai`·`self`·`review`(후기에서 담음) — 보낼 때 이름은 `course_origin`.
    case createCourse(origin: String, dayCount: Int, placeCount: Int)

    // MARK: 여행 (방한 후 사용 — 리텐션)

    /// 한 장소로 안내를 시작했다.
    case startTrip(placeId: Int64)
    case getDirections
    /// 성지에 도착해 도장이 찍혔다.
    case visitStamp(placeId: Int64)
    case askGuide

    // MARK: 커뮤니티

    case postReview(photoCount: Int, hasCourse: Bool)

    /// GA4 이벤트 이름 — 소문자·밑줄, 40자 이하. GA4 가 미리 정해 둔 이름이 있으면 그것을 쓴다
    /// (`sign_up`·`login`·`search`·`tutorial_begin`·`tutorial_complete`·`screen_view`).
    var name: String {
        switch self {
        case .selectLanguage: "select_language"
        case .tutorialBegin: "tutorial_begin"
        case .tutorialComplete: "tutorial_complete"
        case .signUp: "sign_up"
        case .login: "login"
        case .logout: "logout"
        case .deleteAccount: "delete_account"
        case .setNickname: "set_nickname"
        case .screenView: "screen_view"
        case .search: "search"
        case .viewTitle: "view_title"
        case .viewPlace: "view_place"
        case .viewReviews: "view_reviews"
        case .writeReview: "write_review"
        case .deleteReview: "delete_review"
        case .viewPhotos: "view_photos"
        case .likeTitle: "like_title"
        case .savePlace: "save_place"
        case .generatePlan: "generate_plan"
        case .createCourse: "create_course"
        case .startTrip: "start_trip"
        case .getDirections: "get_directions"
        case .visitStamp: "visit_stamp"
        case .askGuide: "ask_guide"
        case .postReview: "post_review"
        }
    }

    /// 매개변수. 값은 문자열·정수뿐이다(GA4 가 받는 모양).
    var parameters: [String: AnyHashable] {
        switch self {
        case let .selectLanguage(language): ["language": language]
        case let .signUp(method), let .login(method): ["method": method]
        case let .screenView(screen): ["screen_name": screen]
        case let .setNickname(skipped): ["skipped": skipped ? 1 : 0]
        case let .search(termLength, kind): ["term_length": termLength, "kind": kind]
        case let .viewTitle(contentId): ["content_id": contentId]
        case let .viewReviews(targetType), let .deleteReview(targetType): ["target_type": targetType]
        case let .viewPhotos(targetType, entry): ["target_type": targetType, "entry": entry]
        case let .writeReview(targetType, rating, photoCount, hasBody, edited):
            [
                "target_type": targetType, "rating": rating, "photo_count": photoCount,
                "has_body": hasBody ? 1 : 0, "edited": edited ? 1 : 0,
            ]
        case let .viewPlace(placeId), let .savePlace(placeId), let .startTrip(placeId), let .visitStamp(placeId):
            ["place_id": placeId]
        case let .likeTitle(contentId, liked): ["content_id": contentId, "liked": liked ? 1 : 0]
        case let .generatePlan(dayCount, titleCount): ["day_count": dayCount, "title_count": titleCount]
        case let .createCourse(origin, dayCount, placeCount):
            // `origin` 이라고 적지 않는다 — Firebase 가 모든 이벤트에 붙이는 제 `origin`(app·auto)과
            // 이름이 겹쳐 보고서에서 헷갈린다(2026-10-05 실기).
            ["course_origin": origin, "day_count": dayCount, "place_count": placeCount]
        case let .postReview(photoCount, hasCourse): ["photo_count": photoCount, "has_course": hasCourse ? 1 : 0]
        case .tutorialBegin, .tutorialComplete, .logout, .deleteAccount, .getDirections, .askGuide: [:]
        }
    }
}
