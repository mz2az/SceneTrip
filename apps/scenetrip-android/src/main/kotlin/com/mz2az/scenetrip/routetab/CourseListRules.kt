package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.newcourse.NewCourseText
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary

// 코스 목록(「내 여행」)의 순수 규칙 (MZ2AZ-547 P7 — L1–L4, 계획 docs/project/plans/ios-web-parity.md).
// iOS `RouteTab/CourseListRules.swift`, 웹 `features/proto/ProtoCourse.tsx`. 화면(`RouteTabView`)은 얇게 두고 판단은 여기서 한다.

/**
 * 여러 개 고르기(L4). 줄을 꾹(0.2초) 누르면 켜지고 그 줄이 골라진다. 켜진 동안 줄을 누르면 체크만 바뀐다.
 * 값을 바꾸지 않고 새 값을 돌려준다(Compose 상태에 그대로 넣는다).
 *
 * [picked] 가 **null 이면 꺼져 있다** — 켜져 있는데 하나도 안 고른 것(빈 집합)과 다르다.
 */
data class CourseListPicking(
    val picked: Set<String>? = null,
) {
    val isOn: Boolean get() = picked != null

    val count: Int get() = picked?.size ?: 0

    fun isPicked(key: String): Boolean = picked?.contains(key) ?: false

    /** 꾹 눌렀다 — 꺼져 있으면 켜고 그 줄을 고른다. 이미 켜져 있으면 그대로다. */
    fun hold(key: String): CourseListPicking = if (picked == null) copy(picked = setOf(key)) else this

    /** 켜진 동안 줄을 눌렀다 — 그 줄의 체크를 뒤집는다. 꺼져 있으면 아무 일도 없다. */
    fun toggle(key: String): CourseListPicking {
        val current = picked ?: return this
        return copy(picked = if (key in current) current - key else current + key)
    }

    /** 지금 목록을 다 골랐나. 꺼져 있거나 목록이 비면 아니다. */
    fun allPicked(keys: List<String>): Boolean {
        val current = picked ?: return false
        return keys.isNotEmpty() && keys.all { it in current }
    }

    /** 머리의 [전체 선택 / 선택 해제] — 다 골랐으면 모두 풀고(켜진 채 0개), 아니면 목록 전부를 고른다. */
    fun toggleAll(keys: List<String>): CourseListPicking = copy(picked = if (allPicked(keys)) emptySet() else keys.toSet())

    /** [취소]·지우기를 마쳤다 — 끈다. */
    fun cancel(): CourseListPicking = copy(picked = null)

    /** 목록이 바뀌었다(지워짐·새로 받음) — 목록에 없는 열쇠를 뺀다. 꺼져 있으면 그대로다. */
    fun keepOnly(keys: List<String>): CourseListPicking {
        val current = picked ?: return this
        return copy(picked = current intersect keys.toSet())
    }
}

/** 코스 목록의 글자. */
object CourseListText {
    /** 머리 가운데 — 꺼져 있으면 「내 여행」, 켜져 있으면 「N개 선택」(0개면 「코스 선택」). */
    fun headerTitle(picking: CourseListPicking): String =
        when {
            !picking.isOn -> tr("내 여행")
            picking.count > 0 -> tr("%d개 선택").format(picking.count)
            else -> tr("코스 선택")
        }

    /** 머리 오른쪽 — 다 골랐으면 「선택 해제」, 아니면 「전체 선택」. */
    fun allButton(allPicked: Boolean): String = if (allPicked) tr("선택 해제") else tr("전체 선택")

    /** 아래 빨간 단추 — 「N개 삭제」, 지우는 동안은 「지우는 중…」. */
    fun deleteButton(
        count: Int,
        removing: Boolean,
    ): String = if (removing) tr("지우는 중…") else tr("%d개 삭제").format(count)

