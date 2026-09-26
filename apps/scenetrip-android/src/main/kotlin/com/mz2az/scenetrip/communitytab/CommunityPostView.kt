package com.mz2az.scenetrip.communitytab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.ui.IOS

/**
 * 글 전문. iOS `CommunityTab/CommunityPostView.swift`를 옮긴 것이다.
 *
 * 게시판의 글은 종이 위의 게시물처럼 보여야 한다 — 말머리 색 띠, 글쓴이 줄, 본문
 * 카드, 첨부 카드가 각자 제 칸을 가진다.
 */
@Composable
fun CommunityPostView(
    post: CommunityPost,
    onDismiss: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground).statusBarsPadding()) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .background(Brush.linearGradient(listOf(IOS.pinLight, IOS.pinDeep))),
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(post.board.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
                androidx.compose.foundation.layout
                    .Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier.size(32.dp).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "닫기",
                        tint = Color.White.copy(alpha = 0.9f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().background(IOS.systemGray6).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().postCard().padding(16.dp),
                ) {
                    Text(post.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = IOS.label)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier.size(28.dp).clip(CircleShape).background(IOS.pinDeep),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("나", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Column {
                            Text("나 · 비회원", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = IOS.label)
                            Text(formatDateTime(post.createdAt), fontSize = 11.sp, color = IOS.tertiaryLabel)
                        }
                    }
                }
            }

            post.courseTitle?.let { title ->
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().postCard().padding(12.dp),
                    ) {
                        Box(
                            modifier = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(IOS.pinDeep),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Filled.Place, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp))
                        }
                        Column {
                            Text("붙인 코스", fontSize = 11.sp, color = IOS.tertiaryLabel)
                            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                        }
                    }
                }
            }

            item {
                Text(
                    post.body.ifEmpty { "본문이 없습니다" },
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    color = if (post.body.isEmpty()) IOS.secondaryLabel else IOS.label,
                    modifier = Modifier.fillMaxWidth().postCard().padding(16.dp),
                )
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Filled.ThumbUp, contentDescription = null, tint = IOS.tertiaryLabel, modifier = Modifier.size(14.dp))
                        Text("좋아요", fontSize = 12.sp, color = IOS.tertiaryLabel)
                    }
                    Text("댓글", fontSize = 12.sp, color = IOS.tertiaryLabel)
                    androidx.compose.foundation.layout
                        .Spacer(Modifier.weight(1f))
                    Text("서버가 열리면 함께 열려요", fontSize = 11.sp, color = IOS.tertiaryLabel)
                }
            }
        }
    }
}

private fun Modifier.postCard(): Modifier =
    this
        .shadow(4.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black.copy(alpha = 0.05f))
        .clip(RoundedCornerShape(14.dp))
        .background(IOS.systemBackground)
