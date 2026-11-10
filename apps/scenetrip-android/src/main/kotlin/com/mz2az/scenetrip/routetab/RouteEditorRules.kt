package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary

// 계획(코스 편집) 화면의 순수 규칙 (MZ2AZ-547 P4 E1–E17, iOS `RouteTab/RouteEditorRules.swift` 를 옮겼다).
// 웹 `route/editor/RouteEditor.tsx`·`proto/ProtoAddMenu.tsx`·`proto/protoCourseAdd.tsx`·`route/map/RouteMap.tsx` 가 명세다.
// 화면(`RouteEditorView`·`RouteEditorModes`·`CourseAddViews`)은 이 답을 그대로 따른다. Compose·Android 에 기대지 않아 JVM 에서 시험한다.

/** 계획 화면이 **보기**인가 **고치기**인가 (E1, 웹 `RouteEditor.tsx` 의 `editing`). */
enum class RouteEditorMode { VIEWING, EDITING }

/** 계획 화면의 보기/편집 규칙 (E1·E2·E3·E14·E16). */
object RouteEditorRules {
    /** 처음 열 때 — 새 초안(isNew)·방금 「도시로」 만든 코스(fresh)는 편집, 저장된 코스는 보기. */
    fun initialMode(
        isNew: Boolean,
        isFresh: Boolean,
    ): RouteEditorMode = if (isNew || isFresh) RouteEditorMode.EDITING else RouteEditorMode.VIEWING

    /** 편집 중 「취소」(뒤로 포함)를 눌렀을 때 할 일. */
    enum class LeaveDecision {
        /** 묻지 않고 화면을 닫는다 — 새 초안인데 바꾼 것이 없다. */
        CLOSE,

        /** 묻지 않고 편집 전으로 되돌려 보기로 — 저장된 코스인데 바꾼 것이 없다. */
        REVERT_TO_VIEW,

        /** 「바꾼 내용을 버릴까요?」 → 「버리기」 면 화면을 닫는다(새 초안). */
        CONFIRM_DISCARD_THEN_CLOSE,

        /** 「바꾼 내용을 버릴까요?」 → 「버리기」 면 편집 전으로 되돌려 보기로(저장된 코스). */
        CONFIRM_DISCARD_THEN_REVERT,

        /** 「저장하지 않고 나갈까요? / 방금 만든 코스가 지워져요.」 → 「나가기」 면 서버에서 지우고 닫는다(방금 만든 코스). */
        CONFIRM_LEAVE_AND_DELETE,
    }

    /**
     * [changed] — 새 초안이면 빈 틀이 아닌가, 저장된 코스면 편집을 시작한 뒤로 바뀐 것이 있는가.
     * 방금 만든 코스는 바꾼 것이 없어도 묻는다(저장해야 남는다).
     */
    fun cancel(
        isNew: Boolean,
        isFresh: Boolean,
        changed: Boolean,
    ): LeaveDecision =
        when {
            isFresh && !isNew -> LeaveDecision.CONFIRM_LEAVE_AND_DELETE
            isNew -> if (changed) LeaveDecision.CONFIRM_DISCARD_THEN_CLOSE else LeaveDecision.CLOSE
            else -> if (changed) LeaveDecision.CONFIRM_DISCARD_THEN_REVERT else LeaveDecision.REVERT_TO_VIEW
        }

    /** 「저장」/「만들기」 가 성공한 뒤. */
    enum class SaveOutcome {
        /** 화면을 닫는다 — 새 초안의 「만들기」. */
        CLOSE,

        /** 화면에 남아 보기로 — 저장된 코스(방금 만든 코스 포함)의 「저장」. */
        VIEW,
    }

    fun afterSave(isNew: Boolean): SaveOutcome = if (isNew) SaveOutcome.CLOSE else SaveOutcome.VIEW

    /** 편집 도구를 보이나 — 일차 ＋/−, 동작 줄, 끌기, 밀어서 빼기, 출발/도착 고정, 편의시설 칩, 떠 있는 ＋ (E2). */
    fun showsEditTools(mode: RouteEditorMode): Boolean = mode == RouteEditorMode.EDITING

