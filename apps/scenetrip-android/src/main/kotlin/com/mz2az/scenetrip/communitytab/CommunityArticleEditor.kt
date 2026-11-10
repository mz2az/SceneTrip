package com.mz2az.scenetrip.communitytab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.mz2az.scenetrip.data.CommunityArticle
import com.mz2az.scenetrip.data.CommunityDraft
import com.mz2az.scenetrip.data.CommunityDraftCaret
import com.mz2az.scenetrip.data.CommunityDraftItem
import com.mz2az.scenetrip.data.CommunityPhotoPlacement
import com.mz2az.scenetrip.data.CommunityRules
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.reviews.DraftPhoto
import com.mz2az.scenetrip.reviews.LocalPhoto
import com.mz2az.scenetrip.reviews.PhotoUploadRules
import com.mz2az.scenetrip.reviews.ReviewPhotoState
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.ArrowClockwiseIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.PhotosIcon
import com.mz2az.scenetrip.ui.WarningTriangleIcon
import com.mz2az.scenetrip.ui.XMarkIcon
import java.util.UUID

/** 사진을 꾹 누르고 있어야 옮기기가 시작되는 시간(웹·iOS 0.3초). 시스템 길게 누르기(0.4–0.5초)보다 짧아 직접 잰다. */
private const val REORDER_PRESS_MILLIS = 300L

/**
 * 블로그형 본문 편집 — 글 조각과 사진을 쓴 순서대로 (MZ2AZ-547 K11, iOS `CommunityArticleEditor`, 웹 `community/ArticleEditor.tsx`).
 *
 * 「사진 넣기」 는 마지막으로 글을 쓰던 자리(커서)에서 그 글 조각을 둘로 나누고 사이에 사진을 넣는다. 글을 쓰지 않았으면 맨 끝에.
 * 사진은 0.3초 꾹 눌러 위아래로 옮기고, 설명을 달고, ✕ 로 뺀다. 첫 사진이 목록의 대표 사진이다.
 * 저장 형식은 그대로다 — 이 목록에서 본문·사진 위치를 계산해 `bodyBlocks` 를 만든다([CommunityDraft]).
 */
