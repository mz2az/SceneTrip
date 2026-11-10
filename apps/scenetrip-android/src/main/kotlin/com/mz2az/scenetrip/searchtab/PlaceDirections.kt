package com.mz2az.scenetrip.searchtab

import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.routetab.RouteDay
import com.mz2az.scenetrip.routetab.RouteStop
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary

/**
 * 촬영지 상세 「길찾기」 (MZ2AZ-532 → MZ2AZ-547 G3, iOS `SearchTab/PlaceDirections.swift`, 웹 `features/route/placeDirections.ts`).
 *
 * 촬영지 한 곳만 가 보고 싶은 사람에게 코스 만들기는 병목이다. 누르면 그 장소 하나로 된 1일 코스를 만들고
 * (「직접 짜기」 저장과 같은 길), 여행 중으로 바꾼 뒤(서버는 여행 중 코스에만 길찾기를 준다), 그 코스를 열어
 * 안내를 켠다. 비회원이면 로그인 시트를 먼저 띄우고, 로그인되면 같은 장소로 이어 간다. 서버·계약은 그대로다.
 */
object PlaceDirectionsRules {
    /** 계약 `CourseCreate`·`CourseReplace` 의 title maxLength. 넘기면 POST 가 400 이다. */
    const val TITLE_MAX = 50

    /**
     * 「{장소} 가는 길」 / "Trip to {place}". 50자를 넘으면 장소 이름 끝을 말줄임(…)으로 잘라 50자 안에 둔다.
     * 길이는 UTF-16 단위로 잰다 — 서버(자바 `String.length`)·웹·iOS 가 재는 방식이다. 글자(자소 묶음) 중간은 자르지 않는다.
     */
    fun title(
        name: String,
        lang: Lang = AppLanguage.current,
    ): String {
        val full = format(name, lang)
        if (full.length <= TITLE_MAX) return full
        // 글자(자소 묶음) 단위로 하나씩 붙여 본다 — 이모지·결합 문자를 반으로 자르지 않게.
        val breaks = java.text.BreakIterator.getCharacterInstance()
        breaks.setText(name)
        var end = 0
        var next = breaks.next()
        while (next != java.text.BreakIterator.DONE) {
            if (format(name.substring(0, next) + "…", lang).length > TITLE_MAX) break
            end = next
            next = breaks.next()
        }
        return format(name.substring(0, end).trimEnd() + "…", lang)
    }

    private fun format(
        name: String,
        lang: Lang,
    ): String = tr("%s 가는 길", lang).format(name)

    /** 그 장소 하나로 된 1일 코스(아직 저장 전). */
    fun course(
        place: PlaceSummary,
        lang: Lang = AppLanguage.current,
    ): RouteCourse = RouteCourse(title = title(place.name, lang), days = listOf(RouteDay(stops = listOf(RouteStop(place = place)))))

    /** 이 장소 하나뿐인 1일 코스인가 — 다시 열어도 되는 「가는 길」. 사용자가 고쳐 다른 곳을 더했으면 아니다. */
    fun isDirectionsCourse(
        course: RouteCourse,
        placeId: Long,
    ): Boolean {
        val only =
            course.days
                .singleOrNull()
                ?.stops
                ?.singleOrNull() ?: return false
        return only.poiId == null && only.customPinId == null && !only.isPinned && only.place.id == placeId
    }

    /**
     * 받아 둔 코스 목록(요약)에서 이 장소의 「가는 길」일 수 있는 것 — 여행 중·1일·1곳·제목이 같은 것의 서버 id.
     * 진짜 그 장소인지는 속을 읽어 확인한다([isDirectionsCourse]).
     */
    fun candidates(
        courses: List<CourseSummary>,
        place: PlaceSummary,
        lang: Lang = AppLanguage.current,
    ): List<Long> {
        val wanted = title(place.name, lang)
        return courses
            .filter { it.status == CourseStatus.active && it.dayCount == 1 && it.placeCount == 1 && it.title == wanted }
            .map { it.id }
    }
}

/** 한 걸음의 결과 — 실패면 화면에 보일 한 줄. */
sealed interface PlaceDirectionsStep {
    data object Ok : PlaceDirectionsStep

    data class Failed(
        val message: String,
    ) : PlaceDirectionsStep
}

/** 「길찾기」를 누른 결과. */
sealed interface PlaceDirectionsOutcome {
    data class Started(
        val courseId: Long,
    ) : PlaceDirectionsOutcome

    data object Cancelled : PlaceDirectionsOutcome

    /** 이미 진행 중이라(로그인 시트가 떠 있는 동안 포함) 이번 누름은 버렸다. */
    data object Busy : PlaceDirectionsOutcome

