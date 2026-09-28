package com.mz2az.scenetrip.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 앱을 열면 처음 보이는 화면. iOS `Onboarding/SplashView.swift`를 옮긴 것이다.
 *
 * `SceneTripApp.init` 상당(지도 SDK 인증·서버 주소)이 진행되는 동안 흰 화면 대신
 * 보이는 것이지, 없던 기다림을 새로 만드는 게 아니다. 1900ms 후 [onDone] 을 부른다 —
 * 핀이 떨어져 튕기는 데 약 1.08초, 워드마크가 다 올라오는 데 1.22초가 들고, 그 뒤로
 * 0.7초쯤 문구를 읽을 새를 남긴다.
 */
@Composable
fun SplashView(onDone: () -> Unit) {
    val easeOut = remember { CubicBezierEasing(0f, 0f, 0.58f, 1f) }

    // 착지("landed")·글자 노출("lettering")
    val landed = remember { Animatable(0f) }
    val lettering = remember { Animatable(0f) }

    // 핀 낙하 — 위에서 떨어져 한 번 눌렸다 편다.
    val shown = remember { Animatable(0f) }
    val fallOffsetDp = remember { Animatable(-340f) }
    val squash = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        launch { shown.animateTo(1f, tween(180, easing = easeOut)) }
        launch {
            fallOffsetDp.animateTo(0f, spring(dampingRatio = 0.52f, stiffness = 146f))
        }
        launch {
            delay(430)
            squash.animateTo(1f, tween(90, easing = easeOut))
            delay(95)
            squash.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 342f))
        }
        launch {
            delay(520)
            landed.animateTo(1f, tween(420, easing = easeOut))
        }
        launch {
            delay(720)
            lettering.animateTo(1f, tween(500, easing = easeOut))
        }
        delay(1900)
        onDone()
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(IOS.pinLight, IOS.pinDeep, Color(0xFF5B4BC4)),
                    ),
                ),
    ) {
        RoadTracery(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))

            Box(contentAlignment = Alignment.BottomCenter) {
                // 착지 그림자 — 핀과 따로 움직여야 "바닥에 꽂혔다"로 보인다.
                Canvas(
                    modifier =
                        Modifier
                            .size(width = 108.dp, height = 22.dp)
                            .graphicsLayer {
                                scaleX = 0.2f + landed.value * 0.8f
                                alpha = landed.value * 0.16f
                                // 프레임 바닥이 아니라 핀 끝 밑에 깔아야 한다.
                                translationY = (11.dp - pinoTipInset(216.dp)).toPx()
                            },
                ) {
                    drawOval(color = Color(0xFF291F61))
                }

                Box(contentAlignment = Alignment.TopCenter) {
                    // 뒤에서 도는 빛무리 — 어두운 바탕에서 실루엣을 띄운다.
                    Box(
                        modifier =
                            Modifier
                                .size(190.dp)
                                .graphicsLayer { translationY = 4.dp.toPx() }
                                .blur(26.dp, BlurredEdgeTreatment.Unbounded)
                                .background(Color.White.copy(alpha = 0.28f), CircleShape),
                    )

                    PinoMascot(
                        pose = PinoPose.PLAIN,
                        width = 216.dp,
                        modifier =
                            Modifier.graphicsLayer {
                                // 바닥을 기준으로 눌러야 한다 — 가운데 기준이면 꼬리 끝이 땅을 파고든다.
                                transformOrigin = TransformOrigin(0.5f, 1f)
                                scaleX = 1 + squash.value * 0.12f
                                scaleY = 1 - squash.value * 0.12f
                                translationY = fallOffsetDp.value.dp.toPx()
                                alpha = shown.value
                            },
                    )
                }
            }

            Spacer(Modifier.height(30.dp))

            // 글자만 늦게 올라온다 — 마스코트까지 넣으면 낙하가 시작하기도 전에
            // 통째로 투명해져서 아무도 낙하를 못 본다(iOS 실측).
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier.graphicsLayer {
                        alpha = lettering.value
                        translationY = (14f * (1 - lettering.value)).dp.toPx()
                    },
            ) {
                BasicText(
                    text = "SceneTrip",
                    style =
                        TextStyle(
                            color = Color.White,
                            fontSize = 44.sp,
                            // 라틴 워드마크라 iOS `.heavy` 가 그대로 두껍다 — 「한글 heavy ≈ Bold」 규칙은 여기 해당 없다.
                            fontWeight = FontWeight.Black,
                            letterSpacing = (-1.2).sp,
                        ),
                )
                Spacer(Modifier.height(12.dp))
                BasicText(
                    text = "Stand where the scene happened",
                    style =
                        TextStyle(
                            color = Color.White.copy(alpha = 0.82f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                )
            }

            Spacer(Modifier.weight(1f))
        }
    }
}

/** 바탕에 아주 흐리게 깔리는 길과 교차점 — "지도라고 말하지 않고 지도로 보이게" 한다. */
@Composable
private fun RoadTracery(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path =
            Path().apply {
                moveTo(-20f, h * 0.24f)
                cubicTo(w * 0.36f, h * 0.28f, w * 0.62f, h * 0.27f, w + 20f, h * 0.32f)
                moveTo(-20f, h * 0.73f)
                cubicTo(w * 0.30f, h * 0.70f, w * 0.68f, h * 0.80f, w + 20f, h * 0.77f)
                moveTo(w * 0.18f, -20f)
                cubicTo(w * 0.06f, h * 0.36f, w * 0.34f, h * 0.64f, w * 0.27f, h + 20f)
                moveTo(w * 0.76f, -20f)
                cubicTo(w * 0.64f, h * 0.34f, w * 0.92f, h * 0.66f, w * 0.84f, h + 20f)
            }
        drawPath(
            path,
            color = Color.White.copy(alpha = 0.13f),
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}
