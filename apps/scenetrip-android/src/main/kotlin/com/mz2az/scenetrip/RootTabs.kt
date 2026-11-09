package com.mz2az.scenetrip

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.communitytab.CommunityTabScreen
import com.mz2az.scenetrip.data.Cover
import com.mz2az.scenetrip.data.RootTab
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.hometab.HomeTabScreen
import com.mz2az.scenetrip.hometab.TrendingWorksView
import com.mz2az.scenetrip.newcourse.NewCourseFlowView
import com.mz2az.scenetrip.newcourse.NewCourseSheet
import com.mz2az.scenetrip.newcourse.TripMemoryStore
import com.mz2az.scenetrip.profiletab.ProfileTabView
import com.mz2az.scenetrip.routetab.RouteTabView
import com.mz2az.scenetrip.searchtab.SearchTabScreen
import com.mz2az.scenetrip.ui.BubblesIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.LocalTabBarClearance
import com.mz2az.scenetrip.ui.MapIcon
import com.mz2az.scenetrip.ui.rememberReduceMotion

/**
 * 앱의 최상위 — 하단 탭 셋과, 탭에서 내려온 화면의 덮개를 든다.
 *
 * 2026-11-10 웹 시안 반영(MZ2AZ-547 T1·T2, iOS `RootTabs.swift` 와 같다): 탭바는 화면 바닥에 떠 있는 알약이고
 * **탭 내용이 그 뒤까지 내려간다.** 바닥에 붙는 목록·서랍·토스트는 [LocalTabBarClearance] 만큼 비운다.
 *
 * 2026-09-01 홈 재편(`docs/project/plans/mobile-home-tab.md`): 탭은 셋(작품검색 · 홈 · 커뮤니티)이고 첫 화면은 홈이다.
 * 경로여정은 홈의 떠 있는 「여행 코스 짜기」 단추가, 마이페이지는 홈 오른쪽 위 프로필 단추가 입구다 — [TabRouter.cover]
 * 로 전체 화면에 띄운다.
 */
