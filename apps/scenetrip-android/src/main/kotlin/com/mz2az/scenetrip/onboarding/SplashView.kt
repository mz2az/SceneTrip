package com.mz2az.scenetrip.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.R
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.rememberReduceMotion
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * 앱을 켤 때 한 번 — 해태가 앞발로 「SceneTrip」 글자 윗변을 잡고 그 사이로 머리를 빼꼼 내민다 (MZ2AZ-549).
 * iOS `Onboarding/SplashView.swift` 와 같은 순서·시간이다(계획 docs/project/plans/splash-peek.md).
 *
 * 순서(약 1.5초): 글자 0.35초 → 앞발 둘(0.35·0.42초부터) → 머리가 글자 뒤에서 뿅(0.72초부터) → 문구(1.1초) → 0.55초 뒤 [onDone].
 * 누르면 바로 넘어가고, 동작 줄이기면 움직이지 않고 0.7초. 예전의 「핀이 떨어져 튕기는」 보라 바탕 화면을 바꿨다.
 */
@Composable
fun SplashView(onDone: () -> Unit) {
    val reduceMotion = rememberReduceMotion()
    val word = remember { Animatable(0f) }
    val leftPaw = remember { Animatable(0f) }
    val rightPaw = remember { Animatable(0f) }
    val head = remember { Animatable(0f) }
    val tagline = remember { Animatable(0f) }
    var finished by remember { mutableStateOf(false) }
    val finish = {
        if (!finished) {
            finished = true
            onDone()
        }
    }

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            listOf(word, leftPaw, rightPaw, head, tagline).forEach { it.snapTo(1f) }
            delay(700)
            finish()
            return@LaunchedEffect
        }
        val pawSpring = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
        coroutineScope {
            async { word.animateTo(1f, tween(350)) }
            async {
                delay(350)
                leftPaw.animateTo(1f, pawSpring)
            }
            async {
                delay(420)
                rightPaw.animateTo(1f, pawSpring)
            }
            async {
                delay(720)
                head.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
            }
            async {
                delay(1100)
                tagline.animateTo(1f, tween(300))
            }
        }
        delay(150)
        finish()
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.White)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish() },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.offset(y = (-14).dp)) {
            // 머리가 올라오는 칸 — 아래 가장자리가 글자 윗변이고, 머리는 이 칸 아래에서 올라와 잘려 보인다.
            Box(modifier = Modifier.size(width = 200.dp, height = STAGE.dp)) {
                Box(Modifier.matchParentSize().clipToBounds()) {
                    Image(
                        painter = painterResource(R.drawable.splash_haetae_face),
                        contentDescription = null,
                        contentScale = ContentScale.FillWidth,
                        modifier =
                            Modifier
                                .width(156.dp)
                                .align(Alignment.BottomCenter)
                                .offset(y = (10f + (1f - head.value) * (STAGE + 10f)).dp),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-3).dp),
                ) {
                    Paw(R.drawable.splash_paw_left, leftPaw.value)
                    Paw(R.drawable.splash_paw_right, rightPaw.value)
                }
            }
            Text(
                "SceneTrip",
                // 글자 줄 위쪽 여백만큼 올려, 머리가 잘리는 선을 글자 윗변에 붙인다(iOS 와 같다).
                modifier = Modifier.offset(y = (-12).dp).graphicsLayer { alpha = word.value },
                style = TextStyle(color = IOS.homePurple, fontSize = 44.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp),
            )
            Text(
                "Stand where the scene happened",
                modifier = Modifier.padding(top = 12.dp).graphicsLayer { alpha = tagline.value },
                style = TextStyle(color = Color(0xFF8E8E93), fontSize = 14.sp),
            )
        }
    }
}

/** 앞발 하나 — `shown` 0 이면 26dp 아래·0.9배·투명, 1 이면 제자리. 살짝 튕기며 올라온다. */
@Composable
private fun Paw(
    res: Int,
    shown: Float,
) {
    Image(
        painter = painterResource(res),
        contentDescription = null,
        contentScale = ContentScale.FillWidth,
        modifier =
            Modifier
                .width(44.dp)
                .graphicsLayer {
                    alpha = shown.coerceIn(0f, 1f)
                    translationY = (1f - shown) * 26.dp.toPx()
                    val scale = 0.9f + 0.1f * shown
                    scaleX = scale
                    scaleY = scale
                },
    )
}

/** 머리가 올라오는 칸의 높이(dp). */
private const val STAGE = 122f
