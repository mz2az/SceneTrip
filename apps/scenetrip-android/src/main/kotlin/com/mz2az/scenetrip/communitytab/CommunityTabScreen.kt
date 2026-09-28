package com.mz2az.scenetrip.communitytab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.CommunityBoard
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.sceneapi.client.api.MarketApi
import com.mz2az.scenetrip.sceneapi.client.model.MarketCourseSummary
import com.mz2az.scenetrip.sceneapi.client.model.MarketSort
import com.mz2az.scenetrip.ui.BubblesIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.SquarePencilIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 커뮤니티 — 게시판 임시판. iOS `CommunityTab/CommunityTabView.swift`를 옮긴 것이다.
 *
 * **지어낸 글은 없다.** 게시판 서버가 아직 없어서 이 판의 글은 둘뿐이다 — 내가 쓴
 * 글(기기 저장, [CommunityStore])과 마켓에 올라온 코스(실서버, 유일한 "남의 글").
 */
@Composable
fun CommunityTabScreen() {
    val context = LocalContext.current
    val store = remember { CommunityStore.getInstance(context) }
    var board by remember { mutableStateOf<CommunityBoard?>(null) }
    var composing by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<CommunityPost?>(null) }
    var marketCourses by remember { mutableStateOf<List<MarketCourseSummary>>(emptyList()) }

    LaunchedEffect(Unit) {
        val deviceId = InstallIdentity.of(context)
        val api = MarketApi(API_BASE)
        marketCourses =
            withContext(Dispatchers.IO) {
                runCatching { api.listMarketCourses(deviceId, sort = MarketSort.likes, limit = 30) }.getOrNull()
            }?.items ?: emptyList()
    }

    val minePosts = store.posts.filter { board == null || it.board == board }
    val showsMarket = board == null || board == CommunityBoard.COURSE

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
            CommunityHeader(onCompose = { composing = true })
            CommunityBoardChips(selected = board, onSelect = { board = it })
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(IOS.separator))

            if (minePosts.isEmpty() && !(showsMarket && marketCourses.isNotEmpty())) {
                // iOS `ContentUnavailableView` 상당 — 심벌 + 굵은 제목 + 설명, 가운데
                // 정렬에 너비를 좁혀 둔다(2026-09-28 실측: 안 좁히면 설명이 화면 폭
                // 그대로라 iOS처럼 두 줄로 안 꺾인다). 심벌은 탭바 커뮤니티 아이콘과
                // 같은 그림([BubblesIcon]) — iOS도 `bubble.left.and.bubble.right`로
                // 같다.
                // **위쪽에 놓는다** — iOS 는 목록(`List`) 첫 칸 안에 그려서 칩 줄 바로 아래에 온다.
                // 제목 22 굵게, 설명 15 secondary(2026-09-28 화면 대조 #16).
                Column(
                    modifier = Modifier.fillMaxSize().padding(top = 36.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // iOS 글리프는 가로:세로 약 1.25 — 정사각 칸이면 세로로 눌린다.
                    BubblesIcon(
                        tint = IOS.secondaryLabel,
                        modifier = Modifier.padding(bottom = 14.dp).size(width = 66.dp, height = 52.dp),
                    )
                    Text(
                        "아직 글이 없습니다",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = IOS.label,
                    )
                    Text(
                        "첫 글을 남겨 보세요. 다른 여행자의 글은 서버가 열리면 보입니다.",
                        fontSize = 15.sp,
                        color = IOS.secondaryLabel,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp).widthIn(max = 280.dp),
                    )
                    // iOS 는 빈 상태가 목록(`List`) 첫 칸이라 그 아래 줄 구분선이 하나 보인다(실측: 왼쪽 60).
                    androidx.compose.material3.HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0xFFE8E8E8),
                        modifier = Modifier.padding(top = 44.dp).padding(start = 60.dp, end = 16.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(minePosts, key = { it.id }) { post ->
                        MyPostRow(post, onClick = { reading = post }, onDelete = { store.remove(post) })
                    }
                    if (showsMarket) {
                        items(marketCourses, key = { it.id }) { course -> MarketRow(course) }
                    }
                }
            }
        }

        reading?.let { post ->
            CommunityPostView(post = post, onDismiss = { reading = null })
        }

        if (composing) {
            CommunityComposeView(
                onDismiss = { composing = false },
                onSubmit = { newBoard, title, body, courseTitle ->
                    store.add(board = newBoard, title = title, body = body, courseTitle = courseTitle)
                    composing = false
                },
            )
        }
    }
}

