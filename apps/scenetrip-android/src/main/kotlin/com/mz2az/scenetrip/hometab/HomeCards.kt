package com.mz2az.scenetrip.hometab

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.R
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.CircleSignIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.MapPinEllipseIcon
import com.mz2az.scenetrip.ui.PersonOutlineIcon

/** 목업의 홈 글자색. `RootTabs.kt`의 것과 같은 값이다. */
internal val HOME_PURPLE = Color(0xFF5B49D6)

/** 흰 카드 바탕 — 목업의 `radius 18 · shadow 0 2 8 rgba(0,0,0,.07)`. */
internal fun Modifier.homeCard(radius: Dp = 18.dp): Modifier =
    this
        .shadow(
            4.dp,
            RoundedCornerShape(radius),
            ambientColor = Color.Black.copy(alpha = 0.07f),
            spotColor = Color.Black.copy(alpha = 0.07f),
        ).clip(RoundedCornerShape(radius))
        .background(IOS.systemBackground)

/** "N박 (N+1)일" — 당일치기면 그 말 그대로. iOS `RouteSpan(days:).label`. */
internal fun courseSpanLabel(dayCount: Int): String {
    val nights = (dayCount - 1).coerceAtLeast(0)
    return if (nights == 0) tr("당일치기") else tr("%d박 %d일").format(nights, nights + 1)
}

/** 절 머리줄 — 제목 · 흐린 부제 · 오른쪽 파란 링크. 홈의 절이 전부 이 모양이다. */
@Composable
internal fun HomeSectionHeader(
    title: String,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = IOS.label)
        if (subtitle != null) {
            Text(subtitle, fontSize = 12.sp, color = IOS.secondaryLabel)
        }
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.accent,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

/** 인사 — 해태 얼굴, 두 줄, 오른쪽 프로필 단추(마이페이지의 입구). */
@Composable
fun HomeHeader(onProfile: () -> Unit) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.haetae_face),
            contentDescription = null,
            modifier = Modifier.size(width = 44.dp, height = 38.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(tr("해태가 기다렸어요"), fontSize = 13.sp, color = IOS.secondaryLabel)
            Text(tr("오늘은 어느 장면으로 갈까요?"), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = IOS.label)
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier =
                Modifier
                    .size(36.dp)
                    .shadow(
                        1.5.dp,
                        CircleShape,
                        ambientColor = Color.Black.copy(alpha = 0.08f),
                        spotColor = Color.Black.copy(alpha = 0.08f),
                    ).clip(CircleShape)
                    .background(IOS.systemBackground)
                    .clickable(onClick = onProfile),
            contentAlignment = Alignment.Center,
        ) {
            PersonOutlineIcon(IOS.label, Modifier.size(17.dp))
        }
    }
}

/**
 * 내 여행 이어가기 묶음 — 코스가 둘 이상이면 옆으로 넘기는 페이저, 아래에 점.
 *
 * iOS 는 `TabView(.page)`가 세로 스크롤 안에서 가로 손짓을 통째로 가로채 아래
 * "지금 뜨는 작품" 줄을 죽여서(2026-09-16) 순수 SwiftUI 스크롤로 갈아 끼웠다. Compose
 * `HorizontalPager`는 애초에 UIKit 같은 별도 제스처 계층이 없어 같은 문제가 없다 —
 * 다만 실기에서 세로 스크롤과 함께 확인은 해 둔다(체크포인트).
 */
