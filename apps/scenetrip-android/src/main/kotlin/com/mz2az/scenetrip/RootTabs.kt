package com.mz2az.scenetrip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.searchtab.SearchTabScreen
import com.mz2az.scenetrip.ui.IOS

/**
 * 앱의 최상위 — 하단 탭 넷을 든다.
 *
 * iOS `RootTabs.swift` 를 그대로 옮겼다. 그쪽 주석이 이유를 적어 뒀다:
 *
 * > **작품검색만 만들고 나머지 셋은 자리만 둔다.** 비어 있어도 지금 만드는 이유는
 * > 화면이 하나뿐인 앱과 넷 중 하나인 앱은 **검색 탭이 차지하는 세로 공간이 다르기**
 * > 때문이다 — 바텀시트의 최대 높이가 탭바 위까지다.
 *
 * 안드로이드에서는 이것을 빠뜨려 두 앱이 눈에 띄게 갈렸다(실측 2026-08-12) —
 * 탭바가 없으니 시트가 화면 끝까지 내려오고 화면 구성 자체가 달라 보였다.
 *
 * **`NavigationBar` 를 쓰지 않는다.** Material 의 탭바는 높이가 80 이고 선택된 칸에
 * 알약 배경이 깔린다. iOS 것은 52 높이에 칸마다 구분선이 있는 얇은 바다.
 */
@Composable
fun RootTabs() {
    var selected by remember { mutableStateOf(RootTab.SEARCH) }

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
            if (selected != RootTab.SEARCH) {
                StubTab(selected)
            }
        }
        TabBar(selected = selected, onSelect = { selected = it })
    }
}

enum class RootTab(
    val label: String,
) {
    SEARCH("작품검색"),
    ROUTE("경로여정"),
    COMMUNITY("커뮤니티"),
    PROFILE("마이페이지"),
}

/** 아직 만들지 않은 탭. 빈 화면 대신 무엇이 올 자리인지 말해 준다. */
@Composable
private fun StubTab(tab: RootTab) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize().background(IOS.systemBackground),
    ) {
        TabIcon(tab, IOS.tertiaryLabel, 44.dp)
        Text(tab.label, style = IOS.headline, color = IOS.secondaryLabel)
        Text("아직 준비 중입니다", style = IOS.subheadline, color = IOS.tertiaryLabel)
    }
}

/**
 * 얇고 칸이 나뉜 탭바. iOS 와 같은 **52 높이**다 — 기본 탭바(약 83)보다 낮게 잡아
 * 지도에 주는 세로 공간을 지킨다.
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
                    .height(52.dp)
                    .background(IOS.systemBackground),
        ) {
            RootTab.values().forEachIndexed { index, tab ->
                if (index > 0) {
                    Box(
                        Modifier
                            .align(Alignment.CenterVertically)
                            .width(0.5.dp)
                            .height(28.dp)
                            .background(IOS.separator),
                    )
                }
                val tint = if (selected == tab) IOS.accent else IOS.secondaryLabel
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clickable { onSelect(tab) }
                            .let { it },
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.BottomCenter) {
                        TabIcon(tab, tint, 16.dp)
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        Text(
                            text = tab.label,
                            fontSize = 10.sp,
                            color = tint,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        Box(Modifier.navigationBarsPadding().background(IOS.systemBackground).fillMaxWidth())
    }
}

/**
 * 탭 아이콘.
 *
 * 검색·마이페이지는 Material 기본 아이콘이 SF Symbols 와 거의 같다. **경로여정과
 * 커뮤니티는 없다** — material-icons-core 에 곡선 경로나 말풍선 쌍이 없고, 전체
 * 아이콘 묶음(material-icons-extended)은 수천 개짜리라 아이콘 둘 때문에 넣을 것이
 * 아니다. 그래서 SF Symbols 모양을 보고 직접 그린다.
 */
@Composable
private fun TabIcon(
    tab: RootTab,
    tint: Color,
    size: androidx.compose.ui.unit.Dp,
) {
    when (tab) {
        RootTab.SEARCH -> {
            Icon(Icons.Filled.Search, tab.label, Modifier.size(size), tint)
        }

        RootTab.PROFILE -> {
            Icon(Icons.Filled.Person, tab.label, Modifier.size(size), tint)
        }

        // `point.topleft.down.to.point.bottomright.curvepath` — 왼쪽 위 점에서
        // 오른쪽 아래 점으로 굽은 길이 이어진다.
        RootTab.ROUTE -> {
            Canvas(Modifier.size(size)) {
                val r = this.size.minDimension * 0.14f
                val w = this.size.width
                val h = this.size.height
                drawCircle(tint, r, Offset(r, r), style = Stroke(width = r * 0.7f))
                drawCircle(tint, r, Offset(w - r, h - r))
                drawPath(
                    Path().apply {
                        moveTo(r, r * 2.4f)
                        cubicTo(r, h * 0.8f, w - r, h * 0.2f, w - r, h - r * 2.4f)
                    },
                    tint,
                    style = Stroke(width = r * 0.7f),
                )
            }
        }

        // `bubble.left.and.bubble.right` — 말풍선 둘이 겹친다.
        RootTab.COMMUNITY -> {
            Canvas(Modifier.size(size)) {
                val w = this.size.width
                val h = this.size.height
                val s = w * 0.09f
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(0f, h * 0.08f),
                    size =
                        androidx.compose.ui.geometry
                            .Size(w * 0.62f, h * 0.52f),
                    cornerRadius =
                        androidx.compose.ui.geometry
                            .CornerRadius(w * 0.16f),
                    style = Stroke(width = s),
                )
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(w * 0.36f, h * 0.36f),
                    size =
                        androidx.compose.ui.geometry
                            .Size(w * 0.62f, h * 0.52f),
                    cornerRadius =
                        androidx.compose.ui.geometry
                            .CornerRadius(w * 0.16f),
                    style = Stroke(width = s),
                )
            }
        }
    }
}
