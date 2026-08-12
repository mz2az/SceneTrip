package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.ui.IOS

/**
 * 목록 행. iOS `SearchTab/Rows.swift` 를 그대로 옮긴 것이다.
 *
 * **치수를 짐작하지 않는다.** 포스터 46×62, 촬영지 사진 54×54, 모서리 6, 번호 배지
 * 22, 행 여백 가로 14 · 세로 10 — 전부 iOS 소스에 적힌 값이다. 하나라도 다르면 두
 * 앱을 나란히 놓았을 때 눈에 띈다.
 */
@Composable
fun WorkRow(
    content: ContentSummary,
    liked: Boolean,
    onTap: () -> Unit,
    onToggleLike: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(horizontal = IOS.gutter, vertical = 7.dp),
    ) {
        RemoteImage(
            url = content.posterUrl?.toString(),
            modifier =
                Modifier
                    .size(width = 46.dp, height = 62.dp)
                    .clip(RoundedCornerShape(6.dp)),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(content.title, style = IOS.headline, color = IOS.label)
            Text(content.meta, style = IOS.caption, color = IOS.secondaryLabel)
            // "촬영지 N" 은 회색 캡슐 안에 들어간다 — iOS 와 같은 모양이다.
            Text(
                text = "촬영지 ${content.placeCount}",
                style = IOS.caption2,
                color = IOS.secondaryLabel,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(IOS.systemGray6)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        // **작품에는 하트, 장소에는 플러스** (8/11 회의 확정). 장소의 `+` 와 같은
        // 자리에 두어 사용자가 규칙을 한 번만 배우게 한다. iOS `Rows.swift` 의
        // `WorkRow` 와 같은 아이콘·같은 색이다.
        Icon(
            imageVector = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (liked) "찜 빼기" else "찜하기",
            tint = if (liked) IOS.accent else IOS.secondaryLabel,
            modifier =
                Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onToggleLike)
                    .padding(2.dp)
                    .size(24.dp),
        )
        Chevron()
    }
}

@Composable
fun PlaceRow(
    place: PlaceSummary,
    number: Int?,
    saved: Boolean,
    onTap: () -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(horizontal = IOS.gutter, vertical = 7.dp),
    ) {
        // 지도 핀에 박힌 번호와 같은 값 — 행과 핀을 눈으로 잇는 다리다. 그래서
        // 배지의 그러데이션도 핀과 **같은 두 색**이어야 한다.
        if (number != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(IOS.pinLight, IOS.pinDeep))),
            ) {
                Text("$number", style = IOS.caption2Heavy, color = IOS.systemBackground)
            }
        }

        RemoteImage(
            url = place.imageUrl?.toString(),
            modifier =
                Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(6.dp)),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(place.name, style = IOS.subheadlineSemibold, color = IOS.label)
            Text(
                text = place.address.orEmpty(),
                style = IOS.caption,
                color = IOS.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = place.worksAndType,
                style = IOS.caption2,
                color = IOS.tertiaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 담긴 상태에서 다시 누르면 뺀다. 아이콘을 `−` 로 바꾸지 않고 체크를
        // 유지하는 이유는 iOS 주석에 있다 — 이 자리의 첫 임무는 "이미 담겼는지" 를
        // 알려 주는 것이고, `−` 로 바꾸면 그 상태가 보이지 않는다.
        // iOS 는 `plus.circle`(**테두리만 있는 원**) → `checkmark.circle.fill`(채움)
        // 이다. Material 의 `Outlined.AddCircle` 도 원 안이 채워져 나와(대조 검사)
        // 담기 전부터 담은 것처럼 보인다. 그래서 선 원은 직접 그린다.
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onAdd)
                    .padding(2.dp)
                    .size(24.dp),
        ) {
            if (saved) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "장바구니에서 빼기",
                    tint = IOS.accent,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                PlusCircle(IOS.secondaryLabel, Modifier.size(24.dp))
            }
        }
        Chevron()
    }
}

/** iOS 의 `chevron.right` — 목록 행 끝의 화살표. */
@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = IOS.tertiaryLabel,
        modifier = Modifier.size(16.dp),
    )
}

/** iOS 의 `plus.circle` — 테두리만 있는 원 안에 ＋. */
@Composable
private fun PlusCircle(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        val w = r * 0.13f
        drawCircle(tint, r - w / 2, c, style = Stroke(width = w))
        val arm = r * 0.46f
        drawLine(tint, Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), strokeWidth = w)
        drawLine(tint, Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), strokeWidth = w)
    }
}
