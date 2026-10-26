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
import com.mz2az.scenetrip.reviews.ReviewDraft
import com.mz2az.scenetrip.reviews.ReviewPhotoStrip
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.RouteCurveIcon
import com.mz2az.scenetrip.ui.XMarkIcon

@Composable
internal fun CommunityPhotoStrip(
    draft: ReviewDraft,
    locked: Boolean,
    onAdd: () -> Unit,
) = ReviewPhotoStrip(draft, locked, onAdd, showsCover = true, horizontalPadding = 20.dp)

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
