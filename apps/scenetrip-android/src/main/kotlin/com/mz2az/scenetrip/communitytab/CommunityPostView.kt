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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.CommunityArticle
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.CommunityRules
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.ModerationController
import com.mz2az.scenetrip.data.ModerationRules
import com.mz2az.scenetrip.data.ModerationTarget
import com.mz2az.scenetrip.data.PostCourse
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.reviews.ModerationMenu
import com.mz2az.scenetrip.reviews.ModerationSheets
import com.mz2az.scenetrip.reviews.rememberModerationController
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.ui.CheckmarkIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.XMarkIcon
import kotlinx.coroutines.launch

private const val PREVIEW_STOPS = 4

/**
 * 여행후기 읽기 (MZ2AZ-351) — 블로그 글처럼 **사진이 먼저, 그 아래 글, 끝에 다녀온 코스.**
 * iOS `CommunityTab/CommunityPostView.swift`를 옮긴 것이다.
 *
 * 붙은 코스는 이름만이 아니라 **일차별 장소까지** 보인다. 「내 코스로 담기」를 누르면 같은
 * 코스가 내 것으로 하나 생긴다 — 후기를 읽고 「나도 이대로 가야지」가 한 번에 되어야 한다.
 */
@Composable
fun CommunityPostView(
    post: CommunityPost,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { CommunityStore.getInstance(context) }
    val scope = rememberCoroutineScope()
    // 남의 후기의 신고·차단(MZ2AZ-461).
    val moderation = rememberModerationController()

    var loaded by remember(post.id) { mutableStateOf<CommunityPost?>(null) }
    var loading by remember(post.id) { mutableStateOf(false) }
    var loadMessage by remember(post.id) { mutableStateOf<String?>(null) }
    var revision by remember(post.id) { mutableStateOf(0) }
    val content = loaded ?: post
    LaunchedEffect(post.id, revision, AuthStore.epoch, AppLanguage.current) {
        loaded = null
        if (post.serverId != null) {
            loading = true
            loadMessage = null
            apiResult { store.detail(post) }.fold(onSuccess = { loaded = it }, onFailure = { loadMessage = CommunityRules.failureText(it) })
            loading = false
        }
    }
    var saving by remember { mutableStateOf(false) }
    var savedCourseId by remember { mutableStateOf<Long?>(null) }
    var saveFailed by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(AuthStore.epoch) {
        savedCourseId = null
        saving = false
        saveFailed = false
    }

    fun shown(stops: List<PostCourse.Stop>) = if (expanded) stops else stops.take(PREVIEW_STOPS)

    Box(Modifier.fillMaxSize().background(IOS.systemBackground)) {
        LazyColumn(Modifier.fillMaxSize()) {
            if (post.serverId != null && loaded == null) {
                item {
                    if (loading) {
                        Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), color = IOS.accent)
                        }
                    }
                    loadMessage?.let {
                        Text(it, color = IOS.secondaryLabel, modifier = Modifier.padding(20.dp))
                        CommunityListButton("다시 시도", !loading) { revision++ }
                    }
                }
            } else {
                val photos =
                    if (content.remotePhotos.isNotEmpty()) {
                        content.remotePhotos.map(
                            CommunityPhotoSource::Remote,
                        )
                    } else {
                        content.photos.orEmpty().map(CommunityPhotoSource::Local)
                    }
                val blocks = content.bodyBlocks ?: CommunityArticle.legacy(content.body, photos.size)
                photos.firstOrNull()?.let { cover ->
                    item {
                        CommunityArticlePhoto(
                            cover,
                            blocks.coverBlock(),
                            representative = true,
                            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 14.dp),
                        )
                    }
                }
                item {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    ) {
                        Text(content.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IOS.label)

                        AuthorRow(content, moderation)
                    }
                }
                itemsIndexed(blocks) { _, block ->
                    CommunityArticleBlock(block, photos, Modifier.padding(horizontal = 20.dp).padding(bottom = 22.dp))
                }
                item {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 18.dp),
                    ) {
                        val course = content.course
                        if (course != null) {
                            CourseCard(
                                course = course,
                                expanded = expanded,
                                shown = ::shown,
                                allowSaving = content.serverId != null,
                                saving = saving,
                                savedCourseId = savedCourseId,
                                onExpand = { expanded = true },
                                onSave = {
                                    if (!AuthStore.signedIn) {
                                        AuthStore.promptSignIn()
                                        return@CourseCard
                                    }
                                    saving = true
                                    scope.launch {
                                        val account = AuthStore.epoch
                                        apiResult { store.saveCourse(content) }.fold(
                                            onSuccess = { if (account == AuthStore.epoch) savedCourseId = it.id },
                                            onFailure = { if (account == AuthStore.epoch) saveFailed = true },
                                        )
                                        saving = false
                                    }
                                },
                                onOpenSaved = {
                                    savedCourseId?.let { id ->
                                        onDismiss()
                                        TabRouter.openCourse(id)
                                    }
                                },
                            )
                        } else if (content.courseTitle != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(IOS.systemGray6)
                                        .padding(12.dp),
                            ) {
                                PostCourseBadge(size = 30.dp)
                                Text(content.courseTitle!!, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                            }
                        }
                    }
                }
            }
        }

        ModerationSheets(moderation) { target ->
            // 차단한 사람의 후기 상세는 이제 404 다 — 목록에서 빼고 닫는다.
            store.blockedInBackground(target.targetId)
            onDismiss()
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.62f))
                    .clickable(onClick = onDismiss),
        ) {
            XMarkIcon(Color.White, Modifier.size(13.dp))
        }

        if (saveFailed) {
            com.mz2az.scenetrip.ui.IOSConfirmPopover(
                title = tr("코스를 담지 못했어요. 잠시 뒤 다시 해 주세요"),
                actions =
                    listOf(
                        com.mz2az.scenetrip.ui
                            .IOSAction(tr("확인")) { saveFailed = false },
                    ),
                anchorX = 0.dp,
                onDismiss = { saveFailed = false },
                below = true,
            )
        }
    }
}

