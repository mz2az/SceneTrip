package com.mz2az.scenetrip.reviews

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Review
import com.mz2az.scenetrip.sceneapi.client.model.ReviewInput
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSAlert
import com.mz2az.scenetrip.ui.IOSRole
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.SheetDetent
import kotlinx.coroutines.launch

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
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var loadRevision by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var discard by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    val account = remember { AuthStore.epoch }
    val changed =
        (existing?.rating ?: 0) != rating || existing?.body != ReviewRules.normalizedBody(body) ||
            existing?.photos.orEmpty().map { it.key } != draft.keys ||
            draft.photos.any { it.key == null }

    fun close() {
        if (!busy) {
            if (changed && !loading && !loadFailed) discard = true else onClose()
        }
    }

    LaunchedEffect(subject, loadRevision) {
        loading = true
        loadFailed = false
        error = null
        apiResult { subject.mine() }.fold(
            onSuccess = { value ->
                if (account != AuthStore.epoch) return@fold
                existing = value
                rating = value?.rating ?: 0
                body = value?.body.orEmpty()
                draft.load(value?.photos.orEmpty())
            },
            onFailure = {
                loadFailed = true
                error = NetworkFailure.of(it).message
            },
        )
        loading = false
    }
    LaunchedEffect(AuthStore.epoch) { if (AuthStore.epoch != account) onClose() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { draft.add(it) }
    val canSave = changed && !loading && !loadFailed && !busy && ReviewRules.canSave(rating, body, draft.ready, draft.photos.size)

    IOSSheet(detents = listOf(SheetDetent.LARGE), onDismiss = { close() }) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            IOSSheetToolbar(
                title = tr(if (existing == null) "리뷰 쓰기" else "리뷰 고치기"),
                leading = tr("취소"),
                onLeading = { close() },
                trailing = tr("저장"),
                trailingEnabled = canSave,
                onTrailing = {
                    if (canSave) {
                        busy = true
                        error = null
                        val input = ReviewInput(rating = rating, body = ReviewRules.normalizedBody(body), photoKeys = draft.keys)
                        scope.launch {
                            val result = apiResult { subject.save(input) }
                            if (AuthStore.epoch == account) {
                                val landed =
                                    result.isSuccess ||
                                        (
                                            result.exceptionOrNull()?.let { NetworkFailure.of(it).code } == "REVIEW_PHOTO_INVALID" &&
                                                apiResult { subject.mine() }.getOrNull()?.let {
                                                    it.rating == input.rating && it.body == input.body &&
                                                        ReviewRules.samePhotoFiles(it.photos.map { photo -> photo.key }, input.photoKeys)
                                                } ==
                                                true
                                        )
                                if (landed) {
                                    onChanged()
                                    onClose()
                                } else {
                                    val problem = NetworkFailure.of(result.exceptionOrNull()!!)
                                    error = problem.message
                                    if (problem.code == "REVIEW_PHOTO_INVALID") draft.restoreInvalidPhotos()
                                }
                            }
                            busy = false
                        }
                    }
                },
            )
            if (loading || busy) CircularProgressIndicator(Modifier.padding(16.dp))
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(subject.name, style = IOS.headline, color = IOS.label)
                Row {
                    (1..5).forEach { star ->
                        Text(
                            if (star <=
                                rating
                            ) {
                                "★"
                            } else {
                                "☆"
                            },
                            style = IOS.headline,
                            color = IOS.systemOrange,
                            modifier =
                                Modifier
                                    .clickable(
                                        enabled =
                                            !busy && !loading && !loadFailed,
                                    ) { rating = star }
                                    .padding(8.dp),
                        )
                    }
                }
                OutlinedTextField(body, {
                    body = it
                }, Modifier.fillMaxWidth(), enabled = !busy && !loading && !loadFailed, label = { Text(tr("리뷰 내용 (선택)")) }, minLines = 4)
                Text("${ReviewRules.normalizedBody(body)?.length ?: 0} / ${ReviewRules.BODY_LIMIT}", color = IOS.secondaryLabel)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    draft.photos.forEachIndexed { index, photo ->
                        Column(Modifier.width(96.dp)) {
                            if (photo.url != null) {
                                RemoteImage(photo.url, Modifier.size(96.dp), retryable = true)
                            } else {
                                photo.uri?.let { LocalPhoto(it, Modifier.size(96.dp)) }
                            }
                            Text(tr("사진 %d").format(index + 1), color = IOS.label)
                            if (photo.uploading) Text(tr("올리는 중"), color = IOS.secondaryLabel)
                            photo.problem?.let { Text(it, style = IOS.caption2, color = IOS.systemOrange) }
                            if (photo.problem != null &&
                                photo.uri != null
                            ) {
                                Text(
                                    tr("다시 시도"),
                                    color = IOS.accent,
                                    modifier = Modifier.clickable(enabled = !busy) { draft.retry(photo.id) }.padding(vertical = 8.dp),
                                )
                            }
                            Text(
                                tr("빼기"),
                                color = IOS.systemRed,
                                modifier = Modifier.clickable(enabled = !busy) { draft.remove(photo.id) }.padding(vertical = 8.dp),
                            )
                        }
                    }
                }
                if (draft.remaining >
                    0
                ) {
                    Text(
                        tr("사진 추가"),
                        color = IOS.accent,
                        modifier =
                            Modifier
                                .clickable(enabled = !busy && !loading && !loadFailed) {
                                    picker.launch("image/*")
                                }.padding(12.dp),
                    )
                }
                Text(tr("사진은 최대 10장 · 촬영 위치 정보는 제거됩니다"), style = IOS.caption, color = IOS.secondaryLabel)
                error?.let { Text(it, color = IOS.systemOrange) }
                if (loadFailed) Text(tr("다시 시도"), color = IOS.accent, modifier = Modifier.clickable { loadRevision += 1 }.padding(12.dp))
                if (existing !=
                    null
                ) {
                    Text(
                        tr("리뷰 지우기"),
                        color = IOS.systemRed,
                        modifier = Modifier.clickable(enabled = !busy) { deleting = true }.padding(12.dp),
                    )
                }
            }
        }
    }
    if (discard) {
        IOSAlert(
            tr("쓰던 내용을 버릴까요?"),
            null,
            listOf(
                IOSAction(tr("계속 쓰기"), IOSRole.CANCEL) {
                },
                IOSAction(tr("버리기"), IOSRole.DESTRUCTIVE) { onClose() },
            ),
            onDismiss = {
                discard =
                    false
            },
        )
    }
    if (deleting) {
        IOSAlert(
            tr("리뷰를 지울까요?"),
            tr("지우면 되돌릴 수 없어요"),
            listOf(
                IOSAction(tr("취소"), IOSRole.CANCEL) {},
                IOSAction(tr("지우기"), IOSRole.DESTRUCTIVE) {
                    busy = true
                    scope.launch {
                        apiResult { subject.delete() }.fold(onSuccess = {
                            if (AuthStore.epoch ==
                                account
                            ) {
                                onChanged()
                                onClose()
                            }
                        }, onFailure = { error = NetworkFailure.of(it).message })
                        busy = false
                    }
                },
            ),
            onDismiss = { deleting = false },
        )
    }
}
