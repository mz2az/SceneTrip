package com.mz2az.scenetrip.reviews

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.onboarding.PinoMascot
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.SheetDetent
import kotlinx.coroutines.launch

private const val LARGE_NICKNAME_FONT_SCALE = 1.3f
private const val PREVIEW_MAX_SP = 20f
private const val PREVIEW_MIN_SP = 13f

// iOS grouped: 라이트의 회색 바탕/흰 카드, 다크의 검은 바탕/회색 카드.
internal val nicknameCardBackground get() = if (IOS.isDark) IOS.systemGray6 else IOS.systemBackground
private val nicknameGroupedBackground get() = if (IOS.isDark) IOS.systemBackground else IOS.systemGray6

@Composable
fun NicknameView(
    onClose: () -> Unit,
    firstTime: Boolean = false,
    editor: NicknameEditor? = null,
) {
    val state =
        editor ?: remember(AuthStore.me?.id) {
            NicknameEditor(AuthStore.me?.nickname.orEmpty(), firstTime, { AuthStore.epoch }, AuthStore::setNickname)
        }
    val scope = rememberCoroutineScope()
    val view by state.updates.collectAsState()
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val large = LocalDensity.current.fontScale >= LARGE_NICKNAME_FONT_SCALE || focused
    val save = {
        if (state.canSave) {
            val requestEpoch = state.epochSnapshot
            focusManager.clearFocus()
            scope.launch { state.submit(requestEpoch, onClose) }
        }
        Unit
    }
    IOSSheet(
        detents = if (large) listOf(SheetDetent.LARGE) else listOf(SheetDetent.MEDIUM, SheetDetent.LARGE),
        fixedHeight = if (large) null else 480.dp,
        onDismiss = { if (!view.busy) onClose() },
        interactiveDismissEnabled = !view.busy,
    ) {
        Column(Modifier.fillMaxSize().background(nicknameGroupedBackground).imePadding()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                NicknameHeader(firstTime, view.busy, onClose)
                NicknamePreview(ReviewRules.nickname(view.text))
                NicknameInput(state, view, onFocused = { focused = it }, onSave = save)
            }
            NicknameActions(state, view, firstTime, save, onClose)
        }
    }
}

@Composable
private fun NicknameHeader(
    firstTime: Boolean,
    busy: Boolean,
    onClose: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(if (firstTime) "닉네임을 정해 주세요" else "닉네임 바꾸기"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IOS.label)
            Text(tr("리뷰와 후기에 이 이름으로 보여요. 로그인 계정의 이름은 다른 사람에게 보이지 않습니다."), style = IOS.subheadline, color = IOS.secondaryLabel)
        }
        IconButton(onClick = onClose, enabled = !busy, modifier = Modifier.size(44.dp).background(nicknameCardBackground, CircleShape)) {
            Icon(Icons.Filled.Close, contentDescription = tr("닫기"), tint = IOS.label)
        }
    }
}

@Composable
private fun NicknamePreview(name: String) {
    Row(
        Modifier.fillMaxWidth().background(nicknameCardBackground, RoundedCornerShape(20.dp)).padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PinoMascot(width = 66.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tr("작성자 이름 미리보기"), style = IOS.caption, color = IOS.secondaryLabel)
            NicknamePreviewName(name.ifEmpty { tr("여행자") })
        }
    }
}

@Composable
private fun NicknamePreviewName(name: String) {
    val scale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        var fontSize by remember(name, maxWidth, scale) { mutableStateOf(PREVIEW_MAX_SP.sp) }
        Text(
            name,
            style = IOS.headline.copy(color = IOS.label, fontSize = fontSize),
            maxLines = 1,
            softWrap = false,
            onTextLayout = {
                if (it.hasVisualOverflow &&
                    fontSize.value > PREVIEW_MIN_SP
                ) {
                    fontSize = (fontSize.value - 1f).coerceAtLeast(PREVIEW_MIN_SP).sp
                }
            },
        )
    }
}

@Composable
private fun NicknameActions(
    state: NicknameEditor,
    view: NicknameEditor.State,
    firstTime: Boolean,
    onSave: () -> Unit,
    onClose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(IOS.systemBackground).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(IOS.hairline).background(IOS.separator))
        Column(Modifier.padding(horizontal = 24.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(
                onClick = onSave,
                enabled = state.canSave,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(
                            min = 52.dp,
                        ).background(IOS.homePurple.copy(alpha = if (state.canSave) 1f else 0.4f), CircleShape),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (view.busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    Text(tr(if (firstTime) "이 닉네임으로 시작" else "저장"), style = IOS.headline, color = Color.White)
                }
            }
            TextButton(onClick = onClose, enabled = !view.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(tr(if (firstTime) "건너뛰기" else "취소"), style = IOS.subheadline, color = IOS.secondaryLabel)
            }
        }
    }
}

@Composable
fun NicknameSheetHost() {
    if (AuthStore.showingNickname &&
        !AuthStore.showingSignIn
    ) {
        NicknameView(onClose = { AuthStore.finishNicknamePrompt() }, firstTime = true)
    }
}
