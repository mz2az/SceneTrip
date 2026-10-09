package com.mz2az.scenetrip.reviews

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.SheetDetent
import kotlinx.coroutines.launch

@Composable
fun NicknameView(
    onClose: () -> Unit,
    firstTime: Boolean = false,
) {
    var name by remember { mutableStateOf(AuthStore.me?.nickname.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val automaticUnchanged = firstTime && name == AuthStore.me?.nickname && ReviewRules.automaticNickname(name)
    val problem = ReviewRules.nicknameProblem(name)
    IOSSheet(detents = listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = { if (!busy) onClose() }) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            IOSSheetToolbar(
                title = tr("닉네임 정하기"),
                leading = tr(if (firstTime) "건너뛰기" else "취소"),
                onLeading = { if (!busy) onClose() },
                trailing = tr("저장"),
                trailingEnabled =
                    !busy && (problem == null || automaticUnchanged),
                onTrailing = {
                    if (automaticUnchanged) {
                        onClose()
                    } else {
                        busy = true
                        scope.launch {
                            val failure = AuthStore.setNickname(ReviewRules.nickname(name))
                            busy = false
                            if (failure == null) onClose() else error = failure
                        }
                    }
                },
            )
            OutlinedTextField(name, {
                name = it
                error = null
            }, enabled = !busy, singleLine = true, modifier = Modifier.padding(16.dp))
            Text(
                tr(error ?: problem ?: "2~16자, 한글·영문·숫자·밑줄(_)"),
                color =
                    if (error != null ||
                        problem != null
                    ) {
                        IOS.systemOrange
                    } else {
                        IOS.secondaryLabel
                    },
                modifier = Modifier.padding(16.dp),
            )
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
