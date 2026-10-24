package com.mz2az.scenetrip.hometab

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.AccountDataReset
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.VisitStamp
import com.mz2az.scenetrip.data.collectVisitStamps
import com.mz2az.scenetrip.sceneapi.client.api.ContentsApi
import com.mz2az.scenetrip.sceneapi.client.api.CoursesApi
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.CourseItem
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/**
 * 홈의 "내 여행 이어가기" 카드가 그리는 것 — 코스 하나와 그 진행.
 *
 * iOS `HomeTrip`은 `RouteCourse`/`RouteStop`(전체 RouteTab 도메인 모델)을 쓰지만,
 * 여기서는 **홈 전용 최소 이식**이라 계약이 그대로 주는 [CourseSummary]/[CourseItem]을
 * 직접 쓴다 — RouteTab 자체를 이식할 때 도메인 모델이 필요해지면 그때 갈아 끼운다.
 */
data class HomeTrip(
    val course: CourseSummary,
    /** 서버에 `visitedAt`이 찍힌 정지점 수. **지어낸 값이 아니다.** */
    val visited: Int,
    val total: Int,
    /** 진행 중인 일차(1부터). 서버의 `currentDayNo`, 없으면 1. */
    val dayNo: Int,
    val dayStops: List<CourseItem>,
    /** "이어서 길찾기"가 향할 곳 — 진행 중 일차의 첫 미방문 정지점. 다 돌았으면 첫 곳. */
    val nextStop: CourseItem?,
)

/**
 * 홈이 서버에서 받아 오는 것들 (`docs/project/plans/mobile-home-tab.md` §2).
 *
 * iOS `HomeTab/HomeTabModel.swift`를 옮긴 것이다. **네 요청을 나란히 보낸다** —
 * 줄줄이 기다리면 코스 상세(스탬프용)가 느릴 때 작품 선반까지 비어 있다. 하나가
 * 실패해도 나머지는 그린다 — 홈은 서버가 꺼져 있어도 죽지 않는다.
 */
