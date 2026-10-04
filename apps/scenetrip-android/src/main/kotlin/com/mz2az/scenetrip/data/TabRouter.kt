package com.mz2az.scenetrip.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
}

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

    /** 홈이 부른다 — 작품검색 탭으로 가서 그 작품의 상세를 연다. */
    fun openContent(content: ContentSummary) {
        pendingContent = content
        selected = RootTab.SEARCH
    }

    /** 홈이 부른다 — 작품검색 탭으로 가서 그 촬영지의 상세를 연다. */
    fun openPlace(place: PlaceSummary) {
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

            else -> {
                selected = RootTab.HOME
            }
        }
        if (openCourseId != null && openCourseId > 0) pendingCourseId = openCourseId
    }
}
