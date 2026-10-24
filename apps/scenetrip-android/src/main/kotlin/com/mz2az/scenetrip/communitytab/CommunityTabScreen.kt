package com.mz2az.scenetrip.communitytab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.SquarePencilIcon

/** iOS와 같은 서버 후기 목록. 기기의 옛 글은 목록 맨 위 「이 기기에만 저장된 옛 글」 구역에서 읽는다(MZ2AZ-396·423). */
@Composable
fun CommunityTabScreen() {
    val store = rememberCommunityStore()
    var composing by remember { mutableStateOf(false) }
    LaunchedEffect(AuthStore.epoch, AppLanguage.current) { store.refresh() }
    Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
        CommunityHeader {
            if (AuthStore.signedIn) composing = true else AuthStore.promptSignIn()
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(IOS.separator))
        CommunityPostList(store)
    }
    if (composing) CommunityComposeView(onDismiss = { composing = false })
}

@Composable
internal fun rememberCommunityStore(): CommunityStore {
    val context = LocalContext.current
    return remember { CommunityStore.getInstance(context) }
}

@Composable
private fun CommunityHeader(onCompose: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp)
            .padding(top = 10.dp, bottom = 8.dp),
    ) {
        Text(tr("커뮤니티"), style = IOS.headline, color = IOS.label, modifier = Modifier.align(Alignment.Center))
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .size(32.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(IOS.pinLight, IOS.pinDeep)))
                .semantics { contentDescription = tr("여행후기 쓰기") }
                .clickable(onClick = onCompose),
            contentAlignment = Alignment.Center,
        ) { SquarePencilIcon(Color.White, Modifier.size(16.dp)) }
    }
}