@Composable
private fun AuthorRow(
    post: CommunityPost,
    moderation: ModerationController,
) {
    val name = post.authorName(AuthStore.me?.nickname)
    val postId = post.serverId
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(32.dp).clip(CircleShape).background(IOS.pinDeep),
        ) {
            Text(name.take(1), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = IOS.label)
            Text(formatDateTime(post.createdAt), fontSize = 11.sp, color = IOS.tertiaryLabel)
        }
        if (postId != null && ModerationRules.showsMenu(isMine = post.isMine)) {
            Spacer(Modifier.weight(1f))
            ModerationMenu(
                target = ModerationTarget.post(postId),
                canBlock = ModerationRules.canBlock(isMine = post.isMine, hasAuthor = post.author != null),
                controller = moderation,
            )
        }
    }
}

@Composable
private fun CourseCard(
    course: PostCourse,
    expanded: Boolean,
    shown: (List<PostCourse.Stop>) -> List<PostCourse.Stop>,
    allowSaving: Boolean,
    saving: Boolean,
    savedCourseId: Long?,
    onExpand: () -> Unit,
    onSave: () -> Unit,
    onOpenSaved: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(IOS.systemGray6)
                .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PostCourseBadge()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("이 후기의 코스"), fontSize = 11.sp, color = IOS.secondaryLabel)
                Text(course.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label, maxLines = 2)
                Text(tr("%d일 · %d곳").format(course.days.size, course.placeCount), fontSize = 12.sp, color = IOS.secondaryLabel)
            }
        }

        course.days.forEachIndexed { dayIndex, stops ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("%d일차").format(dayIndex + 1),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = IOS.pinDeep,
                )
                shown(stops).forEachIndexed { index, stop ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(20.dp).clip(CircleShape).background(IOS.pinLight),
                        ) {
                            Text("${index + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(stop.shownName, fontSize = 15.sp, color = IOS.label)
                            // 영어 화면에서는 간판의 원래 이름(또는 로마자)을 작게 붙인다 — 한국어로 간판을 찾을 수 있게 (iOS 와 같다).
                            if (AppLanguage.current != Lang.ko && stop.shownName != stop.name) {
                                Text(stop.name, fontSize = 11.sp, color = IOS.secondaryLabel)
                            } else if (AppLanguage.current != Lang.ko && stop.nameRoman != null) {
                                Text(stop.nameRoman, fontSize = 11.sp, color = IOS.secondaryLabel)
                            }
                            if (!stop.shownAddress.isNullOrEmpty()) {
                                Text(stop.shownAddress!!, fontSize = 11.sp, color = IOS.secondaryLabel, maxLines = 1)
                            }
                        }
                    }
                }
                if (!expanded && stops.size > PREVIEW_STOPS) {
                    Text(
                        tr("외 %d곳").format(stops.size - PREVIEW_STOPS),
                        fontSize = 12.sp,
                        color = IOS.secondaryLabel,
                        modifier = Modifier.padding(start = 30.dp),
                    )
                }
            }
        }
        if (!expanded && course.days.any { it.size > PREVIEW_STOPS }) {
            Text(
                tr("장소 모두 보기"),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.accent,
                modifier = Modifier.clickable(onClick = onExpand),
            )
        }

        // 서버 글에 첨부된 사본만 담는다. 옛 기기 글은 비공개로 보존한다.
        if (allowSaving) {
            SaveButton(saving = saving, savedCourseId = savedCourseId, onSave = onSave, onOpenSaved = onOpenSaved)
        }
    }
}

@Composable
private fun SaveButton(
    saving: Boolean,
    savedCourseId: Long?,
    onSave: () -> Unit,
    onOpenSaved: () -> Unit,
) {
    if (savedCourseId != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(50))
                    .background(IOS.systemBackground)
                    .border(1.5.dp, IOS.pinDeep, RoundedCornerShape(50))
                    .clickable(onClick = onOpenSaved),
        ) {
            CheckmarkIcon(IOS.pinDeep, Modifier.size(14.dp))
            Text(tr("담았어요 · 코스 보기"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.pinDeep)
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(50))
                    .background(IOS.pinDeep)
                    .clickable(enabled = !saving, onClick = onSave),
        ) {
            if (saving) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                com.mz2az.scenetrip.ui
                    .ArrowDownIcon(Color.White, Modifier.size(14.dp))
            }
            Text(tr("내 코스로 담기"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}
