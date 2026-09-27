package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.IOS

/**
 * 코스 편집 화면의 부품들. iOS `RouteTab/RouteEditorParts.swift`를 옮긴 것이다 —
 * 여행 중(길찾기·고정 배지)·챗봇 관련 부분은 뺐다, 그 화면 자체가 아직 없다.
 */
@Composable
fun RouteStopRow(
    number: Int,
    stop: RouteStop,
    nextKilometers: Double?,
    isFocused: Boolean,
    pinLabel: String?,
    isPinned: Boolean,
    onFocus: () -> Unit,
    onStay: () -> Unit,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onTogglePin: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(if (isFocused) IOS.accent.copy(alpha = 0.07f) else IOS.systemBackground)
                .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(onClick = onFocus)) {
            Box(
                modifier =
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(IOS.pinLight, IOS.pinDeep))),
                contentAlignment = Alignment.Center,
            ) {
                Text("$number", fontSize = 10.sp, fontWeight = FontWeight.Black, color = Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(stop.place.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                    if (stop.isPinned) {
                        Badge(text = "내가 찍은 곳", background = IOS.systemGray5, foreground = IOS.secondaryLabel)
                    }
                    if (stop.placeMissing) {
                        Badge(text = "저장 안 됨", background = IOS.systemOrange.copy(alpha = 0.15f), foreground = IOS.systemOrange)
                    }
                }
                stop.arriveMinute?.let {
                    Text("${RouteGuidePlan.clock(it)} 도착 예정", fontSize = 11.sp, color = IOS.secondaryLabel)
                }
                val subtitle = listOfNotNull(stop.place.type, stop.place.address).joinToString(" · ")
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, fontSize = 11.sp, color = IOS.secondaryLabel, maxLines = 1)
                }
            }
            Text(
                stop.stayLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = IOS.label,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(IOS.accent.copy(alpha = 0.12f))
                        .clickable(onClick = onStay)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 34.dp, top = 4.dp),
        ) {
            if (onMoveUp != null) {
                Text("▲", fontSize = 11.sp, color = IOS.secondaryLabel, modifier = Modifier.clickable(onClick = onMoveUp))
            }
            if (onMoveDown != null) {
                Text("▼", fontSize = 11.sp, color = IOS.secondaryLabel, modifier = Modifier.clickable(onClick = onMoveDown))
            }
            if (pinLabel != null) {
                Text(
                    "$pinLabel 고정",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isPinned) Color.White else IOS.secondaryLabel,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isPinned) IOS.accent else IOS.systemGray6)
                            .clickable(onClick = onTogglePin)
                            .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
            nextKilometers?.let {
                Text("↓ ${RouteFormat.kilometers(it)}", fontSize = 11.sp, color = IOS.tertiaryLabel)
            }
            Spacer(Modifier.weight(1f))
            Text("빼기", fontSize = 11.sp, color = IOS.systemRed, modifier = Modifier.clickable(onClick = onRemove))
        }
    }
}

