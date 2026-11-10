package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.coach.coachTarget
import com.mz2az.scenetrip.data.RoutePoiTone
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup
import com.mz2az.scenetrip.ui.ArrowClockwiseIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.MagnifierIcon
import com.mz2az.scenetrip.ui.MagnifierZoomIcon

/**
 * 지도 맨 위 편의시설 칩 줄 (MZ2AZ-547 M1·M3, iOS `MapPoiBar`, 웹 `proto/protoPoi.tsx` `MapPoiBar`).
 *
 * 🔍 · 전체 · 음식점 · 명소 · 숙소 · 교통. 켜진 칩은 갈래 색 바탕에 흰 글자, 꺼진 칩은 흰 바탕.
 * 칩 옆 개수는 뺐다 — 늘 받은 수(200)라 지도에 보이는 아이콘 수와 달랐다(iOS 6f84954a).
 * 그 아래 가운데에 상태 알약 하나 — 「찾는 중」 / 「↻ 이 지역에서 다시 찾기」 / 「조금 더 확대하면 보여요」.
 * 무엇을 언제 받는지는 [MapPoiChips]·[MapPoiFetch] 가 정하고 여기는 그 답을 그린다.
 */
@Composable
internal fun MapPoiBar(
    chips: MapPoiChips,
    pill: MapPoiPill,
    onSearch: () -> Unit,
    onToggle: (PoiCategoryGroup) -> Unit,
    onAll: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            SearchCircle(onSearch)
            ChipFace(on = chips.allOn, fill = IOS.homePurple, onClick = onAll, modifier = Modifier.coachTarget("map-chips")) {
                Text(tr("전체"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (chips.allOn) Color.White else IOS.label)
            }
            MapPoiChips.ORDER.forEach { group ->
                val on = chips.isOn(group)
                val tone = RoutePoiTone.of(group.toRoutePoiGroup())
                ChipFace(on = on, fill = tone, onClick = { onToggle(group) }) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (on) Color.White else tone))
                    Text(group.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (on) Color.White else IOS.label)
                }
            }
        }
        when (pill) {
            MapPoiPill.NONE -> {
                Unit
            }

            MapPoiPill.SEARCHING -> {
                MapStatusPill {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = IOS.secondaryLabel, modifier = Modifier.size(14.dp))
                    Text(tr("찾는 중"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                }
            }

            MapPoiPill.ZOOM_IN -> {
                MapStatusPill {
                    MagnifierZoomIcon(IOS.label, plus = true, modifier = Modifier.size(14.dp))
                    Text(tr("조금 더 확대하면 보여요"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                }
            }

            MapPoiPill.REFRESH -> {
                MapRefreshPill(onRefresh)
            }
        }
    }
}

/** 검색 — 칩 줄 맨 앞. 평소엔 동그라미 하나라 지도를 가리지 않는다. */
@Composable
private fun SearchCircle(onClick: () -> Unit) {
    val label = tr("장소 검색")
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                // 처음 안내(C5)가 가리킨다.
                .coachTarget("map-search")
                .size(34.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(IOS.systemBackground)
                .border(1.dp, IOS.systemGray5, CircleShape)
                .semantics { contentDescription = label }
                .clickable(role = Role.Button, onClick = onClick),
    ) {
        MagnifierIcon(IOS.label, Modifier.size(16.dp))
    }
}

@Composable
private fun ChipFace(
    on: Boolean,
    fill: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .height(34.dp)
                .shadow(3.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(if (on) fill else IOS.systemBackground)
                .border(1.dp, if (on) Color.Transparent else IOS.systemGray5, RoundedCornerShape(50))
                .semantics { selected = on }
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 12.dp),
    ) { content() }
}

/** 흰 알약 — 상태 한 줄. */
@Composable
internal fun MapStatusPill(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .height(34.dp)
                .shadow(8.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(IOS.systemBackground)
                .padding(horizontal = 14.dp),
    ) { content() }
}

/** 「↻ 이 지역에서 다시 찾기」 — 칩 줄 아래와 검색 결과 지도가 같이 쓴다. */
@Composable
internal fun MapRefreshPill(onClick: () -> Unit) {
    MapStatusPill(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        ArrowClockwiseIcon(IOS.homePurple, Modifier.size(14.dp))
        Text(tr("이 지역에서 다시 찾기"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = IOS.homePurple)
    }
}