@Composable
fun RootTabs() {
    val selected = TabRouter.selected
    val cover = TabRouter.cover

    // 어느 화면을 보는가 — 탭과 덮개 단위로 적는다 (MZ2AZ-353). 덮개가 이긴다 —
    // iOS `RootTabs.screenName`과 같은 우선순위다.
    val screenName =
        when (cover) {
            // 새 코스 만들기도 코스 화면으로 센다 — 화면 이름을 늘리지 않는다(iOS 와 같다).
            Cover.Route, is Cover.NewCourse -> {
                "courses"
            }

            Cover.Profile -> {
                "profile"
            }

            // 전체 보기는 홈의 일부로 센다 — 새 화면 이름을 늘리지 않는다(웹 MZ2AZ-534·iOS 와 같다).
            Cover.Trending -> {
                "home"
            }

            null -> {
                when (selected) {
                    RootTab.SEARCH -> "search"
                    RootTab.HOME -> "home"
                    RootTab.COMMUNITY -> "community"
                }
            }
        }
    LaunchedEffect(screenName) { AppAnalytics.log(AppEvent.ScreenView(screenName)) }

    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 정한 여행(Q4)을 서버와 맞춘다 — 켤 때 한 번(서버 값이 이긴다, `TripMemoryRules.reconcile`).
    val context = LocalContext.current
    LaunchedEffect(Unit) { TripMemoryStore.getInstance(context).sync() }

    Box(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
        // **탭 내용이 알약 뒤까지 내려간다**(T2). 바닥에 붙는 것은 알약 몫(74) + 내비게이션 막대만큼 비운다.
        CompositionLocalProvider(LocalTabBarClearance provides FloatingTabBarRules.CLEARANCE.dp + navBar) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 검색 탭은 항상 살려 둔다 — 다른 탭에 갔다 와도 지도와 검색 결과가
                // 그대로여야 한다. iOS 가 `opacity` + `allowsHitTesting` 으로 하는 것과
                // 같다. 여기서 조건부로 그리면 지도 SDK 가 매번 다시 뜬다.
                //
                // **`alpha` 만으로는 안 된다.** Compose 에는 SwiftUI의
                // `allowsHitTesting(false)` 짝이 없다 — 투명해도 터치는 그대로
                // 받는다. `zIndex`로 **지금 보이는 쪽만 위로** 올려 맞바꾼다(2026-09-28 실측 —
                // 검색 탭에서 작품을 눌렀는데 홈의 "지금 뜨는 작품" 첫 카드가 열렸다).
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .zIndex(if (selected == RootTab.SEARCH) 1f else 0f)
                            .alpha(if (selected == RootTab.SEARCH) 1f else 0f),
                ) {
                    SearchTabScreen()
                }
                // 홈도 검색처럼 항상 살려 둔다 — 서버를 다시 부르지 않기 위해서다.
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .zIndex(if (selected == RootTab.HOME) 1f else 0f)
                            .alpha(if (selected == RootTab.HOME) 1f else 0f),
                ) {
                    HomeTabScreen()
                }
                // 커뮤니티는 값이 싸서 선택했을 때만 만들고 버린다.
                if (selected == RootTab.COMMUNITY) {
                    CommunityTabScreen()
                }
            }
        }

        // 알약 밖의 빈 자리는 손가락을 받지 않아 밑의 목록으로 통과한다(눌림을 받는 것은 알약 안의 탭뿐이다).
        FloatingTabBar(
            selected = selected,
            onSelect = { TabRouter.selected = it },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(
                        start = FloatingTabBarRules.SIDE_MARGIN.dp,
                        end = FloatingTabBarRules.SIDE_MARGIN.dp,
                        bottom = navBar + FloatingTabBarRules.BOTTOM_GAP.dp,
                    ),
        )

        when (cover) {
            null -> {
                Unit
            }

            Cover.Profile -> {
                ProfileTabView(onClose = { TabRouter.cover = null })
            }

            Cover.Route -> {
                val routeStore = remember { RouteStore(context) }
                RouteTabView(
                    store = routeStore,
                    onClose = { TabRouter.cover = null },
                )
            }

            // 새 코스 만들기 — 도시로 시작·작품으로·담은 곳으로(MZ2AZ-547 P3). 시작이 바뀌면 새 흐름으로 처음부터.
            is Cover.NewCourse -> {
                key(cover) {
                    NewCourseFlowView(start = cover.start, onClose = { TabRouter.cover = null })
                }
            }

            Cover.Trending -> {
                TrendingWorksView(
                    onClose = { TabRouter.cover = null },
                    onOpen = { work ->
                        TabRouter.cover = null
                        TabRouter.openContent(work)
                    },
                )
            }
        }

        // 「새 여행 코스」 시트(MZ2AZ-547 F1) — 홈 떠 있는 줄의 「+ 새 코스」·코스 0개의 「여행 코스 짜기」.
        if (TabRouter.newCourseSheet) {
            NewCourseSheet(
                onDismiss = { TabRouter.newCourseSheet = false },
                onChoose = { TabRouter.chooseNewCourse(it) },
            )
        }
    }
}

private val RootTab.label: String
    get() =
        when (this) {
            RootTab.SEARCH -> tr("작품검색")
            RootTab.HOME -> tr("홈")
            RootTab.COMMUNITY -> tr("커뮤니티")
        }

/** iOS `.buttonStyle(.plain)` — 누르는 동안 내용이 흐려진다(iOS 시뮬레이터 실측 불투명도 ≈ 0.7). */
private const val PRESSED_ALPHA = 0.7f

/** 웹 `cubic-bezier(0.22, 1, 0.36, 1)` — 고른 탭 알약이 미끄러지는 곡선(iOS `timingCurve` 와 같은 값). */
private val SLIDE_EASING = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * 탭바 — **떠 있는 알약 하나**에 세 탭이 같은 폭으로 (MZ2AZ-547 T1, 웹 `features/proto/ProtoTabBar.tsx`, iOS `TabBar`).
 *
 * 전에는 가운데 홈만 위로 솟은 동그라미였다(2026-09-01 목업). 셋을 같은 폭으로 두고, 고른 탭 뒤에 보라 단색 알약이 0.38초에
 * 걸쳐 미끄러진다. **흐림(blur)은 없다** — Compose 에서 뒤 내용을 흐리게 하려면 API 31+ 의 `RenderEffect` 로 탭 내용 전체를
 * 따로 그려야 해서, 바탕 88% 불투명 + 얇은 테두리로 대신한다(뒤 내용이 옅게 비친다).
 */
