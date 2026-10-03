package com.mz2az.scenetrip.hometab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.LikeStore
import com.mz2az.scenetrip.data.RootTab
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.launch

/**
 * 홈 — 첫 화면 (`docs/product/canvas/home/Main.dc.html`·`HomeFull.dc.html`).
 *
 * iOS `HomeTab/HomeTabView.swift`를 옮긴 것이다. 위에서부터: 인사 · **내 여행
 * 이어가기**(경로여정의 입구) · 지금 뜨는 작품 · 오늘의 성지 ·
 * 커뮤니티 지금 · **내 기록**(마이페이지의 입구). 목업의 맨 윗줄 검색칸은 뺐다 —
 * 검색은 작품검색 탭이 맡는다. 「여행자들의 코스」(코스마켓) 섹션은 쓰지 않는
 * 기능이라 걷어냈다(2026-10-03 팀 회의, MZ2AZ-350).
 *
 * 숫자·문구는 전부 서버·기기 값이다.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun HomeTabScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val routes = remember { RouteStore(context) }
    val posts = remember { CommunityStore.getInstance(context) }
    val likes = remember { LikeStore.getInstance(context) }
    val model = remember { HomeTabModel(context) }
    // "오늘의 성지"의 담기 — 검색 탭과 같은 장바구니(설치 id 가 같다).
    val cart = remember { CartStore(context) }

    var refreshing by remember { mutableStateOf(false) }

    suspend fun reload() {
        routes.refresh()
        cart.refresh()
        model.load(routes.courses)
    }

    // 계정이 바뀌면 다시 읽는다 — iOS `.onAccountChange`.
    LaunchedEffect(AuthStore.epoch) { reload() }

    // 덮개(경로여정·마이페이지)를 닫고 돌아오면 코스·스탬프가 달라졌을 수 있다.
    var previousCover by remember { mutableStateOf(TabRouter.cover) }
    LaunchedEffect(TabRouter.cover) {
        if (previousCover != null && TabRouter.cover == null) {
            reload()
        }
        previousCover = TabRouter.cover
    }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            scope.launch {
                refreshing = true
                reload()
                refreshing = false
            }
        },
        modifier = Modifier.fillMaxSize().background(IOS.systemGray6),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item { HomeHeader(onProfile = { TabRouter.openProfile() }) }

            item {
                HomeTripPager(
                    trips = model.trips,
                    hasCourses = routes.courses.isNotEmpty(),
                    loading = model.loading && model.trips.isEmpty(),
                    // 코스 여행의 길찾기는 그 코스의 편집 화면 안에서 돈다 — 별도
                    // 창을 띄우지 않고 코스를 열며 안내를 켠다.
                    onNavigate = { trip ->
                        if (trip.nextStop != null) {
                            TabRouter.pendingTripStart = true
                            TabRouter.openCourse(trip.course.id)
                        }
                    },
                    // "코스 보기"는 코스 전체 목록으로 간다 — 홈에서 누르는 사람은
                    // "내 코스들이 뭐가 있나"를 보려는 것이다.
                    onOpenCourse = { TabRouter.openRoute() },
                    onCreate = { TabRouter.openRoute() },
                )
            }

            item {
                HomeWorkShelf(
                    works = model.works,
                    failed = model.failed,
                    onOpen = { TabRouter.openContent(it) },
                    onAll = { TabRouter.selected = RootTab.SEARCH },
                )
            }

            model.today?.let { place ->
                item {
                    HomeTodayCard(
                        place = place,
                        saved = cart.contains(place.id),
                        // 카드 본체를 누르면 그 촬영지의 상세로.
                        onOpen = { TabRouter.openPlace(place) },
                        onSave = {
                            scope.launch {
                                if (cart.contains(place.id)) cart.remove(place.id) else cart.add(place.id)
                            }
                        },
                    )
                }
            }

            item {
                HomeCommunityNow(
                    posts = posts.posts.take(2),
                    onOpen = { TabRouter.selected = RootTab.COMMUNITY },
                )
            }

            item {
                HomeMyRecord(
                    stamps = model.stamps,
                    likeCount = likes.contentIds.size,
                    onOpen = { TabRouter.openProfile() },
                )
            }
        }
    }
}
