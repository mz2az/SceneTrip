package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.delay

/**
 * 담기 결과 알림. iOS `SearchTabView` 의 하단 캡슐 — 계약이 409 에 「이미 저장된 장소입니다」 를 띄우라고 적어 뒀고
 * 담기는 화면을 옮기지 않으므로 알림이 그 자리에 떠야 한다. 2초 뒤 스스로 걷힌다. 숨김 장소 거절 안내도 여기로 온다(MZ2AZ-458).
 */
@Composable
fun CartToast(
    message: String,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 같은 문구가 다시 와도 새 알림이다 — 문구가 바뀔 때마다 시간을 새로 센다.
    LaunchedEffect(message) {
        delay(2000)
        onTimeout()
    }
    Text(
        text = message,
        style = IOS.subheadline,
        color = IOS.label,
        modifier =
            modifier
                .clip(CircleShape)
                .background(IOS.systemBackground.copy(alpha = 0.92f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}
