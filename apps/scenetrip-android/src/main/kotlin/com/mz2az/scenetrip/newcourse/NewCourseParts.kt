package com.mz2az.scenetrip.newcourse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.hometab.HomeCityRules
import com.mz2az.scenetrip.hometab.cityCoverDrawable
import com.mz2az.scenetrip.searchtab.FilmIcon
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.LocalStepFrom
import com.mz2az.scenetrip.ui.ProtoMotion
import com.mz2az.scenetrip.ui.StepMotion
import com.mz2az.scenetrip.ui.pressClickable
import com.mz2az.scenetrip.ui.rememberReduceMotion
import com.mz2az.scenetrip.ui.stepSlide
import kotlinx.coroutines.delay

/** 새 코스 만들기 화면들이 같이 쓰는 값 (MZ2AZ-547 P3, 웹 `proto/protoTokens.ts`, iOS `NewCourseStyle`). */
object NewCourseStyle {
    /** 주 색 — 앱 시그니처 보라 단색(탭바와 같다, 흰 글씨 대비 약 6:1). */
    val purple = IOS.homePurple
    val radiusS = 8.dp
    val radiusM = 14.dp
    val radiusL = 20.dp

    /** iOS `systemGroupedBackground`. */
    val grouped = IOS.systemGray6

    /** 사진이 없을 때의 바탕 — 하늘→보라. 글자를 올리지 않는 자리에만 쓴다. */
    val brandFill: Brush get() = Brush.linearGradient(listOf(IOS.pinLight, IOS.pinDeep))
}

/**
 * 전체 화면 덮개가 뒤(탭·홈)로 손가락을 흘리지 않게 한다 — Compose 는 위 형제에 입력 모디파이어가 없으면 아래 형제에 터치를 준다.
 * 받기만 하고 소비하지 않는다(소비하면 안쪽 단추가 조금 움직인 탭을 취소한다 — `MainActivity.blockTouches` 와 같은 이유).
 */
fun Modifier.coverTouches(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent()
        }
    }

/**
 * 화면 전체를 덮는 새 코스 화면의 틀 — 위 줄(‹ 뒤로 · 단계 점), 가운데 내용(스크롤), 아래 단추 (웹 `ProtoScreen`, iOS `NewCourseScreen`).
 *
 * [hero] 를 주면 맨 위를 가장자리까지 사진 머리로 채우고, 위 줄은 그 위에 흰색으로 얹힌다.
 */
@Composable
fun NewCourseScreen(
    onBack: (() -> Unit)?,
    step: Pair<Int, Int>? = null,
    hero: (@Composable () -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().coverTouches().background(NewCourseStyle.grouped)) {
        if (hero == null) {
            NewCourseBar(step, onBack, onPhoto = false, modifier = Modifier.statusBarsPadding())
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                hero?.let { Box(Modifier.stepSlide()) { it() } }
                Column(
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier =
                        Modifier
                            .stepSlide()
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 20.dp, top = if (hero != null) 16.dp else 4.dp, bottom = 24.dp)
                            .then(if (footer == null) Modifier.navigationBarsPadding() else Modifier),
                    content = content,
                )
            }
            if (hero != null) {
                NewCourseBar(step, onBack, onPhoto = true, modifier = Modifier.statusBarsPadding())
            }
        }
        if (footer != null) {
            Column(modifier = Modifier.fillMaxWidth().background(NewCourseStyle.grouped)) {
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(IOS.separator))
                Column(
                    modifier =
                        Modifier
                            .stepSlide()
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 14.dp),
                    content = footer,
                )
            }
        }
    }
}

@Composable
private fun NewCourseBar(
    step: Pair<Int, Int>?,
    onBack: (() -> Unit)?,
    onPhoto: Boolean,
    modifier: Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp).heightIn(min = 44.dp),
    ) {
        if (step != null) StepDots(step.first, step.second, onPhoto)
        if (onBack != null) {
            val label = tr("뒤로")
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .align(Alignment.CenterStart)
                        .size(44.dp)
                        .pressClickable(onClick = onBack)
                        .semantics {
                            contentDescription = label
                            role = Role.Button
                        },
            ) {
                // SF `chevron.left` — 오른쪽 화살표를 뒤집어 쓴다.
                ChevronRightIcon(
                    if (onPhoto) Color.White else NewCourseStyle.purple,
                    Modifier.size(width = 12.dp, height = 20.dp).graphicsLayer { scaleX = -1f },
                    weight = 0.16f,
                )
            }
        }
    }
}

