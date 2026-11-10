package com.mz2az.scenetrip.newcourse

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.ui.ArrowClockwiseIcon
import com.mz2az.scenetrip.ui.PhotosIcon
import com.mz2az.scenetrip.ui.rememberReduceMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

// 추천 코스 카드의 사진 칸 — 누르면 세로 축으로 돌아 뒷면에 그 작품의 장면 그림 (MZ2AZ-547 §6, 계획 motion-works-drawer-parity.md).
// 고르기·숫자는 [SceneFlipRules], 여기는 장소 상세를 받아 기억하는 것과 그리기. iOS `NewCourse/SceneFlipPhoto.swift`.

/** 카드 뒷면의 장면 그림 — 장소마다 `GET /places/{placeId}` 를 한 번 받아 기억한다. 동시에 4개까지. iOS `SceneFlipStore`. */
object SceneFlipStore {
    /** 장소 번호 → 뒷면 그림. 받았는데 그림이 없으면 값이 null, 아직 안 받았으면 키가 없다. */
    private val images = mutableStateMapOf<Long, String?>()
    private val asked = mutableSetOf<Long>()
    private val waiting = ArrayDeque<PlaceSummary>()
    private var running = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val api by lazy { PlacesApi(API_BASE) }

    /** 사용자가 한 번이라도 뒤집었나 — 그 뒤로는 처음 한 번 살짝 돌기를 하지 않는다(앱에 기억). 처음 읽을 때 기기에서 꺼낸다. */
    private var flipped by mutableStateOf<Boolean?>(null)

    fun hasFlipped(context: Context): Boolean = flipped ?: prefs(context).getBoolean(FLIPPED_KEY, false).also { flipped = it }

    fun markFlipped(context: Context) {
        if (flipped == true) return
        flipped = true
        prefs(context).edit().putBoolean(FLIPPED_KEY, true).apply()
    }

    /** 카드가 보였다 — 처음이면 받을 차례에 넣는다. */
    fun load(place: PlaceSummary) {
        if (!asked.add(place.id)) return
        waiting.addLast(place)
        pump()
    }

    /** 두 면 — 장소 사진과 받아 둔 장면 그림([SceneFlipRules.faces]). */
    fun faces(place: PlaceSummary): SceneFlipFaces = SceneFlipRules.faces(place.imageUrl?.toString(), images[place.id])

