package com.mz2az.scenetrip.communitytab

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSAlert
import com.mz2az.scenetrip.ui.IOSRole
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.SheetDetent
import kotlinx.coroutines.launch

@Composable
fun CommunityComposeView(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = rememberCommunityStore()
    val draft = remember { CommunityComposeState(context, scope, store) }
    DisposableEffect(draft) {
        draft.photos.resume()
        onDispose { draft.photos.cancel() }
    }
    LaunchedEffect(AuthStore.epoch) { if (!draft.sameAccount) draft.photos.cancel() }
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(draft.photos.limit)) {
            if (!draft.locked) draft.photos.add(it)
        }
    IOSSheet(
        listOf(SheetDetent.LARGE),
        onDismiss = { draft.close(onDismiss) },
        interactiveDismissEnabled = !draft.status.working && !draft.hasDraft,
    ) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground).imePadding()) {
            IOSSheetToolbar(
                title = tr("여행후기 쓰기"),
                leading = tr("취소"),
                onLeading = { draft.close(onDismiss) },
                trailing = tr("올리기"),
                trailingEnabled = draft.canPost,
                onTrailing = { scope.launch { draft.submit(onDismiss) } },
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                ComposeStatus(draft, onDismiss)
                CommunityPhotoStrip(draft.photos, draft.locked) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                ComposeEditor(draft)
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
    }
    ComposeOverlays(draft, onDismiss)
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
private fun ComposeEditor(draft: CommunityComposeState) {
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        BasicTextField(
            draft.title,
            { draft.title = it },
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
        BasicTextField(
            draft.story,
            { draft.story = it },
            enabled = !draft.locked,
            textStyle = IOS.body.copy(color = IOS.label, lineHeight = 22.sp),
            cursorBrush = SolidColor(IOS.accent),
            minLines = 8,
            modifier = Modifier.fillMaxWidth().testTag("post-body"),
            decorationBox = { inner ->
                if (draft.story.isEmpty()) {
                    Text(
                        tr("어디를 다녀왔나요? 장면 속 그 자리에 선 이야기를 들려주세요"),
                        fontSize = 17.sp,
                        color = IOS.tertiaryLabel,
                    )
                }
                inner()
            },
        )
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
            tr("쓰던 후기를 버릴까요?"),
            null,
            listOf(
                IOSAction(tr("계속 쓰기"), IOSRole.CANCEL) {},
                IOSAction(tr("버리기"), IOSRole.DESTRUCTIVE) { onDismiss() },
            ),
            onDismiss = { draft.confirmingDiscard = false },
        )
    }
}
