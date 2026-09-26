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
import com.mz2az.scenetrip.sceneapi.client.api.MarketApi
import com.mz2az.scenetrip.sceneapi.client.model.CourseCreate
import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.CourseOrigin
import com.mz2az.scenetrip.sceneapi.client.model.CoursePace
import com.mz2az.scenetrip.sceneapi.client.model.CourseProgress
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlanRequest
import com.mz2az.scenetrip.sceneapi.client.model.MarketCourseSummary
import com.mz2az.scenetrip.sceneapi.client.model.MarketSort
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
    var marketCourses by mutableStateOf<List<MarketCourseSummary>>(emptyList())
        private set

    /** 마지막 호출이 실패했나. 화면이 "불러오지 못했습니다"를 띄우는 데 쓴다. */
    var failure by mutableStateOf<ApiFailure?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    private val coursesApi = CoursesApi(API_BASE)
    private val marketApi = MarketApi(API_BASE)
    private val guideApi = GuideApi(API_BASE)
    private val contentsApi = ContentsApi(API_BASE)
    private val deviceId: UUID = InstallIdentity.of(context)

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

    /** 마켓 목록을 받아온다. **코스 목록과 따로 실패한다** — 마켓이 안 떠도 내 코스는 보여야 한다. */
    suspend fun refreshMarket(sort: MarketSort = MarketSort.saves) {
        runCatching {
            withContext(Dispatchers.IO) { marketApi.listMarketCourses(deviceId, sort = sort, limit = 30) }
        }.onSuccess {
            marketCourses = it.items ?: emptyList()
        }
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

    /** 마켓 코스를 내 코스로 담는다. 서버가 사본을 만들어 준다 — 가입 사용자만 할 수 있다. */
    suspend fun saveFromMarket(course: MarketCourseSummary): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { marketApi.saveMarketCourse(deviceId, course.id) }
        }.fold(
            onSuccess = {
                failure = null
                refresh()
                true
            },
            onFailure = {
                failure = ApiFailure.of(it)
                false
            },
        )

    /** 좋아요를 켜고 끈다. 가입 사용자만 할 수 있다. */
    suspend fun toggleMarketLike(course: MarketCourseSummary) {
        runCatching {
            withContext(Dispatchers.IO) {
                if (course.liked) marketApi.unlikeMarketCourse(deviceId, course.id) else marketApi.likeMarketCourse(deviceId, course.id)
            }
        }.onSuccess {
            failure = null
            refreshMarket()
        }.onFailure {
            failure = ApiFailure.of(it)
        }
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
     * "AI 로 여정 짜기". 작품을 고르는 단계(iOS 5단계 마법사의 `.works`)는 아직 없어 —
     * 인기 작품 상위 3개로 채운다(iOS도 아무것도 안 고르면 같은 폴백을 쓴다). 실패하면
     * `failure`에 남긴다.
     */
    suspend fun guideDraft(
        span: RouteSpan,
        startDate: LocalDate?,
        pace: RoutePace,
    ): RouteCourse? =
        withContext(Dispatchers.IO) {
            runCatching {
                val titles =
                    contentsApi
                        .listContents(limit = 3)
                        .items
                        .map { it.title }
                        .filter { it.isNotBlank() }
                require(titles.isNotEmpty()) { "인기 작품을 불러오지 못했습니다" }
                val request =
                    GuidePlanRequest(
                        titles = titles,
                        days = span.days,
                        pace = if (pace == RoutePace.LOOSE) GuidePlanRequest.Pace.relaxed else GuidePlanRequest.Pace.packed,
                    )
                val reply = guideApi.planWithGuide(request)
                val title = titles.first().let { if (titles.size == 1) it else "$it 외 ${titles.size - 1}" }
                RouteGuidePlan.course(reply.plan, "$title ${span.label}", startDate, pace)
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

    /** "직접 짜기" — 빈 일차만 있는 코스. */
    fun emptyCourse(
        span: RouteSpan,
        startDate: LocalDate?,
    ): RouteCourse =
        RouteCourse(
            title = "내 코스",
            startDate = startDate,
            days = List(span.days) { RouteDay() },
        )
}
