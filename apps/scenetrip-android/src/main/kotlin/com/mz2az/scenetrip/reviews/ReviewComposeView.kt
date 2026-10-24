package com.mz2az.scenetrip.reviews

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.routetab.PoiLabel
import com.mz2az.scenetrip.sceneapi.client.model.Review
import com.mz2az.scenetrip.sceneapi.client.model.ReviewInput
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSAlert
import com.mz2az.scenetrip.ui.IOSRole
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.SheetDetent
import kotlinx.coroutines.launch

private enum class ComposePhase { LOADING, READY, FAILED }

/**
 * 리뷰 쓰기·고치기 (MZ2AZ-363, iOS `ReviewComposeView`). 별점은 필수, 글은 선택이다 — 별점만 남겨도 된다.
 *
 * 한 대상에 한 개라, 열 때 내 리뷰를 물어(`GET …/reviews/me`) 있으면 그것을 고치는 화면이 된다. **가입한 사람만** 쓴다 — 이 화면은
 * 로그인한 뒤에만 뜬다(`ReviewsSheet` 가 가른다). 사진은 10장까지 — 고르는 즉시 줄여서 올리고([ReviewDraft]), 저장할 때 그 키를 화면
 * 순서대로 보낸다. 올리는 중이거나 못 올린 사진이 있으면 저장하지 못한다 — 사진이 말없이 빠진 리뷰를 만들지 않는다.
 */