    /** 아래 줄(「코스 시작」 등)을 보이나 — 저장된 코스를 고치는 동안은 숨긴다(E3). 새 초안은 「코스 만들기」 가 있어 늘 보인다. */
    fun showsBottomBar(
        mode: RouteEditorMode,
        isNew: Boolean,
    ): Boolean = isNew || mode == RouteEditorMode.VIEWING

    /** 지도의 「내 위치」 단추 윗변(dp) — 편집 중이면 칩 줄 아래(62), 보기면 맨 위(10) (E14). */
    fun locateTop(mode: RouteEditorMode): Int = if (mode == RouteEditorMode.EDITING) 62 else 10
}

/** 계획 화면 지도를 **다시 맞추는가** (E12·E13, 웹 `RouteMap.tsx` 카메라 규칙). */
object RouteCameraRules {
    enum class Change {
        /** 다른 일차를 골랐다. */
        DAY_SWITCH,

        /** 장소를 넣었다(지도에서·보관함·핀). */
        ADD,

        /** 끌어서 순서를 바꿨다. */
        REORDER,

        /** 줄을 뺐다. */
        REMOVE,

        /** 동선 최적화. */
        OPTIMIZE,

        /** 일정 서랍 높이만 바뀌었다. */
        SHEET_HEIGHT,

        /** 지도를 처음 펼쳤다. */
        MAP_FIRST_OPEN,

        /** 접었던 지도를 다시 펼쳤다. */
        MAP_REOPEN,

        /** 장소·편의시설 정보 카드를 ✕ 로 닫았다(고른 표시만 놓는다, 2026-11-10 사용자 지적). */
        CARD_CLOSE,
    }

    /** 일차 바꾸기·장소 넣기·처음 펼치기는 맞춘다. 순서 끌기·빼기·최적화·서랍 높이·다시 펼치기·카드 닫기는 확대와 위치를 그대로 둔다. */
    fun refits(change: Change): Boolean =
        when (change) {
            Change.DAY_SWITCH, Change.ADD, Change.MAP_FIRST_OPEN -> true
            Change.REORDER, Change.REMOVE, Change.OPTIMIZE, Change.SHEET_HEIGHT, Change.MAP_REOPEN, Change.CARD_CLOSE -> false
        }

    /**
     * 지도가 카메라를 옮길까 — 열쇠([RouteCameraKey])가 바뀌었거나 [fitChanged](맞춤 신호가 올랐다)일 때.
     * 단 **고른 곳을 놓기만 한 것**(코스 줄·편의시설 카드 ✕, 다른 것은 그대로)은 옮기지 않는다. 처음([previous] null)은 옮긴다.
     */
    fun moves(
        previous: RouteCameraKey?,
        next: RouteCameraKey,
        fitChanged: Boolean,
    ): Boolean {
        if (fitChanged) return true
        if (previous == null) return true
        if (previous == next) return false
        if (previous.context != next.context) return true
        val focusedKept = previous.focused == next.focused
        val pickedKept = previous.picked == next.picked
        val focusedReleased = previous.focused != null && next.focused == null
        val pickedReleased = previous.picked != null && next.picked == null
        val onlyReleased = (focusedReleased || focusedKept) && (pickedReleased || pickedKept)
        return !onlyReleased
    }
}

/** 계획 화면 지도의 **카메라 열쇠** — 무엇을 보고 있나. 같으면 카메라를 다시 옮기지 않는다. */
data class RouteCameraKey(
    /** 목록·번호 핀에서 고른 코스 줄(id). */
    val focused: String? = null,
    /** 정보 카드가 떠 있는 편의시설·검색 결과(id). */
    val picked: String? = null,
    /** 그 밖의 것 — 내 위치 토글·담을까 보는 곳·AI 장소 목록. */
    val context: String = "",
)

/**
 * 계획 화면의 「지도」 알약 (E4·E5). 기본은 목록 전체(지도 접힘), 여행 중이면 펼친 채로 연다.
 * Compose 상태에 그대로 담기게 바꾸지 않는 값이다 — 동작마다 다음 값과 「이번에 처음 펼쳤나」 를 돌려준다([Step]).
 */
