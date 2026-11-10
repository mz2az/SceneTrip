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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.R
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.searchtab.FilmIcon
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.CircleSignIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.MapPinEllipseIcon
import com.mz2az.scenetrip.ui.PersonOutlineIcon
import com.mz2az.scenetrip.ui.iosLineBox
import com.mz2az.scenetrip.ui.iosShadow
import com.mz2az.scenetrip.ui.keepLastWordsTogether
import com.mz2az.scenetrip.ui.sfLine

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
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = IOS.label, modifier = Modifier.sfLine(17f))
        // iOS `HStack(alignment: .firstTextBaseline)` — 작은 글은 큰 글의 첫 기준선에 맞춰 (ascent 차이만큼) 내려앉는다: 부제 +4.8, 링크 +3.9.
        if (subtitle != null) {
            Text(subtitle, fontSize = 12.sp, color = IOS.secondaryLabel, modifier = Modifier.padding(top = 4.8.dp).sfLine(12f))
        }
        Spacer(Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(
                action,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.accent,
                modifier = Modifier.padding(top = 3.9.dp).clickable(onClick = onAction).sfLine(13f),
            )
        }
    }
}

/** 인사 — 해태 얼굴, 두 줄, 오른쪽 프로필 단추(마이페이지의 입구). */
@Composable
fun HomeHeader(onProfile: () -> Unit) {
    val profileLabel = tr("마이페이지")
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
            Text(tr("해태가 기다렸어요"), fontSize = 13.sp, color = IOS.secondaryLabel, modifier = Modifier.sfLine(13f))
            Text(
                tr("오늘은 어느 장면으로 갈까요?"),
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                color = IOS.label,
                modifier = Modifier.sfLine(19f),
            )
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
                    .semantics { contentDescription = profileLabel }
                    .clickable(onClick = onProfile),
            contentAlignment = Alignment.Center,
        ) {
            PersonOutlineIcon(IOS.label, Modifier.size(17.dp))
        }
    }
}

/** 지금 뜨는 작품 — 서버 인기순 첫20편을 그대로 보여 주는 포스터 가로 스크롤. */
@Composable
fun HomeWorkShelf(
    works: List<ContentSummary>,
    failed: Boolean,
    onOpen: (ContentSummary) -> Unit,
    onAll: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeSectionHeader(tr("지금 뜨는 작품"), subtitle = tr("인기순"), action = tr("전체 보기"), onAction = onAll)
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
            RemoteImage(
                url = work.posterUrl?.toString(),
                keepOnDisk = true,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = { FilmIcon(IOS.tertiaryLabel, Modifier.size(28.dp)) },
            )
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
                // SwiftUI `lineLimit` 는 잘리면 꼬리를 「…」 로 줄인다 — Compose 기본은 그냥 자른다(2026-10-29 감사 #20).
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
        Text(tr("촬영지 %d곳").format(work.placeCount), fontSize = 11.sp, color = IOS.secondaryLabel, modifier = Modifier.sfLine(11f))
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
                // 사진이 없거나 받는 중에도 아래 그라데이션이 보이게 바탕을 비운다 (iOS `AsyncImage` 의 placeholder `Color.clear`).
                RemoteImage(url = place.imageUrl?.toString(), modifier = Modifier.fillMaxSize(), backgroundColor = Color.Transparent)
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
                // 줄 칸을 iOS 값으로 고정한다 — iOS 카드 162, Android 는 글꼴 높이 탓에 168 이었다(2026-10-30 재감사 R11). 15→18, 한 줄 행 16.
                var titleLines by remember { mutableIntStateOf(1) }
                Text(
                    title,
                    fontSize = 15.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = IOS.label,
                    onTextLayout = { titleLines = it.lineCount },
                    modifier = Modifier.fillMaxWidth().height((titleLines * 18).dp).clickable(onClick = onOpen),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(16.dp),
                ) {
                    MapPinEllipseIcon(IOS.pinDeep, Modifier.size(13.dp))
                    Text(
                        place.address ?: tr("주소 없음"),
                        fontSize = 12.sp,
                        color = IOS.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