/** 질문 단계 점 — 지금 단계만 길고 진하게(20×7). 화면 읽기에는 「3단계 중 1단계」. */
@Composable
fun StepDots(
    at: Int,
    of: Int,
    onPhoto: Boolean = false,
) {
    val label = tr("%d단계 중 %d단계").format(of, at)
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier =
            Modifier.semantics(mergeDescendants = true) {
                contentDescription =
                    label
            },
    ) {
        // 단계가 넘어온 화면이면 이전 단계 모양에서 0.28초에 지금 모양으로 자란다(§5). 처음 화면이거나 동작 줄이기면 바로 지금 모양.
        val reduceMotion = rememberReduceMotion()
        val from = LocalStepFrom.current?.takeIf { !reduceMotion && it != at }
        val grow = remember { Animatable(if (from == null) 1f else 0f) }
        LaunchedEffect(at) { grow.animateTo(1f, tween(StepMotion.MILLIS, easing = ProtoMotion.EaseOutSheet)) }

        fun dotWidth(
            step: Int?,
            index: Int,
        ) = if (index == step) 20.dp else 7.dp

        fun dotColor(
            step: Int?,
            index: Int,
        ): Color =
            if (onPhoto) {
                if (step != null && index <= step) Color.White else Color.White.copy(alpha = 0.45f)
            } else {
                if (step != null && index <= step) NewCourseStyle.purple else IOS.systemGray4
            }
        for (index in 1..maxOf(of, 1)) {
            val p = grow.value
            val start = from ?: at
            val width = lerp(dotWidth(start, index), dotWidth(at, index), p)
            val color = lerp(dotColor(start, index), dotColor(at, index), p)
            Box(
                Modifier
                    .width(width)
                    .height(7.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** 화면 제목 — 26 heavy. */
@Composable
fun NewCourseTitle(text: String) {
    Text(
        text,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.ExtraBold,
        color = IOS.label,
        modifier = Modifier.semantics { heading() },
    )
}

/** 주 단추 — 보라 단색, 흰 글씨 17 bold. 막혀 있으면 흐리게. [leading] 은 글자 앞 그림(✨ 등). */
@Composable
fun NewCoursePrimaryButton(
    title: String,
    disabled: Boolean = false,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .pressClickable(enabled = !disabled, onClick = onClick)
                .fillMaxWidth()
                .alpha(if (disabled) 0.45f else 1f)
                .clip(RoundedCornerShape(NewCourseStyle.radiusM))
                .background(NewCourseStyle.purple)
                .semantics { role = Role.Button }
                .padding(vertical = 15.dp),
    ) {
        leading?.invoke()
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** 보조 단추 — 흰 바탕, 보라 테두리·글씨. */
@Composable
fun NewCourseSecondaryButton(
    title: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(NewCourseStyle.radiusM)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .pressClickable(onClick = onClick)
                .fillMaxWidth()
                .clip(shape)
                .background(IOS.systemBackground)
                .border(1.5.dp, NewCourseStyle.purple, shape)
                .semantics { role = Role.Button }
                .padding(vertical = 15.dp),
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = NewCourseStyle.purple)
    }
}

/** 사진 한 장 — 절대 http(s) 주소만 받고, 없거나 못 받으면 브랜드 바탕. */
@Composable
fun NewCoursePhoto(
    url: String?,
    modifier: Modifier = Modifier,
    edge: Int = 480,
) {
    Box(modifier = modifier.background(NewCourseStyle.brandFill)) {
        if (NewCourseText.usablePhoto(url)) {
            RemoteImage(url = url, modifier = Modifier.fillMaxSize(), edge = edge, backgroundColor = Color.Transparent)
        }
    }
}

/** 도시 사진(고정 사진이면 앱에 실린 그림, 아니면 받은 사진). 없으면 브랜드 바탕. */
@Composable
fun CityPhoto(
    photo: HomeCityRules.Photo?,
    modifier: Modifier = Modifier,
) {
    when (photo) {
        is HomeCityRules.Photo.Asset -> {
            val res = cityCoverDrawable(photo.name)
            Box(modifier = modifier.background(NewCourseStyle.brandFill)) {
                if (res != null) {
                    Image(
                        painterResource(res),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        is HomeCityRules.Photo.Remote -> {
            NewCoursePhoto(photo.url, modifier)
        }

        null -> {
            Box(modifier = modifier.background(NewCourseStyle.brandFill))
        }
    }
}

/** 묶음 하나 — 위 작은 제목, 흰 칸(모서리 12), 아래 도움말 (웹 `ProtoPlanFlows.tsx` `Group`). */
@Composable
fun NewCourseGroup(
    label: String,
    footnote: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = IOS.secondaryLabel,
            modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
        )
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(IOS.systemBackground),
            content = content,
        )
        if (footnote != null) {
            Text(footnote, fontSize = 12.sp, color = IOS.secondaryLabel, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** 묶음 안 줄 사이의 얇은 선. */
@Composable
fun Hairline() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(IOS.separator))
}

/** 사진 위의 작은 둥근 단추 — 흰 바탕 32, 누르는 자리 44. */
@Composable
fun PhotoRoundButton(
    label: String,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(44.dp)
                .pressClickable(onClick = onClick)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(32.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.92f)),
        ) {
            icon(Color(0xFF3C3C43))
        }
    }
}

/** 작품 표 — 보라 12% 바탕 알약에 film 과 작품 이름. [compact] 면 바탕 없이 작게. */
@Composable
fun WorkTag(
    title: String,
    compact: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 5.dp),
        modifier =
            if (compact) {
                Modifier
            } else {
                Modifier
                    .clip(CircleShape)
                    .background(NewCourseStyle.purple.copy(alpha = 0.12f))
                    .padding(horizontal = 9.dp, vertical = 3.dp)
            },
    ) {
        FilmIcon(NewCourseStyle.purple, Modifier.size(if (compact) 11.dp else 12.dp))
        Text(
            title,
            fontSize = 12.sp,
            fontWeight = if (compact) FontWeight.SemiBold else FontWeight.Bold,
            color = NewCourseStyle.purple,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 방금 「도시로」 만든 코스의 안내 띠 (MZ2AZ-547 R6) — 계획 화면 맨 위에 6초 떠 있다 사라진다. 누르면 바로 닫힌다.
 * 값은 [TabRouter.justCreated](만든 직후 `courseCreated` 가 켠다).
 */
@Composable
fun CreatedCourseBanner(modifier: Modifier = Modifier) {
    val created = TabRouter.justCreated
    LaunchedEffect(created) {
        if (created == null) return@LaunchedEffect
        delay(NewCourseText.BANNER_MILLIS)
        if (TabRouter.justCreated == created) TabRouter.justCreated = null
    }
    AnimatedVisibility(
        visible = created != null,
        enter = slideInVertically(tween(250)) { -it } + fadeIn(tween(250)),
        exit = slideOutVertically(tween(250)) { -it } + fadeOut(tween(250)),
        modifier = modifier,
    ) {
        // 사라지는 동안에도 글이 남아 있게 마지막 값을 쥔다.
        val shown = remember { created }
        val value = created ?: shown ?: return@AnimatedVisibility
        Text(
            NewCourseText.createdBanner(value.city, value.days),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            modifier =
                Modifier
                    .statusBarsPadding()
                    .padding(top = 64.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
                    .shadow(10.dp, RoundedCornerShape(NewCourseStyle.radiusM))
                    .clip(RoundedCornerShape(NewCourseStyle.radiusM))
                    .background(NewCourseStyle.purple)
                    .clickable { TabRouter.justCreated = null }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}