data class RouteMapToggle(
    val isOpen: Boolean,
    /** 코스 전체에 한 번 맞췄나 — 처음 펼칠 때만 맞춘다(접힌 동안 맞춘 화면은 엉뚱하다). */
    val fitted: Boolean,
) {
    /** 동작 하나의 결과 — 다음 상태와, 이번이 처음 펼친 것이라 코스 전체에 맞춰야 하는가. */
    data class Step(
        val toggle: RouteMapToggle,
        val fits: Boolean,
    )

    /** 알약 글자 — 접혀 있으면 「지도」, 펼쳐져 있으면 「목록만」. */
    enum class Pill { MAP, LIST_ONLY }

    val pill: Pill get() = if (isOpen) Pill.LIST_ONLY else Pill.MAP

    /** 펼친다. */
    fun open(): Step =
        if (fitted) Step(copy(isOpen = true), fits = false) else Step(RouteMapToggle(isOpen = true, fitted = true), fits = true)

    fun close(): RouteMapToggle = copy(isOpen = false)

    /** 알약을 눌렀다 — 펼쳐져 있으면 접고, 접혀 있으면 펼친다. */
    fun toggle(): Step = if (isOpen) Step(close(), fits = false) else open()

    /** 줄을 눌렀다·핀 찍기를 켰다 — 접혀 있으면 펼친다(펼쳐져 있으면 그대로). */
    fun reveal(): Step = if (isOpen) Step(this, fits = false) else open()

    companion object {
        /** 여행 중([running])이면 펼친 채로(이미 맞춘 것으로) 연다. */
        fun initial(running: Boolean): RouteMapToggle = RouteMapToggle(isOpen = running, fitted = running)
    }
}

/** 「보관함에서 추가」 의 고르기 (E10) — 고른 순서를 기억한다(넣을 때 그 순서대로). 바꾸지 않는 값. */
data class CourseAddPicker(
    val picked: List<Long> = emptyList(),
) {
    /** 고르거나 푼다. 풀면 뒤의 번호가 하나씩 당겨진다. */
    fun toggle(id: Long): CourseAddPicker = CourseAddPicker(if (id in picked) picked - id else picked + id)

    /** 고른 순서 번호(1부터). 안 골랐으면 null. */
    fun order(id: Long): Int? = picked.indexOf(id).takeIf { it >= 0 }?.plus(1)

    /** 넣을 수 있는 곳([free])을 전부 골랐나. 넣을 수 있는 곳이 없으면 false. */
    fun allPicked(free: List<Long>): Boolean = free.isNotEmpty() && free.all { it in picked }

    /** 「모두 고르기 / 모두 풀기」 — 다 골라져 있으면 전부 풀고, 아니면 [free] 순서대로 전부 고른다. */
    fun toggleAll(free: List<Long>): CourseAddPicker = CourseAddPicker(if (allPicked(free)) emptyList() else free)

    /** 고른 곳을 고른 순서대로. [places] 에 없는 id 는 건너뛴다. */
    fun ordered(places: List<PlaceSummary>): List<PlaceSummary> = picked.mapNotNull { id -> places.firstOrNull { it.id == id } }

    /** 아래 고정 단추 — 고르기 전 「넣을 곳을 골라 주세요」(흐림), 고른 뒤 「N곳 선택 완료」. */
    sealed interface Done {
        data object PickFirst : Done

        data class Picked(
            val count: Int,
        ) : Done
    }

    val done: Done get() = if (picked.isEmpty()) Done.PickFirst else Done.Picked(picked.size)
}

/** 장소 넣기 두 갈래(지도에서 추가·보관함에서 추가)가 함께 쓰는 규칙 (E9·E10). */
object CourseAddRules {
    data class Split(
        /** 넣을 수 있는 곳. */
        val free: List<PlaceSummary>,
        /** 이미 코스에 있는 곳(흐림). */
        val inCourse: List<PlaceSummary>,
    )

