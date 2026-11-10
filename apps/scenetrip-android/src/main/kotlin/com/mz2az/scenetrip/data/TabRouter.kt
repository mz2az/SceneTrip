package com.mz2az.scenetrip.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.newcourse.NewCourseEntry
import com.mz2az.scenetrip.newcourse.NewCourseEntryRules
import com.mz2az.scenetrip.newcourse.NewCourseStart
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary

/** 3탭(검색·홈·커뮤니티). iOS `RootTabs.Tab`. */
enum class RootTab { SEARCH, HOME, COMMUNITY }

/**
 * 홈이 띄우는 전체 화면 덮개. 탭바에 없는 화면은 여기로 연다.
 *
 * `cover`는 한 번에 하나다 — 마이페이지에서 "경로여정에서 열기"를 누르면 값이
 * [Profile]에서 [Route]로 바뀌고 Compose 가 덮개를 갈아 끼운다. 둘을 겹치지 않는다.
 */
sealed class Cover {
    /** 경로여정. */
    data object Route : Cover()

    data object Profile : Cover()

    /** 「지금 뜨는 작품」 전체 보기 — 인기순 포스터 격자(MZ2AZ-547 H6, 웹 MZ2AZ-534). */
    data object Trending : Cover()

    /** 새 코스 만들기 — 도시로 시작(질문)·작품으로(AI)·담은 곳으로(MZ2AZ-547 P3). */
    data class NewCourse(
        val start: NewCourseStart,
    ) : Cover()
}

/** 방금 「도시로」 만든 코스의 안내 띠(R6) — 계획 화면 위에 6초 뜬다. iOS `TabRouter.JustCreated`. */
data class JustCreated(
    /** 보이는 도시 이름. */
    val city: String,
    val days: Int,
)

/**
 * 탭 사이를 잇는 길 안내. iOS `Models/TabRouter.swift`(`TabRouter.shared`)를 옮긴 것이다.
 *
 * 탭은 셋이다 — 작품검색 · 홈 · 커뮤니티(`docs/project/plans/mobile-home-tab.md`).
 * 경로여정과 마이페이지는 탭에서 내려와 홈이 띄우는 전체 화면 덮개([Cover])가 됐다.
 *
 * [pendingCourseId]·[pendingContent]·[pendingPlace]는 **한 번 쓰고 버리는 쪽지**다 —
 * 받는 화면이 열어 주고 곧장 지운다. 남겨 두면 그 화면에 올 때마다 같은 것이 또 열린다.
 *
 * Context 가 필요 없는 순수 상태라 DI 없이 `object` 싱글턴으로 둔다 — iOS 의
 * `static let shared`와 동치.
 */
object TabRouter {
    var selected by mutableStateOf(RootTab.HOME)
    var cover by mutableStateOf<Cover?>(null)

    /** 경로 탭이 열어 줘야 할 코스의 서버 id. 확인용 뒷문 `-e openCourseId 26`도 이걸 채운다. */
    var pendingCourseId by mutableStateOf<Long?>(null)

    /**
     * 가이드 창의 「코스 추천받기 (AI)」·한도 안내의 「AI 로 짜기 열기」가 남긴다 — 편집 화면이 닫힌 뒤 코스 목록이
     * 일정짜기 마법사를 띄우고 곧장 지운다(iOS `TabRouter.pendingPlanner`).
     */
    var pendingPlanner by mutableStateOf(false)

    /** 홈 "이어서 길찾기" — 코스를 열자마자 첫 미방문 성지로 안내를 켠다. 편집 화면이 읽고 끈다. */
    var pendingTripStart by mutableStateOf(false)

    /**
     * 작품검색 탭이 열어 줘야 할 작품 — 홈의 "지금 뜨는 작품"이 남긴다.
     *
     * id 가 아니라 요약 자체를 넘긴다 — id 만 넘기면 검색 탭이 자기 목록에서 찾아야
     * 하는데, 그 탭에서 한 번이라도 검색한 뒤면 목록이 걸러져 있어 못 찾는다.
     */
    var pendingContent by mutableStateOf<ContentSummary?>(null)

    /** 작품검색 탭이 열어 줘야 할 촬영지 — 홈의 "오늘의 성지"가 남긴다. */
    var pendingPlace by mutableStateOf<PlaceSummary?>(null)

    /**
     * 「새 여행 코스」 시트(F1, 세 갈래)를 홈 위에 띄우는가 — 홈 떠 있는 줄의 「+ 새 코스」·코스 0개의 「여행 코스 짜기」.
     * 코스 목록(경로여정 덮개)의 「코스 추가」 는 그 덮개가 같은 시트를 직접 띄운다.
     */
    var newCourseSheet by mutableStateOf(false)

    /** 방금 「도시로」 만든 코스의 안내 띠(R6). 띠가 6초 뒤 스스로 지운다. */
    var justCreated by mutableStateOf<JustCreated?>(null)

    /** 방금 「도시로」 만든 코스(서버 id) — 계획 화면이 편집 상태로 열고, 저장 없이 나가면 묻고 지운다(E16). */
    var freshCourseId by mutableStateOf<Long?>(null)
        private set

