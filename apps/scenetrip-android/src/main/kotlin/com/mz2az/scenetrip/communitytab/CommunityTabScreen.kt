package com.mz2az.scenetrip.communitytab

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.BubblesIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.RouteCurveIcon
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.SquarePencilIcon
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 커뮤니티 — **여행후기** (2026-10-05 재편, MZ2AZ-351). iOS
 * `CommunityTab/CommunityTabView.swift`를 옮긴 것이다.
 *
 * 말머리 넷(코스 추천·장소 후기·인증샷·자유)이던 게시판을 **여행후기 하나**로 줄였다
 * (2026-10-03 팀 회의). 후기는 사진과 다녀온 코스를 붙여 쓰고, 읽는 사람은 그 코스를
 * 보고 내 코스로 담는다.
 *
 * 게시판 서버가 아직 없어 글은 **기기에만** 있다 — 남의 글을 받아 올 길이 없다.
 * 지어낸 글을 앱에 박지 않는다.
 */
@Composable
fun CommunityTabScreen() {
    val context = LocalContext.current
    val store = remember { CommunityStore.getInstance(context) }
    var composing by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<CommunityPost?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
            CommunityHeader(onCompose = { composing = true })
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(IOS.separator))

            if (store.posts.isEmpty()) {
                // iOS `ContentUnavailableView` 상당 — 심벌 + 굵은 제목 + 설명, 가운데
                // 정렬에 너비를 좁혀 둔다. 심벌은 탭바 커뮤니티 아이콘과 같은 그림
                // ([BubblesIcon]) — iOS도 `bubble.left.and.bubble.right`로 같다.
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
                        tr("아직 후기가 없습니다"),
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = IOS.label,
                    )
                    Text(
                        tr("다녀온 코스와 사진으로 첫 후기를 남겨 보세요"),
                        fontSize = 15.sp,
                        color = IOS.secondaryLabel,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp).widthIn(max = 280.dp),
                    )
                    androidx.compose.material3.HorizontalDivider(
                        thickness = 1.dp,
                        color = Color(0xFFE8E8E8),
                        modifier = Modifier.padding(top = 44.dp).padding(start = 60.dp, end = 16.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(store.posts, key = { it.id }) { post ->
                        MyPostRow(store = store, post = post, onClick = { reading = post }, onDelete = { store.remove(post) })
                    }
                }
            }
        }

        // iOS `.sheet(item:)` + `.presentationDetents([.large])` — 탭 위 페이지가 아니라 시트.
        reading?.let { post ->
            IOSSheet(detents = listOf(SheetDetent.LARGE), onDismiss = { reading = null }) {
                CommunityPostView(post = post, onDismiss = { reading = null })
            }
        }

        if (composing) {
            CommunityComposeView(
                onDismiss = { composing = false },
                onSubmit = { title, body, photos, course ->
                    store.add(title = title, body = body, photos = photos, course = course)
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
        Text(tr("커뮤니티"), style = IOS.headline, color = IOS.label, modifier = Modifier.align(Alignment.Center))
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

/** "3분 전" 처럼 상대 시각으로. iOS `Date.formatted(.relative)`의 근사치. */
private fun relativeTime(millis: Long): String {
    val diff = System.currentTimeMillis() - millis
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    return when {
        minutes < 1 -> tr("방금")
        minutes < 60 -> tr("%d분 전").format(minutes)
        minutes < 24 * 60 -> tr("%d시간 전").format(minutes / 60)
        else -> tr("%d일 전").format(minutes / (24 * 60))
    }
}

/** 후기 한 줄 — 왼쪽에 제목·본문·글쓴이, 오른쪽에 대표 사진. */
@Composable
private fun MyPostRow(
    store: CommunityStore,
    post: CommunityPost,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(post.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, color = IOS.label)
            if (post.body.isNotEmpty()) {
                Text(post.body, fontSize = 12.sp, color = IOS.secondaryLabel, maxLines = 2)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(post.author ?: tr("나"), fontSize = 11.sp, color = IOS.tertiaryLabel)
                Text(relativeTime(post.createdAt), fontSize = 11.sp, color = IOS.tertiaryLabel)
            }
            val courseTitle = post.course?.title ?: post.courseTitle
            if (courseTitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    RouteCurveIcon(IOS.pinDeep, Modifier.size(10.dp))
                    Text(courseTitle, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = IOS.pinDeep, maxLines = 1)
                }
            }
        }
        val photoName = post.photos?.firstOrNull()
        val photo = photoName?.let { remember(it) { store.photo(it) } }
        if (photo != null) {
            Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(76.dp).clip(RoundedCornerShape(12.dp)),
            )
        }
        // 지우기는 내 글만 — 남의 글을 지울 수 있으면 안 된다.
        if (post.isMine) {
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = tr("지우기"), tint = IOS.tertiaryLabel, modifier = Modifier.size(18.dp))
            }
        }
    }
}

internal fun formatDateTime(millis: Long): String = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(millis))
