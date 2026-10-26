package com.mz2az.scenetrip.reviews

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.ArrowClockwiseIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.PhotoAddIcon
import com.mz2az.scenetrip.ui.WarningTriangleIcon
import com.mz2az.scenetrip.ui.XMarkIcon

/**
 * 리뷰·여행후기 쓰기의 사진 줄 (iOS `ReviewPhotoStrip`) — 첫 칸 「사진 추가」, 그 뒤로 붙어 있던 사진과 새로 고른 사진.
 *
 * 칸마다 올리는 중(돌아가는 표시)·실패(다시 시도)·멈춰 기다림(멈춤 표시)이 보이고, 어느 칸이든 뺄 수 있다. 줄 아래 한 줄은 못 올린
 * 사진이 몇 번째이고 왜인지이거나, 진행(「사진 올리는 중 2/3」)이거나, 안내다.
 */
@Composable
fun ReviewPhotoStrip(
    draft: ReviewDraft,
    locked: Boolean,
    onAdd: () -> Unit,
    /** 여행후기는 첫 사진이 대표다. 리뷰는 아니다. */
    showsCover: Boolean = false,
    horizontalPadding: Dp = 0.dp,
) {
    val stalled = draft.stalled
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = horizontalPadding)) {
            item {
                val open = !locked && draft.remaining > 0
                Column(
                    Modifier
                        .size(PHOTO_TILE)
                        .alpha(if (draft.remaining > 0) 1f else 0.5f)
                        .clip(RoundedCornerShape(PHOTO_CORNER))
                        .background(IOS.systemGray6)
                        .semantics {
                            contentDescription = tr("사진 추가")
                            stateDescription = "${draft.photos.size}/${draft.limit}"
                        }.clickable(enabled = open, onClick = onAdd),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    PhotoAddIcon(IOS.accent.copy(alpha = if (draft.remaining > 0) 0.6f else 0.3f), Modifier.size(28.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("${draft.photos.size}/${draft.limit}", fontSize = 11.sp, color = IOS.accent.copy(alpha = 0.6f))
                }
            }
            itemsIndexed(draft.photos, key = { _, photo -> photo.id }) { index, photo ->
                DraftPhotoTile(photo, index, locked, stalled > 0, showsCover, { draft.retry(photo.id) }, { draft.remove(photo.id) })
            }
        }
        PhotoStripStatus(draft, stalled, Modifier.padding(horizontal = horizontalPadding))
    }
}

private val PHOTO_TILE = 92.dp
private val PHOTO_CORNER = 14.dp

/** 줄 아래 한 줄 — 실패가 먼저, 다음이 진행, 아무 일도 없으면 안내. */
@Composable
private fun PhotoStripStatus(
    draft: ReviewDraft,
    stalled: Int,
    modifier: Modifier,
) {
    val failures =
        draft.photos.mapIndexedNotNull { index, photo ->
            photo.failure?.let { index + 1 to PhotoUploadRules.text(it) }
        }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        when {
            failures.isNotEmpty() -> {
                // 몇 번째 사진이 왜 안 됐는지 — 「사진 2: 사진이 너무 커요…」
                failures.forEach { (number, text) ->
                    Text(tr("사진 %d").format(number) + ": " + text, style = IOS.footnote, color = IOS.systemRed)
                }
                if (stalled > 0) {
                    // 저장이 왜 막혔는지 — 남은 사진이 멈춰 있다.
                    Text(
                        tr("남은 사진 %d장은 기다리고 있어요. 「다시 시도」 를 누르면 이어서 올려요").format(stalled),
                        style = IOS.footnote,
                        color = IOS.secondaryLabel,
                    )
                }
            }

            ReviewPhotoRules.busy(draft.states) -> {
                val (current, total) = draft.progress
                Text(tr("사진 올리는 중 %d/%d").format(current, total), style = IOS.footnote, color = IOS.secondaryLabel)
            }

            else -> {
                Text(
                    tr("사진은 %d장까지. 촬영 위치 정보는 올리기 전에 지워요").format(draft.limit),
                    style = IOS.caption,
                    color = IOS.tertiaryLabel,
                )
            }
        }
    }
}

@Composable
private fun DraftPhotoTile(
    photo: DraftPhoto,
    index: Int,
    locked: Boolean,
    stalled: Boolean,
    showsCover: Boolean,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    val spoken =
        when (val state = photo.state) {
            ReviewPhotoState.Waiting -> if (stalled) tr("기다리는 중") else tr("올리는 중")
            ReviewPhotoState.Uploading -> tr("올리는 중")
            is ReviewPhotoState.Failed -> PhotoUploadRules.text(state.failure)
            else -> ""
        }
    Box(
        Modifier
            .size(PHOTO_TILE)
            .clip(RoundedCornerShape(PHOTO_CORNER))
            .background(IOS.systemGray6)
            .semantics {
                contentDescription = tr("사진 %d").format(index + 1)
                if (spoken.isNotEmpty()) stateDescription = spoken
            },
    ) {
        if (photo.url != null) {
            RemoteImage(photo.url, Modifier.fillMaxSize(), retryable = true)
        } else {
            photo.uri?.let { LocalPhoto(it, Modifier.fillMaxSize()) }
        }
        // 사진 위에 덮는 상태 — 올리는 중이면 어둡게 하고 돌리고, 실패면 다시 시도.
        when (val state = photo.state) {
            ReviewPhotoState.Waiting -> {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    if (stalled) {
                        // 앞 사진이 실패해 줄이 멈췄다 — 도는 표시를 보이면 올라가는 줄 안다.
                        Text("Ⅱ", color = Color.White, fontSize = 18.sp)
                    } else {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                }
            }

            ReviewPhotoState.Uploading -> {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                }
            }

            is ReviewPhotoState.Failed -> {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                    if (PhotoUploadRules.retryable(state.failure)) {
                        Column(
                            Modifier.fillMaxSize().clickable(enabled = !locked, onClick = onRetry),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            ArrowClockwiseIcon(Color.White, Modifier.size(18.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(tr("다시 시도"), color = Color.White, style = IOS.caption2.copy(fontWeight = FontWeight.SemiBold))
                        }
                    } else {
                        WarningTriangleIcon(Color.White, Modifier.size(20.dp))
                    }
                }
            }

            else -> {
                Unit
            }
        }
        if (showsCover && index == 0) {
            Text(
                tr("대표"),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(5.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(5.dp),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(44.dp)
                .semantics { contentDescription = tr("사진 빼기") }
                .clickable(enabled = !locked, onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
                XMarkIcon(Color.White, Modifier.size(10.dp))
            }
        }
    }
}