@Composable
fun ReviewComposeView(
    subject: ReviewSubject,
    onClose: () -> Unit,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val draft = remember(subject) { ReviewDraft(context, scope) }
    var existing by remember { mutableStateOf<Review?>(null) }
    var rating by remember { mutableStateOf(0) }
    var body by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf(ComposePhase.LOADING) }
    var loadRevision by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val account = remember { AuthStore.epoch }

    fun current() = AuthStore.signedIn && AuthStore.epoch == account

    // 고친 것이 있는가 — 새 리뷰는 별이나 글이나 사진을 건드렸을 때.
    val dirty =
        phase == ComposePhase.READY &&
            if (existing == null) {
                rating != 0 || ReviewRules.normalizedBody(body) != null || draft.changed
            } else {
                ReviewRules.changed(existing, rating, body) || draft.changed
            }
    val canSave = current() && !working && dirty && ReviewRules.canSave(rating, body) && draft.canSave

    fun close() {
        if (!working) {
            if (dirty) discard = true else onClose()
        }
    }

    LaunchedEffect(subject, loadRevision) {
        phase = ComposePhase.LOADING
        message = null
        apiResult { subject.mine() }.fold(
            onSuccess = { value ->
                if (!current()) return@fold
                existing = value
                rating = value?.rating ?: 0
                body = value?.body.orEmpty()
                draft.load(value?.photos.orEmpty())
                phase = ComposePhase.READY
            },
            onFailure = { if (current()) phase = ComposePhase.FAILED },
        )
    }
    // 화면이 사라지면(저장·버리기·닫기) 올리던 사진을 그만둔다. 이미 올라간 것은 서버에 있어 영향이 없다.
    DisposableEffect(draft) {
        draft.resume()
        onDispose { draft.cancel() }
    }
    LaunchedEffect(AuthStore.epoch) {
        if (AuthStore.epoch != account) {
            draft.cancel()
            onClose()
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { draft.add(it) }

    fun finishSaved(
        saved: Review,
        wasNew: Boolean,
    ) {
        AppAnalytics.log(
            AppEvent.WriteReview(
                targetType = subject.kind,
                rating = saved.rating,
                photoCount = saved.photos.size,
                hasBody = saved.body != null,
                edited = !wasNew,
            ),
        )
        onChanged()
        onClose()
    }

    fun save() {
        if (!canSave) return
        working = true
        message = null
        scope.launch {
            val wasNew = existing == null
            val normalized = ReviewRules.normalizedBody(body)
            // 화면에 남아 있는 사진이 전부다 — 붙어 있던 것은 그 키로, 새로 올린 것은 새 키로, 보이는 순서대로.
            val keys = draft.keys
            val result = apiResult { subject.save(ReviewInput(rating = rating, body = normalized, photoKeys = keys)) }
            if (current()) {
                result.fold(
                    onSuccess = { saved -> finishSaved(saved, wasNew) },
                    onFailure = { error ->
                        val problem = NetworkFailure.of(error)
                        if (problem.code != "REVIEW_PHOTO_INVALID") {
                            message = ReviewRules.failureText(problem.status, problem.code)
                        } else {
                            // 서버가 사진 키를 거절했다. **먼저 이미 저장된 것이 아닌지 본다** — 앞선 요청이 응답만 잃고 저장됐으면,
                            // 다시 보낸 이 요청은 사진이 이미 옮겨져 거절된다.
                            val mine = apiResult { subject.mine() }.getOrNull()
                            if (mine != null && ReviewRules.landed(mine, rating, normalized, keys)) {
                                finishSaved(mine, wasNew)
                            } else {
                                // 정말 거절이다 — 이번에 올린 사진을 「다시 시도」 로 돌려놓는다.
                                draft.restoreInvalidPhotos()
                                message = ReviewRules.failureText(problem.status, problem.code)
                            }
                        }
                    },
                )
            }
            working = false
        }
    }

    fun remove() {
        if (!current() || working || existing == null) return
        working = true
        message = null
        scope.launch {
            apiResult { subject.delete() }.fold(
                onSuccess = {
                    if (current()) {
                        AppAnalytics.log(AppEvent.DeleteReview(subject.kind))
                        onChanged()
                        onClose()
                    }
                },
                onFailure = {
                    if (current()) {
                        val problem = NetworkFailure.of(it)
                        message = ReviewRules.failureText(problem.status, problem.code)
                    }
                },
            )
            working = false
        }
    }

    // 편의시설은 「영어 이름 · 한국어 이름」 이라 길다 — 한 줄이면 간판과 맞춰 볼 한국어 쪽이 잘렸다. 카드처럼 제목 / 작은 아랫줄로 나눈다.
    val (nameTitle, nameReading) = remember(subject.name) { PoiLabel.lines(subject.name) }

    IOSSheet(detents = listOf(SheetDetent.LARGE), onDismiss = { close() }, interactiveDismissEnabled = !working && !dirty) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            IOSSheetToolbar(
                title = tr(if (existing == null) "리뷰 쓰기" else "리뷰 고치기"),
                subtitle = nameTitle,
                reading = nameReading,
                leading = tr("취소"),
                onLeading = { close() },
                trailing = tr("저장"),
                trailingEnabled = canSave,
                trailingBusy = working,
                onTrailing = { save() },
            )
            HorizontalDivider(thickness = 0.5.dp, color = IOS.separator.copy(alpha = 0.25f))
            // 오류는 머리줄 바로 아래에 고정한다 — 글이 길면 본문 아래 줄은 화면 밖으로 밀린다.
            message?.let {
                Text(
                    it,
                    style = IOS.footnote,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().background(IOS.systemRed).padding(horizontal = 16.dp, vertical = 9.dp),
                )
            }
            when (phase) {
                ComposePhase.LOADING -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }

                ComposePhase.FAILED -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ReviewLoadFailure(onRetry = { loadRevision += 1 })
                    }
                }

                ComposePhase.READY -> {
                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(top = 22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StarPicker(rating, enabled = !working) { rating = it }
                            Text(
                                if (rating == 0) tr("별을 눌러 점수를 주세요") else tr("%d점").format(rating),
                                style = IOS.footnote,
                                color = IOS.secondaryLabel,
                            )
                        }
                        ReviewBodyField(body, enabled = !working, onChange = { body = it })
                        ReviewPhotoStrip(draft, locked = working, onAdd = { picker.launch("image/*") })
                        if (existing != null) {
                            Row(
                                Modifier.padding(top = 4.dp).clickable(enabled = !working) { deleting = true }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                TrashIcon(if (working) IOS.tertiaryLabel else IOS.systemRed, Modifier.size(16.dp))
                                Text(tr("리뷰 지우기"), style = IOS.subheadline, color = if (working) IOS.tertiaryLabel else IOS.systemRed)
                            }
                        }
                    }
                }
            }
        }
    }
    if (discard) {
        IOSAlert(
            tr("쓰던 리뷰를 버릴까요?"),
            null,
            listOf(
                IOSAction(tr("계속 쓰기"), IOSRole.CANCEL) {},
                IOSAction(tr("버리기"), IOSRole.DESTRUCTIVE) { onClose() },
            ),
            onDismiss = { discard = false },
        )
    }
    if (deleting) {
        IOSAlert(
            tr("리뷰를 지울까요?"),
            tr("지우면 되돌릴 수 없어요."),
            listOf(
                IOSAction(tr("취소"), IOSRole.CANCEL) {},
                IOSAction(tr("지우기"), IOSRole.DESTRUCTIVE) { remove() },
            ),
            onDismiss = { deleting = false },
        )
    }
}

/** 글 입력 카드 + 글자 수 — 높이에 상한을 둔다(글만큼 계속 커지면 글자 수와 지우기 단추가 화면 밖으로 밀린다). */
@Composable
private fun ReviewBodyField(
    body: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    val count = ReviewRules.bodyLength(body)
    val over = count > ReviewRules.BODY_LIMIT
    val label = tr("리뷰 글")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(IOS.systemGray6)
                .padding(8.dp)
                .heightIn(min = 150.dp, max = 260.dp),
        ) {
            if (body.isEmpty()) {
                Text(
                    tr("어땠는지 들려주세요 (선택)"),
                    style = IOS.body,
                    color = IOS.tertiaryLabel,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 8.dp),
                )
            }
            BasicTextField(
                value = body,
                onValueChange = onChange,
                enabled = enabled,
                textStyle = IOS.body.copy(color = IOS.label),
                cursorBrush = SolidColor(IOS.accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 8.dp).semantics { contentDescription = label },
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (over) Text(tr("글이 너무 길어요"), style = IOS.footnote, color = IOS.systemRed)
            Spacer(Modifier.weight(1f))
            Text(
                "$count/${ReviewRules.BODY_LIMIT}",
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                color = if (over) IOS.systemRed else IOS.tertiaryLabel,
            )
        }
    }
}
