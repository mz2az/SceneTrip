package com.mz2az.scenetrip.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.ui.IOS

/**
 * 첫 실행의 언어 고르기 (MZ2AZ-343). 사용법보다 먼저 나온다 — 사용법부터 그 언어로 읽어야 한다.
 * iOS `Onboarding/LanguagePickView.swift`를 옮긴 것이다.
 *
 * 이 화면만은 **두 언어를 같이 적는다.** 아직 무엇을 읽을 수 있는지 모르기 때문이다.
 */
@Composable
fun LanguagePickView(onDone: () -> Unit) {
    val context = LocalContext.current
    val language = remember(context) { AppLanguage.getInstance(context) }

    Column(
        modifier = Modifier.fillMaxSize().background(IOS.systemBackground).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        PinoMascot(width = 150.dp)
        Text(
            "언어를 선택하세요",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = IOS.label,
            modifier = Modifier.padding(top = 28.dp),
        )
        Text(
            "Choose your language",
            fontSize = 20.sp,
            color = IOS.secondaryLabel,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(bottom = 40.dp),
        ) {
            language.choices.forEach { lang ->
                val selected = lang == language.lang
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .clip(CircleShape)
                            .background(if (selected) IOS.accent else IOS.systemGray6)
                            .clickable {
                                language.choose(lang)
                                onDone()
                            },
                ) {
                    Text(
                        AppLanguage.name(of = lang),
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selected) Color.White else IOS.label,
                    )
                }
            }
        }
    }
}
