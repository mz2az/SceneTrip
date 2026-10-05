package com.mz2az.scenetrip

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.mz2az.scenetrip.profiletab.ProfileTabView
import com.mz2az.scenetrip.routetab.RouteTabView
import com.mz2az.scenetrip.searchtab.SearchTabScreen
import com.mz2az.scenetrip.ui.BubblesIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.MagnifierIcon

/**
 * 앱의 최상위 — 하단 탭 셋과, 탭에서 내려온 두 화면의 덮개를 든다.
 *
 * 2026-09-01 홈 재편(`docs/project/plans/mobile-home-tab.md`, iOS `RootTabs.swift`를
 * 그대로 옮겼다): 탭 넷(작품검색·경로여정·커뮤니티·마이페이지)이 **셋**(작품검색 ·
 * 가운데 동그란 홈 · 커뮤니티)이 됐고 첫 화면은 홈이다. 경로여정은 홈의 "내 여행
 * 이어가기" 카드가, 마이페이지는 홈 오른쪽 위 프로필 단추가 입구다 — [TabRouter.cover]
 * 로 전체 화면에 띄운다.
 *
 * 탭 선택·덮개는 [TabRouter]가 든다 — 마이페이지가 "경로여정에서 열기"로, 홈이
 * "코스 보기"로 화면을 바꿀 수 있어야 해서다.
 */
@Composable
fun RootTabs() {
    val selected = TabRouter.selected
    val cover = TabRouter.cover

    // 어느 화면을 보는가 — 탭과 덮개 단위로 적는다 (MZ2AZ-353). 덮개가 이긴다 —
    // iOS `RootTabs.screenName`과 같은 우선순위다.
    val screenName =
        when (cover) {
            Cover.Route -> {
                "courses"
            }

            Cover.Profile -> {
                "profile"
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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // 검색 탭은 항상 살려 둔다 — 다른 탭에 갔다 와도 지도와 검색 결과가
                // 그대로여야 한다. iOS 가 `opacity` + `allowsHitTesting` 으로 하는 것과
                // 같다. 여기서 조건부로 그리면 지도 SDK 가 매번 다시 뜬다.
                //
                // **`alpha` 만으로는 안 된다.** Compose 에는 SwiftUI의
                // `allowsHitTesting(false)` 짝이 없다 — 투명해도 터치는 그대로
                // 받는다. 검색·홈 둘 다 같은 자리에 늘 떠 있으니, 항상 나중에 그려지는
                // (= 항상 위에 있는) 홈이 안 보일 때도 검색 탭의 탭을 가로챈다 — 실측:
                // 검색 탭에서 작품을 눌렀는데 홈의 "지금 뜨는 작품" 첫 카드가 열렸다
                // (2026-09-28). `zIndex`로 **지금 보이는 쪽만 위로** 올려 맞바꾼다 —
                // 소스 순서(그래서 조립 순서·상태)는 그대로 두고 그리기·히트테스트
                // 순서만 선택된 탭 쪽으로 넘긴다. (처음에 눌러 삼키는 `pointerInput`
                // 으로 막아 봤는데, 그건 위에 있는 쪽이 안 보여도 여전히 위에 있어서
                // 터치 자체를 통째로 먹어 버렸다 — 둘 다 안 먹힐 뻔했다.)
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
            TabBar(selected = selected, onSelect = { TabRouter.selected = it })
        }

        when (cover) {
            null -> {
                Unit
            }

            Cover.Profile -> {
                ProfileTabView(onClose = { TabRouter.cover = null })
            }

            Cover.Route -> {
                val context = LocalContext.current
                val routeStore = remember { RouteStore(context) }
                RouteTabView(
                    store = routeStore,
                    onClose = { TabRouter.cover = null },
                )
            }
        }
    }
}

/** 아직 만들지 않은 탭·덮개. 빈 화면 대신 무엇이 올 자리인지 말해 준다. */
@Composable
private fun StubTab(tab: RootTab) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().background(IOS.systemBackground),
    ) {
        Text(tab.stubLabel, style = IOS.headline, color = IOS.secondaryLabel)
        Text(tr("아직 준비 중입니다"), style = IOS.subheadline, color = IOS.tertiaryLabel)
    }
}

private val RootTab.stubLabel: String
    get() =
        when (this) {
            RootTab.SEARCH -> tr("작품검색")
            RootTab.HOME -> tr("홈")
            RootTab.COMMUNITY -> tr("커뮤니티")
        }

/**
 * 탭바 — 양옆은 얇은 아이콘, 가운데는 위로 솟은 동그란 홈(해태 얼굴).
 *
 * 목업(`Main.dc.html`)의 배치를 그대로 옮겼다: 62dp 원에 핀 그러데이션, 바탕색 4dp
 * 테, 18dp 만큼 바 위로 나온다(iOS `RootTabs.swift` 실측값 — 계획 문서의 26은 초안
 * 수치). 바 자체는 56dp — 검색 탭 바텀시트의 최대 높이가 이 위까지라 그 이상 키우지
 * 않는다.
 */