@Composable
private fun FloatingTabBar(
    selected: RootTab,
    onSelect: (RootTab) -> Unit,
    modifier: Modifier,
) {
    val tabs = RootTab.entries
    val index = tabs.indexOf(selected).coerceAtLeast(0)
    val reduceMotion = rememberReduceMotion()
    val pill = RoundedCornerShape(percent = 50)
    BoxWithConstraints(
        modifier =
            modifier
                .fillMaxWidth()
                .height(FloatingTabBarRules.HEIGHT.dp)
                .shadow(elevation = 10.dp, shape = pill, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(pill)
                .background(IOS.systemBackground.copy(alpha = 0.88f))
                .border(0.5.dp, IOS.separator, pill),
    ) {
        val barWidth = maxWidth.value
        val width = FloatingTabBarRules.selectionWidth(barWidth, tabs.size)
        val offset by animateFloatAsState(
            targetValue = FloatingTabBarRules.selectionOffset(index, barWidth, tabs.size),
            animationSpec = tween(if (reduceMotion) 0 else FloatingTabBarRules.SLIDE_MILLIS, easing = SLIDE_EASING),
            label = "tabSelection",
        )
        // 고른 탭 뒤의 알약 — 탭을 바꾸면 미끄러져 옮겨 간다.
        Box(
            modifier =
                Modifier
                    .offset(x = offset.dp)
                    .padding(vertical = FloatingTabBarRules.INNER_PADDING.dp)
                    .width(width.dp)
                    .fillMaxHeight()
                    .clip(pill)
                    .background(IOS.homePurple),
        )
        Row(modifier = Modifier.fillMaxSize().padding(horizontal = FloatingTabBarRules.INNER_PADDING.dp)) {
            tabs.forEach { tab ->
                TabButton(tab, tab == selected, Modifier.weight(1f)) { onSelect(tab) }
            }
        }
    }
}

@Composable
private fun TabButton(
    tab: RootTab,
    on: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val tint by animateColorAsState(if (on) Color.White else IOS.secondaryLabel, tween(200), label = "tabTint")
    val label = tab.label
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        // Android 기본 눌림 잔상(회색 사각형)은 끄고(MZ2AZ-513) iOS `.plain` 처럼 누르는 동안 내용을 흐리게 한다.
        modifier =
            modifier
                .fillMaxHeight()
                .selectable(
                    selected = on,
                    interactionSource = source,
                    indication = null,
                    role = Role.Tab,
                    onClick = onClick,
                ).semantics(mergeDescendants = true) { contentDescription = label }
                .graphicsLayer { alpha = if (pressed) PRESSED_ALPHA else 1f },
    ) {
        TabIcon(tab, on, tint)
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 고르지 않은 홈 아이콘 — 웹 `grayscale(0.6) opacity(0.8)`, iOS `saturation(0.4)`. */
private val DIM_FACE = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.4f) })

@Composable
private fun TabIcon(
    tab: RootTab,
    on: Boolean,
    tint: Color,
) {
    when (tab) {
        // 지도 — 「지도에서 촬영지를 찾는 탭」(웹 시안과 같다, MZ2AZ-547 T1).
        RootTab.SEARCH -> {
            MapIcon(tint, Modifier.size(width = 20.dp, height = 18.dp))
        }

        // 홈은 해태 얼굴을 작게 — 고르지 않으면 흐리게.
        RootTab.HOME -> {
            Image(
                painter = painterResource(R.drawable.haetae_face),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                colorFilter = if (on) null else DIM_FACE,
                modifier = Modifier.size(width = 26.dp, height = 22.dp).alpha(if (on) 1f else 0.8f),
            )
        }

        // `bubble.left.and.bubble.right` — 말풍선 둘이 겹친다([BubblesIcon]).
        RootTab.COMMUNITY -> {
            BubblesIcon(tint = tint, modifier = Modifier.size(width = 22.dp, height = 18.dp))
        }
    }
}
