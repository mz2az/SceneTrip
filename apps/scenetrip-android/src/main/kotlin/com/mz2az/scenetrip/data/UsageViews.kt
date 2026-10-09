package com.mz2az.scenetrip.data

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.routetab.ExternalDirections
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.delay

@Composable
fun usageNow(): Long {
    var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = SystemClock.elapsedRealtime()
        }
    }
    return now
}

@Composable
fun GuideUsageNotice() {
    val now = usageNow()
    val block = LimitLedger.guideBlock
    val message =
        when {
            block?.blocked(now) == true && (block.retryAfter ?: 0) > 3600 -> tr("오늘은 가이드와 충분히 이야기했어요. 내일 다시 만나요")
            block?.blocked(now) == true -> tr("가이드가 잠시 쉬고 있어요. 약 %d분 뒤에 다시 물어보세요").format(block.minutes(now))
            block?.lifted(now) == true -> tr("이제 다시 물어볼 수 있어요")
            block != null -> tr("가이드가 잠시 쉬고 있어요. 조금 뒤에 다시 물어보세요")
            else -> null
        }
    message?.let { Text(it, style = IOS.caption, color = IOS.secondaryLabel) }
    LimitLedger.guideQuota
        ?.takeIf {
            it.visible(now)
        }?.let { Text(tr("오늘 남은 대화 %d회").format(it.remaining), style = IOS.caption, color = IOS.secondaryLabel) }
}

@Composable
fun NavigationUsageNotice(
    start: ExternalDirections.Spot?,
    end: ExternalDirections.Spot,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    val now = usageNow()
    val block = LimitLedger.navigationBlock
    var failed by remember { mutableStateOf(false) }

    fun intent(url: String) = Intent(Intent.ACTION_VIEW, Uri.parse(url))

    fun available(url: String?) = url != null && intent(url).resolveActivity(context.packageManager) != null

    fun open(url: String?) {
        if (url == null) return
        try {
            context.startActivity(intent(url))
            failed = false
        } catch (_: ActivityNotFoundException) {
            failed = true
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("앱 안의 길찾기는 잠시 쉬고 있어요. 지도 앱으로 이어서 안내받을 수 있어요"), style = IOS.caption, color = IOS.secondaryLabel)
        if (block?.blocked(now) ==
            true
        ) {
            Text(tr("약 %d분 뒤 다시 시도할 수 있어요").format(block.minutes(now)), style = IOS.caption, color = IOS.secondaryLabel)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val kakao = ExternalDirections.kakaoApp(start, end)
            Text(
                tr("카카오맵 길찾기"),
                color = IOS.accent,
                modifier =
                    Modifier
                        .clickable {
                            open(if (available(kakao)) kakao else ExternalDirections.kakaoWeb(start, end))
                        }.padding(vertical = 8.dp),
            )
            val naver = ExternalDirections.naver(start, end)
            if (available(
                    naver,
                )
            ) {
                Text(tr("네이버 지도 길찾기"), color = IOS.accent, modifier = Modifier.clickable { open(naver) }.padding(vertical = 8.dp))
            }
        }
        if (block?.blocked(now) !=
            true
        ) {
            Text(tr("앱에서 다시 시도"), color = IOS.accent, modifier = Modifier.clickable(onClick = onRetry).padding(vertical = 8.dp))
        }
        if (failed) Text(tr("지도를 열 수 없어요. 잠시 뒤 다시 시도해 주세요"), color = IOS.systemOrange, style = IOS.caption)
    }
}
