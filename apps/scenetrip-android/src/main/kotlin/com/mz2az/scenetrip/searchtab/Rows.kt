package com.mz2az.scenetrip.searchtab

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 목록 행. iOS `Rows.swift` 와 짝이다.
 *
 * **번호는 지도 핀과 목록을 잇는 장치다.** 행의 N 번이 곧 지도의 N 번이어야 하므로
 * 번호를 여기서 새로 세지 않고 밖에서 받는다 — 목록을 거른 뒤의 순서가 곧 번호다.
 */
@Composable
fun PlaceRow(
    place: PlaceSummary,
    number: Int?,
    inCart: Boolean,
    onTap: () -> Unit,
    onToggleCart: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (number != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
            ) {
                Text(
                    text = "$number",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = if (number != null) 12.dp else 0.dp),
        ) {
            Text(place.name, fontWeight = FontWeight.SemiBold)
            Text(
                text = place.address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // **장소는 플러스, 담기면 체크다.** 작품의 하트와 짝을 이루는 규칙이라
        // (8/11 회의 확정) 여기서 임의로 바꾸지 않는다.
        Text(
            text = if (inCart) "✓" else "＋",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .clickable(onClick = onToggleCart)
                    .padding(8.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

/**
 * 작품 행.
 *
 * **작품에는 하트다** — 장소의 장바구니와 별개 저장소다 (MZ2AZ-231 · MZ2AZ-251).
 * 서버가 아직 없어 지금은 화면 안에서만 유지된다.
 */
@Composable
fun ContentRow(
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(content.title, fontWeight = FontWeight.SemiBold)
            Text(
                text = "${content.year} · ${content.type} · 촬영지 ${content.placeCount}곳",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = if (liked) "♥" else "♡",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier
                    .clickable(onClick = onToggleLike)
                    .padding(8.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}
