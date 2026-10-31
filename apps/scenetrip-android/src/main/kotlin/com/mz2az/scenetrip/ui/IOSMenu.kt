package com.mz2az.scenetrip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/** iOS `Menu` 의 한 줄 — 글자와 앞의 그림. 파괴 역할(`role: .destructive`)은 빨갛게. */
data class IOSMenuItem(
    val label: String,
    val destructive: Boolean = false,
    val icon: @Composable (Color, Modifier) -> Unit,
    val onClick: () -> Unit,
)

/**
 * iOS 26 `Menu` — 누른 「⋯」 단추 위로 펼쳐져 단추를 덮는 유리 카드(카드 윗변이 점 중심 13.5dp 위, 오른쪽 끝이 14dp 오른쪽). 화면 가장자리에선 안쪽으로 밀린다.
 * 이 컴포저블을 **단추 자리 안에** 둔다 — 그 자리를 기준으로 뜬다. 바깥을 누르면 닫힌다.
 */
@Composable
fun IOSMenuPopover(
    items: List<IOSMenuItem>,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val provider =
        remember(density) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                    val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                    // iOS 26 메뉴는 「⋯」 에서 펼쳐져 단추를 덮는다(6차 실측 report-ios: 카드 윗변이 점 중심보다 13.5pt 위,
                    // 오른쪽 끝이 점 중심보다 14pt 오른쪽). 카드는 팝업 안쪽 12dp 여백 안에 있고 앵커는 44dp 상자(중심 22dp).
                    val dx = with(density) { 6.dp.roundToPx() }
                    val dy = with(density) { 8.6.dp.roundToPx() } // 8차 RM1: 팝오버가 iOS 보다 3.6 낮았다(5 → 8.6)
                    return IntOffset(
                        (anchorBounds.right + dx - popupContentSize.width).coerceIn(0, maxX),
                        (anchorBounds.top - dy).coerceIn(0, maxY),
                    )
                }
            }
        }
    Popup(popupPositionProvider = provider, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(
            modifier =
                Modifier
                    .padding(12.dp)
                    .shadow(
                        18.dp,
                        RoundedCornerShape(22.dp),
                        ambientColor = Color.Black.copy(alpha = 0.2f),
                        spotColor = Color.Black.copy(alpha = 0.2f),
                    ).clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFFF5F5F6))
                    // iOS 실측(5차 감사 report): 폭 249 · 구분선 없음 · 줄 간격 42 · 위 9 아래 10 (전: 폭 231 · 구분선 · 줄 48).
                    .width(249.dp)
                    .padding(top = 9.dp, bottom = 10.dp),
        ) {
            items.forEachIndexed { index, item ->
                val tint = if (item.destructive) IOS.systemRed else IOS.label
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onDismiss()
                                item.onClick()
                            }.height(42.dp)
                            .padding(horizontal = 30.dp),
                ) {
                    item.icon(tint, Modifier.size(20.dp))
                    Text(item.label, fontSize = 17.sp, color = tint)
                }
            }
        }
    }
}
