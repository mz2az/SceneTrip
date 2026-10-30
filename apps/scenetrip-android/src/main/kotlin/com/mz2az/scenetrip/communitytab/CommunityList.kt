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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.DisposableEffect
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
import com.mz2az.scenetrip.data.CommunityPhotoRefresh
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.DateText
import com.mz2az.scenetrip.data.ModerationController
import com.mz2az.scenetrip.data.ModerationRules
import com.mz2az.scenetrip.data.ModerationTarget
import com.mz2az.scenetrip.data.PhotoRenewal
import com.mz2az.scenetrip.data.PhotoRenewalRules
import com.mz2az.scenetrip.data.RelativeTime
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.reviews.ModerationMenu
import com.mz2az.scenetrip.reviews.ModerationSheets
import com.mz2az.scenetrip.reviews.rememberModerationController
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.BubblesIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSAlert
import com.mz2az.scenetrip.ui.IOSGlassSheetColor
import com.mz2az.scenetrip.ui.IOSLinedText
import com.mz2az.scenetrip.ui.IOSListDivider
import com.mz2az.scenetrip.ui.IOSRole
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.RouteCurveIcon
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.sfLine
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
    // 남의 후기의 신고·차단(MZ2AZ-461).
    val moderation = rememberModerationController()
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
    // 당겨서 새로고침의 동그라미는 **당겨서 시작한 새로고침 동안만** 돈다. 처음 불러오는 중에도 켰더니 목록 가운데 스피너와
    // 맨 위 새로고침 동그라미가 한꺼번에 돌아 두 겹으로 보였다(오프라인에서 특히 길게). iOS `.refreshable` 도 당겼을 때만 돈다 (MZ2AZ-513).
    var pulled by remember { mutableStateOf(false) }
    PullToRefreshBox(
        isRefreshing = pulled,
        onRefresh = {
            scope.launch {
                pulled = true
                try {
                    store.refresh(mine)
                } finally {
                    pulled = false
                }
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = sheetListBottom()) {
            state.message?.let { message ->
                item {
                    Text(message, color = IOS.systemRed, style = IOS.footnote, modifier = Modifier.padding(16.dp))
                    // 내 글 시트는 오류 한 줄뿐이다 — 당겨서 새로고침이 다시 시도다 (iOS `MyPostsSheet`).
                    if (!mine) CommunityListButton("다시 시도", !loading) { scope.launch { store.refresh(mine) } }
                }
            }
            if (mine && !AuthStore.signedIn) item { CommunityListButton("로그인", plainRow = true) { AuthStore.promptSignIn() } }
            if (mine) item { SectionTitle("서버에 게시한 글", plainHeader = true) }
            if (loading && posts.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = IOS.accent)
                    }
                }
            }
            // 기기에만 있던 옛 글은 목록 맨 위에 구역으로 보인다 — 눌러서 읽는다 (iOS `CommunityTabView`, MZ2AZ-396·423).
            if (!mine && store.legacyPosts.isNotEmpty()) {
                item(key = "legacy-title") { SectionTitle("이 기기에만 저장된 옛 글") }
                items(store.legacyPosts, key = { "legacy:" + it.id }) { post ->
                    CommunityPostRow(store, post, false, moderation) { reading = post }
                    IOSListDivider(start = 16.dp)
                }
                item(key = "legacy-footer") {
                    Text(
                        tr("기존 사진과 코스를 보관하고 있어요. 이 기기에서만 보입니다"),
                        style = IOS.footnote,
                        color = IOS.secondaryLabel,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
            if (mine && posts.isEmpty()) {
                // 내 글이 없으면 로딩·오류 중에도 이 줄이 「서버에 게시한 글」 아래에 선다 (iOS `MyPostsSheet`).
                // iOS 목록 행은 44 높이에 글 17 이고 아래에 구분선이 선다(2026-10-29 실측, 선 사이 44).
                item {
                    Text(
                        tr("아직 쓴 글이 없습니다"),
                        style = IOS.body,
                        color = IOS.secondaryLabel,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 11.dp),
                    )
                    IOSListDivider(start = 16.dp)
                }
            }
            if (!mine && posts.isEmpty() && store.legacyPosts.isEmpty() && !loading && state.message == null) {
                item { CommunityEmptyState() }
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
                ) { CommunityPostRow(store, post, mine, moderation) { reading = post } }
                IOSListDivider(start = 16.dp, overlay = !mine)
            }
            if (posts.size < total) {
                item {
                    CommunityListButton("더 보기", !loading, plainRow = mine) { scope.launch { store.refresh(mine, more = true) } }
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
    ModerationSheets(moderation) { target -> store.blockedInBackground(target.targetId) }
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
private fun SectionTitle(
    title: String,
    plainHeader: Boolean = false,
) {
    if (plainHeader) {
        // iOS `List(.plain)` 의 `Section("…")` 머리글 — 글 17 semibold 회색, 위 30·아래 11 (구분선에서 글 가운데까지 41, 실측).
        Text(
            tr(title),
            style = IOS.body,
            fontWeight = FontWeight.SemiBold,
            color = IOS.secondaryLabel,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 30.dp, bottom = 11.dp),
        )
        return
    }
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
    // 「내가 쓴 글」 의 `List(.plain)` 안 단추 — 글자는 검정, 높이 44, 아래 구분선(iOS 실측). 폼 안 단추는 강조색.
    plainRow: Boolean = false,
    onClick: () -> Unit,
) {
    if (plainRow) {
        Text(
            tr(title),
            style = IOS.body,
            color = if (enabled) IOS.label else IOS.secondaryLabel,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .clickable(enabled = enabled, onClick = onClick)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
        )
        IOSListDivider(start = 16.dp)
        return
    }
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
    moderation: ModerationController,
    onClick: () -> Unit,
) {
    val postId = post.serverId
    val menu = postId != null && ModerationRules.showsMenu(isMine = post.isMine)
    Row(
        Modifier
            .fillMaxWidth()
            // 「내가 쓴 글」 시트(compact)는 유리 바탕이라 줄도 같은 색이다.
            .background(if (compact) IOSGlassSheetColor else IOS.systemBackground),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                // 커뮤니티 목록 한 줄은 iOS 118(사진 76 + 위아래 21, 2026-10-29 실측) — `List` 줄 안쪽 여백 15 에 줄 안 6 이 더해진다.
                // 「내가 쓴 글」 시트는 따로 그린 줄이라 그대로 둔다.
                .padding(start = 16.dp, end = if (menu) 0.dp else 16.dp)
                .padding(vertical = if (compact) 10.dp else 21.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp)) {
                // 줄 칸은 iOS 줄 높이(15pt 17.9 · 12pt 14.3 · 11pt 13.1)로 고정한다 — 7차 감사 C1: 글꼴 제 높이라 본문 +5.1, 글쓴이 +7.6 아래였다.
                if (compact) {
                    Text(post.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, color = IOS.label)
                } else {
                    IOSLinedText(
                        post.title,
                        lineDp = 17.9f,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = IOS.label,
                        maxLines = 2,
                    )
                }
                if (compact) {
                    Text(formatDateTime(post.createdAt), fontSize = 11.sp, color = IOS.tertiaryLabel)
                } else {
                    if (post.body.isNotEmpty()) {
                        IOSLinedText(
                            post.body,
                            lineDp = 14.3f,
                            fontSize = 12.sp,
                            color = IOS.secondaryLabel,
                            maxLines = 2,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            post.authorName(AuthStore.me?.nickname),
                            fontSize = 11.sp,
                            color = IOS.tertiaryLabel,
                            modifier = Modifier.sfLine(11f),
                        )
                        Text(relativeTime(post.createdAt), fontSize = 11.sp, color = IOS.tertiaryLabel, modifier = Modifier.sfLine(11f))
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
                    CommunityCoverPhoto(post, store, photoModifier)
                } else if (bitmap != null) {
                    Image(bitmap.asImageBitmap(), null, photoModifier, contentScale = ContentScale.Crop)
                }
            }
        }
        // 눌리는 줄 밖에 둔다 — 안에 두면 「⋯」 를 눌러도 글이 열린다.
        if (postId != null && menu) {
            ModerationMenu(
                target = ModerationTarget.post(postId),
                canBlock = ModerationRules.canBlock(isMine = post.isMine, hasAuthor = post.author != null),
                controller = moderation,
                // 「⋯」 가운데가 제목 첫 줄 가운데(줄 위에서 29.5)에 놓인다 — 메뉴 칸 44 의 가운데가 줄 위 8.5+22.
                // iOS 메뉴 칸은 32×28, 줄 윗에서 15 아래 · 오른쪽 16 (7차 감사 C3: 「…」 가 6.7dp 오른쪽이었다).
                modifier = Modifier.padding(top = if (compact) 12.dp else 15.dp, end = if (compact) 10.dp else 16.dp).size(32.dp, 28.dp),
            )
        }
    }
}

/**
 * 목록 줄의 대표 사진. 주소가 죽으면(한 시간) 그 글의 상세를 다시 읽어 새 주소로 바꾼다(MZ2AZ-477, iOS `CommunityCoverPhoto`).
 * 줄마다 따로 갱신하므로 한 줄의 실패가 다른 줄을 건드리지 않는다. 사진마다 저절로 한 번만 청하고, 「다시 시도」 는 언제든 된다.
 */
@Composable
internal fun CommunityCoverPhoto(
    post: CommunityPost,
    store: CommunityStore,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var refreshed by remember(post.id) { mutableStateOf<CommunityPost?>(null) }
    val refresh =
        remember(post.id) {
            CommunityPhotoRefresh(post, { AuthStore.epoch }, { store.detail(it) }, PhotoRenewal(scope)) { refreshed = it }
        }
    // 목록을 다시 받아 주소가 바뀌었으면 그 글을 기준으로 다시 시작한다.
    LaunchedEffect(post.remotePhotos) { refresh.show(post) }
    DisposableEffect(refresh) { onDispose { refresh.cancelPending() } }
    val url = (refreshed ?: post).remotePhotos.firstOrNull() ?: return
    val objectKey = PhotoRenewalRules.unsigned(url)
    var renewedFor by remember(objectKey) { mutableStateOf(false) }
    RemoteImage(
        url,
        modifier,
        cacheKey = objectKey,
        retryable = true,
        onFailure = {
            if (PhotoRenewalRules.renewsAfterFailure(true, renewedFor)) {
                renewedFor = true
                scope.launch { refresh.renew(false) }
            }
        },
        onRetry = { scope.launch { refresh.renew(true) } },
    )
}

/** 「3일 전」·「어제」 — 앱 언어의 이름 붙은 상대 시각 (iOS `.relative(presentation: .named)`). */
private fun relativeTime(millis: Long): String =
    RelativeTime.format(java.time.OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault()))

internal fun formatDateTime(millis: Long): String = DateText.dateTime(millis)

@Composable
fun MyPostsSheet(onClose: () -> Unit) {
    val store = rememberCommunityStore()
    LaunchedEffect(AuthStore.epoch, AppLanguage.current) { store.refresh(mine = true) }
    IOSSheet(listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onClose, containerColor = IOSGlassSheetColor) {
        Column(Modifier.fillMaxSize().background(IOSGlassSheetColor)) {
            // 마이페이지의 다른 시트와 같은 머리줄 — 제목 가운데, 오른쪽 작은 X (iOS `ProfileSheetHeader`).
            com.mz2az.scenetrip.profiletab
                .ProfileSheetHeader(tr("내가 쓴 글"), onClose)
            CommunityPostList(store, mine = true)
        }
    }
}
