package com.mz2az.scenetrip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.data.ApiFailure
import com.mz2az.scenetrip.data.UnavailableItem
import com.mz2az.scenetrip.data.tr

/** 숨김·폐업 행을 약하게 보이는 정도. iOS 는 `opacity(0.65)`. */
const val UNAVAILABLE_ROW_ALPHA = 0.65f

/** 숨김·폐업이면 약하게, 아니면 그대로 보이는 알파. */
fun unavailableAlpha(notice: UnavailableItem?): Float = if (notice == null) 1f else UNAVAILABLE_ROW_ALPHA

/** iOS `exclamationmark.circle` — 원 안에 느낌표. */
@Composable
private fun ExclamationCircle(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        val w = r * 0.14f
        drawCircle(tint, r - w / 2, c, style = Stroke(width = w))
        drawLine(tint, Offset(c.x, c.y - r * 0.50f), Offset(c.x, c.y + r * 0.10f), strokeWidth = w * 1.1f, cap = StrokeCap.Round)
        drawCircle(tint, w * 0.75f, Offset(c.x, c.y + r * 0.45f))
    }
}

/** 「더 이상 소개하지 않는 장소예요」 같은 한 줄 안내. iOS `UnavailableItemNotice`. */
@Composable
fun UnavailableItemNotice(
    item: UnavailableItem,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        ExclamationCircle(IOS.secondaryLabel, Modifier.size(12.dp))
        Text(item.message, style = IOS.caption, color = IOS.secondaryLabel)
    }
}

/** 안내 옆의 「빼기」 단추. 눌러서 직접 빼게 한다 — 서버가 지우지 않는다. 눌리는 자리는 높이 48dp 이상이다. */
@Composable
fun UnavailableRemoveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            modifier
                .defaultMinSize(minHeight = 48.dp)
                .clip(RoundedCornerShape(8.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 8.dp),
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null, tint = IOS.systemRed, modifier = Modifier.size(14.dp))
        Text(tr("빼기"), style = IOS.caption, color = IOS.systemRed)
    }
}

/** 안내 한 줄과 「빼기」 — 코스 행 아래에 붙는다. */
@Composable
fun UnavailableNoticeRow(
    item: UnavailableItem,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = modifier.fillMaxWidth(),
    ) {
        UnavailableItemNotice(item, Modifier.weight(1f))
        UnavailableRemoveButton(onRemove)
    }
}

/**
 * 찜을 바꾸지 못했을 때의 안내. 검색 목록·상세, 코스 마법사, 찜 목록이 같은 문구를 쓴다. iOS `likeFailureNotice`.
 * 실패가 없으면 아무것도 그리지 않는다.
 */
@Composable
fun LikeFailureNotice(
    failure: ApiFailure?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (failure == null) return
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(IOS.systemBackground.copy(alpha = 0.96f))
                .padding(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("찜을 바꾸지 못했습니다"), style = IOS.caption.copy(fontWeight = FontWeight.SemiBold), color = IOS.label)
            Text(failure.message, style = IOS.caption, color = IOS.label)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clickable(role = Role.Button, onClick = onClose),
        ) {
            Text(tr("닫기"), style = IOS.caption, color = IOS.accent)
        }
    }
}
