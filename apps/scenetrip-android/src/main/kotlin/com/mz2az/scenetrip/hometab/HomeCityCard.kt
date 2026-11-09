package com.mz2az.scenetrip.hometab

import androidx.annotation.DrawableRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.R
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.PlaceCity
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.keepLastWordsTogether
import com.mz2az.scenetrip.ui.rememberReduceMotion
import kotlinx.coroutines.delay

/**
 * 홈 맨 위 「어디로 여행 가세요?」 사진 카드 (MZ2AZ-547 H1–H3, 웹 `proto/ProtoScreens.tsx` `AskCard`, iOS `HomeCityCard.swift`).
 *
 * 앱에 실린 도시 사진 8장([CityCovers])이 4초마다 겹쳐 바뀌고, 위에는 질문, 아래에는 지금 사진의 「도시 · 촬영지」 와
 * 도시 알약 줄(`GET /places/cities`, 서버 순서). 알약을 누르면 그 도시로 새 코스를 짓기 시작한다([onPick]).
 * 가운데는 비워 사진이 보이게 하고 위·아래만 글자가 읽힐 만큼 어둡게 한다(웹 2026-11-09 사용자 지적).
 */
@Composable
fun HomeCityCard(
    cities: List<PlaceCity>,
    onPick: (PlaceCity) -> Unit,
) {
    val reduceMotion = rememberReduceMotion()
    val slides = CityCovers.all
    var at by remember { mutableIntStateOf(0) }
    // 움직임 줄이기면 첫 장에 멈춘다.
    LaunchedEffect(reduceMotion, slides.size) {
        if (!CitySlideRules.advances(slides.size, reduceMotion)) {
            at = 0
            return@LaunchedEffect
        }
        while (true) {
            delay(CitySlideRules.INTERVAL_MILLIS)
            at = CitySlideRules.next(at, slides.size)
        }
    }
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier =
            Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth()
                .height(250.dp)
                .shadow(elevation = 3.dp, shape = shape)
                .clip(shape)
                .background(Brush.linearGradient(listOf(IOS.pinLight, IOS.pinDeep))),
    ) {
        CitySlideImages(slides = slides, at = at, zooms = !reduceMotion)
        Box(
            modifier =
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        0.34f to Color.Black.copy(alpha = 0.12f),
                        0.52f to Color.Transparent,
                        0.66f to Color.Black.copy(alpha = 0.1f),
                        1f to Color.Black.copy(alpha = 0.6f),
                    ),
                ),
        )
        Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp, bottom = 6.dp)) {
            Text(
                tr("어디로 여행 가세요?"),
                style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 4f), blurRadius = 8f)),
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 18.dp).semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(
                // 마지막 낱말 하나만 다음 줄로 넘어가지 않게(「…드 / 려요」 실측).
                tr("도시를 고르면 그곳 드라마·영화 촬영지로 코스를 만들어 드려요").keepLastWordsTogether(),
                style = LocalTextStyle.current.copy(shadow = Shadow(Color.Black.copy(alpha = 0.4f), Offset(0f, 2f), blurRadius = 4f)),
                fontSize = 14.sp,
                color = Color.White.copy(alpha = 0.92f),
                modifier = Modifier.padding(horizontal = 18.dp),
            )
            Spacer(Modifier.weight(1f))
            // 사진과 같은 박자로 겹쳐 바뀐다 — 글만 먼저 바뀌면 잠깐 다음 도시 이름이 앞 사진 위에 뜬다.
            Crossfade(targetState = at, animationSpec = tween(CitySlideRules.CROSSFADE_MILLIS), label = "cityCaption") { index ->
                slides.getOrNull(index)?.let { cover ->
                    Text(
                        HomeCityRules.caption(cover, AppLanguage.current),
                        style =
                            LocalTextStyle.current.copy(
                                shadow = Shadow(Color.Black.copy(alpha = 0.5f), Offset(0f, 2f), blurRadius = 3f),
                            ),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.9f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            CityChips(cities, onPick)
        }
    }
}

/** 도시 알약 — 사진 위의 작은 반투명 알약(높이 32). 누르는 칸은 위아래로 늘려 44 를 지킨다(웹 2026-11-08). */
@Composable
private fun CityChips(
    cities: List<PlaceCity>,
    onPick: (PlaceCity) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 18.dp),
        modifier = Modifier.fillMaxWidth().height(44.dp),
    ) {
        items(HomeCityRules.chips(cities), key = { it.key }) { city ->
            val label = tr("%s, 촬영지 %d곳").format(city.name, city.placeCount)
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .height(44.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onPick(city) }
                        .semantics(mergeDescendants = true) { contentDescription = label },
            ) {
                val pill = RoundedCornerShape(percent = 50)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier =
                        Modifier
                            .height(32.dp)
                            .clip(pill)
                            // 흐림(blur) 대신 옅은 어둠 + 흰 18% — 사진 위에서 글자가 읽힌다(iOS 는 `ultraThinMaterial`).
                            .background(Color.Black.copy(alpha = 0.12f))
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(1.dp, Color.White.copy(alpha = 0.45f), pill)
                            .padding(horizontal = 12.dp),
                ) {
                    Text(city.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1)
                    Text("${city.placeCount}", fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
                }
            }
        }
    }
}

/**
 * 겹쳐 바뀌는 도시 사진들 — 지금 장만 보이고(1.2초에 걸쳐 겹침), 지금 장은 5초 동안 1.06배로 천천히 커진다.
 * 지금 장과 앞·뒤 장만 그려 둔다([CitySlideRules.isMounted]). P3 의 질문 화면 사진 머리도 이것을 쓴다.
 */
@Composable
fun CitySlideImages(
    slides: List<CityCover>,
    at: Int,
    zooms: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        slides.forEachIndexed { index, slide ->
            if (CitySlideRules.isMounted(index, at, slides.size)) {
                key(slide.key) {
                    val shown = index == at
                    val alpha = remember { Animatable(if (shown) 1f else 0f) }
                    val scale = remember { Animatable(1f) }
                    LaunchedEffect(shown) { alpha.animateTo(if (shown) 1f else 0f, tween(CitySlideRules.CROSSFADE_MILLIS)) }
                    LaunchedEffect(shown, zooms) {
                        scale.animateTo(
                            if (shown && zooms) CitySlideRules.ZOOM_SCALE else 1f,
                            tween(if (shown) CitySlideRules.ZOOM_MILLIS else CitySlideRules.CROSSFADE_MILLIS, easing = EaseOut),
                        )
                    }
                    cityCoverDrawable(slide.asset)?.let { res ->
                        Image(
                            painter = painterResource(res),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier =
                                Modifier.fillMaxSize().graphicsLayer {
                                    this.alpha = alpha.value
                                    scaleX = scale.value
                                    scaleY = scale.value
                                },
                        )
                    }
                }
            }
        }
    }
}

/** [CityCover.asset] → 실린 그림. 모르는 이름이면 null(바탕 그러데이션만 보인다). */
@DrawableRes
internal fun cityCoverDrawable(asset: String): Int? =
    when (asset) {
        "city_cover_seoul" -> R.drawable.city_cover_seoul
        "city_cover_busan" -> R.drawable.city_cover_busan
        "city_cover_gyeongju" -> R.drawable.city_cover_gyeongju
        "city_cover_jeju" -> R.drawable.city_cover_jeju
        "city_cover_incheon" -> R.drawable.city_cover_incheon
        "city_cover_suwon" -> R.drawable.city_cover_suwon
        "city_cover_pohang" -> R.drawable.city_cover_pohang
        "city_cover_daegu" -> R.drawable.city_cover_daegu
        else -> null
    }