/** 손수 그린 머리줄 — 글쓰기가 피노 색 동그라미로 떠 있다. */
@Composable
private fun CommunityHeader(onCompose: () -> Unit) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp)
                .padding(top = 10.dp, bottom = 8.dp),
    ) {
        Text("커뮤니티", style = IOS.headline, color = IOS.label, modifier = Modifier.align(Alignment.Center))
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(IOS.pinLight, IOS.pinDeep)))
                    .clickable(onClick = onCompose),
            contentAlignment = Alignment.Center,
        ) {
            SquarePencilIcon(Color.White, Modifier.size(16.dp)) // iOS `square.and.pencil`
        }
    }
}

@Composable
private fun CommunityBoardChips(
    selected: CommunityBoard?,
    onSelect: (CommunityBoard?) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(IOS.systemBackground)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BoardChip(label = "전체", active = selected == null, onClick = { onSelect(null) })
        CommunityBoard.entries.forEach { item ->
            BoardChip(label = item.label, active = selected == item, onClick = { onSelect(item) })
        }
    }
}

@Composable
private fun BoardChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Text(
        label,
        fontSize = 12.sp,
        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
        color = if (active) IOS.label else IOS.secondaryLabel,
        modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (active) IOS.accent.copy(alpha = 0.14f) else IOS.systemGray6)
                .border(1.dp, if (active) IOS.accent.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(horizontal = 11.dp, vertical = 6.dp),
    )
}

private fun badgeColor(board: CommunityBoard): Color =
    when (board) {
        CommunityBoard.PHOTO -> IOS.accent
        CommunityBoard.REVIEW -> IOS.pinDeep
        CommunityBoard.COURSE -> IOS.accent
        CommunityBoard.CHAT -> IOS.secondaryLabel
    }

@Composable
private fun Badge(
    text: String,
    tint: Color,
) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = tint,
        modifier =
            Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(tint.copy(alpha = 0.13f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** "3분 전" 처럼 상대 시각으로. iOS `Date.formatted(.relative)`의 근사치. */
private fun relativeTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    return when {
        minutes < 1 -> "방금"
        minutes < 60 -> "${minutes}분 전"
        minutes < 24 * 60 -> "${minutes / 60}시간 전"
        else -> "${minutes / (24 * 60)}일 전"
    }
}

@Composable
private fun MyPostRow(
    post: CommunityPost,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(post.board.label, badgeColor(post.board))
                Text(post.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = IOS.label)
            }
            if (post.body.isNotEmpty()) {
                Text(post.body, fontSize = 12.sp, color = IOS.secondaryLabel, maxLines = 2)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("나", fontSize = 11.sp, color = IOS.tertiaryLabel)
                Text(relativeTime(post.createdAt), fontSize = 11.sp, color = IOS.tertiaryLabel)
                post.courseTitle?.let { title ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(Icons.Filled.Place, contentDescription = null, tint = IOS.pinDeep, modifier = Modifier.size(10.dp))
                        Text(title, fontSize = 11.sp, color = IOS.pinDeep, maxLines = 1)
                    }
                }
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "지우기", tint = IOS.tertiaryLabel, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MarketRow(course: MarketCourseSummary) {
    Column(
        verticalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Badge("코스 추천", IOS.pinDeep)
            Text(course.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, color = IOS.label)
        }
        val works =
            course.contents
                .orEmpty()
                .take(2)
                .joinToString(" · ") { it.title }
        val summary = "${course.dayCount}일 · ${course.placeCount}곳" + if (works.isNotEmpty()) " · $works" else ""
        Text(summary, fontSize = 12.sp, color = IOS.secondaryLabel, maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("여행자", fontSize = 11.sp, color = IOS.tertiaryLabel)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Icon(Icons.Filled.Favorite, contentDescription = null, tint = IOS.tertiaryLabel, modifier = Modifier.size(10.dp))
                Text("${course.likeCount}", fontSize = 11.sp, color = IOS.tertiaryLabel)
            }
            Spacer(Modifier.weight(1f))
            Text("담기는 경로여정 탭에서", fontSize = 11.sp, color = IOS.tertiaryLabel)
        }
    }
}

internal fun formatDateTime(millis: Long): String = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(millis))
