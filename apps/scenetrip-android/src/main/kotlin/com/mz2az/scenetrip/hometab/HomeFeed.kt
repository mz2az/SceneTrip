package com.mz2az.scenetrip.hometab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.CommunityBoard
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.VisitStamp
import com.mz2az.scenetrip.sceneapi.client.model.MarketCourseSummary
import com.mz2az.scenetrip.ui.IOS

/** 여행자들의 코스 — 마켓에서 담기 많은 순으로 둘. 카드·링크 모두 경로여정의 마켓으로. */
@Composable
fun HomeMarketPreview(
    courses: List<MarketCourseSummary>,
    onOpen: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeSectionHeader("여행자들의 코스", subtitle = "담기 많은 순", action = "둘러보기", onAction = onOpen)
        if (courses.isEmpty()) {
            Text(
                "아직 올라온 코스가 없습니다",
                style = IOS.footnote,
                color = IOS.secondaryLabel,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                courses.forEach { course -> MarketCourseCard(course, onClick = onOpen, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MarketCourseCard(
    course: MarketCourseSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            modifier
                .homeCard()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            course.contents?.firstOrNull()?.title ?: "코스",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = HOME_PURPLE,
            maxLines = 1,
        )
        Text(
            "${course.title}\n${courseSpanLabel(course.dayCount)}",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            color = IOS.label,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${course.placeCount}곳", fontSize = 12.sp, color = IOS.secondaryLabel)
            Text("담기 ${course.saveCount}", fontSize = 12.sp, color = IOS.secondaryLabel)
            Text("♥ ${course.likeCount}", fontSize = 12.sp, color = IOS.systemRed)
        }
    }
}

/** 커뮤니티 지금 — 최근 글 둘. 게시판 배지 · 제목 · 하트 자리. */
@Composable
fun HomeCommunityNow(
    posts: List<CommunityPost>,
    onOpen: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeSectionHeader("커뮤니티 지금", subtitle = "방금 올라온 글", action = "더 보기", onAction = onOpen)
        if (posts.isEmpty()) {
            Text(
                "첫 글을 남겨 보세요",
                style = IOS.footnote,
                color = IOS.secondaryLabel,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        } else {
            Column(modifier = Modifier.padding(horizontal = 20.dp).homeCard()) {
                posts.forEachIndexed { index, post ->
                    if (index > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp)
                                .height(0.5.dp)
                                .background(IOS.separator),
                        )
                    }
                    CommunityPostRow(post, onClick = onOpen)
                }
            }
        }
    }
}

@Composable
private fun CommunityPostRow(
    post: CommunityPost,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        val tone = communityBadgeTone(post.board)
        Text(
            post.board.label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = tone,
            modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(tone.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Text(post.title, fontSize = 14.sp, maxLines = 2, color = IOS.label, modifier = Modifier.weight(1f))
        Icon(Icons.Filled.FavoriteBorder, contentDescription = null, tint = IOS.tertiaryLabel, modifier = Modifier.size(12.dp))
    }
}

private fun communityBadgeTone(board: CommunityBoard): Color =
    when (board) {
        CommunityBoard.PHOTO -> HOME_PURPLE
        CommunityBoard.REVIEW -> Color(0xFF2E7D45)
        CommunityBoard.COURSE -> IOS.accent
        CommunityBoard.CHAT -> IOS.secondaryLabel
    }

/** 내 기록 — 방문 스탬프 셋과 "+N", 찜한 작품 수. 둘 다 마이페이지로 이어진다. */
@Composable
fun HomeMyRecord(
    stamps: List<VisitStamp>,
    likeCount: Int,
    onOpen: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeSectionHeader("내 기록", subtitle = "프로필에서 전부 보기")
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        ) {
            StampsCard(stamps, onClick = onOpen, modifier = Modifier.weight(1f))
            LikesCard(likeCount, onClick = onOpen)
        }
    }
}

@Composable
private fun StampsCard(
    stamps: List<VisitStamp>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.homeCard().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text("방문 스탬프", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = IOS.label)
        if (stamps.isEmpty()) {
            Text("여행 중 성지에 닿으면 도장이 찍힙니다", fontSize = 12.sp, color = IOS.secondaryLabel)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                stamps.take(3).forEachIndexed { index, _ ->
                    // iOS 는 opacity(1 - index*0.25)로 뒤로 갈수록 흐리게 한다.
                    Box(
                        modifier =
                            Modifier
                                .size(34.dp)
                                .alpha(1f - index * 0.25f)
                                .clip(CircleShape)
                                .background(Brush.linearGradient(listOf(IOS.pinLight, IOS.pinDeep))),
                    )
                }
                if (stamps.size > 3) {
                    Box(
                        modifier = Modifier.size(34.dp).clip(CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("+${stamps.size - 3}", fontSize = 12.sp, color = IOS.secondaryLabel)
                    }
                }
            }
        }
    }
}

@Composable
private fun LikesCard(
    likeCount: Int,
    onClick: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .width(132.dp)
                .homeCard()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text("찜한 작품", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = IOS.label)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$likeCount", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IOS.systemRed)
            Text("편", fontSize = 12.sp, color = IOS.secondaryLabel)
        }
    }
}
