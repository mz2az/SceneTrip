package com.mz2az.scenetrip.onboarding

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.R
import com.mz2az.scenetrip.ui.IOS

/**
 * 해태 — SceneTrip 마스코트, 일러스트 판. iOS `Onboarding/PinoMascot.swift`를 옮긴 것이다.
 *
 * 래스터 PNG(`res/drawable-nodpi/haetae_*`)는 관절을 못 움직인다. Lottie 를 들이는
 * 대신 통그림에 코드 모션(숨쉬기·살랑)만 얹는다 — [PinoMascot]이 직접 애니메이션한다.
 *
 * 포즈 → 그림: plain/speech → haetae_sit, magnifier → haetae_bow, sparkle → haetae_joy,
 * paw → haetae_pinhold. 소품(반짝별·말풍선)은 그림에 굽지 않고 여기서 얹는다 — 장마다
 * 자리가 달라서다.
 */
enum class PinoPose { PLAIN, MAGNIFIER, SPARKLE, PAW, SPEECH }

/** 그림의 세로/가로 비율 (해태 앉기 컷아웃 525×582). */
private const val PINO_ASPECT = 582f / 525f

/** 프레임 바닥과 발끝 사이 — 그림 아래 여백만큼이다. 스플래시의 그림자가 쓴다. */
fun pinoTipInset(width: Dp): Dp = width * 0.05f

private val easeInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

@Composable
fun PinoMascot(
    modifier: Modifier = Modifier,
    pose: PinoPose = PinoPose.PLAIN,
    width: Dp = 180.dp,
    isAlive: Boolean = true,
) {
    val imageRes =
        when (pose) {
            PinoPose.PLAIN, PinoPose.SPEECH -> R.drawable.haetae_sit
            PinoPose.MAGNIFIER -> R.drawable.haetae_bow
            PinoPose.SPARKLE -> R.drawable.haetae_joy
            PinoPose.PAW -> R.drawable.haetae_pinhold
        }

    val transition = rememberInfiniteTransition(label = "pino-alive")
    val breathScale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.02f,
        animationSpec =
            infiniteRepeatable(tween(2400, easing = easeInOut), RepeatMode.Reverse),
        label = "pino-breath",
    )
    val swayDeg by transition.animateFloat(
        initialValue = -1.4f,
        targetValue = 1.4f,
        animationSpec =
            infiniteRepeatable(tween(1300, easing = easeInOut), RepeatMode.Reverse),
        label = "pino-sway",
    )

    Box(
        modifier =
            modifier
                .size(width = width, height = width * PINO_ASPECT)
                .graphicsLayer {
                    val scale = if (isAlive) breathScale else 1f
                    scaleX = scale
                    scaleY = scale
                    rotationZ = if (isAlive) swayDeg else 0f
                    // 발끝(프레임 바닥)을 축으로 — 몸 전체가 그 점을 중심으로 숨쉬고 살랑인다.
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
    ) {
        Image(
            painter = painterResource(imageRes),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(width = width, height = width * PINO_ASPECT),
        )

        if (pose == PinoPose.SPARKLE) PinoSparkles(width)
        if (pose == PinoPose.SPEECH) PinoSpeechBubble(width)
    }
}

/** 반짝별 둘 — 「AI 가 짜 준다」의 표시. */
@Composable
private fun PinoSparkles(width: Dp) {
    val gold = Color(1f, 0.83f, 0.15f)
    PinoSparkle(arm = width * 0.07f, color = gold, center = Offset(0.94f, 0.12f), width = width)
    PinoSparkle(arm = width * 0.045f, color = gold, center = Offset(0.08f, 0.5f), width = width)
}

/** 네 갈래 별. `center` 는 마스코트 폭에 대한 비율(0..1)이다. */
@Composable
private fun PinoSparkle(
    arm: Dp,
    color: Color,
    center: Offset,
    width: Dp,
) {
    Canvas(
        modifier =
            Modifier
                .size(arm * 2)
                .offset(x = width * center.x - arm, y = width * center.y - arm),
    ) {
        val a = size.width / 2f
        val waist = a * 0.42f
        val cx = size.width / 2f
        val cy = size.height / 2f
        val path =
            Path().apply {
                moveTo(cx, cy - a)
                lineTo(cx + waist, cy - waist)
                lineTo(cx + a, cy)
                lineTo(cx + waist, cy + waist)
                lineTo(cx, cy + a)
                lineTo(cx - waist, cy + waist)
                lineTo(cx - a, cy)
                lineTo(cx - waist, cy - waist)
                close()
            }
        drawPath(path, color = color)
    }
}

/** 말풍선 — 「거든다」의 표시. */
@Composable
private fun PinoSpeechBubble(width: Dp) {
    Box(
        modifier =
            Modifier
                .offset(x = width * 0.72f, y = -width * 0.04f)
                .size(width = width * 0.3f, height = width * 0.19f)
                .background(IOS.accent, RoundedCornerShape(width * 0.07f)),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(width * 0.035f)) {
            repeat(3) {
                Box(
                    modifier =
                        Modifier
                            .size(width * 0.032f)
                            .background(Color.White, CircleShape),
                )
            }
        }
    }
}
