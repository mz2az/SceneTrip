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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.ui.IOS

/**
 * 장바구니 — 담은 장소를 보여 주고 뺄 수 있게 한다.
 *
 * iOS `SearchTab/CartSheet.swift` 를 옮긴 것이다. 그 주석이 경계를 정확히 적어 뒀다 —
 * **"담기까지만 (루트 만들기는 MVP1 범위 밖)"**. 담긴 것을 보고 빼는 것까지가 여기이고,
 * 이 목록을 코스로 엮는 것은 경로여정 탭이다.
 *
 * **화면 전체를 덮는 모달이다.** 절반짜리 바텀시트로 만들었더니 지도와 탭바가 그대로
 * 보여 iOS 와 다른 화면이 됐다(대조 검사). iOS 는 `NavigationStack` 을 시트로 띄우므로
 * 아래가 보이지 않는다.
 *
 * **순번을 매기는 이유**도 iOS 주석에 있다 — 담은 순서가 나중에 코스의 기본 순서가
 * 된다. 계약도 "담은 순서(오래된 것부터)로 돌려준다" 고 적어 뒀다.
 */
@Composable
fun CartSheet(
    items: List<CartItem>,
    onRemove: (Long) -> Unit,
    onClose: () -> Unit,
) {
    // **`Dialog` 로 띄운다.** 검색 탭 안에 그리면 하단 탭바가 그대로 남아 "다른
    // 화면" 으로 보이지 않는다(대조 검사). `usePlatformDefaultWidth = false` 라야
    // 좌우 여백 없이 화면을 다 덮는다.
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        CartContent(items = items, onRemove = onRemove, onClose = onClose)
    }
}

@Composable
private fun CartContent(
    items: List<CartItem>,
    onRemove: (Long) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(IOS.systemBackground)
                .statusBarsPadding(),
    ) {
        // iOS 는 `navigationBarTitleDisplayMode(.inline)` — 제목이 가운데,
        // 「닫기」가 오른쪽이다. 아이콘 ✕ 가 아니라 **글자**다.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        ) {
            Text(
                text = if (items.isEmpty()) "장바구니" else "장바구니 ${items.size}곳",
                style = IOS.headline,
                color = IOS.label,
            )
            Text(
                text = "닫기",
                style = IOS.body,
                // iOS 는 이 글자를 검정으로 그린다(실측). 강조색이 아니다.
                color = IOS.label,
                modifier =
                    Modifier
                        .align(Alignment.CenterEnd)
                        .clip(CircleShape)
                        .clickable(onClick = onClose)
                        .padding(horizontal = IOS.gutter, vertical = 4.dp),
            )
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(IOS.separator))

        if (items.isEmpty()) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                modifier = Modifier.fillMaxSize(),
            ) {
                Text("담은 장소가 없습니다", style = IOS.headline, color = IOS.secondaryLabel)
                Text(
                    "장소를 저장하면 여기에 모입니다",
                    style = IOS.subheadline,
                    color = IOS.tertiaryLabel,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                itemsIndexed(items, key = { _, item -> item.placeId }) { index, item ->
                    CartRow(index = index, item = item, onRemove = { onRemove(item.placeId) })
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
        Box(Modifier.navigationBarsPadding().fillMaxWidth())
    }
}

@Composable
private fun CartRow(
    index: Int,
    item: CartItem,
    onRemove: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = IOS.gutter, vertical = 14.dp),
    ) {
        // **강조색 원에 흰 숫자** — 목록의 번호 배지(핀과 짝을 이루는 보라
        // 그러데이션)와는 다른 것이다. 여기 번호는 담은 순서이지 지도 핀이 아니다.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(24.dp).clip(CircleShape).background(IOS.accent),
        ) {
            Text("${index + 1}", style = IOS.caption2Heavy, color = IOS.systemBackground)
        }

        RemoteImage(
            url = item.imageUrl?.toString(),
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = IOS.subheadlineSemibold,
                color = IOS.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 어느 작품 때문에 담았는지를 서버가 기억한다 (sourceContentId).
            // 같은 장소라도 담은 맥락이 다르면 사용자에게는 다른 의미다.
            val second = item.sourceContentTitle ?: item.address
            if (!second.isNullOrEmpty()) {
                Text(
                    text = second,
                    style = IOS.caption,
                    color = IOS.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Icon(
            Icons.Filled.Delete,
            contentDescription = "빼기",
            tint = IOS.secondaryLabel,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onRemove).size(22.dp),
        )
    }
}