    data class Failed(
        val message: String,
    ) : PlaceDirectionsOutcome
}

/** 바깥과 닿는 것 — 시험이 가짜로 바꿔 끼운다. */
interface PlaceDirectionsDeps {
    fun isSignedIn(): Boolean

    /** 로그인 시트를 띄우고 결과를 기다린다. 로그인되면 true, 로그인 없이 시트가 내려가면 false. */
    suspend fun askSignIn(): Boolean

    /** 새 코스를 서버에 만든다. 실패면 화면에 보일 한 줄을 담은 [Result.failure]. */
    suspend fun createCourse(course: RouteCourse): Result<RouteCourse>

    /** 코스를 여행 중(1일차)으로 바꾼다. */
    suspend fun startCourse(course: RouteCourse): PlaceDirectionsStep

    /** 그 코스를 열며 안내를 켠다. */
    fun openTrip(courseId: Long)

    /** 장소 id → 이 기능이 전에 만든 코스 id. 상세를 닫았다 다시 열어도 남는다. */
    val made: MutableMap<Long, Long>

    /** 받아 둔 코스 목록에서 이 장소의 「가는 길」 후보. */
    fun existingCandidates(place: PlaceSummary): List<Long>

    /** 코스 하나의 속(읽기만). 없거나 못 받으면 null. */
    suspend fun loadCourse(courseId: Long): RouteCourse?
}

/** 「길찾기」 실패 한 줄을 실어 나르는 예외 — [PlaceDirectionsDeps.createCourse] 의 실패에 쓴다. */
class PlaceDirectionsFailure(
    override val message: String,
) : Exception(message)

/** 촬영지 상세 하나에 하나. 장소가 바뀌거나 화면을 떠나면 [dispose] 하고 새로 만든다. 메인 스레드에서만 부른다. */
class PlaceDirections(
    private val deps: PlaceDirectionsDeps,
) {
    private var running = false
    private var disposed = false

    /** 「길찾기」를 눌렀다. 진행 중이면(로그인 시트가 떠 있는 동안 포함) 새 누름은 버린다. */
    suspend fun start(place: PlaceSummary): PlaceDirectionsOutcome {
        if (running || disposed) return PlaceDirectionsOutcome.Busy
        running = true
        try {
            return run(place)
        } finally {
            running = false
        }
    }

    /** 화면을 떠났다 — 기다리던 로그인이 끝나도 이어 가지 않는다. */
    fun dispose() {
        disposed = true
    }

    private suspend fun run(place: PlaceSummary): PlaceDirectionsOutcome {
        if (!deps.isSignedIn()) {
            val signedIn = deps.askSignIn()
            if (!signedIn || disposed) return PlaceDirectionsOutcome.Cancelled
        }
        var course = reusable(place)
        if (disposed) return PlaceDirectionsOutcome.Cancelled
        if (course == null) {
            val created =
                deps.createCourse(PlaceDirectionsRules.course(place)).getOrElse {
                    return PlaceDirectionsOutcome.Failed(it.message ?: tr("코스를 불러오지 못했어요"))
                }
            val id = created.serverId ?: return PlaceDirectionsOutcome.Failed(tr("코스를 불러오지 못했어요"))
            deps.made[place.id] = id
            course = created
            // 만드는 사이 화면을 떠났다 — 여행 중으로 바꾸지 않는다(다시 누르면 이 코스를 쓴다).
            if (disposed) return PlaceDirectionsOutcome.Cancelled
        }
        val courseId = course.serverId ?: return PlaceDirectionsOutcome.Failed(tr("코스를 불러오지 못했어요"))
        if (!course.isRunning) {
            val step = deps.startCourse(course)
            if (step is PlaceDirectionsStep.Failed) return PlaceDirectionsOutcome.Failed(step.message)
            if (disposed) return PlaceDirectionsOutcome.Cancelled
        }
        deps.openTrip(courseId)
        return PlaceDirectionsOutcome.Started(courseId)
    }

    /**
     * 다시 쓸 코스 — 이 기능이 전에 만든 것(기억), 그다음 받아 둔 목록의 여행 중 「가는 길」. 진짜 이 장소
     * 하나뿐인지 속을 읽어 확인한다. 지워졌거나 고쳐 다른 코스가 된 기억은 버린다.
     */
    private suspend fun reusable(place: PlaceSummary): RouteCourse? {
        val remembered = deps.made[place.id]
        val ids = listOfNotNull(remembered) + deps.existingCandidates(place).filter { it != remembered }
        for (id in ids) {
            val course = deps.loadCourse(id)
            if (course?.serverId != null && PlaceDirectionsRules.isDirectionsCourse(course, place.id)) return course
            if (id == remembered) deps.made.remove(place.id)
        }
        return null
    }
}