    /** 홈의 전체 보기만 목록을 초기화한다. 일반 검색 탭 선택은 상세 상태를 보존한다. */
    var pendingWorkList by mutableStateOf(false)

    fun openWorkList() {
        pendingContent = null
        pendingPlace = null
        pendingWorkList = true
        selected = RootTab.SEARCH
    }

    fun openCourse(serverId: Long) {
        pendingCourseId = serverId
        cover = Cover.Route
    }

    fun openRoute() {
        cover = Cover.Route
    }

    fun openProfile() {
        cover = Cover.Profile
    }

    /** 새 코스 만들기의 입구 하나 — 홈의 도시 칩(`city`)은 「며칠?」(Q2)부터, 「+ 새 코스」(null)는 새 코스 시트(F1). */
    fun startNewCourse(city: String?) {
        when (val entry = NewCourseEntryRules.entry(city)) {
            NewCourseEntry.Sheet -> newCourseSheet = true
            is NewCourseEntry.Flow -> cover = Cover.NewCourse(entry.start)
        }
    }

    /** 새 코스 시트에서 한 갈래를 골랐다 — 시트를 닫고 그 흐름을 덮개로 연다. */
    fun chooseNewCourse(start: NewCourseStart) {
        newCourseSheet = false
        cover = Cover.NewCourse(start)
    }

    /** 「이 코스로 만들기」·「N곳으로 코스 만들기」 로 서버에 만든 직후 — 방금 만든 코스로 표시하고 안내 띠를 켠 뒤 계획 화면으로. */
    fun courseCreated(
        id: Long,
        city: String,
        days: Int,
    ) {
        freshCourseId = id
        justCreated = JustCreated(city, days)
        openCourse(id)
    }

    /** 이 코스가 방금 「도시로」 만든 코스인가. */
    fun isFreshCourse(id: Long?): Boolean = NewCourseEntryRules.isFresh(freshCourseId, id)

    /** 확인용 뒷문(MZ2AZ-547 P4) — `--ez freshCourse true` 는 `openCourseId` 로 여는 코스를 「방금 만든 코스」 로 연다(E16, iOS `-freshCourse 1`). */
    fun applyFreshBackdoor(fresh: Boolean) {
        if (fresh) freshCourseId = pendingCourseId
    }

    /** 저장했거나 지웠다 — 더는 방금 만든 코스가 아니다. */
    fun clearFreshCourse() {
        freshCourseId = null
    }

    /** 홈 「지금 뜨는 작품」 의 「전체 보기」. */
    fun openTrending() {
        cover = Cover.Trending
    }

    /** 홈이 부른다 — 작품검색 탭으로 가서 그 작품의 상세를 연다. */
    fun openContent(content: ContentSummary) {
        pendingWorkList = false
        pendingPlace = null
        pendingContent = content
        selected = RootTab.SEARCH
    }

    /** 홈이 부른다 — 작품검색 탭으로 가서 그 촬영지의 상세를 연다. */
    fun openPlace(place: PlaceSummary) {
        pendingWorkList = false
        pendingContent = null
        pendingPlace = place
        selected = RootTab.SEARCH
    }

    /**
     * 확인용 뒷문 — `adb shell am start -e initialTab profile`로 첫 화면을 지정한다.
     * iOS `simctl launch … -initialTab profile`과 짝이다. `route`·`profile`은 탭이
     * 아니라 홈 위의 덮개이므로 홈 + 덮개로 푼다. 인자가 없으면 홈이다.
     */
    fun applyInitialTab(
        tab: String?,
        openCourseId: Long?,
    ) {
        when (tab) {
            "search" -> {
                selected = RootTab.SEARCH
            }

            "community" -> {
                selected = RootTab.COMMUNITY
            }

            "route" -> {
                selected = RootTab.HOME
                cover = Cover.Route
            }

            "profile" -> {
                selected = RootTab.HOME
                cover = Cover.Profile
            }

            "trending" -> {
                selected = RootTab.HOME
                cover = Cover.Trending
            }

            else -> {
                selected = RootTab.HOME
            }
        }
        if (openCourseId != null && openCourseId > 0) pendingCourseId = openCourseId
    }

    /**
     * 확인용 뒷문(MZ2AZ-547 P3) — `-e newCourse sheet|city|works|saved` 는 새 코스 시트·흐름을, `-e cityFlow 부산` 은 그 도시의
     * 「며칠?」 부터 연다(iOS `-newCourse`·`-cityFlow`). 값이 없으면 아무것도 하지 않는다.
     */
    fun applyNewCourseBackdoor(
        newCourse: String?,
        cityFlow: String?,
    ) {
        when (newCourse) {
            "sheet" -> newCourseSheet = true
            "city" -> cover = Cover.NewCourse(NewCourseStart.City(null))
            "works" -> cover = Cover.NewCourse(NewCourseStart.Works)
            "saved" -> cover = Cover.NewCourse(NewCourseStart.Saved)
        }
        if (!cityFlow.isNullOrBlank()) {
            selected = RootTab.HOME
            cover = Cover.NewCourse(NewCourseStart.City(cityFlow))
        }
    }
}
