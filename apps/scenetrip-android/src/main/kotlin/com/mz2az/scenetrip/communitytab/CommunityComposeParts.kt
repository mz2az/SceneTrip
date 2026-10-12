package com.mz2az.scenetrip.communitytab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.PostCourse
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.reviews.DraftPhoto
import com.mz2az.scenetrip.reviews.LocalPhoto
import com.mz2az.scenetrip.reviews.ReviewDraft
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.RouteCurveIcon
import com.mz2az.scenetrip.ui.XMarkIcon

@Composable
internal fun CommunityPhotoStrip(
    draft: ReviewDraft,
    locked: Boolean,
    onAdd: () -> Unit,
) {
    val stalled = draft.photos.any { it.problem != null }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 20.dp)) {
            item {
                Column(
                    Modifier
                        .size(92.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(IOS.systemGray6)
                        .semantics { contentDescription = tr("사진 추가") }
                        .clickable(enabled = !locked && draft.remaining > 0, onClick = onAdd),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    com.mz2az.scenetrip.ui.PhotoAddIcon(
                        IOS.accent.copy(
                            alpha =
                                if (draft.remaining >
                                    0
                                ) {
                                    0.6f
                                } else {
                                    0.3f
                                },
                        ),
                        Modifier.size(28.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("${draft.photos.size}/${draft.limit}", fontSize = 11.sp, color = IOS.accent.copy(alpha = 0.6f))
                }
            }
            itemsIndexed(draft.photos, key = { _, photo -> photo.id }) { index, photo ->
                DraftPhotoTile(photo, index, locked, stalled, { draft.retry(photo.id) }, { draft.remove(photo.id) })
            }
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                stalled -> {
                    draft.photos.forEachIndexed { index, photo ->
                        photo.problem?.let {
                            Text(tr("사진 %d").format(index + 1) + ": " + it, style = IOS.footnote, color = IOS.systemRed)
                        }
                    }
                }

                draft.photos.any { it.key == null } -> {
                    Text(
                        tr("사진 올리는 중 %d/%d").format(draft.ready + 1, draft.photos.size),
                        style = IOS.footnote,
                        color = IOS.secondaryLabel,
                    )
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
}

@Composable
private fun DraftPhotoTile(
    photo: DraftPhoto,
    index: Int,
    locked: Boolean,
    stalled: Boolean,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Box(
        Modifier
            .size(92.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(IOS.systemGray6)
            .semantics { contentDescription = tr("사진 %d").format(index + 1) },
    ) {
        if (photo.url != null) {
            RemoteImage(photo.url, Modifier.fillMaxSize(), retryable = true)
        } else {
            photo.uri?.let { LocalPhoto(it, Modifier.fillMaxSize()) }
        }
        if (photo.key == null) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (photo.problem != null) 0.55f else 0.35f)),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    photo.problem != null -> {
                        Text(
                            tr("다시 시도"),
                            color = Color.White,
                            style = IOS.caption2,
                            modifier = Modifier.fillMaxSize().clickable(enabled = !locked, onClick = onRetry).padding(12.dp),
                        )
                    }

                    stalled && !photo.uploading -> {
                        Text("Ⅱ", color = Color.White, fontSize = 18.sp)
                    }

                    else -> {
                        CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                }
            }
        }
        if (index == 0) {
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

@Composable
fun PostCourseBadge(size: Dp = 34.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(IOS.pinDeep), contentAlignment = Alignment.Center) {
        RouteCurveIcon(Color.White, Modifier.size(size * 0.47f))
    }
}

@Composable
internal fun ComposeCourseCard(
    course: PostCourse?,
    locked: Boolean,
    onAttach: () -> Unit,
    onDetach: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(IOS.systemGray6)
            .clickable(enabled = course == null && !locked, onClick = onAttach)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PostCourseBadge()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(course?.title ?: tr("다녀온 코스 붙이기"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.label, maxLines = 1)
            Text(
                if (course != null) {
                    tr("%d일 · %d곳").format(course.days.size, course.placeCount - course.excludedPins)
                } else {
                    tr("읽는 사람이 이 코스를 보고 그대로 담아 갈 수 있어요")
                },
                fontSize = 12.sp,
                color = IOS.secondaryLabel,
            )
        }
        if (course != null) {
            Box(
                Modifier
                    .size(44.dp)
                    .semantics { contentDescription = tr("코스 떼기") }
                    .clickable(enabled = !locked, onClick = onDetach),
                contentAlignment = Alignment.Center,
            ) {
                XMarkIcon(IOS.secondaryLabel, Modifier.size(12.dp))
            }
        } else {
            ChevronRightIcon(IOS.tertiaryLabel, Modifier.size(12.dp))
        }
    }
}