@Composable
private fun Badge(
    text: String,
    background: Color,
    foreground: Color,
) {
    Text(
        text,
        fontSize = 9.sp,
        color = foreground,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(background).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/**
 * 장바구니에서 담기. 검색 탭의 장바구니(같은 기기 id)를 그대로 이어받는다.
 * iOS는 비었을 때 서버 인기 장소로 채우지만, 그 목록을 캐시하는 자리가 Android
 * `RouteStore`엔 아직 없어 — 여기서는 빈 상태 문구만 보여준다.
 */
@Composable
fun RouteCartSheet(
    cart: CartStore,
    taken: Set<Long>,
    onPreview: (List<PlaceSummary>) -> Unit,
    onPick: (List<PlaceSummary>) -> Unit,
    onDismiss: () -> Unit,
) {
    var picked by remember { mutableStateOf(setOf<Long>()) }
    val places = cart.items.mapNotNull { it.toPlaceSummary() }

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("취소", fontSize = 15.sp, color = IOS.accent, modifier = Modifier.clickable(onClick = onDismiss))
            Spacer(Modifier.weight(1f))
            Text("장바구니에서 담기", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            Spacer(Modifier.weight(1f))
            Text(
                "담기 ${picked.size}",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (picked.isEmpty()) IOS.tertiaryLabel else IOS.accent,
                modifier =
                    Modifier.clickable(enabled = picked.isNotEmpty()) {
                        onPick(places.filter { picked.contains(it.id) })
                        onDismiss()
                    },
            )
        }
        if (places.isEmpty()) {
            Text(
                "장바구니가 비어 있습니다",
                fontSize = 13.sp,
                color = IOS.secondaryLabel,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(places, key = { it.id }) { place ->
                val isTaken = taken.contains(place.id)
                val isPicked = picked.contains(place.id)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !isTaken) {
                                picked = if (isPicked) picked - place.id else picked + place.id
                                onPreview(places.filter { picked.contains(it.id) || it.id == place.id })
                            }.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    RemoteImage(url = place.imageUrl?.toString(), modifier = Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(place.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                        Text(place.address ?: place.type ?: "", fontSize = 11.sp, color = IOS.secondaryLabel, maxLines = 1)
                    }
                    Text(
                        if (isTaken) {
                            "담김"
                        } else if (isPicked) {
                            "선택"
                        } else {
                            "+"
                        },
                        fontSize = 13.sp,
                        color = if (isTaken || isPicked) IOS.accent else IOS.secondaryLabel,
                    )
                }
            }
        }
    }
}

private fun CartItem.toPlaceSummary(): PlaceSummary? {
    val lat = latitude ?: return null
    val lng = longitude ?: return null
    return PlaceSummary(id = placeId, name = name, type = null, address = address, latitude = lat, longitude = lng, imageUrl = imageUrl)
}

/** 지도를 눌러 찍은 자리에 이름과 갈래만 붙인다. */
@Composable
fun RoutePinSheet(
    pin: RoutePin,
    onDone: (name: String, category: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(PIN_CATEGORIES[0]) }

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("취소", fontSize = 15.sp, color = IOS.accent, modifier = Modifier.clickable(onClick = onDismiss))
            Spacer(Modifier.weight(1f))
            Text("이 자리에 추가", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            Spacer(Modifier.weight(1f))
            Text(
                "추가",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.accent,
                modifier =
                    Modifier.clickable {
                        onDone(name.ifBlank { "이름 없는 장소" }, category)
                        onDismiss()
                    },
            )
        }
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text("이름", fontSize = 12.sp, color = IOS.secondaryLabel)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("예: 오늘 묵을 숙소") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
            )
            Text("갈래", fontSize = 12.sp, color = IOS.secondaryLabel)
            Column {
                PIN_CATEGORIES.forEach { each ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { category = each },
                    ) {
                        RadioButton(selected = category == each, onClick = { category = each })
                        Text(each, fontSize = 14.sp, color = IOS.label)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "위도 %.5f · 경도 %.5f".format(pin.latitude, pin.longitude),
                fontSize = 11.sp,
                color = IOS.secondaryLabel,
            )
        }
    }
}

private val PIN_CATEGORIES = listOf("숙소", "음식점·카페", "명소·자연", "거리·다리", "건물·시설")

/** 체류 시간 고르기. */
@Composable
fun RouteStaySheet(
    stop: RouteStop,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("취소", fontSize = 15.sp, color = IOS.accent, modifier = Modifier.clickable(onClick = onDismiss))
            Spacer(Modifier.weight(1f))
            Text("얼마나 머무를까요?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(32.dp))
        }
        LazyColumn {
            items(RouteStop.STAY_OPTIONS) { minutes ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(minutes)
                                onDismiss()
                            }.padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    Text(RouteFormat.minutes(minutes), fontSize = 15.sp, color = IOS.label)
                    Spacer(Modifier.weight(1f))
                    if (minutes == stop.stayMinutes) {
                        Text("✓", fontSize = 15.sp, color = IOS.accent)
                    }
                }
            }
        }
    }
}
