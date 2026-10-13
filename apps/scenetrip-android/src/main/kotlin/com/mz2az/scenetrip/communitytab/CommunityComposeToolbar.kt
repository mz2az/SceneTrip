package com.mz2az.scenetrip.communitytab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS

/** 큰 글자에서도 취소·미리보기·게시를 누를 수 있게 제목에 남은 너비를 준다. */
@Composable
internal fun CommunityComposeToolbar(
    draft: CommunityComposeState,
    onDismiss: () -> Unit,
    onPost: () -> Unit,
) {
    Row(Modifier.padding(horizontal = 12.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        ComposeToolbarAction("취소", !draft.status.working) { draft.close(onDismiss) }
        Text(
            tr("여행후기 쓰기"),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = IOS.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        ComposeToolbarAction("미리보기", draft.canPreview) { draft.previewing = true }
        ComposeToolbarAction("올리기", draft.canPost, onPost)
    }
}

@Composable
private fun ComposeToolbarAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Text(
        tr(label),
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        color = IOS.label.copy(alpha = if (enabled) 1f else 0.25f),
        modifier =
            Modifier
                .padding(3.dp)
                .shadow(1.dp, RoundedCornerShape(50))
                .background(IOS.systemBackground, RoundedCornerShape(50))
                .clickable(enabled = enabled, onClick = onClick)
                .heightIn(min = 44.dp)
                .padding(horizontal = 8.dp, vertical = 12.dp),
    )
}