class HomeTabModel(
    context: Context,
) {
    /** "지금 뜨는 작품" — 서버 인기순 그대로 최대20개 (MZ2AZ-422). */
    var works by mutableStateOf<List<ContentSummary>>(emptyList())
        private set

    /** "오늘의 성지" — 하루 동안 같은 곳. */
    var today by mutableStateOf<PlaceSummary?>(null)
        private set

    /**
     * "내 여행 이어가기"에 넘길 코스들 — 여행 중인 것 먼저, 최대 셋. 카드가 옆으로
     * 넘겨 가며 보여 준다 — 하나만 떡하니 있으면 나머지 코스는 있는지도 모른다.
     */
    var trips by mutableStateOf<List<HomeTrip>>(emptyList())
        private set

    var stamps by mutableStateOf<List<VisitStamp>>(emptyList())
        private set

    /** 코스 카드에 넣을 코스의 상세를 받는 중인가. */
    var loading by mutableStateOf(false)
        private set

    /** 작품 목록조차 못 받았나 — 화면이 "불러오지 못했습니다"를 띄우는 기준. */
    var failed by mutableStateOf(false)
        private set

    private val contentsApi = ContentsApi(API_BASE)
    private val placesApi = PlacesApi(API_BASE)
    private val coursesApi = CoursesApi(API_BASE)
    private val deviceId: UUID = InstallIdentity.of(context)

    init {
        // 로그아웃·탈퇴 — 앞 계정의 코스 카드와 스탬프를 먼저 비운다(다시 읽기가 실패해도 남지 않게, MZ2AZ-480).
        AccountDataReset.watch {
            trips = emptyList()
            stamps = emptyList()
            loading = false
        }
    }

    /** 코스 목록과 코스 카드를 한꺼번에 — 시험·옛 호출용. 화면은 [loadTrips] 와 [loadRest] 를 따로 부른다. */
    suspend fun load(courses: List<CourseSummary>) =
        coroutineScope {
            launch { loadTrips(courses) }
            launch { loadRest() }
        }

    /** 코스 카드 — 코스 목록이 오는 대로 부른다. 나머지([loadRest])를 기다리지 않는다. */
    suspend fun loadTrips(courses: List<CourseSummary>) {
        val generation = AccountDataReset.generation
        loading = true
        try {
            val fetched = trips(courses)
            if (generation != AccountDataReset.generation) return
            trips = fetched
        } finally {
            if (generation == AccountDataReset.generation) loading = false
        }
    }

    /**
     * **코스와 무관한 셋을 나란히 받고, 온 것부터 그린다** (MZ2AZ-372). 하나가 느려도 다른 것을 기다리지 않는다 — 오늘의 성지·스탬프가
     * 작품 선반 뒤로 밀리지 않는다.
     */
    suspend fun loadRest() =
        coroutineScope {
            launch { loadToday() }
            launch { loadStamps() }
            launch { loadShelf() }
        }

    private suspend fun loadShelf() {
        val fresh = withContext(Dispatchers.IO) { runCatching { contentsApi.listContents(limit = SHELF_COUNT) }.getOrNull() }
        if (fresh != null) {
            works = HomeShelf.from(fresh.items.orEmpty())
            failed = false
        } else {
            failed = works.isEmpty()
        }
    }

    private suspend fun loadToday() {
        val placeItems =
            withContext(Dispatchers.IO) { runCatching { placesApi.listPlaces(limit = 60) }.getOrNull() }?.items.orEmpty()
        if (placeItems.isEmpty()) return
        // 사진 있는 곳에서 고른다 — 카드 윗단이 96dp 그림 자리인데 사진이 없으면 그라데이션만 남아 빈 띠로 보인다.
        val pool = placeItems.filter { hasPhoto(it) }.ifEmpty { placeItems }
        // 연중 몇 번째 날인가로 고른다 — 무작위면 화면을 다시 그릴 때마다 성지가 바뀌어 「오늘의」 라는 말이 거짓이 된다.
        today = pool[LocalDate.now().dayOfYear % pool.size]
    }

    private suspend fun loadStamps() {
        val generation = AccountDataReset.generation
        val fetched = runCatching { collectVisitStamps(deviceId) }.getOrDefault(emptyList())
        if (generation == AccountDataReset.generation) stamps = fetched
    }

    private suspend fun trips(courses: List<CourseSummary>): List<HomeTrip> =
        coroutineScope {
            val ordered =
                courses.filter { it.status == CourseStatus.active } +
                    courses.filter { it.status != CourseStatus.active }
            ordered
                .take(3)
                .map { pick -> async { trip(pick) } }
                .awaitAll()
                .filterNotNull()
        }

    private suspend fun trip(pick: CourseSummary): HomeTrip? {
        val detail =
            withContext(Dispatchers.IO) {
                runCatching { coursesApi.getCourse(deviceId, pick.id) }.getOrNull()
            }
                // 상세를 못 받아도 카드는 뜬다 — 제목과 곳수는 목록에 있다.
                ?: return HomeTrip(
                    course = pick,
                    visited = 0,
                    total = pick.placeCount,
                    dayNo = 1,
                    dayStops = emptyList(),
                    nextStop = null,
                )

        val allItems = detail.days.flatMap { it.items }
        val dayNo = (detail.currentDayNo ?: 1).coerceIn(1, maxOf(detail.days.size, 1))
        val dayItems = detail.days.getOrNull(dayNo - 1)?.items ?: emptyList()
        val next = dayItems.firstOrNull { it.visitedAt == null } ?: dayItems.firstOrNull()

        return HomeTrip(
            course = pick,
            visited = allItems.count { it.visitedAt != null },
            total = allItems.size,
            dayNo = dayNo,
            dayStops = dayItems,
            nextStop = next,
        )
    }

    /** 코스 카드의 모습. 값만 보는 규칙이라 시험이 선다. */
    enum class TripCard {
        /** 코스가 있다 — 제목·진행·단추 둘. */
        FILLED,

        /** 아직 모른다 — 「내 여행을 불러오는 중…」. */
        LOADING,

        /** 물었는데 못 받았다 — 「내 여행을 불러오지 못했어요」. */
        UNAVAILABLE,

        /** 코스가 **정말 없다** — 「코스 만들기」. */
        CREATE,
    }

    companion object {
        /** 선반에 올리는 수 — 서버 인기순 첫 20편 (MZ2AZ-411·422). */
        const val SHELF_COUNT = 20

        /**
         * **코스가 있는지 모르는 동안에는 「코스 만들기」 를 권하지 않는다** (MZ2AZ-372). 전에는 코스 목록이 오기 전에도 「여행을 시작해
         * 볼까요? · 코스 만들기」 가 떴다 — 코스가 있는 사람에게. 그 단추는 일정짜기(유료)의 입구다. 「없다」 는 목록을 받아 본 뒤에만 말한다.
         */
        fun tripCard(
            hasTrip: Boolean,
            courseList: RouteStore.CourseListState,
            hasCourses: Boolean,
            tripsLoading: Boolean,
        ): TripCard =
            when {
                hasTrip -> TripCard.FILLED

                courseList == RouteStore.CourseListState.UNKNOWN -> TripCard.LOADING

                courseList == RouteStore.CourseListState.FAILED -> TripCard.UNAVAILABLE

                // 목록은 왔고 코스가 있는데 카드가 아직 없으면 그 코스의 상세를 받는 중이다.
                hasCourses && tripsLoading -> TripCard.LOADING

                else -> TripCard.CREATE
            }

        /**
         * 화면에 실제로 뜨는 사진이 있는가. 주소가 있다고 그림이 나오는 것은 아니다 —
         * 시드에 호스트 없는 상대 경로가 섞여 있어 이미지 로더가 조용히 빈 자리를
         * 남긴다. 그래서 **절대 http(s) 주소**만 사진으로 친다.
         *
         * 값만 보는 순수 함수라 인스턴스 없이도 테스트할 수 있다.
         */
        fun hasPhoto(place: PlaceSummary): Boolean {
            val uri = place.imageUrl ?: return false
            val scheme = uri.scheme ?: return false
            return (scheme == "http" || scheme == "https") && uri.host != null
        }
    }
}
