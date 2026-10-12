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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.BubblesIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSAlert
import com.mz2az.scenetrip.ui.IOSListDivider
import com.mz2az.scenetrip.ui.IOSRole
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.RouteCurveIcon
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.sheetListBottom
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 서버 목록의 새로고침·페이지 이동·소유자 삭제와 비공개 옛 글을 함께 다룬다. */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CommunityPostList(
    store: CommunityStore,
    mine: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf<CommunityPost?>(null) }
    var deleting by remember { mutableStateOf<CommunityPost?>(null) }
    val state = store.state
    val posts = if (mine) state.mine else state.posts
    val loading = if (mine) state.mineLoading else state.loading
    val total = if (mine) state.mineTotal else state.total
    LaunchedEffect(AuthStore.epoch) {
        reading = null
        deleting = null
    }
    PullToRefreshBox(isRefreshing = loading, onRefresh = { scope.launch { store.refresh(mine) } }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = sheetListBottom()) {
            state.message?.let { message ->
                item {
                    Text(message, color = IOS.systemRed, style = IOS.footnote, modifier = Modifier.padding(16.dp))
                    CommunityListButton("다시 시도", !loading) { scope.launch { store.refresh(mine) } }
                }
            }
            if (mine && !AuthStore.signedIn) item { CommunityListButton("로그인") { AuthStore.promptSignIn() } }
            if (mine) item { SectionTitle("서버에 게시한 글") }
            if (loading && posts.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = IOS.accent)
                    }
                }
            }
            if (posts.isEmpty() && !loading && state.message == null) {
                item {
                    if (mine) {
                        Text(tr("아직 쓴 글이 없습니다"), color = IOS.secondaryLabel, modifier = Modifier.padding(16.dp))
                    } else {
                        CommunityEmptyState()
                    }
                }
            }
            items(posts, key = { it.id }) { post ->
                val swipe =
                    rememberSwipeToDismissBoxState(confirmValueChange = {
                        if (it == SwipeToDismissBoxValue.EndToStart) deleting = post
                        false
                    })
                SwipeToDismissBox(
                    state = swipe,
                    enableDismissFromStartToEnd = false,
                    enableDismissFromEndToStart = post.isMine && post.serverId != null,
                    backgroundContent = {
                        Box(Modifier.fillMaxSize().background(IOS.systemRed).padding(20.dp), contentAlignment = Alignment.CenterEnd) {
                            Text(tr("지우기"), color = Color.White)
                        }
                    },
                ) { CommunityPostRow(store, post, mine) { reading = post } }
                IOSListDivider(start = 16.dp)
            }
            if (posts.size < total) {
                item {
                    CommunityListButton("더 보기", !loading) { scope.launch { store.refresh(mine, more = true) } }
                }
            }
            if (mine && store.legacyPosts.isNotEmpty()) {
                item { SectionTitle("이 기기에만 저장된 옛 글") }
                items(store.legacyPosts, key = { "legacy:" + it.id }) { post ->
                    Text(
                        post.title,
                        color = IOS.label,
                        modifier = Modifier.fillMaxWidth().clickable { reading = post }.padding(16.dp),
                    )
                    IOSListDivider(start = 16.dp)
                }
            }
        }
    }
    reading?.let { post ->
        IOSSheet(listOf(SheetDetent.LARGE), onDismiss = { reading = null }) {
            CommunityPostView(post, onDismiss = { reading = null })
        }
    }
    deleting?.let { post ->
        IOSAlert(
            tr("후기를 지울까요?"),
            null,
            listOf(
                IOSAction(tr("취소"), IOSRole.CANCEL) {},
                IOSAction(tr("지우기"), IOSRole.DESTRUCTIVE) { scope.launch { store.remove(post) } },
            ),
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        tr(title),
        style = IOS.footnote,
        color = IOS.secondaryLabel,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

@Composable
internal fun CommunityListButton(
    title: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        tr(title),
        color = if (enabled) IOS.accent else IOS.secondaryLabel,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(16.dp),
    )
}

@Composable
private fun CommunityEmptyState() {
    Column(Modifier.fillMaxWidth().padding(top = 54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BubblesIcon(IOS.secondaryLabel, Modifier.padding(bottom = 14.dp).size(66.dp, 52.dp))
        Text(tr("아직 후기가 없습니다"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IOS.label)
        Text(
            tr("다녀온 코스와 사진으로 첫 후기를 남겨 보세요"),
            fontSize = 15.sp,
            color = IOS.secondaryLabel,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp).padding(horizontal = 20.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 44.dp)
                .padding(start = 60.dp, end = 16.dp)
                .height(0.5.dp)
                .background(IOS.separator),
        )
    }
}

@Composable
private fun CommunityPostRow(
    store: CommunityStore,
    post: CommunityPost,
    compact: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(IOS.systemBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
            Text(
                post.title,
                fontSize = 15.sp,
                fontWeight = if (compact) FontWeight.Medium else FontWeight.SemiBold,
                maxLines = if (compact) 1 else 2,
                color = IOS.label,
            )
            if (compact) {
                Text(formatDateTime(post.createdAt), fontSize = 11.sp, color = IOS.tertiaryLabel)
            } else {
                if (post.body.isNotEmpty()) Text(post.body, fontSize = 12.sp, color = IOS.secondaryLabel, maxLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(post.authorName(AuthStore.me?.nickname), fontSize = 11.sp, color = IOS.tertiaryLabel)
                    Text(relativeTime(post.createdAt), fontSize = 11.sp, color = IOS.tertiaryLabel)
                }
                (post.course?.title ?: post.courseTitle)?.let { title ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        RouteCurveIcon(IOS.pinDeep, Modifier.size(10.dp))
                        Text(title, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = IOS.pinDeep, maxLines = 1)
                    }
                }
            }
        }
        if (!compact) {
            val remote = post.remotePhotos.firstOrNull()
            val bitmap = post.photos?.firstOrNull()?.let { remember(it) { store.photo(it) } }
            val photoModifier = Modifier.size(76.dp).clip(RoundedCornerShape(12.dp))
            if (remote != null) {
                RemoteImage(remote, photoModifier, retryable = true)
            } else if (bitmap != null) {
                Image(bitmap.asImageBitmap(), null, photoModifier, contentScale = ContentScale.Crop)
            }
        }
    }
}

private fun relativeTime(millis: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes((System.currentTimeMillis() - millis).coerceAtLeast(0))
    return when {
        minutes < 1 -> tr("방금")
        minutes < 60 -> tr("%d분 전").format(minutes)
        minutes < 24 * 60 -> tr("%d시간 전").format(minutes / 60)
        else -> tr("%d일 전").format(minutes / (24 * 60))
    }
}

internal fun formatDateTime(millis: Long): String = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(millis))

@Composable
fun MyPostsSheet(onClose: () -> Unit) {
    val store = rememberCommunityStore()
    LaunchedEffect(AuthStore.epoch, AppLanguage.current) { store.refresh(mine = true) }
    IOSSheet(listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onClose) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            IOSSheetToolbar(title = tr("내가 쓴 글"), trailing = tr("닫기"), onTrailing = onClose)
            CommunityPostList(store, mine = true)
        }
    }
}
