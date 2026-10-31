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
import com.mz2az.scenetrip.data.LocationConsentStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.AIConsentCopy
import com.mz2az.scenetrip.ui.AIConsentSheetHost
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSToggle
import com.mz2az.scenetrip.ui.LocationArrowIcon
import com.mz2az.scenetrip.ui.LocationConsentCopy
import com.mz2az.scenetrip.ui.LocationConsentSheetHost
import com.mz2az.scenetrip.ui.SparklesIcon
import com.mz2az.scenetrip.ui.rememberConsentTick

// iOS 26 `.purple` — 감사 스크린샷에서 (203,48,224) 로 쟀다(이전 값 AF52DE 는 iOS 18 까지의 보라).
private val SYSTEM_PURPLE = Color(0xFFCB30E0)

/**
 * 마이페이지의 AI 기능·위치기반서비스 이용 토글 (MZ2AZ-468·472). iOS `ProfileTab/ProfileAISettings.swift` 를 옮긴 것이다.
 *
 * 켜진 상태는 저장된 동의 버전이 현재 안내 버전과 같을 때뿐이다. **끄면 동의를 철회**하고, 다시 켤 때도
 * 같은 안내를 확인해야 한다(안내를 취소하면 켜지지 않는다). 두 동의는 저장 키·철회가 서로 독립이다 —
 * 위치를 끄면 이미 나가 있는 위치 요청만 끊고 AI 동의는 그대로다.
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
    ProfileSection(null, afterFooter = true) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().height(PROFILE_ROW_HEIGHT).padding(horizontal = 16.dp),
        ) {
            Box(modifier = Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                SparklesIcon(SYSTEM_PURPLE, Modifier.size(18.dp))
            }
            Text(tr(AIConsentCopy.TOGGLE_KO), fontSize = 15.sp, color = IOS.label, modifier = Modifier.weight(1f), maxLines = 2)
            IOSToggle(checked = allowed, onCheckedChange = { gate.setEnabled(it) })
        }
    }
    ProfileFooter(tr(AIConsentCopy.FOOTNOTE_KO))
    AIConsentSheetHost(gate)

    // 위치기반서비스 이용 (MZ2AZ-472) — 지도 범위 검색·주변 편의시설·가이드·길 안내가 쓰는 기기 위치. 시스템 위치 권한과 별개다.
    val locationStore = LocationConsentStore.getInstance(context)
    val locationGate = remember(locationStore) { AIConsentGate(locationStore) }
    val locationSubscribe = remember(locationStore) { locationStore::addListener }
    rememberConsentTick(locationSubscribe)
    val locationAllowed = locationStore.isAllowed
    ProfileSection(null, afterFooter = true) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().height(PROFILE_ROW_HEIGHT).padding(horizontal = 16.dp),
        ) {
            Box(modifier = Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                LocationArrowIcon(IOS.accent, filled = false, modifier = Modifier.size(18.dp))
            }
            Text(tr(LocationConsentCopy.TOGGLE_KO), fontSize = 15.sp, color = IOS.label, modifier = Modifier.weight(1f), maxLines = 2)
            IOSToggle(checked = locationAllowed, onCheckedChange = { locationGate.setEnabled(it) })
        }
    }
    ProfileFooter(tr(LocationConsentCopy.FOOTNOTE_KO))
    LocationConsentSheetHost(locationGate)
}
