package com.mz2az.scenetrip.hometab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.mz2az.scenetrip.data.rememberAccountResetGeneration
import com.mz2az.scenetrip.reviews.ModerationSheets
import com.mz2az.scenetrip.reviews.rememberModerationController
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSPullToRefreshBox
import com.mz2az.scenetrip.ui.LocalTabBarClearance
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 홈 — 첫 화면 (`docs/product/canvas/home/Main.dc.html`·`HomeFull.dc.html`).
 *
 * iOS `HomeTab/HomeTabView.swift`를 옮긴 것이다. 위에서부터: 인사 · **어디로 여행 가세요?** 사진 카드(도시 칩) ·
 * 지금 뜨는 작품 · 오늘의 성지 · 커뮤니티 지금 · **내 기록**(마이페이지의 입구). 경로여정의 입구는 오른쪽 아래 떠 있는
 * 「여행 코스 짜기」 단추다(MZ2AZ-547, 웹 시안 — 「내 여행 이어가기」 카드를 대신한다). 목업의 맨 윗줄 검색칸은 뺐다 —
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
    // 「커뮤니티 지금」 의 신고·차단(MZ2AZ-461). 비회원의 차단은 앱 맨 위의 로그인 화면이 받는다.
    val moderation = rememberModerationController()
    // "오늘의 성지"의 담기 — 검색 탭과 같은 장바구니(설치 id 가 같다).
    val cart = remember { CartStore(context) }

    var refreshing by remember { mutableStateOf(false) }

    // **서로 기다리지 않는다** (MZ2AZ-372). 떠 있는 단추는 코스 목록만 있으면 된다 — 목록이 오는 대로 그린다. 오늘의 성지·스탬프·선반도
    // 각자 받는 대로 제자리에 넣는다. 하나가 느리다고 홈 전체가 그만큼 비어 있지 않다.
    suspend fun reload() =
        coroutineScope {
            launch { routes.refresh() }
            launch { model.loadRest() }
            launch { cart.refresh() }
            launch { posts.refresh() }
        }

    // 계정이 바뀌면 다시 읽는다 — iOS `.onAccountChange`. 로그아웃·탈퇴는 앞 계정의 사본을 먼저 비운 세대가 오른다.
    LaunchedEffect(AuthStore.epoch, rememberAccountResetGeneration()) { reload() }

    // 덮개(경로여정·마이페이지)를 닫고 돌아오면 코스·스탬프가 달라졌을 수 있다.
    var previousCover by remember { mutableStateOf(TabRouter.cover) }
    LaunchedEffect(TabRouter.cover) {
        if (previousCover != null && TabRouter.cover == null) {
            reload()
        }
        previousCover = TabRouter.cover
    }

    val clearance = LocalTabBarClearance.current
    Box(modifier = Modifier.fillMaxSize()) {
        IOSPullToRefreshBox(
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
                        // 떠 있는 「여행 코스 짜기」 단추에 마지막 줄이 가리지 않게(웹 84) + 탭바 몫.
                        .PaddingValues(top = 8.dp, bottom = 84.dp + clearance),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item { HomeHeader(onProfile = { TabRouter.openProfile() }) }

                // 「어디로 여행 가세요?」 사진 카드(MZ2AZ-547 H1–H3). 「내 여행 이어가기」 카드는 숨겼다 —
                // 아래에 떠 있는 「여행 코스 짜기」 단추가 대신한다(H5).
                item {
                    // 그 도시의 「며칠 머무세요?」(Q2)부터([TabRouter.startNewCourse], MZ2AZ-547 P3).
                    HomeCityCard(cities = model.cities, onPick = { TabRouter.startNewCourse(it.key) })
                }

                item {
                    HomeWorkShelf(
                        works = model.works,
                        failed = model.failed,
                        onOpen = { TabRouter.openContent(it) },
                        // 「전체 보기」 는 인기순 포스터 격자(MZ2AZ-547 H6, 웹 MZ2AZ-534) — 전에는 작품검색 탭이었다.
                        onAll = { TabRouter.openTrending() },
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
                        moderation = moderation,
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
        HomeTripFloat(
            courses = HomeTripFloatRules.courses(routes.courses),
            courseList = routes.courseList,
            onOpenCourse = { TabRouter.openCourse(it) },
            onAll = { TabRouter.openRoute() },
            // 「새 여행 코스」 시트(F1, [TabRouter.startNewCourse]).
            onNew = { TabRouter.startNewCourse(null) },
            initiallyOpen = HomeDebug.floatOpen,
            // 단추 아래끝이 탭바 위 약 18 — 웹 `탭바 + 22` 자리.
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = clearance + 12.dp),
        )
    }

    ModerationSheets(moderation) { target -> posts.blockedInBackground(target.targetId) }
}
