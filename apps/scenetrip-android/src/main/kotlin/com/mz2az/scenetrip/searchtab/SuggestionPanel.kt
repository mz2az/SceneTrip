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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.sceneapi.client.model.EntityType
import com.mz2az.scenetrip.sceneapi.client.model.Suggestion
import com.mz2az.scenetrip.ui.IOS

/**
 * 검색 중 지도·시트를 덮는 막. 바깥을 누르면 검색이 닫힌다.
 *
 * iOS 는 `Color.black.opacity(0.25)` 다.
 */
@Composable
fun SearchScrim(onDismiss: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable(onClick = onDismiss),
    )
}

/**
 * 자동완성 드롭다운. 검색창 **바로 아래에 붙어** 내려온다.
 *
 * iOS `SearchTab/SuggestionPanel.swift` 와 같은 자리다. 각 줄은 무엇의 이름인지를
 * 함께 보여 준다 — **배우를 검색했을 때 작품이 떠야 하고 장소가 뜨면 안 된다**는
 * 것이 검색 탭 버그정리에서 잡힌 규칙이다. 그래서 갈래(`type`)를 그대로 넘겨
 * 고른 뒤 어느 탭을 열지 호출부가 정한다.
 */
@Composable
fun SuggestionPanel(
    suggestions: List<Suggestion>,
    onPick: (Suggestion) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = IOS.gutter, vertical = 2.dp)
                .shadow(3.dp, RoundedCornerShape(14.dp), ambientColor = IOS.label, spotColor = IOS.label)
                .clip(RoundedCornerShape(14.dp))
                .background(IOS.systemBackground),
    ) {
        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
            items(suggestions, key = { "${it.type}-${it.id}" }) { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(item) }
                            .padding(horizontal = IOS.gutter, vertical = 11.dp),
                ) {
                    Text(
                        text = item.type.badge,
                        style = IOS.caption2,
                        color = IOS.accent,
                        modifier =
                            Modifier
                                .clip(CircleShape)
                                .background(IOS.accent.copy(alpha = 0.12f))
                                .padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.name,
                            style = IOS.subheadline,
                            color = IOS.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        item.subtitle?.takeIf { it.isNotEmpty() }?.let {
                            Text(
                                text = it,
                                style = IOS.caption,
                                color = IOS.secondaryLabel,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
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

private val EntityType.badge: String
    get() =
        when (this) {
            EntityType.content -> "작품"
            EntityType.person -> "인물"
            EntityType.place -> "장소"
        }

/**
 * 드릴다운 헤더 — `<` 로 한 단계 나온다.
 *
 * iOS `Rows.swift` 의 `DetailHeader` 와 같다. **검색 결과 목록에도 같은 헤더를 쓴다** —
 * 상세에서 `<` 를 눌러 검색 결과까지 온 사용자가 거기서 더 나갈 자리를 못 찾았던
 * 것이 검색 탭 버그정리에서 잡힌 문제다.
 */
@Composable
fun DetailHeader(
    title: String,
    subtitle: String = "",
    onBack: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Icon(
            Icons.Filled.KeyboardArrowLeft,
            contentDescription = "뒤로",
            tint = IOS.accent,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = IOS.headline,
                color = IOS.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = IOS.caption,
                    color = IOS.secondaryLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(IOS.separator))
}
