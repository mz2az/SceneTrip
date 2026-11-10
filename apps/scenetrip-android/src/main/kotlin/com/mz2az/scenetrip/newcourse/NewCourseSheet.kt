package com.mz2az.scenetrip.newcourse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.coach.CoachTour
import com.mz2az.scenetrip.coach.CoachTourEffect
import com.mz2az.scenetrip.coach.coachTarget
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.hometab.CityCovers
import com.mz2az.scenetrip.hometab.HomeCityRules
import com.mz2az.scenetrip.ui.BagIcon
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.MapPinEllipseIcon
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.SparklesIcon
import kotlinx.coroutines.launch

/** 시트 높이 — 머리 + 세 줄 묶음에 맞춘다(웹 368 과 거의 같다. 340 이면 셋째 줄이 눌려 설명이 잘렸다 — 에뮬레이터 실측). */
private val SHEET_HEIGHT = 380.dp

/**
 * 「새 여행 코스」 시트 — 세 갈래를 한 묶음의 세 줄로 (MZ2AZ-547 F1·F2, 웹 `proto/ProtoFork.tsx`, iOS `NewCourseSheet`).
 *
 * ① 도시로 시작(질문 흐름) ② 작품으로 [AI](작품 고르기 → AI) ③ 담은 곳으로(보관함을 날마다 나눠 넣기).
 * 줄마다 왼쪽에 무엇으로 시작하는지 보이는 사진 한 장(도시·포스터·담은 곳), 오른쪽 화살표, 줄 사이 얇은 선.
 * 여는 곳: 홈 떠 있는 줄 「+ 새 코스」(코스 0개면 「여행 코스 짜기」), 코스 목록 「코스 추가」·「새 코스 만들기」.
 */
@Composable
fun NewCourseSheet(
    onDismiss: () -> Unit,
    onChoose: (NewCourseStart) -> Unit,
) {
    val context = LocalContext.current
    val routes = remember { RouteStore(context) }
    val cart = remember { CartStore(context) }
    LaunchedEffect(Unit) {
        launch { CityPlacesStore.loadCities() }
        launch { cart.refresh() }
        launch { routes.loadWorks() }
    }
    val cityPhoto =
        CityPlacesStore.shownCities.firstNotNullOfOrNull { CityPlacesStore.photo(it.key) }
            ?: CityCovers.all.firstOrNull()?.let { HomeCityRules.Photo.Asset(it.asset) }
    val poster =
        routes.sortedWorks
            .firstOrNull { NewCourseText.usablePhoto(it.posterUrl?.toString()) }
            ?.posterUrl
            ?.toString()
    val saved = cart.items.filter { it.delisted != true }
    val savedPhoto = saved.firstOrNull { NewCourseText.usablePhoto(it.imageUrl?.toString()) }?.imageUrl?.toString()

    IOSSheet(
        detents = listOf(SheetDetent.MEDIUM),
        fixedHeight = SHEET_HEIGHT,
        containerColor = NewCourseStyle.grouped,
        onDismiss = onDismiss,
    ) {
        // 처음 안내(MZ2AZ-547 C3) — 세 갈래를 하나씩 짚는다. 시트가 다 올라온 뒤에(0.55초).
        CoachTourEffect(CoachTour.forkOptions, active = true)
        Column(
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 18.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 40.dp, height = 5.dp).clip(CircleShape).background(IOS.systemGray3))
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(
                    tr("새 여행 코스"),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = IOS.label,
                    modifier = Modifier.semantics { heading() },
                )
                Text(tr("어떻게 시작할까요?"), fontSize = 13.sp, color = IOS.secondaryLabel)
            }
            Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(IOS.systemBackground)) {
                ForkRow(
                    first = true,
                    modifier = Modifier.coachTarget("fork-city"),
                    art = { CityPhoto(cityPhoto, Modifier.size(52.dp)) },
                    title = tr("도시로 시작"),
                    caption = tr("어디로·며칠만 고르면 코스 완성"),
                ) { onChoose(NewCourseStart.City(null)) }
                ForkRow(
                    modifier = Modifier.coachTarget("fork-shows"),
                    art = { ArtOrIcon(poster) { SparklesIcon(Color.White, Modifier.size(20.dp)) } },
                    title = tr("작품으로"),
                    tag = "AI",
                    caption = tr("좋아하는 작품을 고르면 촬영지를 이어 줘요"),
                ) { onChoose(NewCourseStart.Works) }
                ForkRow(
                    modifier = Modifier.coachTarget("fork-saved"),
                    art = { ArtOrIcon(savedPhoto) { BagIcon(Color.White, Modifier.size(20.dp)) } },
                    title = tr("담은 곳으로"),
                    caption = NewCourseText.savedCaption(saved.size),
                ) { onChoose(NewCourseStart.Saved) }
            }
        }
    }
}

/** 사진이 있으면 사진, 없으면 브랜드 바탕 위 흰 그림. */
@Composable
private fun ArtOrIcon(
    url: String?,
    icon: @Composable () -> Unit,
) {
    if (url != null) {
        NewCoursePhoto(url, Modifier.size(52.dp))
    } else {
        Box(Modifier.size(52.dp).background(NewCourseStyle.brandFill), contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
private fun ForkRow(
    first: Boolean = false,
    modifier: Modifier = Modifier,
    art: @Composable () -> Unit,
    title: String,
    tag: String? = null,
    caption: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clickable(onClick = onClick)
                .semantics(mergeDescendants = true) { role = Role.Button }
                .padding(start = 12.dp),
    ) {
        Box(Modifier.padding(vertical = 12.dp).clip(RoundedCornerShape(NewCourseStyle.radiusS))) { art() }
        Column(modifier = Modifier.weight(1f)) {
            // 줄 사이 선은 글자 쪽에만(사진 아래로는 긋지 않는다) — 설정 화면 묶음처럼.
            if (!first) Box(Modifier.fillMaxWidth().height(0.5.dp).background(IOS.separator))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 12.dp, bottom = 12.dp, end = 12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            title,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = IOS.label,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (tag != null) {
                            Text(
                                tag,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = NewCourseStyle.purple,
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(NewCourseStyle.purple.copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Text(caption, fontSize = 13.sp, color = IOS.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                ChevronRightIcon(IOS.tertiaryLabel, Modifier.size(width = 7.dp, height = 12.dp))
            }
        }
    }
}