    /** 보관함을 「담아 둔 곳」 과 「이미 코스에 있는 곳」 으로 가른다. 각자 원래 순서. */
    fun split(
        places: List<PlaceSummary>,
        taken: Set<Long>,
    ): Split = Split(places.filterNot { it.id in taken }, places.filter { it.id in taken })

    /** 장소 상세의 「코스에 추가」 단추 상태. */
    sealed interface Button {
        /** 「＋ 코스에 추가」. */
        data object Add : Button

        /** 「이미 코스에 있어요」(흐림, 못 누름). */
        data object Already : Button

        /** 「✓ N일차에 넣었어요」 — 이 화면에서 방금 넣었다. [day] 는 0부터. */
        data class Added(
            val day: Int,
        ) : Button
    }

    /** 방금 넣은 것이 「이미 있음」 보다 먼저다. */
    fun button(
        placeId: Long,
        taken: Set<Long>,
        addedTo: Map<Long, Int>,
    ): Button {
        addedTo[placeId]?.let { return Button.Added(it) }
        return if (placeId in taken) Button.Already else Button.Add
    }

    /** 일차 고르기 줄 오른쪽 — 그 일차에 든 곳 수. */
    sealed interface DayCaption {
        data object Empty : DayCaption

        data class Places(
            val count: Int,
        ) : DayCaption
    }

    fun dayCaption(count: Int): DayCaption = if (count > 0) DayCaption.Places(count) else DayCaption.Empty

    data class Adding(
        val course: RouteCourse,
        /** 실제로 넣은 수. */
        val added: Int,
    )

    /** 고른 일차의 끝에 고른 순서대로 넣는다. 코스(어느 일차든)에 이미 있는 곳은 거른다(`RouteDedupe`). 일차가 범위 밖이면 그대로 두고 0. */
    fun adding(
        places: List<PlaceSummary>,
        day: Int,
        course: RouteCourse,
    ): Adding {
        if (day !in course.days.indices) return Adding(course, 0)
        val fresh = RouteDedupe.fresh(places.map { RouteStop(place = it) }, course.stops)
        val days = course.days.toMutableList()
        days[day] = days[day].copy(stops = days[day].stops + fresh)
        return Adding(course.copy(days = days), fresh.size)
    }
}

/** 계획 화면 지도의 코스 번호 핀 모양 (E11, 웹 `proto/protoPoi.tsx` 의 `protoNumberedPinSvg`, iOS `RouteNumberPin`). */
object RouteNumberPinSpec {
    /** 그림 폭·높이(dp). */
    const val WIDTH = 30f
    const val HEIGHT = 38f

    /** 꼬리 끝 — 지도 좌표가 닿는 점. */
    const val TIP_X = 15f
    const val TIP_Y = 37f

    /** 고른 핀은 1.2배. */
    const val SELECTED_SCALE = 1.2f

    /** 시그니처 보라 `#5c4ad6`. */
    const val PURPLE = 0xFF5C4AD6

    /** 마커 기준점(0…1) — 꼬리 끝. */
    val anchorX: Float get() = TIP_X / WIDTH
    val anchorY: Float get() = TIP_Y / HEIGHT

    /** 번호 글자 크기 — 한 자리 13, 두 자리 11.5, 세 자리 이상 9. */
    fun fontSize(number: Int): Float =
        when {
            number < 10 -> 13f
            number < 100 -> 11.5f
            else -> 9f
        }
}

/** 코스 여는 중 덮개·실패 띠 (E17, iOS `RouteTabView` 의 `opening.isLoading` 덮개). */
object CourseOpeningRules {
    /** 상세를 받는 동안 목록을 덮개+스피너로 덮는다(머리까지 덮어 두 번 누르지 못하게). */
    fun showsOverlay(state: RouteOpeningState): Boolean = state.isLoading

    /** 「코스를 불러오지 못했어요 · 다시 시도」 띠 — 실패했고, 다시 여는 중이 아닐 때만(목록 위에 뜬다). */
    fun showsFailure(state: RouteOpeningState): Boolean = state.failedCourse != null && !state.isLoading
}