    private fun pump() {
        while (running < SceneFlipRules.MAX_CONCURRENT_DETAIL_FETCHES && waiting.isNotEmpty()) {
            val place = waiting.removeFirst()
            running += 1
            scope.launch {
                val detail = apiResult { api.getPlace(place.id) }.getOrNull()
                images[place.id] =
                    detail?.let { SceneFlipRules.sceneImageUrl(it, place.contents?.firstOrNull()?.contentId)?.toString() }
                running -= 1
                pump()
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences("scenetrip", Context.MODE_PRIVATE)

    // InstallIdentity 와 같은 "scenetrip." 접두를 쓴다.
    private const val FLIPPED_KEY = "scenetrip.sceneFlipSeen"
}

/**
 * 사진 칸 — 모든 카드가 뒤집힌다. 앞면은 장소 사진(없으면 장면 그림), 뒷면은 남은 쪽이나 「없음」 면(§6 표, [SceneFlipRules.faces]).
 * 왼쪽 아래 알약은 앞면에서는 「장면 보기」(앞면이 장면 그림이면 「「작품」 속 장면」), 뒷면에서는 늘 앞면으로 돌아가는 단추다.
 * [peek] 이면 화면이 처음 뜰 때 한 번 살짝 돌았다 돌아온다([SceneFlipRules.peekIndex]).
 */
@Composable
fun SceneFlipPhoto(
    place: PlaceSummary,
    peek: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val reduceMotion = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    var flipped by remember { mutableStateOf(false) }
    val angle = remember { Animatable(0f) }
    val fade by animateFloatAsState(
        targetValue = if (flipped) 1f else 0f,
        animationSpec = tween(SceneFlipRules.REDUCED_MOTION_FADE_MILLIS.toInt()),
        label = "sceneFlipFade",
    )
    val faces = SceneFlipStore.faces(place)
    val work =
        place.contents
            ?.firstOrNull()
            ?.title
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    LaunchedEffect(place.id) { SceneFlipStore.load(place) }

    // 처음 한 번 살짝(25°) 돌았다 돌아온다 — 뒤집힌다는 것을 알려 준다.
    LaunchedEffect(peek) {
        if (!peek || reduceMotion) return@LaunchedEffect
        delay(SceneFlipRules.PEEK_DELAY_MILLIS)
        if (flipped) return@LaunchedEffect
        angle.animateTo(SceneFlipRules.PEEK_DEGREES, tween(300))
        angle.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow))
    }

    // 90° 를 넘으면 뒷면이 보인다. 동작 줄이기면 돌지 않고 서서히 바뀐다.
    val showingBack = if (reduceMotion) flipped else abs(angle.value % 360f) > 90f

    fun toggle() {
        SceneFlipStore.markFlipped(context)
        flipped = !flipped
        if (!reduceMotion) {
            scope.launch {
                angle.animateTo(
                    if (flipped) 180f else 0f,
                    tween(SceneFlipRules.FLIP_MILLIS.toInt(), easing = FastOutSlowInEasing),
                )
            }
        }
    }

    val label = if (showingBack) tr("장소 사진 보기") else tr("장면 그림 보기")
    Box(
        modifier =
            modifier
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { toggle() }
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    onClick { toggle().let { true } }
                },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                rotationY = if (reduceMotion) 0f else angle.value
                cameraDistance = 12f * density
            },
        ) {
            val backAlpha =
                if (reduceMotion) {
                    fade
                } else if (showingBack) {
                    1f
                } else {
                    0f
                }
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - backAlpha }) {
                val front = faces.front
                if (front != null) {
                    NewCoursePhoto(front, Modifier.fillMaxSize(), edge = 720)
                } else {
                    // 그림이 하나도 없으면 빈 칸 대신 「장소 사진이 아직 없어요」(iOS 2026-11-10 사용자 지적).
                    MissingFace(tr("장소 사진이 아직 없어요"))
                }
            }
            // 뒷면은 미리 뒤집어 둔다 — 돌고 나면 바로 읽힌다.
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    alpha = backAlpha
                    rotationY = if (reduceMotion) 0f else 180f
                },
            ) {
                when (val back = faces.back) {
                    is SceneFlipBack.Scene -> NewCoursePhoto(back.url, Modifier.fillMaxSize(), edge = 720)
                    SceneFlipBack.NoPhoto -> MissingFace(tr("장소 사진이 아직 없어요"))
                    SceneFlipBack.NoScene -> MissingFace(tr("장면 그림이 아직 없어요"))
                }
            }
        }
        // 알약은 돌지 않고 늘 왼쪽 아래에 둔다 — 면이 바뀌면 글만 바뀐다.
        val pill: Pair<String, Boolean> =
            when {
                showingBack -> (if (faces.frontIsScene) tr("장면 보기") else tr("장소 사진 보기")) to false
                faces.frontIsScene -> (work?.let { tr("「%s」 속 장면").format(it) } ?: tr("장면 그림")) to true
                else -> tr("장면 보기") to false
            }
        FlipPill(pill.first, pill.second, Modifier.align(Alignment.BottomStart))
    }
}

/** 「없음」 면 — 보라 바탕에 가운데 글. */
@Composable
private fun MissingFace(text: String) {
    Box(Modifier.fillMaxSize().background(NewCourseStyle.brandFill), contentAlignment = Alignment.Center) {
        Text(
            text,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

/** 사진 왼쪽 아래 작은 알약 — 뒤집기 화살표(앞면이 장면 그림이면 사진 아이콘) + 글. */
@Composable
private fun FlipPill(
    text: String,
    isScene: Boolean,
    modifier: Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier =
            modifier
                .padding(10.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        if (isScene) {
            PhotosIcon(Color.White, Modifier.size(12.dp))
        } else {
            ArrowClockwiseIcon(Color.White, Modifier.size(11.dp))
        }
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
