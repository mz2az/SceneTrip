package com.mz2az.scenetrip.searchtab

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.delay

/**
 * 담기 결과 알림. iOS `SearchTabView` 의 하단 캡슐 — 계약이 409 에 「이미 저장된 장소입니다」 를 띄우라고 적어 뒀고
 * 담기는 화면을 옮기지 않으므로 알림이 그 자리에 떠야 한다. 2초 뒤 스스로 걷히고 200ms 로 나타나고 사라진다.
 * 숨김 장소 거절 안내도 여기로 온다(MZ2AZ-458).
 *
 * iOS 는 `.ultraThinMaterial` 이라 뒤 내용을 흐리고 회색으로 눌러 흰 목록 위에서도 알약으로 읽힌다. Compose 에는
 * 그 재질이 없어, 흰 바탕 위 실측에 가까운 연회색(systemGray6)을 거의 불투명하게 깔고 그림자와 가는 테두리로 떼어 놓는다.
 */
@Composable
fun CartToast(
    message: String?,
    onTimeout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 사라지는 동안에도 글자가 남아야 한다 — 마지막 문구를 붙들어 둔다.
    var last by remember { mutableStateOf("") }
    if (message != null) last = message
    // 같은 문구가 다시 와도 새 알림이다 — 문구가 바뀔 때마다 시간을 새로 센다.
    LaunchedEffect(message) {
        if (message == null) return@LaunchedEffect
        delay(2000)
        onTimeout()
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        Text(
            text = last,
            style = IOS.subheadline,
            color = IOS.label,
            modifier =
                Modifier
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(IOS.systemGray6.copy(alpha = 0.97f))
                    .border(0.5.dp, IOS.separator, CircleShape)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}
