package com.mz2az.scenetrip.newcourse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.ui.CheckmarkIcon
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.launch

/**
 * 「직접 고를게요」 — 도시 촬영지 목록에서 담기 (MZ2AZ-547 S1, 웹 `proto/protoParts.tsx` `CityPicker`, iOS `CityPickView`).
 *
 * 담기·빼기는 진짜 보관함(`POST/DELETE /cart/items`)이다. 아래 「N곳으로 코스 만들기」 는 고른 곳을 날마다 나눠
 * ([CityPlanRules.splitDays]) 「{도시} 여행」 으로 저장하고 추천 코스(R5)와 같은 길로 계획 화면을 연다.
 * 며칠은 질문(Q2) 값을 쓴다 — 다시 묻지 않는다.
 */
@Composable
fun CityPickView(
    cityKey: String,
    days: Int,
    routes: RouteStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cart = remember { CartStore(context) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val cityName = CityPlacesStore.city(cityKey)?.name ?: cityKey
    val loaded = CityPlacesStore.places[cityKey]
    val places = loaded.orEmpty().take(CityListRules.PICK_LIMIT)
    // 이 도시 목록 가운데 담은 곳 — 목록 순서대로.
    val chosen = places.filter { cart.contains(it.id) }

    LaunchedEffect(cityKey) {
        launch { CityPlacesStore.loadPlaces(cityKey) }
        launch { cart.refresh() }
    }

    fun create() {
        if (busy || chosen.isEmpty()) return
        busy = true
        failed = false
        scope.launch {
            val course =
                CityPlanRules.course(
                    title = NewCourseText.pickedTitle(cityName),
                    plan = CityPlanRules.splitDays(chosen, days),
                    pace = CITY_PACE,
                    madeByAI = false,
                )
            val id = routes.save(course, origin = "city")?.serverId
            busy = false
            if (id == null) {
                failed = true
                return@launch
            }
            TripMemoryStore.getInstance(context).set(TripMemo(cityKey, days, CITY_PACE, id))
            TabRouter.courseCreated(id, cityName, days)
        }
    }

    NewCourseScreen(
        onBack = onBack,
        footer = {
            if (failed) {
                Text(
                    tr("코스를 만들지 못했어요. 다시 눌러 주세요"),
                    fontSize = 13.sp,
                    color = IOS.systemRed,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            val title =
                when {
                    busy -> tr("만드는 중…")
                    chosen.isEmpty() -> tr("가 볼 곳을 담아 주세요")
                    else -> tr("%d곳으로 코스 만들기").format(chosen.size)
                }
            NewCoursePrimaryButton(title, disabled = chosen.isEmpty() || busy) { create() }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            NewCourseTitle(tr("%s의 촬영지").format(cityName))
            Text(tr("가 보고 싶은 곳을 담으면 동선을 이어 코스로 만들어 드려요"), fontSize = 15.sp, color = IOS.secondaryLabel)
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loaded == null) {
                if (cityKey in CityPlacesStore.placeFailures) {
                    NewCourseSecondaryButton(tr("다시 시도")) { scope.launch { CityPlacesStore.loadPlaces(cityKey) } }
                } else {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), color = IOS.secondaryLabel, strokeWidth = 2.dp)
                    }
                }
            } else if (places.isEmpty()) {
                Text(tr("아직 이 도시의 촬영지가 없어요. 뒤로 가서 다른 도시를 골라 주세요"), fontSize = 15.sp, color = IOS.secondaryLabel)
            }
            places.forEach { place ->
                SpotRow(place, cart.contains(place.id)) {
                    scope.launch {
                        if (cart.contains(place.id)) {
                            cart.remove(place.id)
                        } else {
                            cart.add(place.id, place.contents?.firstOrNull()?.contentId)
                        }
                    }
                }
            }
        }
    }
}

/** 촬영지 한 줄 — 사진 72 · 이름 15 두 줄 · film+작품 · 장면 설명(없으면 주소) 두 줄 · [＋ 담기]/[✓ 담음] (웹 `SpotRow`). */
@Composable
private fun SpotRow(
    place: PlaceSummary,
    saved: Boolean,
    onToggle: () -> Unit,
) {
    val shape = RoundedCornerShape(NewCourseStyle.radiusM)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .shadow(2.dp, shape)
                .clip(shape)
                .background(IOS.systemBackground)
                .padding(10.dp),
    ) {
        NewCoursePhoto(place.imageUrl?.toString(), Modifier.size(72.dp).clip(RoundedCornerShape(NewCourseStyle.radiusS)))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f)) {
            Text(
                place.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = IOS.label,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val work = place.contents?.firstOrNull()?.title
            if (!work.isNullOrEmpty()) WorkTag(work, compact = true)
            val scene = place.sceneDescription?.trim().orEmpty()
            Text(
                scene.ifEmpty { place.address.orEmpty() },
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = IOS.secondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                Modifier
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(if (saved) NewCourseStyle.purple else IOS.systemGray6)
                    .clickable(onClick = onToggle)
                    .semantics(mergeDescendants = true) {
                        role = Role.Button
                        selected = saved
                    }.padding(horizontal = 14.dp),
        ) {
            if (saved) {
                CheckmarkIcon(Color.White, Modifier.size(12.dp))
            } else {
                Icon(Icons.Filled.Add, contentDescription = null, tint = NewCourseStyle.purple, modifier = Modifier.size(15.dp))
            }
            Text(
                if (saved) tr("담음") else tr("담기"),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (saved) Color.White else NewCourseStyle.purple,
            )
        }
    }
}
