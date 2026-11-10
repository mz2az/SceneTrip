package com.mz2az.scenetrip.communitytab

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.CommunityFeedRules
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBoard
import com.mz2az.scenetrip.ui.CheckmarkIcon
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSAlert
import com.mz2az.scenetrip.ui.IOSRole
import kotlinx.coroutines.launch

/**
 * 글쓰기 — **블로그 글쓰기처럼** (MZ2AZ-351, 블로그형 MZ2AZ-547 K10·K11, iOS `CommunityComposeView`).
 *
 * 커뮤니티 탭에서 오른쪽으로 밀려 들어오는 새 화면이다([CommunityPageHost]). 머리줄 「글쓰기」 아래 게시판 칩(처음 값은 보던 게시판)으로
 * 올릴 게시판을 고른다(`TripPostCreate.board`). 제목 → 본문(글 조각과 사진을 쓴 순서대로, [CommunityArticleEditor]) → 다녀온 코스.
 * 비로그인도 화면은 열린다 — 사진 넣기·올리기를 누르면 로그인으로 안내한다. 하드웨어 뒤로는 「취소」 와 같다(쓰던 것이 있으면 묻는다).
 */
@Composable
fun CommunityComposeView(
    onDismiss: () -> Unit,
    suppliedDraft: CommunityComposeState? = null,
    board: TripPostBoard = TripPostBoard.review,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = rememberCommunityStore()
    val draft = suppliedDraft ?: remember { CommunityComposeState(context, scope, store, board) }
    DisposableEffect(draft) {
        draft.photos.resume()
        onDispose { draft.photos.cancel() }
    }
    LaunchedEffect(AuthStore.epoch) { if (!draft.sameAccount) draft.photos.cancel() }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(draft.photos.limit)) {
            // 아무것도 고르지 않고 닫았으면 정해 둔 자리를 버린다.
            if (it.isEmpty()) draft.insertAfter = null
            if (!draft.locked) draft.photos.add(it)
        }
    BackHandler { draft.close(onDismiss) }
    Column(
        Modifier
            .fillMaxSize()
            .background(IOS.systemBackground)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        CommunityComposeToolbar(draft, onDismiss) { scope.launch { draft.submit(onDismiss) } }
        ComposeBoardChip(draft)
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ComposeStatus(draft, onDismiss)
            ComposeEditor(draft) {
                if (!AuthStore.signedIn) {
                    AuthStore.promptSignIn()
                    false
                } else {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    true
                }
            }
            Box(Modifier.padding(horizontal = 16.dp).padding(top = 6.dp)) {
                ComposeCourseCard(draft.course, draft.locked, { draft.pickingCourse = true }, { draft.course = null })
            }
            draft.course?.excludedPins?.takeIf { it > 0 }?.let {
                Text(
                    tr("직접 찍은 핀 %d곳은 공유되지 않습니다").format(it),
                    style = IOS.footnote,
                    color = IOS.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
            Text(
                tr("제목 %d/100 · 본문 %d/5000").format(draft.title.length, draft.story.length),
                style = IOS.caption2,
                color = IOS.secondaryLabel,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
    }
    ComposeOverlays(draft, onDismiss)
    if (draft.previewing) {
        CommunityArticlePreview(draft.title, draft.articleBlocks, draft.photos.photos.map(CommunityPhotoSource::from), draft.course) {
            draft.previewing =
                false
        }
    }
}

/** 머리줄 아래 게시판 고르기 — 보라 옅은 칩 하나(「여행후기 ⌄」). 누르면 바로 아래로 목록이 펼쳐진다(고른 것에 ✓). */
@Composable
private fun ComposeBoardChip(draft: CommunityComposeState) {
    var open by remember { mutableStateOf(false) }
    val label = tr(CommunityFeedRules.boardLabelKey(draft.board))
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 6.dp)) {
        Box {
            Row(
                Modifier
                    .heightIn(min = 44.dp)
                    .semantics { contentDescription = tr("게시판: %s").format(label) }
                    .clickable(enabled = !draft.locked) { open = true }
                    .testTag("community-board-picker"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    Modifier
                        .height(28.dp)
                        .clip(RoundedCornerShape(50))
                        .background(IOS.pinDeep.copy(alpha = 0.1f))
                        .padding(start = 11.dp, end = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = IOS.pinDeep)
                    ChevronRightIcon(IOS.pinDeep, Modifier.size(9.dp).rotate(90f), weight = 0.2f)
                }
            }
            DropdownMenu(open, { open = false }, Modifier.background(IOS.systemBackground)) {
                CommunityFeedRules.boards.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(tr(CommunityFeedRules.boardLabelKey(item)), style = IOS.subheadline, color = IOS.label) },
                        trailingIcon = { if (item == draft.board) CheckmarkIcon(IOS.pinDeep, Modifier.size(13.dp)) },
                        onClick = {
                            draft.board = item
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposeStatus(
    draft: CommunityComposeState,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    draft.status.message?.let {
        Text(it, style = IOS.footnote, color = IOS.systemRed, modifier = Modifier.padding(horizontal = 20.dp))
    }
    if (draft.status.uncertain) {
        CommunityListButton("게시 여부 확인", !draft.status.working && draft.sameAccount) { scope.launch { draft.check(onDismiss) } }
        CommunityListButton("내 글 목록 보기", !draft.status.working) { draft.checkingPosts = true }
    }
    if (!AuthStore.signedIn) {
        CommunityListButton("로그인한 뒤에 쓸 수 있어요") { AuthStore.promptSignIn() }
    } else if (!draft.sameAccount) {
        Text(
            tr("계정이 바뀌었어요. 이 초안을 닫고 새로 작성해 주세요"),
            style = IOS.footnote,
            color = IOS.systemRed,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun ComposeEditor(
    draft: CommunityComposeState,
    onPick: () -> Boolean,
) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BasicTextField(
            draft.title,
            // 제목은 한 줄 글이다 — 줄바꿈은 빈칸으로.
            { draft.title = it.replace('\n', ' ') },
            enabled = !draft.locked,
            textStyle = IOS.body.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IOS.label),
            cursorBrush = SolidColor(IOS.accent),
            maxLines = 3,
            modifier = Modifier.fillMaxWidth().testTag("post-title"),
            decorationBox = { inner ->
                if (draft.title.isEmpty()) Text(tr("제목"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IOS.tertiaryLabel)
                inner()
            },
        )
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(IOS.separator))
        CommunityArticleEditor(draft, onPick)
    }
}

@Composable
private fun ComposeOverlays(
    draft: CommunityComposeState,
    onDismiss: () -> Unit,
) {
    if (draft.pickingCourse) {
        CoursePickSheet(onDismiss = { draft.pickingCourse = false }) {
            if (draft.sameAccount && !draft.locked) draft.course = it
            draft.pickingCourse = false
        }
    }
    if (draft.checkingPosts) MyPostsSheet(onClose = { draft.checkingPosts = false })
    if (draft.confirmingDiscard) {
        IOSAlert(
            tr("쓰던 글을 버릴까요?"),
            null,
            listOf(
                IOSAction(tr("계속 쓰기"), IOSRole.CANCEL) {},
                IOSAction(tr("버리기"), IOSRole.DESTRUCTIVE) { onDismiss() },
            ),
            onDismiss = { draft.confirmingDiscard = false },
        )
    }
}