@Composable
fun HomeTripPager(
    trips: List<HomeTrip>,
    hasCourses: Boolean,
    loading: Boolean,
    onNavigate: (HomeTrip) -> Unit,
    onOpenCourse: (HomeTrip) -> Unit,
    onCreate: () -> Unit,
) {
    if (trips.size > 1) {
        val pagerState = rememberPagerState(pageCount = { trips.size })
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxWidth().height(196.dp)) { page ->
                HomeTripCard(
                    trip = trips[page],
                    rank = page + 1,
                    hasCourses = hasCourses,
                    loading = false,
                    onNavigate = onNavigate,
                    onOpenCourse = onOpenCourse,
                    onCreate = onCreate,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Spacer(Modifier.weight(1f))
                trips.indices.forEach { index ->
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (index == pagerState.currentPage) HOME_PURPLE else IOS.systemGray4),
                    )
                }
                Spacer(Modifier.weight(1f))
            }
        }
    } else {
        HomeTripCard(
            trip = trips.firstOrNull(),
            rank = null,
            hasCourses = hasCourses,
            loading = loading,
            onNavigate = onNavigate,
            onOpenCourse = onOpenCourse,
            onCreate = onCreate,
        )
    }
}

/**
 * 내 여행 이어가기 — 홈 맨 위 고정 카드. 경로여정 탭이 없어진 자리를 이 카드가 맡는다.
 *
 * 세 모습: 코스가 없으면 "코스 만들기", 있으면 제목·기간·스탬프 진행과 단추 둘,
 * 아직 받는 중이면 자리만 지킨다.
 */
@Composable
fun HomeTripCard(
    trip: HomeTrip?,
    rank: Int?,
    hasCourses: Boolean,
    loading: Boolean,
    onNavigate: (HomeTrip) -> Unit,
    onOpenCourse: (HomeTrip) -> Unit,
    onCreate: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .shadow(
                    8.dp,
                    RoundedCornerShape(22.dp),
                    ambientColor = IOS.pinDeep.copy(alpha = 0.28f),
                    spotColor = IOS.pinDeep.copy(alpha = 0.28f),
                ).clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(IOS.pinLight, IOS.pinDeep)))
                .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        when {
            trip != null -> {
                HomeTripCardFilled(trip, rank, onNavigate, onOpenCourse)
            }

            loading -> {
                Text(
                    tr("내 여행을 불러오는 중…"),
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.fillMaxWidth().height(72.dp),
                )
            }

            else -> {
                HomeTripCardEmpty(hasCourses, onCreate)
            }
        }
    }
}

@Composable
private fun HomeTripCardFilled(
    trip: HomeTrip,
    rank: Int?,
    onNavigate: (HomeTrip) -> Unit,
    onOpenCourse: (HomeTrip) -> Unit,
) {
    val running = trip.course.status == CourseStatus.active
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rank != null) {
            Box(
                modifier = Modifier.size(22.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.28f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("$rank", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
        Text(
            if (running) tr("여행 중") else tr("예정"),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.22f))
                    .padding(horizontal = 9.dp, vertical = 3.dp),
        )
        Text(
            "${courseSpanLabel(trip.course.dayCount)} · ${tr("%d곳").format(trip.course.placeCount)}",
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.85f),
        )
    }
    Text(
        trip.course.title,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val progress = if (trip.total > 0) trip.visited.toFloat() / trip.total else 0f
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.3f)),
        ) {
            Box(
                modifier =
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White),
            )
        }
        Text(
            tr("스탬프 %d/%d").format(trip.visited, trip.total),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val canNavigate = trip.nextStop != null
        Text(
            tr("이어서 길찾기"),
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = HOME_PURPLE.copy(alpha = if (canNavigate) 1f else 0.6f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier =
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = if (canNavigate) 1f else 0.6f))
                    .clickable(enabled = canNavigate) { onNavigate(trip) }
                    .padding(vertical = 10.dp),
        )
        Text(
            tr("코스 보기"),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.2f))
                    .clickable { onOpenCourse(trip) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun HomeTripCardEmpty(
    hasCourses: Boolean,
    onCreate: () -> Unit,
) {
    Text(
        if (hasCourses) tr("코스") else tr("첫 여행"),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.White.copy(alpha = 0.22f))
                .padding(horizontal = 9.dp, vertical = 3.dp),
    )
    Text(tr("여행을 시작해 볼까요?"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White)
    Text(
        tr("보고 싶은 작품과 기간만 고르면 촬영지를 이어서 일정으로 짜 드립니다"),
        fontSize = 13.sp,
        color = Color.White.copy(alpha = 0.9f),
    )
    Text(
        tr("코스 만들기"),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = HOME_PURPLE,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White)
                .clickable(onClick = onCreate)
                .padding(vertical = 10.dp),
    )
}

