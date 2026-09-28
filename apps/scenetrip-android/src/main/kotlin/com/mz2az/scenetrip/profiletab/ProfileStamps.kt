package com.mz2az.scenetrip.profiletab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.VisitStamp
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.SheetDetent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 방문 스탬프첩. iOS `ProfileTab/ProfileStamps.swift`의 `StampsSheet`를 옮긴 것이다.
 *
 * 도장첩처럼 격자로 찍힌다 — 목록이 아니라 모은 것으로 보여야 다음 성지를 찍으러
 * 가고 싶어진다.
 */
@Composable
private fun StampsSheetBody(
    stamps: List<VisitStamp>,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
        ProfileSheetHeader("방문 스탬프", onClose)
        if (stamps.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("아직 스탬프가 없습니다", style = IOS.headline, color = IOS.secondaryLabel)
                Text(
                    "여행 중 성지 100m 안에 들어가면 저절로 찍혀요",
                    style = IOS.footnote,
                    color = IOS.tertiaryLabel,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(stamps, key = { it.id }) { stamp ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        StampBadge(stamp, size = 88.dp)
                        Text(formatDate(stamp.visitedAt.toInstant().toEpochMilli()), fontSize = 11.sp, color = IOS.secondaryLabel)
                        stamp.workTitle?.let {
                            Text(it, fontSize = 11.sp, color = IOS.accent, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** 도장 하나 — 피노 색 이중 원 테에 살짝 기울여 "찍었다"는 손맛을 준다. */
@Composable
fun StampBadge(
    stamp: VisitStamp,
    size: Dp = 64.dp,
) {
    val angle = (stamp.id % 7).toFloat() - 3f
    // **겹쳐 그린다(Box).** Column 이면 테 그림(Canvas)이 칸을 다 먹어 체크·이름이 그 아래로 밀려
    // 잘렸다 — 도장 안이 텅 빈 원으로 보였다(2026-09-28 실기).
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(size)
                .rotate(angle)
                .clip(CircleShape),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.linearGradient(listOf(IOS.pinLight, IOS.pinDeep)),
                style = Stroke(width = (size / 29).toPx()),
            )
            drawCircle(
                color = IOS.pinDeep.copy(alpha = 0.35f),
                radius = size.toPx() / 2 - (size / 18).toPx(),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = IOS.pinDeep,
                modifier = Modifier.size(size * 0.18f),
            )
            Text(
                stamp.name,
                fontSize = (size.value * 0.1f).sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                color = IOS.label,
                modifier = Modifier.padding(horizontal = size * 0.09f),
            )
        }
    }
}

private fun formatDate(millis: Long): String = SimpleDateFormat("M월 d일", Locale.KOREA).format(Date(millis))

/** iOS 에서 `.sheet` 로 뜬다 — 아래에서 올라오는 시트([IOSSheet]). */
@Composable
fun StampsSheet(
    stamps: List<VisitStamp>,
    onClose: () -> Unit,
) {
    IOSSheet(detents = listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onClose) {
        StampsSheetBody(stamps = stamps, onClose = onClose)
    }
}
