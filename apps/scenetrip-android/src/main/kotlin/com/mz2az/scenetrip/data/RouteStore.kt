package com.mz2az.scenetrip.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.routetab.RouteBridge
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.routetab.RouteDay
import com.mz2az.scenetrip.routetab.RouteGuidePlan
import com.mz2az.scenetrip.routetab.RoutePace
import com.mz2az.scenetrip.routetab.RouteSpan
import com.mz2az.scenetrip.sceneapi.client.api.ContentsApi
import com.mz2az.scenetrip.sceneapi.client.api.CoursesApi
import com.mz2az.scenetrip.sceneapi.client.api.GuideApi
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.CourseCreate
import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.CourseOrigin
import com.mz2az.scenetrip.sceneapi.client.model.CoursePace
import com.mz2az.scenetrip.sceneapi.client.model.CourseProgress
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlanRequest
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.VisitUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/**
 * 경로여정 탭의 상태. iOS `RouteTab/RouteStore.swift`를 옮긴 것이다.
 *
 * **서버가 정본이다.** 서버가 꺼져 있으면 목록이 빈다 — 코스는 사용자가 만든
 * 것이라 예시 값으로 메우면 "내가 만든 게 어디 갔지"가 된다.
 *
 * [courses]는 홈이 쓰는 목록 그대로([CourseSummary], 일차 속이 없다) 남겨 뒀다 —
 * Home 코드를 건드리지 않으려고. RouteTab 화면(편집·상세)은 [routeCourse]로
 * [RouteCourse](화면 도메인 모델)를 얻어 쓴다. AI 가이드 초안(`guideDraft`)은 아직
 * 이식하지 않았다 — RouteGuide 계열(챗봇) 자체가 다음 단계다.
 */
