package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS

/**
 * 가이드 창 입력창 **바로 아래** 작은 회색 줄 (MZ2AZ-439). iOS `RouteGuideAINotice` 를 옮긴 것이다.
 * 답은 모델이 만든 것이라 틀리거나 낡을 수 있다(약관 초안 §4 「AI 가이드의 답변」).
 *
 * 창은 높이가 고정(470dp)이라 이 줄이 커지면 대화 자리를 먹는다. 가장 큰 글자 크기에서는 앞 문장만 보이고
 * 글자 크기에 상한을 건다. 화면 낭독기는 크기와 관계없이 늘 전체 문장을 읽는다.
 */
object GuideAiNotice {
    const val FULL_KO = "AI 답변은 틀릴 수 있어요. 영업시간·요금·교통은 직접 확인해 주세요."
    const val SHORT_KO = "AI 답변은 틀릴 수 있어요."

    /**
     * iOS 의 접근성 글자 크기(`accessibility1` 이상, 본문 약 1.65배)에 해당하는 Android 글자 배율.
     * 앱의 다른 큰 글자 분기(온보딩 `fontScale >= 1.6`)와 같은 값이다.
     */
    const val ACCESSIBILITY_FONT_SCALE = 1.6f

    /** iOS `.dynamicTypeSize(...accessibility1)` — 이 배율보다 더 커지지 않는다. */
    const val MAX_FONT_SCALE = 1.65f

    val copyKeys: List<String> = listOf(FULL_KO, SHORT_KO)

    /** 큰 글자에서는 앞 문장만 보인다. */
    fun usesShortSentence(fontScale: Float): Boolean = fontScale >= ACCESSIBILITY_FONT_SCALE

    /** 보이는 한국어 원문(번역 전). */
    fun visibleKorean(fontScale: Float): String = if (usesShortSentence(fontScale)) SHORT_KO else FULL_KO

    fun cappedFontScale(fontScale: Float): Float = fontScale.coerceAtMost(MAX_FONT_SCALE)
}

@Composable
fun GuideAiNoticeLine(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val shown = tr(GuideAiNotice.visibleKorean(density.fontScale))
    val full = tr(GuideAiNotice.FULL_KO)
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, GuideAiNotice.cappedFontScale(density.fontScale)),
    ) {
        Text(
            shown,
            style = IOS.caption2,
            color = IOS.secondaryLabel,
            textAlign = TextAlign.Center,
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 8.dp)
                    // 낭독기는 글자 크기와 관계없이 전체 문장을 읽는다.
                    .semantics { contentDescription = full },
        )
    }
}
