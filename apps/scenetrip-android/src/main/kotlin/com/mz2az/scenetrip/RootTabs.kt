package com.mz2az.scenetrip

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.Cover
import com.mz2az.scenetrip.data.RootTab
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.searchtab.SearchTabScreen
import com.mz2az.scenetrip.ui.IOS

/**
 * 앱의 최상위 — 하단 탭 셋과, 탭에서 내려온 두 화면의 덮개를 든다.
 *
 * 2026-09-01 홈 재편(`docs/project/plans/mobile-home-tab.md`, iOS `RootTabs.swift`를
 * 그대로 옮겼다): 탭 넷(작품검색·경로여정·커뮤니티·마이페이지)이 **셋**(작품검색 ·
 * 가운데 동그란 홈 · 커뮤니티)이 됐고 첫 화면은 홈이다. 경로여정은 홈의 "내 여행
 * 이어가기" 카드가, 마이페이지는 홈 오른쪽 위 프로필 단추가 입구다 — [TabRouter.cover]
 * 로 전체 화면에 띄운다. 두 화면 다 아직 Android 에 없어 [CoverPlaceholder]로 자리만
 * 잡아 둔다.
 *
 * 탭 선택·덮개는 [TabRouter]가 든다 — 마이페이지가 "경로여정에서 열기"로, 홈이
 * "코스 보기"로 화면을 바꿀 수 있어야 해서다.
 */
@Composable
fun RootTabs() {
    val selected = TabRouter.selected

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // 검색 탭은 항상 살려 둔다 — 다른 탭에 갔다 와도 지도와 검색 결과가
                // 그대로여야 한다. iOS 가 `opacity` + `allowsHitTesting` 으로 하는 것과
                // 같다. 여기서 조건부로 그리면 지도 SDK 가 매번 다시 뜬다.
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .alpha(if (selected == RootTab.SEARCH) 1f else 0f),
                ) {
                    SearchTabScreen()
                }
                // 홈도 검색처럼 항상 살려 둔다 — 서버를 다시 부르지 않기 위해서다.
                // 실제 HomeTabScreen 은 아직 없다(다음 단계) — 그때까지 자리표시자.
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .alpha(if (selected == RootTab.HOME) 1f else 0f),
                ) {
                    StubTab(RootTab.HOME)
                }
                // 커뮤니티는 값이 싸서 선택했을 때만 만들고 버린다.
                if (selected == RootTab.COMMUNITY) {
                    StubTab(RootTab.COMMUNITY)
                }
            }
            TabBar(selected = selected, onSelect = { TabRouter.selected = it })
        }

        TabRouter.cover?.let { cover ->
            CoverPlaceholder(cover, onClose = { TabRouter.cover = null })
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
        Text("아직 준비 중입니다", style = IOS.subheadline, color = IOS.tertiaryLabel)
    }
}

private val RootTab.stubLabel: String
    get() =
        when (this) {
            RootTab.SEARCH -> "작품검색"
            RootTab.HOME -> "홈"
            RootTab.COMMUNITY -> "커뮤니티"
        }

/**
 * 경로여정·마이페이지 덮개 자리표시자. iOS 는 실제 `RouteTabView`/`ProfileTabView`를
 * `onClose`와 함께 띄우는데, 두 화면 다 Android 에 아직 없어(RouteTab·ProfileTab 이식은
 * 다음 단계들) 라벨 + 닫기 단추만 그린다.
 */
@Composable
private fun CoverPlaceholder(
    cover: Cover,
    onClose: () -> Unit,
) {
    val label =
        when (cover) {
            is Cover.Route -> if (cover.market) "경로여정 · 둘러보기" else "경로여정"
            Cover.Profile -> "마이페이지"
        }
    Column(
        modifier = Modifier.fillMaxSize().background(IOS.systemBackground),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "닫기", tint = IOS.label)
            }
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(label, style = IOS.headline, color = IOS.secondaryLabel)
            Text("아직 준비 중입니다", style = IOS.subheadline, color = IOS.tertiaryLabel)
        }
    }
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
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.fillMaxSize().clickable(onClick = onClick),
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
            TabIcon(tab, tint, 20.dp)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
            Text(tab.stubLabel, fontSize = 11.sp, color = tint, textAlign = TextAlign.Center)
        }
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier =
            modifier
                .fillMaxSize()
                .clickable(onClick = onClick)
                .offset(y = (-18).dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(62.dp)
                    .shadow(7.dp, CircleShape, ambientColor = IOS.pinDeep, spotColor = IOS.pinDeep)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(colors = listOf(IOS.pinLight, IOS.pinDeep)))
                    .border(4.dp, IOS.systemGray6, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.haetae_face),
                contentDescription = "홈",
                contentScale = ContentScale.Fit,
                modifier = Modifier.size(width = 40.dp, height = 34.dp),
            )
        }
        Text(
            "홈",
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) HOME_PURPLE else IOS.secondaryLabel,
        )
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
            Icon(Icons.Filled.Search, tab.stubLabel, Modifier.size(size), tint)
        }

        // 가운데 원형 버튼이 대신한다.
        RootTab.HOME -> {}

        // `bubble.left.and.bubble.right` — 말풍선 둘이 겹친다.
        RootTab.COMMUNITY -> {
            Canvas(Modifier.size(size)) {
                val w = this.size.width
                val h = this.size.height
                val stroke = w * 0.09f
                val radius =
                    androidx.compose.ui.geometry
                        .CornerRadius(w * 0.16f)

                fun bubble(
                    x: Float,
                    y: Float,
                    bw: Float,
                    bh: Float,
                    tailAt: Float,
                ) {
                    drawRoundRect(
                        color = tint,
                        topLeft = Offset(x, y),
                        size =
                            androidx.compose.ui.geometry
                                .Size(bw, bh),
                        cornerRadius = radius,
                        style = Stroke(width = stroke),
                    )
                    val tx = x + bw * tailAt
                    drawLine(tint, Offset(tx, y + bh), Offset(tx, y + bh + h * 0.12f), strokeWidth = stroke)
                }

                bubble(0f, h * 0.06f, w * 0.60f, h * 0.42f, tailAt = 0.24f)
                bubble(w * 0.40f, h * 0.34f, w * 0.60f, h * 0.42f, tailAt = 0.76f)
            }
        }
    }
}