@Composable
internal fun CommunityArticleEditor(
    draft: CommunityComposeState,
    /** 사진 고르기를 연다 — 비로그인이면 false 를 돌려 막는다(사진은 올릴 때 로그인이 필요하다). */
    onPick: () -> Boolean,
) {
    val locked = draft.locked
    val photos = draft.photos.photos
    // 사진 칸이 바뀌면(골라 넣음·뺌) 목록을 맞춘다.
    LaunchedEffect(photos.map { it.id }) { draft.syncPhotos(photos.map { it.id }) }
    var caret by remember { mutableStateOf<CommunityDraftCaret?>(null) }
    var dragging by remember { mutableStateOf<UUID?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // 항목마다 위·높이(부모 안, 픽셀) — 놓은 자리를 셀 때만 읽는다.
    val frames = remember { HashMap<UUID, Pair<Float, Float>>() }
    val haptic = LocalHapticFeedback.current
    val cover = CommunityDraft.photoOrder(draft.items).firstOrNull()

    fun drop(
        id: UUID,
        translation: Float,
    ) {
        val items = draft.items
        val from = items.indexOfFirst { it.id == id }
        val frame = frames[id]
        if (from < 0 || frame == null) return
        val center = frame.first + frame.second / 2 + translation
        var to = from
        items.forEachIndexed { index, item ->
            if (item.id == id) return@forEachIndexed
            val other = frames[item.id] ?: return@forEachIndexed
            val otherMid = other.first + other.second / 2
            if (index > from && center > otherMid) {
                to = index
            } else if (index < from && center < otherMid && (to == from || index < to)) {
                to = index
            }
        }
        if (to == from) return
        draft.items = CommunityDraft.normalize(CommunityDraft.moveItem(items, from, to)) { UUID.randomUUID() }
        draft.alignPhotoOrder()
    }

    Column(Modifier.alpha(if (locked) 0.6f else 1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            draft.items.forEachIndexed { index, item ->
                val positioned =
                    Modifier.onGloballyPositioned { frames[item.id] = it.positionInParent().y to it.size.height.toFloat() }
                when (item) {
                    is CommunityDraftItem.Text -> {
                        DraftTextPiece(
                            item,
                            placeholder = if (index == 0) tr("어디를 다녀왔나요? 장면 속 그 자리에 선 이야기를 들려주세요") else tr("이어서 쓰기…"),
                            enabled = !locked,
                            modifier = positioned.testTag("community-draft-text-$index"),
                            onChange = { value ->
                                draft.items =
                                    draft.items.map { if (it.id == item.id) CommunityDraftItem.Text(item.id, value) else it }
                            },
                            onCaret = { caret = CommunityDraftCaret(item.id, it) },
                        )
                    }

                    is CommunityDraftItem.Photo -> {
                        val slot = photos.firstOrNull { it.id == item.id } ?: return@forEachIndexed
                        val lifted = dragging == item.id
                        DraftPhotoBlock(
                            slot,
                            cover = cover == item.id,
                            meta = draft.meta[item.id] ?: CommunityPhotoPlacement(afterParagraph = 0),
                            enabled = !locked,
                            onMeta = { draft.meta = draft.meta + (item.id to it) },
                            onRemove = { draft.photos.remove(item.id) },
                            onRetry = { draft.photos.retry(item.id) },
                            modifier =
                                positioned
                                    .zIndex(if (lifted) 2f else 0f)
                                    .graphicsLayer {
                                        translationY = if (lifted) dragOffset else 0f
                                        scaleX = if (lifted) 1.02f else 1f
                                        scaleY = if (lifted) 1.02f else 1f
                                    }.shadow(if (lifted) 10.dp else 0.dp, RoundedCornerShape(14.dp), clip = false)
                                    .pointerInput(item.id, locked) {
                                        if (locked) return@pointerInput
                                        awaitEachGesture {
                                            val down = awaitFirstDown(requireUnconsumed = false)
                                            // 0.3초 안에 떼거나 움직이면(스크롤) 옮기기가 아니다.
                                            val cancelled =
                                                withTimeoutOrNull(REORDER_PRESS_MILLIS) {
                                                    while (true) {
                                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                                        if (!change.pressed) break
                                                        if ((change.position - down.position).getDistance() >
                                                            viewConfiguration.touchSlop
                                                        ) {
                                                            break
                                                        }
                                                    }
                                                    true
                                                }
                                            if (cancelled != null) return@awaitEachGesture
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            dragging = item.id
                                            dragOffset = 0f
                                            var total = 0f
                                            while (true) {
                                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                                if (!change.pressed) break
                                                total += change.positionChange().y
                                                change.consume()
                                                dragOffset = total
                                            }
                                            drop(item.id, total)
                                            dragging = null
                                            dragOffset = 0f
                                        }
                                    }.testTag("community-draft-photo-$index"),
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val full = draft.photos.remaining <= 0
            Row(
                Modifier
                    .heightIn(min = 40.dp)
                    .alpha(if (full) 0.5f else 1f)
                    .clip(RoundedCornerShape(50))
                    .background(IOS.systemGray6)
                    .clickable(enabled = !full && !locked) {
                        if (!onPick()) return@clickable
                        val split = CommunityDraft.split(draft.items, caret) { UUID.randomUUID() }
                        draft.items = split.items
                        draft.insertAfter = split.insertAfter
                    }.padding(horizontal = 14.dp)
                    .testTag("community-insert-photo"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PhotosIcon(IOS.pinDeep, Modifier.size(16.dp))
                Text(tr("사진 넣기"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.pinDeep)
                Text("${photos.size}/${CommunityRules.PHOTO_LIMIT}", fontSize = 13.sp, color = IOS.secondaryLabel)
            }
            Text(
                tr("글을 쓰던 자리에 들어가요. 사진은 꾹 눌러 옮길 수 있어요."),
                fontSize = 12.sp,
                color = IOS.tertiaryLabel,
                modifier = Modifier.weight(1f),
            )
        }
        if (CommunityArticle.paragraphs(draft.story).size > CommunityArticle.PARAGRAPH_LIMIT) {
            Text(tr("본문은 64개 문단까지 쓸 수 있어요"), color = IOS.systemRed, style = IOS.footnote)
        }
    }
}

/** 글 조각 — 쓰는 만큼 늘어나는 테두리 없는 칸. 커서 자리(UTF-16)를 알린다. */
@Composable
private fun DraftTextPiece(
    item: CommunityDraftItem.Text,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier,
    onChange: (String) -> Unit,
    onCaret: (Int) -> Unit,
) {
    var value by remember(item.id) { mutableStateOf(TextFieldValue(item.text, TextRange(item.text.length))) }
    // 바깥에서 바뀐 글(사진 넣기로 나뉨)은 칸에 다시 넣는다 — 커서는 끝으로.
    if (value.text != item.text) value = TextFieldValue(item.text, TextRange(item.text.length))
    BasicTextField(
        value,
        { next ->
            value = next
            if (next.text != item.text) onChange(next.text)
            onCaret(next.selection.start)
        },
        enabled = enabled,
        textStyle = IOS.body.copy(color = IOS.label, lineHeight = 22.sp),
        cursorBrush = SolidColor(IOS.accent),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .semantics { contentDescription = placeholder }
                .onFocusChanged { if (it.isFocused) onCaret(value.selection.start) },
        decorationBox = { inner ->
            Box {
                if (value.text.isEmpty()) Text(placeholder, fontSize = 17.sp, color = IOS.tertiaryLabel)
                inner()
            }
        },
    )
}

/** 본문 속 사진 하나 — 4:3 크게, 왼쪽 위 「대표」, 오른쪽 위 ✕, 아래 설명. 출처·AI 표시는 접어 둔다. */
@Composable
private fun DraftPhotoBlock(
    slot: DraftPhoto,
    cover: Boolean,
    meta: CommunityPhotoPlacement,
    enabled: Boolean,
    onMeta: (CommunityPhotoPlacement) -> Unit,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    var more by remember(slot.id) { mutableStateOf(false) }
    Column(modifier.background(IOS.systemBackground), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
                .clip(RoundedCornerShape(14.dp))
                .background(IOS.systemGray5),
        ) {
            if (slot.url != null) {
                RemoteImage(slot.url, Modifier.fillMaxSize(), retryable = true)
            } else {
                slot.uri?.let { LocalPhoto(it, Modifier.fillMaxSize()) }
            }
            when (val state = slot.state) {
                ReviewPhotoState.Waiting, ReviewPhotoState.Uploading -> {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                }

                is ReviewPhotoState.Failed -> {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                        if (PhotoUploadRules.retryable(state.failure)) {
                            Column(
                                Modifier.clickable(enabled = enabled, onClick = onRetry).padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                ArrowClockwiseIcon(Color.White, Modifier.size(20.dp))
                                Spacer(Modifier.height(4.dp))
                                Text(tr("다시 시도"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                            }
                        } else {
                            WarningTriangleIcon(Color.White, Modifier.size(22.dp))
                        }
                    }
                }

                else -> {
                    Unit
                }
            }
            if (cover) {
                Text(
                    tr("대표"),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier =
                        Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(44.dp)
                    .semantics { contentDescription = tr("사진 빼기") }
                    .clickable(enabled = enabled, onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    XMarkIcon(Color.White, Modifier.size(13.dp))
                }
            }
        }
        MetaField(
            meta.caption,
            tr("사진 설명 (선택)"),
            tr("사진 설명"),
            enabled,
            centered = true,
            limit = CommunityPhotoPlacement.CAPTION_LIMIT,
        ) { onMeta(meta.copy(caption = it)) }
        if (more || meta.credit.isNotEmpty() || meta.aiGenerated) {
            Column(
                Modifier.fillMaxWidth().background(IOS.systemGray6, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MetaField(
                    meta.credit,
                    tr("출처·이용 조건 (남의 사진이면)"),
                    tr("출처"),
                    enabled,
                    centered = false,
                    limit = CommunityPhotoPlacement.CREDIT_LIMIT,
                ) {
                    onMeta(meta.copy(credit = it))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("AI 로 바꾼 사진이에요"), fontSize = 13.sp, color = IOS.secondaryLabel, modifier = Modifier.weight(1f))
                    Switch(
                        meta.aiGenerated,
                        { onMeta(meta.copy(aiGenerated = it)) },
                        enabled = enabled,
                        modifier = Modifier.semantics { contentDescription = tr("AI 로 바꾼 사진이에요") },
                        colors =
                            SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = IOS.pinDeep,
                                uncheckedTrackColor = IOS.systemGray3,
                                uncheckedThumbColor = Color.White,
                            ),
                    )
                }
            }
        } else {
            Text(
                tr("출처·AI 표시 더하기"),
                fontSize = 12.sp,
                color = IOS.tertiaryLabel,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min = 32.dp,
                        ).clickable(enabled = enabled) { more = true }
                        .padding(vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun MetaField(
    value: String,
    placeholder: String,
    label: String,
    enabled: Boolean,
    centered: Boolean,
    limit: Int,
    onChange: (String) -> Unit,
) {
    val align = if (centered) TextAlign.Center else TextAlign.Start
    BasicTextField(
        value,
        { onChange(it.take(limit)) },
        enabled = enabled,
        textStyle =
            IOS.subheadline.copy(
                color = if (centered) IOS.secondaryLabel else IOS.label,
                fontSize = if (centered) 14.sp else 13.sp,
                textAlign = align,
            ),
        cursorBrush = SolidColor(IOS.accent),
        modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics { contentDescription = label },
        decorationBox = { inner ->
            Box(contentAlignment = if (centered) Alignment.Center else Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        fontSize = if (centered) 14.sp else 13.sp,
                        color = IOS.tertiaryLabel,
                        textAlign = align,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                inner()
            }
        },
    )
}
