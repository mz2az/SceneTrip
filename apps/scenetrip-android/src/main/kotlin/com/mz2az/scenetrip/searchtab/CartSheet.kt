package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.ui.IOS

/**
 * 장바구니 시트.
 *
 * iOS `SearchTab/CartSheet.swift` 와 같은 자리다. 담긴 장소를 보여 주고 빼기만 한다 —
 * 코스로 만드는 것은 경로여정 탭의 몫이다.
 *
 * **행에서 빼면 확인창을 두지 않는다.** 담기가 한 번에 되는데 빼기만 물어보면 무겁고,
 * 잘못 빼도 다시 담으면 그만이라 되돌리는 비용이 낮다 (iOS `Rows.swift` 의 판단).
 */
@Composable
fun CartSheet(
    items: List<CartItem>,
    onRemove: (Long) -> Unit,
    onClose: () -> Unit,
) {
    // 지도·시트를 덮는 스크림. 바깥을 누르면 닫힌다 — iOS 의 시트와 같은 동작이다.
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    androidx.compose.ui.graphics.Color.Black
                        .copy(alpha = 0.25f),
                ).clickable(onClick = onClose),
    )
    Box(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxSize(0.62f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(IOS.systemBackground),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(width = 40.dp, height = 5.dp)
                            .clip(CircleShape)
                            .background(IOS.systemGray3),
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = IOS.gutter, vertical = 8.dp),
            ) {
                Text("장바구니", style = IOS.headline, color = IOS.label)
                Text(
                    text = " ${items.size}",
                    style = IOS.headline,
                    color = IOS.accent,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "닫기",
                    tint = IOS.secondaryLabel,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onClose).size(22.dp),
                )
            }
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(IOS.separator))

            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "담은 장소가 없습니다",
                        style = IOS.subheadline,
                        color = IOS.secondaryLabel,
                    )
                }
            } else {
                LazyColumn {
                    items(items, key = { it.placeId }) { item ->
                        CartRow(item = item, onRemove = { onRemove(item.placeId) })
                        Box(
                            Modifier
                                .padding(start = IOS.gutter)
                                .fillMaxWidth()
                                .height(0.5.dp)
                                .background(IOS.separator),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CartRow(
    item: CartItem,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = IOS.gutter, vertical = 10.dp),
    ) {
        RemoteImage(
            url = item.imageUrl?.toString(),
            modifier = Modifier.size(46.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = IOS.subheadlineSemibold,
                color = IOS.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.address.orEmpty(),
                style = IOS.caption,
                color = IOS.secondaryLabel,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.Close,
            contentDescription = "빼기",
            tint = IOS.secondaryLabel,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onRemove).size(20.dp),
        )
    }
}