/** 지금 뜨는 작품 — 포스터 가로 스크롤, 촬영지 많은 순. */
@Composable
fun HomeWorkShelf(
    works: List<ContentSummary>,
    failed: Boolean,
    onOpen: (ContentSummary) -> Unit,
    onAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeSectionHeader(tr("지금 뜨는 작품"), subtitle = tr("촬영지가 많은 순"), action = tr("전체 보기"), onAction = onAll)
        if (failed) {
            Text(
                tr("작품을 불러오지 못했습니다 — 백엔드(:8081)가 켜져 있나요?"),
                style = IOS.footnote,
                color = IOS.secondaryLabel,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(horizontal = 20.dp),
            ) {
                items(works) { work -> WorkPoster(work, onClick = { onOpen(work) }) }
            }
        }
    }
}

@Composable
private fun WorkPoster(
    work: ContentSummary,
    onClick: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.width(108.dp).clickable(onClick = onClick),
    ) {
        Box(
            modifier =
                Modifier
                    .size(width = 108.dp, height = 144.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(IOS.pinDeep.copy(alpha = 0.7f), IOS.pinDeep))),
        ) {
            RemoteImage(url = work.posterUrl?.toString(), modifier = Modifier.fillMaxSize())
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))),
            )
            Text(
                work.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
        Text(tr("촬영지 %d곳").format(work.placeCount), fontSize = 11.sp, color = IOS.secondaryLabel)
    }
}

/** 오늘의 성지 — 하루 한 곳. 작품 배지 · 장소명과 장면 · 주소 · 담기. */
@Composable
fun HomeTodayCard(
    place: PlaceSummary,
    saved: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeSectionHeader(tr("오늘의 성지"), subtitle = tr("매일 한 장면"))
        Column(
            modifier =
                Modifier
                    .padding(horizontal = 20.dp)
                    .homeCard(radius = 20.dp),
        ) {
            // 사진 띠는 통째로 단추다 — "담기"와 겹치지 않는 자리라 여기서 연다.
            Box(
                modifier = Modifier.fillMaxWidth().height(96.dp).clickable(onClick = onOpen),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.horizontalGradient(
                                    listOf(IOS.pinDeep.copy(alpha = 0.13f), IOS.pinLight.copy(alpha = 0.33f)),
                                ),
                            ),
                )
                RemoteImage(url = place.imageUrl?.toString(), modifier = Modifier.fillMaxSize())
                val workTitle = place.contents?.firstOrNull()?.title
                if (workTitle != null) {
                    Text(
                        workTitle,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = HOME_PURPLE,
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 16.dp, bottom = 12.dp)
                                .clip(RoundedCornerShape(50))
                                .background(IOS.systemBackground)
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 14.dp),
            ) {
                val scene = place.sceneDescription?.trim()
                val title = if (!scene.isNullOrEmpty()) "${place.name} — $scene" else place.name
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    color = IOS.label,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MapPinEllipseIcon(IOS.pinDeep, Modifier.size(13.dp))
                    Text(
                        place.address ?: tr("주소 없음"),
                        fontSize = 12.sp,
                        color = IOS.secondaryLabel,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        modifier = Modifier.clickable(onClick = onSave),
                    ) {
                        if (saved) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = IOS.pinDeep, modifier = Modifier.size(15.dp))
                        } else {
                            CircleSignIcon(plus = true, tint = IOS.accent, modifier = Modifier.size(13.dp))
                        }
                        Text(
                            if (saved) tr("담김") else tr("담기"),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (saved) IOS.pinDeep else IOS.accent,
                        )
                    }
                }
            }
        }
    }
}
