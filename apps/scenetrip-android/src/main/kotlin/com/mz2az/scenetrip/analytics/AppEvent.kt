package com.mz2az.scenetrip.analytics

/**
 * 앱이 기록하는 사용 이벤트 (MZ2AZ-353). **이름과 매개변수의 정본은
 * `docs/project/plans/analytics-events.md`** — 이 파일과 iOS 쪽이 그 표를 따른다.
 * 고치면 문서와 두 앱을 같이 고친다.
 *
 * ## 무엇을 재려는가
 *
 * 퍼널: 설치 → 가입 → 정보 조회 → **씬 담기·찜** → **첫 코스 생성**(핵심 지표) → 여행 시작 → 방문 스탬프.
 * 광고를 집행하기 전에 「들어온 사람이 무엇을 하고 다시 오는가」를 알아야 한다(2026-10-01 마케팅 멘토링).
 *
 * ## 보내지 않는 것
 *
 * 이름·이메일·좌표·글 본문·검색어 원문은 보내지 않는다. 작품·장소·코스는 **우리 DB 의 id** 로만 적는다.
 *
 * iOS `Analytics/AppEvent.swift`의 `enum` + `when`을 Kotlin에서는 `sealed class` 로 옮긴다 —
 * 각 하위 타입이 제 이름·매개변수를 직접 들고 있어, 이벤트가 늘 때 하나의 거대한 `when`을
 * 고치지 않아도 된다.
 */
sealed class AppEvent {
    /** GA4 이벤트 이름 — 소문자·밑줄, 40자 이하. GA4 가 미리 정해 둔 이름이 있으면 그것을 쓴다. */
    abstract val name: String

    /** 매개변수. 값은 문자열·정수뿐이다(GA4 가 받는 모양). */
    abstract val parameters: Map<String, Any>

    // 들어옴

    /** 언어를 골랐다(첫 실행·마이페이지). */
    data class SelectLanguage(
        val language: String,
    ) : AppEvent() {
        override val name = "select_language"
        override val parameters = mapOf("language" to language)
    }

    data object TutorialBegin : AppEvent() {
        override val name = "tutorial_begin"
        override val parameters = emptyMap<String, Any>()
    }

    data object TutorialComplete : AppEvent() {
        override val name = "tutorial_complete"
        override val parameters = emptyMap<String, Any>()
    }

    /** 가입 — 이 구글 계정으로 처음 로그인했다. */
    data class SignUp(
        val method: String,
    ) : AppEvent() {
        override val name = "sign_up"
        override val parameters = mapOf("method" to method)
    }

    data class Login(
        val method: String,
    ) : AppEvent() {
        override val name = "login"
        override val parameters = mapOf("method" to method)
    }

    data object Logout : AppEvent() {
        override val name = "logout"
        override val parameters = emptyMap<String, Any>()
    }

    data object DeleteAccount : AppEvent() {
        override val name = "delete_account"
        override val parameters = emptyMap<String, Any>()
    }

    // 정보 조회

    /** 화면을 봤다. 탭과 덮개 단위(`search`·`home`·`community`·`courses`·`profile`). */
    data class ScreenView(
        val screen: String,
    ) : AppEvent() {
        override val name = "screen_view"
        override val parameters = mapOf("screen_name" to screen)
    }

    /** 검색했다. **검색어는 보내지 않는다** — 길이와 갈래만. */
    data class Search(
        val termLength: Int,
        val kind: String,
    ) : AppEvent() {
        override val name = "search"
        override val parameters = mapOf("term_length" to termLength, "kind" to kind)
    }

    data class ViewTitle(
        val contentId: Long,
    ) : AppEvent() {
        override val name = "view_title"
        override val parameters = mapOf("content_id" to contentId)
    }

    data class ViewPlace(
        val placeId: Long,
    ) : AppEvent() {
        override val name = "view_place"
        override val parameters = mapOf("place_id" to placeId)
    }

    // 담기·찜 (의미 있는 첫 행동)

    data class LikeTitle(
        val contentId: Long,
        val liked: Boolean,
    ) : AppEvent() {
        override val name = "like_title"
        override val parameters = mapOf("content_id" to contentId, "liked" to if (liked) 1 else 0)
    }

    data class SavePlace(
        val placeId: Long,
    ) : AppEvent() {
        override val name = "save_place"
        override val parameters = mapOf("place_id" to placeId)
    }

    // 코스 (핵심 지표)

    /** AI 일정 초안을 받았다(아직 저장 전). */
    data class GeneratePlan(
        val dayCount: Int,
        val titleCount: Int,
    ) : AppEvent() {
        override val name = "generate_plan"
        override val parameters = mapOf("day_count" to dayCount, "title_count" to titleCount)
    }

    /**
     * **코스를 만들어 저장했다.** `origin` 은 `ai`·`self`·`review`(후기에서 담음) — 보낼 때
     * 이름은 `course_origin`이다. Firebase 가 모든 이벤트에 제 `origin`(app·auto)을 붙여
     * 겹치므로(iOS 실기 확인, MZ2AZ-353) 그냥 `origin`이라고 적지 않는다.
     */
    data class CreateCourse(
        val origin: String,
        val dayCount: Int,
        val placeCount: Int,
    ) : AppEvent() {
        override val name = "create_course"
        override val parameters = mapOf("course_origin" to origin, "day_count" to dayCount, "place_count" to placeCount)
    }

    // 여행 (방한 후 사용 — 리텐션)

    /** 한 장소로 안내를 시작했다. */
    data class StartTrip(
        val placeId: Long,
    ) : AppEvent() {
        override val name = "start_trip"
        override val parameters = mapOf("place_id" to placeId)
    }

    data object GetDirections : AppEvent() {
        override val name = "get_directions"
        override val parameters = emptyMap<String, Any>()
    }

    /** 성지에 도착해 도장이 찍혔다. */
    data class VisitStamp(
        val placeId: Long,
    ) : AppEvent() {
        override val name = "visit_stamp"
        override val parameters = mapOf("place_id" to placeId)
    }

    data object AskGuide : AppEvent() {
        override val name = "ask_guide"
        override val parameters = emptyMap<String, Any>()
    }

    // 커뮤니티

    data class PostReview(
        val photoCount: Int,
        val hasCourse: Boolean,
    ) : AppEvent() {
        override val name = "post_review"
        override val parameters = mapOf("photo_count" to photoCount, "has_course" to if (hasCourse) 1 else 0)
    }
}