@Composable
private fun TabBar(
    selected: RootTab,
    onSelect: (RootTab) -> Unit,
) {
    Column {
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(IOS.separator))
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(IOS.systemBackground),
        ) {
            SideTab(RootTab.SEARCH, selected == RootTab.SEARCH, Modifier.weight(1f)) { onSelect(RootTab.SEARCH) }
            HomeTab(selected == RootTab.HOME, Modifier.weight(1f)) { onSelect(RootTab.HOME) }
            SideTab(RootTab.COMMUNITY, selected == RootTab.COMMUNITY, Modifier.weight(1f)) {
                onSelect(RootTab.COMMUNITY)
            }
        }
        Box(Modifier.navigationBarsPadding().background(IOS.systemBackground).fillMaxWidth())
    }
}

@Composable
private fun SideTab(
    tab: RootTab,
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tint = if (active) IOS.accent else IOS.secondaryLabel
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // iOS `VStack(spacing: 3)` 을 칸 가운데에 — 위아래 반씩 나눠 아이콘을 아래로 붙이면 묶음이 4.8 위로 떴다.
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        modifier = modifier.fillMaxSize().clickable(onClick = onClick),
    ) {
        TabIcon(tab, tint, 22.dp)
        Text(tab.stubLabel, fontSize = 11.sp, color = tint, textAlign = TextAlign.Center)
    }
}

/** 목업의 홈 글자색. 핀 보라보다 한 톤 짙어 흰 바탕에서 읽힌다. */
private val HOME_PURPLE = Color(0xFF5B49D6)

@Composable
private fun HomeTab(
    active: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    // **`wrapContentSize(unbounded = true)` 가 핵심이다.** 원 62dp + 간격 3dp + 글자가
    // 바 높이 56dp보다 크다. Compose 의 `Column` 은 자식마다 남은 높이를 예산처럼
    // 나눠 주므로(iOS `VStack` 은 그렇지 않다) — 원이 예산 56dp를 다 써버리면 다음
    // 자식인 "홈" 글자는 남은 높이 0dp로 측정돼 완전히 사라진다(2026-09-28 실측 —
    // `unbounded` 없이는 `Text` 가 화면은커녕 접근성 트리에도 안 잡혔다).
    // `unbounded = true` 는 이 Column 을 부모의 56dp 제약과 무관하게 제 내용 크기
    // (~80dp)대로 측정하게 하고, 부모 Box 의 `contentAlignment = Center` 가 그 모양
    // 그대로를 56dp 칸 가운데에 앉힌다 — iOS 가 `.offset` 을 먼저 먹이고 그 결과를
    // 무한 프레임이 가운데로 앉히는 것과 같은 효과다.
    Box(
        modifier = modifier.fillMaxSize().clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.wrapContentSize(unbounded = true).offset(y = (-18).dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(62.dp)
                        // iOS `.shadow(pinDeep 42%, r7, y6)` — 아래로 퍼지는 보라 글로우. Android elevation 그림자는
                        // 옅은 회색에 가까워 직접 그린다(2차 대조).
                        .drawBehind {
                            val glow = size.minDimension / 2 + 9.dp.toPx()
                            val c = center + Offset(0f, 6.dp.toPx())
                            drawCircle(
                                Brush.radialGradient(
                                    0.6f to IOS.pinDeep.copy(alpha = 0.42f),
                                    1f to Color.Transparent,
                                    center = c,
                                    radius = glow,
                                ),
                                radius = glow,
                                center = c,
                            )
                        }.clip(CircleShape)
                        .background(Brush.linearGradient(colors = listOf(IOS.pinLight, IOS.pinDeep)))
                        .border(4.dp, IOS.systemGray6, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.haetae_face),
                    contentDescription = tr("홈"),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 40.dp, height = 34.dp),
                )
            }
            Text(
                tr("홈"),
                fontSize = 11.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (active) HOME_PURPLE else IOS.secondaryLabel,
            )
        }
    }
}

/**
 * 탭 아이콘. 검색은 Material 기본 아이콘이 SF Symbols 와 거의 같다. 커뮤니티는 없다
 * — material-icons-core 에 말풍선 쌍이 없고, 전체 아이콘 묶음(material-icons-extended)은
 * 수천 개짜리라 아이콘 하나 때문에 넣을 것이 아니다. 그래서 SF Symbols 모양을 보고
 * 직접 그린다.
 */
@Composable
private fun TabIcon(
    tab: RootTab,
    tint: Color,
    size: androidx.compose.ui.unit.Dp,
) {
    when (tab) {
        RootTab.SEARCH -> {
            // SF `magnifyingglass` 20pt 는 글리프가 약 22pt — Material 것은 24 격자 안에 작게 그려져 30% 작았다.
            MagnifierIcon(tint, Modifier.size(size))
        }

        // 가운데 원형 버튼이 대신한다.
        RootTab.HOME -> {}

        // `bubble.left.and.bubble.right` — 말풍선 둘이 겹친다. 커뮤니티 탭의 빈
        // 상태와 같은 그림이다([com.mz2az.scenetrip.ui.BubblesIcon]).
        RootTab.COMMUNITY -> {
            BubblesIcon(tint = tint, modifier = Modifier.size(width = size + 6.dp, height = size))
        }
    }
}
