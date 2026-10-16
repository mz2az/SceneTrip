package com.mz2az.scenetrip.profiletab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.AIConsentGate
import com.mz2az.scenetrip.data.AIConsentStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.AIConsentCopy
import com.mz2az.scenetrip.ui.AIConsentSheetHost
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSToggle
import com.mz2az.scenetrip.ui.SparklesIcon
import com.mz2az.scenetrip.ui.rememberConsentTick

private val SYSTEM_PURPLE = Color(0xFFAF52DE)

/**
 * 마이페이지의 AI 기능 토글 (MZ2AZ-468). iOS `ProfileTab/ProfileAISettings.swift` 를 옮긴 것이다.
 *
 * 켜진 상태는 저장된 동의 버전이 현재 안내 버전과 같을 때뿐이다. **끄면 동의를 철회**하고, 다시 켤 때도
 * 같은 안내를 확인해야 한다(안내를 취소하면 켜지지 않는다). 다른 기능에는 영향이 없다.
 * 위치기반서비스 동의(MZ2AZ-472)가 들어오면 같은 저장소 클래스([com.mz2az.scenetrip.data.VersionedConsentStore])로
 * 바로 아래에 토글 하나를 더 두면 된다.
 */
@Composable
fun ProfileAISettings() {
    val context = LocalContext.current
    val store = AIConsentStore.getInstance(context)
    val gate = remember(store) { AIConsentGate(store) }
    // 동의가 바뀔 때(시트에서 허용·토글로 철회) 다시 그린다.
    val subscribe = remember(store) { store::addListener }
    rememberConsentTick(subscribe)
    val allowed = store.isAllowed
    ProfileSection(null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
        ) {
            Box(modifier = Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                SparklesIcon(SYSTEM_PURPLE, Modifier.size(18.dp))
            }
            Text(tr(AIConsentCopy.TOGGLE_KO), fontSize = 15.sp, color = IOS.label, modifier = Modifier.weight(1f), maxLines = 2)
            IOSToggle(checked = allowed, onCheckedChange = { gate.setEnabled(it) })
        }
    }
    Text(
        tr(AIConsentCopy.FOOTNOTE_KO),
        fontSize = 13.sp,
        color = IOS.secondaryLabel,
        modifier = Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 8.dp),
    )
    AIConsentSheetHost(gate)
}
