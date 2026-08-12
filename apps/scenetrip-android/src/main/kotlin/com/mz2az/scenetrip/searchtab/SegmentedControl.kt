package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.ui.IOS

/**
 * iOS 의 세그먼트 컨트롤 (`Picker(...).pickerStyle(.segmented)`).
 *
 * **Material 의 `TabRow` 를 쓰지 않는다.** 그것은 밑줄 인디케이터가 달린 전혀 다른
 * 물건이라, 같은 자리에 놓으면 두 앱이 한눈에 다르게 보인다. iOS 것은 회색 트랙
 * 안에서 흰 알약이 움직이는 모양이다.
 */
@Composable
fun <T> SegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = IOS.gutter)
                .clip(RoundedCornerShape(9.dp))
                .background(IOS.segmentTrack)
                .padding(2.dp),
    ) {
        options.forEach { option ->
            val isOn = option == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(7.dp))
                        .then(
                            if (isOn) {
                                Modifier
                                    .shadow(
                                        2.dp,
                                        RoundedCornerShape(7.dp),
                                        ambientColor = IOS.label,
                                        spotColor = IOS.label,
                                    ).background(IOS.systemBackground)
                            } else {
                                Modifier
                            },
                        ).clickable { onSelect(option) }
                        .padding(vertical = 5.dp),
            ) {
                Text(
                    text = label(option),
                    style =
                        IOS.footnote.copy(
                            fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                    color = IOS.label,
                )
            }
        }
    }
}

/**
 * 카테고리 칩 줄. 목록과 지도를 **둘 다** 좁히고 카메라는 건드리지 않는다.
 *
 * **Material 의 `FilterChip` 을 쓰지 않는다.** 그것은 테두리와 체크 아이콘을 달고
 * 나오는데 iOS 것은 그냥 알약이다. 켜지면 accent 배경에 흰 글자, 꺼지면 systemGray6
 * 배경에 검은 글자다.
 */
@Composable
fun ChipRow(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(horizontal = IOS.gutter),
        horizontalArrangement =
            androidx.compose.foundation.layout.Arrangement
                .spacedBy(8.dp),
    ) {
        items(CategoryChip.names.size) { index ->
            val name = CategoryChip.names[index]
            val isOn = selected == name
            Text(
                text = name,
                style = IOS.subheadline,
                color = if (isOn) IOS.systemBackground else IOS.label,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .background(if (isOn) IOS.accent else IOS.systemGray6)
                        .clickable { onSelect(if (isOn) CategoryChip.ALL else name) }
                        .padding(horizontal = 13.dp, vertical = 6.dp),
            )
        }
    }
}
