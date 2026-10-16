package com.mz2az.scenetrip.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
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
 * iOS 26 `Menu` — 누른 단추 **아래에 붙어** 뜨는 유리 카드. 단추의 오른쪽 끝에 맞춰 열린다(오른쪽 위에 놓인 「⋯」 의 모양).
 * 이 컴포저블을 **단추 자리 안에** 둔다 — 그 자리를 기준으로 뜬다. 바깥을 누르면 닫힌다.
 */
@Composable
fun IOSMenuPopover(
    items: List<IOSMenuItem>,
    onDismiss: () -> Unit,
) {
    val provider =
        remember {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
                    val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                    return IntOffset(
                        (anchorBounds.right - popupContentSize.width).coerceIn(0, maxX),
                        anchorBounds.bottom.coerceIn(0, maxY),
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
                    .background(if (IOS.isDark) Color(0xFF2C2C2E) else Color(0xFFF5F5F6))
                    .width(232.dp),
        ) {
            items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(thickness = 0.5.dp, color = IOS.separator.copy(alpha = 0.25f))
                val tint = if (item.destructive) IOS.systemRed else IOS.label
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier =
                        Modifier
                            .clickable {
                                onDismiss()
                                item.onClick()
                            }.height(48.dp)
                            .padding(horizontal = 18.dp),
                ) {
                    item.icon(tint, Modifier.size(20.dp))
                    Text(item.label, fontSize = 17.sp, color = tint)
                }
            }
        }
    }
}
