package com.mz2az.scenetrip.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.sceneapi.client.api.CoursesApi
import com.mz2az.scenetrip.sceneapi.client.api.MarketApi
import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.sceneapi.client.model.MarketCourseSummary
import com.mz2az.scenetrip.sceneapi.client.model.MarketSort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 경로여정 탭의 상태 — **홈 전용 최소 이식**.
 *
 * iOS `RouteTab/RouteStore.swift`(391줄) 중 홈이 실제로 쓰는 것만 옮겼다 —
 * [courses]·[marketCourses]·[loading]·[failure]·[refresh]·[refreshMarket]·[detail].
 * 위저드·AI 플래닝·코스 편집 등은 RouteTab 자체를 이식할 때 이 파일을 넓힌다.
 *
 * **서버가 정본이다.** 서버가 꺼져 있으면 목록이 빈다 — 코스는 사용자가 만든
 * 것이라 예시 값으로 메우면 "내가 만든 게 어디 갔지"가 된다.
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
    private val deviceId: UUID = InstallIdentity.of(context)

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
}
