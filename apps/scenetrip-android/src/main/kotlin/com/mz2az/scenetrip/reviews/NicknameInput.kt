package com.mz2az.scenetrip.reviews

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS

@Composable
internal fun NicknameInput(
    state: NicknameEditor,
    view: NicknameEditor.State,
    onFocused: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("닉네임"), style = IOS.subheadline, color = IOS.label)
            Spacer(Modifier.weight(1f))
            Text(
                "${state.count}/${ReviewRules.NICKNAME_MAX_LENGTH}",
                style = IOS.caption,
                color = if (state.count > ReviewRules.NICKNAME_MAX_LENGTH) IOS.systemRed else IOS.secondaryLabel,
            )
        }
        NicknameEntry(state, view, onFocused, onSave)
        val problem = state.shownProblem?.takeIf { view.text.isNotEmpty() }
        Text(
            tr(view.error ?: problem ?: "2~16자, 한글·영문·숫자·밑줄(_)"),
            style = IOS.footnote,
            color = if (view.error != null || problem != null) IOS.systemRed else IOS.secondaryLabel,
        )
    }
}

@Composable
private fun NicknameEntry(
    state: NicknameEditor,
    view: NicknameEditor.State,
    onFocused: (Boolean) -> Unit,
    onSave: () -> Unit,
) {
    val requester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .background(nicknameCardBackground, RoundedCornerShape(14.dp))
            .border(1.5.dp, if (focused) IOS.homePurple else Color.Transparent, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NicknameTextField(
            state,
            view,
            Modifier.weight(1f).focusRequester(requester).onFocusChanged {
                focused = it.isFocused
                onFocused(it.isFocused)
            },
            onSave,
        )
        if (view.text.isNotEmpty()) {
            IconButton(onClick = {
                state.text = ""
                requester.requestFocus()
            }, enabled = !view.busy, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Filled.Close, tr("입력 지우기"), tint = IOS.secondaryLabel)
            }
        }
    }
}

@Composable
private fun NicknameTextField(
    state: NicknameEditor,
    view: NicknameEditor.State,
    modifier: Modifier,
    onSave: () -> Unit,
) {
    BasicTextField(
        value = view.text,
        onValueChange = { state.text = it },
        enabled = !view.busy,
        singleLine = true,
        textStyle = IOS.body.copy(color = IOS.label),
        cursorBrush = SolidColor(IOS.homePurple),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { onSave() }),
        modifier = modifier.semantics { contentDescription = tr("닉네임") },
        decorationBox = { field ->
            Box {
                if (view.text.isEmpty()) Text(tr("닉네임"), color = IOS.tertiaryLabel, style = IOS.body)
                field()
            }
        },
    )
}
