package com.mz2az.scenetrip.searchtab

import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.routetab.RouteBridge
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import kotlinx.coroutines.delay

/**
 * 「길찾기」의 실제 의존성 (MZ2AZ-547 G3, iOS `LivePlaceDirectionsDeps`) — 로그인은 [AuthStore], 코스는 검색 탭의 [RouteStore],
 * 열기는 [TabRouter](홈 「이어서 길찾기」와 같은 길 — 경로여정을 열어 그 코스로 들어가고, 열리자마자 안내를 켠다).
 */
class LivePlaceDirectionsDeps(
    private val routes: RouteStore,
) : PlaceDirectionsDeps {
    override val made: MutableMap<Long, Long> get() = remembered

    override fun isSignedIn(): Boolean = AuthStore.signedIn

    /** 로그인 시트를 이유 한 줄과 함께 띄우고, 로그인되거나 시트가 내려갈 때까지 기다린다. */
    override suspend fun askSignIn(): Boolean {
        AuthStore.promptSignIn(reason = tr("로그인하면 바로 길안내를 시작해요"))
        // 시트는 다음 틀에 뜬다 — 뜨기 전에 「내려갔다」 로 읽지 않게 잠깐 기다린다.
        var waited = 0
        while (!AuthStore.showingSignIn && !AuthStore.signedIn && waited < SHOW_WAIT_MS) {
            delay(POLL_MS)
            waited += POLL_MS.toInt()
        }
        while (true) {
            if (AuthStore.signedIn) return true
            if (!AuthStore.showingSignIn) return false
            delay(POLL_MS)
        }
    }

    override suspend fun createCourse(course: RouteCourse): Result<RouteCourse> {
        val saved = routes.save(course, origin = "place_directions")
        return if (saved != null) Result.success(saved) else Result.failure(PlaceDirectionsFailure(lastFailure()))
    }

    override suspend fun startCourse(course: RouteCourse): PlaceDirectionsStep =
        if (routes.setRunning(course, true, dayNo = 1)) {
            PlaceDirectionsStep.Ok
        } else {
            PlaceDirectionsStep.Failed(routes.failure?.message ?: FALLBACK)
        }

    override fun openTrip(courseId: Long) {
        TabRouter.pendingTripStart = true
        TabRouter.openCourse(courseId)
    }

    override fun existingCandidates(place: PlaceSummary): List<Long> = PlaceDirectionsRules.candidates(routes.courses, place)

    override suspend fun loadCourse(courseId: Long): RouteCourse? = routes.detail(courseId)?.let(RouteBridge::course)

    private fun lastFailure(): String = routes.saveFailure?.courseSaveMessage ?: FALLBACK

    companion object {
        /** 장소 id → 「길찾기」가 만든 코스 id. 상세를 닫았다 다시 열어도 남는다(앱을 다시 켜면 목록 찾기가 대신한다). */
        private val remembered = mutableMapOf<Long, Long>()
        private const val POLL_MS = 200L
        private const val SHOW_WAIT_MS = 1_000
        private val FALLBACK: String get() = tr("연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요")
    }
}
