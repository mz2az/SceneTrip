package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onTap: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onTap)
                .padding(horizontal = IOS.gutter, vertical = 10.dp),
    ) {
        Placeholder(width = 46.dp, height = 62.dp)

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

        // **하트를 여기 두지 않는다.** 8/11 회의가 「작품에는 찜(하트)」 를 확정했지만
        // iOS 에 아직 없다 (MZ2AZ-231 · MZ2AZ-251). 한쪽에만 먼저 넣으면 두 앱이
        // 갈리므로, 그 티켓에서 **양쪽에 동시에** 넣는다.
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
                .padding(horizontal = IOS.gutter, vertical = 10.dp),
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

        Placeholder(width = 54.dp, height = 54.dp)

        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f),
        ) {
            Text(place.name, style = IOS.subheadlineSemibold, color = IOS.label)
            Text(
                text = place.address,
                style = IOS.caption,
                color = IOS.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = place.subtitle,
                style = IOS.caption2,
                color = IOS.tertiaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // 담긴 상태에서 다시 누르면 뺀다. 아이콘을 `−` 로 바꾸지 않고 체크를
        // 유지하는 이유는 iOS 주석에 있다 — 이 자리의 첫 임무는 "이미 담겼는지" 를
        // 알려 주는 것이고, `−` 로 바꾸면 그 상태가 보이지 않는다.
        Icon(
            imageVector = if (saved) Icons.Filled.CheckCircle else Icons.Filled.AddCircle,
            contentDescription = if (saved) "장바구니에서 빼기" else "장바구니에 담기",
            tint = if (saved) IOS.accent else IOS.secondaryLabel,
            modifier =
                Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onAdd)
                    .padding(2.dp)
                    .size(24.dp),
        )
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

/**
 * 사진 자리.
 *
 * iOS 는 `RemoteImage` 로 실제 사진을 받아 오지만 여기서는 회색 네모다 —
 * **서버를 아직 부르지 않기 때문이지 디자인이 달라서가 아니다.** 크기와 모서리는
 * iOS 와 같게 두어, 클라이언트가 붙었을 때 레이아웃이 흔들리지 않게 한다.
 */
@Composable
private fun Placeholder(
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
) {
    Spacer(
        modifier =
            Modifier
                .size(width = width, height = height)
                .clip(RoundedCornerShape(6.dp))
                .background(IOS.systemGray6),
    )
}