class RouteStore(
    context: Context,
) {
    var courses by mutableStateOf<List<CourseSummary>>(emptyList())
        private set

    /** 마지막 호출이 실패했나. 화면이 "불러오지 못했습니다"를 띄우는 데 쓴다. */
    var failure by mutableStateOf<ApiFailure?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    /** 질문 흐름이 쓰는 작품 목록 — [loadWorks]가 채운다. */
    var works by mutableStateOf<List<ContentSummary>>(emptyList())
        private set

    /**
     * 편집 화면 안 검색·장바구니 빈 상태가 쓰는 촬영지 전체 — [loadPlaces]가 채운다.
     * iOS `RouteStore.places`와 같다: **넉넉히 받는다** — 60건만 받으면 도깨비
     * 촬영지에 몰려 다른 작품은 없는 것처럼 보인다(iOS 실측: 눈물의 여왕 67곳인데
     * 60건 안엔 3곳뿐). 촬영지 전체가 155건이라 한 번에 받아도 된다. 글자를 칠 때마다
     * 서버에 묻지 않고 이 목록을 메모리에서 거른다 — 편집 화면 검색이 타이핑마다
     * 호출을 늘리면 안 된다는 게 iOS `RouteSearchSheet`의 결정이다.
     */
    var places by mutableStateOf<List<PlaceSummary>>(emptyList())
        private set

    private val coursesApi = CoursesApi(API_BASE)
    private val guideApi = GuideApi(API_BASE)
    private val contentsApi = ContentsApi(API_BASE)
    private val placesApi = PlacesApi(API_BASE)
    private val likeStore = LikeStore.getInstance(context)
    private val deviceId: UUID = InstallIdentity.of(context)

    /** [works]를 채운다. 마법사가 작품 선택 단계에 들어갈 때 부른다. */
    suspend fun loadWorks() {
        runCatching { withContext(Dispatchers.IO) { contentsApi.listContents(limit = 30) } }
            .onSuccess { works = it.items }
    }

    /** [places]를 채운다. 이미 있으면 다시 부르지 않는다 — 검색·장바구니를 열 때마다 부른다. */
    suspend fun loadPlaces() {
        if (places.isNotEmpty()) return
        runCatching { withContext(Dispatchers.IO) { placesApi.listPlaces(limit = 200) } }
            .onSuccess { places = it.items ?: emptyList() }
    }

    /** 질문 흐름에 뿌릴 순서 — **찜한 것이 먼저, 나머지는 인기도순.** iOS `RouteStore.sortedWorks`. */
    val sortedWorks: List<ContentSummary>
        get() {
            val favorites = likeStore.contentIds
            return works.filter { favorites.contains(it.id) } + works.filter { !favorites.contains(it.id) }
        }

    fun isFavoriteWork(contentId: Long): Boolean = likeStore.contains(contentId)

    fun toggleFavoriteWork(contentId: Long) = likeStore.toggle(contentId)

    fun clearFailure() {
        failure = null
    }

    /** 코스 목록을 받아온다. 홈이 뜰 때 부른다. */
    suspend fun refresh() {
        loading = courses.isEmpty()
        runCatching {
            withContext(Dispatchers.IO) { coursesApi.listCourses(deviceId) }
        }.onSuccess {
            courses = it.items ?: emptyList()
            failure = null
        }.onFailure {
            failure = ApiFailure.of(it)
        }
        loading = false
    }

    /** 코스 상세(일차·방문 체크) — 홈의 "내 여행 이어가기" 카드가 진행률을 계산하는 데 쓴다. */
    suspend fun detail(courseId: Long): CourseDetail? =
        withContext(Dispatchers.IO) {
            runCatching { coursesApi.getCourse(deviceId, courseId) }.getOrNull()
        }

    /**
     * 코스 하나의 속을 [RouteCourse]로 받아온다. 목록 카드에는 일차 속이 없으므로
     * 편집 화면을 열 때 부른다 — 목록에서 전부 받아 두면 코스가 많을 때 첫 화면이
     * 느려진다. 서버 id가 없는(아직 안 저장한) 코스는 그대로 돌려준다.
     */
    suspend fun routeCourse(course: RouteCourse): RouteCourse? {
        val serverId = course.serverId ?: return course
        return withContext(Dispatchers.IO) {
            runCatching { coursesApi.getCourse(deviceId, serverId) }
        }.fold(
            onSuccess = {
                failure = null
                RouteBridge.course(it)
            },
            onFailure = {
                failure = ApiFailure.of(it)
                null
            },
        )
    }

    /**
     * 코스를 넣거나 덮어쓴다. 편집 화면이 작업 사본을 들고 있다가 저장할 때 부른다.
     * 서버 id가 없으면 만들고(POST), 있으면 통째로 덮어쓴다(PUT) — **덮어쓰기는
     * 보낸 것이 전부다**, 빠진 아이템은 지운 것이 된다.
     */
    suspend fun save(course: RouteCourse): RouteCourse? =
        withContext(Dispatchers.IO) {
            runCatching {
                val serverId = course.serverId
                if (serverId != null) {
                    coursesApi.replaceCourse(deviceId, serverId, RouteBridge.replace(course))
                } else {
                    // 만들기와 내용 채우기가 두 번에 나뉜다 — 계약이 만들 때는 기간과
                    // 출처만 받고, 장소는 편집 완료로 넣게 돼 있다.
                    val created =
                        coursesApi.createCourse(
                            deviceId,
                            CourseCreate(
                                dayCount = course.days.size,
                                title = course.title,
                                origin = if (course.madeByAI) CourseOrigin.ai else CourseOrigin.self,
                                pace = if (course.pace == RoutePace.LOOSE) CoursePace.loose else CoursePace.tight,
                            ),
                        )
                    try {
                        coursesApi.replaceCourse(deviceId, created.id, RouteBridge.replace(course))
                    } catch (error: Exception) {
                        // 채우기가 실패하면 만든 코스를 되돌린다 — 안 그러면 장소 없는
                        // 껍데기가 목록에 남는다.
                        runCatching { coursesApi.deleteCourse(deviceId, created.id) }
                        throw error
                    }
                }
            }
        }.fold(
            onSuccess = { saved ->
                failure = null
                val result = RouteBridge.course(saved)
                refresh()
                result
            },
            onFailure = {
                failure = ApiFailure.of(it)
                null
            },
        )

    suspend fun delete(course: RouteCourse) {
        val serverId = course.serverId
        if (serverId == null) {
            // 저장 전 코스는 서버에 없다 — 목록에서 지울 것도 없다(호출부가 로컬 상태를 지운다).
            return
        }
        runCatching {
            withContext(Dispatchers.IO) { coursesApi.deleteCourse(deviceId, serverId) }
        }.onSuccess {
            failure = null
        }.onFailure {
            failure = ApiFailure.of(it)
        }
        refresh()
    }

    /**
     * "코스 시작 / 여행 종료". `active`로 바꿀 때 [dayNo]를 반드시 보낸다 — 빠뜨리면
     * 서버가 400을 준다. `upcoming`으로 되돌릴 때는 보내지 않는다.
     */
    suspend fun setRunning(
        course: RouteCourse,
        running: Boolean,
        dayNo: Int = 1,
    ) {
        val serverId = course.serverId ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                coursesApi.updateCourseProgress(
                    deviceId,
                    serverId,
                    CourseProgress(
                        status = if (running) CourseStatus.active else CourseStatus.upcoming,
                        currentDayNo = if (running) dayNo else null,
                    ),
                )
            }
        }.onSuccess {
            failure = null
        }.onFailure {
            failure = ApiFailure.of(it)
        }
        // 실패했으면 되돌린다 — 화면과 서버가 어긋난 채 두면 다음 저장이 엉뚱하게 나간다.
        refresh()
    }

    /**
     * 도착 표시를 서버에 남긴다. iOS `RouteEditorTrip.markVisited` — 화면은 이 호출 전에
     * 이미 자기 사본을 `visited = true`로 바꿔 둔다(`markVisitedLocally`와 같은 뜻).
     * 실패해도 조용히 넘긴다 — 다음에 코스를 다시 열면 서버 값으로 맞춰진다, 방문
     * 자체를 막을 이유는 아니다.
     */
    suspend fun markVisited(
        courseId: Long,
        itemId: Long,
    ) {
        runCatching {
            withContext(Dispatchers.IO) {
                coursesApi.updateCourseItemVisit(deviceId, courseId, itemId, VisitUpdate(visited = true))
            }
        }
    }

    /**
     * "AI 로 여정 짜기". 고른 작품이 없으면 인기 작품 상위 3개로 채운다(iOS와 같은
     * 폴백) — 다만 **이름은 실제로 고른 작품에서만 짓는다**, 아무것도 안 골랐으면
     * "인기 촬영지"로 남는다. iOS `RouteStore.guideDraft`/`title(for:span:)`.
     */
    suspend fun guideDraft(
        workIds: Set<Long>,
        span: RouteSpan,
        startDate: LocalDate?,
        pace: RoutePace,
        // **출발점.** 없으면 서버가 촬영지가 가장 몰린 곳을 중심으로 잡는데, 그러면
        // 실제 출발지에서 먼 순서로 나올 수도 있다 — iOS `RouteWizardView.here`처럼
        // 마법사가 열리자마자 위치를 물어 여기로 넘긴다(2026-09-28 실기 비교로
        // 발견: 안 넘겼을 때 1일차 정지점 순서가 iOS와 거꾸로 나왔다).
        latitude: Double? = null,
        longitude: Double? = null,
    ): RouteCourse? =
        withContext(Dispatchers.IO) {
            runCatching {
                val chosenTitles = works.filter { workIds.contains(it.id) }.map { it.title }
                val requestTitles = chosenTitles.ifEmpty { sortedWorks.take(3).map { it.title } }
                require(requestTitles.isNotEmpty()) { "인기 작품을 불러오지 못했습니다" }
                val request =
                    GuidePlanRequest(
                        titles = requestTitles.take(5),
                        days = span.days,
                        pace = if (pace == RoutePace.LOOSE) GuidePlanRequest.Pace.relaxed else GuidePlanRequest.Pace.packed,
                        latitude = latitude,
                        longitude = longitude,
                    )
                val reply = guideApi.planWithGuide(request)
                RouteGuidePlan.course(reply.plan, courseTitle(chosenTitles, span), startDate, pace)
            }
        }.fold(
            onSuccess = { course ->
                failure = null
                course
            },
            onFailure = {
                failure = ApiFailure.of(it)
                null
            },
        )

    /** 이름 없이 고르면 "인기 촬영지 1박 2일", 고르면 "도깨비 외 2 1박 2일". */
    private fun courseTitle(
        chosenTitles: List<String>,
        span: RouteSpan,
    ): String {
        val first = chosenTitles.firstOrNull() ?: return tr("인기 촬영지 %s").format(span.label)
        val name = if (chosenTitles.size == 1) first else tr("%s 외 %d").format(first, chosenTitles.size - 1)
        return "$name ${span.label}"
    }

    /** "직접 짜기" — 빈 일차만 있는 코스. */
    fun emptyCourse(
        span: RouteSpan,
        startDate: LocalDate?,
    ): RouteCourse =
        RouteCourse(
            title = tr("내 코스", "코스 제목"),
            startDate = startDate,
            days = List(span.days) { RouteDay() },
        )
}
