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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.CommunityBoard
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.VisitStamp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS

/** 커뮤니티 지금 — 최근 글 둘. 게시판 배지 · 제목 · 하트 자리. */
@Composable
fun HomeCommunityNow(
    posts: List<CommunityPost>,
    onOpen: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeSectionHeader(tr("커뮤니티 지금"), subtitle = tr("방금 올라온 글"), action = tr("더 보기"), onAction = onOpen)
        if (posts.isEmpty()) {
            Text(
                tr("첫 글을 남겨 보세요"),
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
            tr(post.board.label),
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
        HomeSectionHeader(tr("내 기록"), subtitle = tr("프로필에서 전부 보기"))
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
        Text(tr("방문 스탬프"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = IOS.label)
        if (stamps.isEmpty()) {
            Text(tr("여행 중 성지에 닿으면 도장이 찍힙니다"), fontSize = 12.sp, color = IOS.secondaryLabel)
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
                        // iOS: systemGray3 1.5 점선 원(dash 3).
                        modifier =
                            Modifier.size(34.dp).drawBehind {
                                drawCircle(
                                    IOS.systemGray3,
                                    radius = size.minDimension / 2 - 0.75.dp.toPx(),
                                    style =
                                        Stroke(
                                            width = 1.5.dp.toPx(),
                                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())),
                                        ),
                                )
                            },
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
        Text(tr("찜한 작품"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = IOS.label)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$likeCount", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IOS.systemRed)
            Text(tr("편"), fontSize = 12.sp, color = IOS.secondaryLabel)
        }
    }
}