    /** 여러 개 지우기 확인 제목 — 「코스 N개를 지울까요?」. */
    fun bulkConfirmTitle(count: Int): String = tr("코스 %d개를 지울까요?").format(count)

    /** 줄의 둘째 줄 — 「2일차 · 1박 2일 · 5곳」. 「N일차」 는 여행 중에만(모르면 1일차), 기간은 당일치기·N박 N+1일. */
    fun subtitle(
        isRunning: Boolean,
        currentDayNo: Int?,
        dayCount: Int,
        placeCount: Int,
    ): String =
        buildList {
            if (isRunning) add(tr("%d일차").format(currentDayNo ?: 1))
            add(NewCourseText.days(dayCount))
            add(tr("%d곳").format(placeCount))
        }.joinToString(" · ")
}

object CourseListRules {
    /** 꾹 누르기 — 웹 `ProtoCourse.tsx` 와 같은 0.2초. */
    const val HOLD_MILLIS = 200L

    /** 꾹 누르는 동안 이만큼(dp) 넘게 움직이면 누르기가 아니라 스크롤이다. */
    const val HOLD_SLOP_DP = 8f

    /** 작품 줄에 적는 작품 수의 상한. */
    const val WORK_LIMIT = 2

    /** 작품을 찾으려고 들여다보는 앞의 장소 수 — 코스마다 앞의 세 곳만(호출이 코스 수 × 장소 수로 불지 않게). */
    const val WORK_STOPS = 3

    /** 줄의 열쇠 — 서버 id 로 「s123」. 목록을 다시 받아도 같은 코스는 같은 열쇠다. */
    fun key(course: CourseSummary): String = "s${course.id}"

    /** 꾹 누르기로 친다 — [HOLD_MILLIS] 이상 눌렀고 [HOLD_SLOP_DP] 안에서만 움직였다. */
    fun isHold(
        elapsedMillis: Long,
        travelDp: Float,
    ): Boolean = elapsedMillis >= HOLD_MILLIS && travelDp <= HOLD_SLOP_DP

    /** 「여행 중」·「다가오는 여행」 두 묶음. 목록 순서를 지킨다. */
    fun sections(courses: List<CourseSummary>): Pair<List<CourseSummary>, List<CourseSummary>> =
        courses.partition { it.status == CourseStatus.active }

    /** 지울 코스 — 고른 것만, **목록 순서대로**. */
    fun doomed(
        courses: List<CourseSummary>,
        picking: CourseListPicking,
    ): List<CourseSummary> = courses.filter { picking.isPicked(key(it)) }

    /** 작품 줄 — 장소마다 첫 작품을 차례로 모아 겹치지 않게, 최대 [WORK_LIMIT] 개. 작품을 모르는 곳(null·빈 글)은 건너뛴다. */
    fun works(
        stops: List<RouteStop>,
        titleOf: (RouteStop) -> String?,
    ): List<String> {
        val found = mutableListOf<String>()
        for (stop in stops) {
            if (found.size >= WORK_LIMIT) break
            val title = titleOf(stop)
            if (title.isNullOrEmpty() || title in found) continue
            found += title
        }
        return found
    }

    /** 줄 사진 — 서버가 준 대표 사진(`thumbnailUrl`)이 먼저, 없으면 상세의 첫 사진 있는 장소. */
    fun photo(
        thumbnail: String?,
        stops: List<RouteStop>,
    ): String? {
        if (!thumbnail.isNullOrEmpty()) return thumbnail
        return stops.firstNotNullOfOrNull { stop ->
            stop.place.imageUrl
                ?.toString()
                ?.takeIf { it.isNotEmpty() }
        }
    }

    /** 고른 코스를 **하나씩 차례로** 지운다 — 서버에 여러 개 지우기가 없다. 앞의 것이 끝나야 다음을 부른다. */
    suspend fun <T> removeOneByOne(
        doomed: List<T>,
        delete: suspend (T) -> Unit,
    ) {
        for (item in doomed) delete(item)
    }
}
