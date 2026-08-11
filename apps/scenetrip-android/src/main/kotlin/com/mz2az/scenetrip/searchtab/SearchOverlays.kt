package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.ui.IOS

/**
 * 지도 위에 얹히는 것들 — 검색바와 지도 조작 버튼.
 *
 * iOS `SearchTab/SearchTabOverlays.swift` 를 그대로 옮겼다. **검색어와 장바구니가
 * 한 캡슐**인 것, 원형 조작 버튼이 38 인 것 모두 그쪽 결정이다.
 */
@Composable
fun SearchBar(
    draft: String,
    cartCount: Int,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    onOpenCart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = IOS.gutter, vertical = 8.dp)
                // 캡슐이다. Material 의 네모난 TextField 가 아니다 — 이 하나로도 두 앱이
                // 다른 제품처럼 보인다.
                .shadow(3.dp, CircleShape, ambientColor = IOS.label, spotColor = IOS.label)
                .clip(CircleShape)
                .background(IOS.systemBackground)
                .padding(horizontal = IOS.gutter, vertical = 11.dp),
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = IOS.secondaryLabel,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (draft.isEmpty()) {
                Text("작품·배우·장소로 검색", style = IOS.body, color = IOS.tertiaryLabel)
            }
            // Material 의 TextField 는 자기 배경·밑줄·라벨을 들고 온다. 캡슐 안에
            // 글자만 놓으려면 BasicTextField 여야 한다.
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                singleLine = true,
                textStyle = LocalTextStyle.current.merge(IOS.body).copy(color = IOS.label),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (draft.isNotEmpty()) {
            Icon(
                Icons.Filled.Clear,
                contentDescription = "지우기",
                tint = IOS.tertiaryLabel,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onClear)
                        .size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
        }

        // iOS 의 `Divider().frame(height: 22)`.
        Spacer(
            Modifier
                .width(1.dp)
                .height(22.dp)
                .background(IOS.separator),
        )
        Spacer(Modifier.width(12.dp))

        Box {
            Icon(
                Icons.Filled.ShoppingCart,
                contentDescription = "장바구니",
                // iOS 는 이 자리를 강조색으로 그린다 — 실기 스크린샷으로 확인했다.
                tint = IOS.accent,
                modifier =
                    Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onOpenCart)
                        .size(22.dp),
            )
            if (cartCount > 0) {
                Text(
                    text = "$cartCount",
                    style = IOS.caption2Heavy,
                    color = IOS.systemBackground,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 10.dp, y = (-8).dp)
                            .clip(CircleShape)
                            .background(IOS.systemRed)
                            .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * 지도 위 원형 조작 버튼.
 *
 * 보이는 원은 38 이고 누르는 자리는 44 다 — 애플이 권하는 최소 터치 크기이며
 * 안드로이드 권장치(48)보다 작지만, **두 앱이 같아야 하므로 iOS 값을 따른다.**
 */
@Composable
fun MapControl(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (Color, androidx.compose.ui.unit.Dp) -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .size(44.dp)
                .clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(38.dp)
                    .shadow(3.dp, CircleShape, ambientColor = IOS.label, spotColor = IOS.label)
                    .clip(CircleShape)
                    .background(IOS.systemBackground),
        ) {
            icon(IOS.label, 20.dp)
        }
    }
}

/**
 * 「현 지도 내 성지 검색」 토글.
 *
 * iOS `nearbyButton` 을 그대로 옮겼다. 켜면 지도 중심 반경 안의 촬영지만 남고, 다시
 * 누르면 검색 전 상태로 돌아간다. 켜진 상태에서는 **색이 반전**된다 — 검은 알약에
 * 흰 글자.
 *
 * **카메라를 건드리지 않는다.** 이 기능은 "지금 보고 있는 이 화면 안" 을 묻는 것이라,
 * 결과가 왔다고 지도를 옮기거나 확대하면 사용자가 물어본 그 화면이 사라진다.
 */
@Composable
fun NearbyButton(
    on: Boolean,
    count: Int,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .shadow(3.dp, CircleShape, ambientColor = IOS.label, spotColor = IOS.label)
                .clip(CircleShape)
                .background(if (on) IOS.label else IOS.systemBackground)
                .clickable(onClick = onToggle)
                .padding(horizontal = IOS.gutter, vertical = 9.dp),
    ) {
        Icon(
            imageVector = if (on) Icons.Filled.Close else Icons.Filled.Refresh,
            contentDescription = null,
            tint = if (on) IOS.systemBackground else IOS.label,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = if (on) "이 지도에서 ${count}곳 · 해제" else "현 지도 내 성지 검색",
            style = IOS.subheadline.copy(fontWeight = FontWeight.Medium),
            color = if (on) IOS.systemBackground else IOS.label,
        )
    }
}

/**
 * 현위치 버튼의 **과녁 십자**(SF Symbols `dot.scope`).
 *
 * iOS 쪽에서 화살표 모양을 쓰다 바꾼 자리다 — "현위치는 화살표가 아니라 조준선
 * 같이 동그란 것" 이 사용자의 지적이었고, 네이버·카카오 지도가 쓰는 모양이다.
 * `location.circle` 같은 화살표 아이콘은 "방향" 으로 읽힌다.
 *
 * material-icons-core 에 같은 모양이 없어 직접 그린다.
 */
@Composable
fun ScopeIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        val w = r * 0.16f
        drawCircle(tint, r * 0.62f, c, style = Stroke(width = w))
        drawCircle(tint, r * 0.16f, c)
        // 원 밖으로 삐져나오는 십자 네 개.
        listOf(
            Offset(c.x, 0f) to Offset(c.x, r * 0.30f),
            Offset(c.x, size.height) to Offset(c.x, size.height - r * 0.30f),
            Offset(0f, c.y) to Offset(r * 0.30f, c.y),
            Offset(size.width, c.y) to Offset(size.width - r * 0.30f, c.y),
        ).forEach { (from, to) -> drawLine(tint, from, to, strokeWidth = w) }
    }
}
