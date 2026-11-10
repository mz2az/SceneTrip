package com.mz2az.scenetrip.hometab

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.coach.CoachRules
import com.mz2az.scenetrip.coach.CoachTour
import com.mz2az.scenetrip.coach.CoachTourEffect
import com.mz2az.scenetrip.coach.coachTarget
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.ListBulletIcon
import com.mz2az.scenetrip.ui.NorthArrowIcon
import com.mz2az.scenetrip.ui.PaperPlaneIcon
import com.mz2az.scenetrip.ui.PlusIcon
import com.mz2az.scenetrip.ui.XmarkIcon
import com.mz2az.scenetrip.ui.rememberReduceMotion
import kotlinx.coroutines.delay

/** 웹 `cubic-bezier(0.22, 1, 0.36, 1)`. */
private val FLOAT_EASING = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** 알약이 미끄러져 들어오는 시간(밀리초) — iOS `timingCurve(…, duration: 0.42)`. */
private const val ITEM_MILLIS = 420

/** 접힌 단추가 오그라드는 시간(밀리초). */
private const val COLLAPSE_MILLIS = 300

/**
 * 홈 오른쪽 아래에 떠 있는 「여행 코스 짜기」 단추 (MZ2AZ-547 H4, 웹 `proto/ProtoScreens.tsx` `TripFloatBar`, iOS `HomeTripFloat.swift`).
 *
 * 홈 맨 위의 「내 여행 이어가기」 카드를 대신한다(H5). 모습과 동작은 [HomeTripFloatRules] 가 정한다 —
 * 여행 중 코스가 있으면 단추가 곧 그 코스, 없으면 「여행 코스 짜기」. 펼치면 같은 줄에서 단추가 오그라들고
 * 오른쪽 끝부터 ✕ · ≡ · 「+ 새 코스」 · 내 코스(최대 5)가 45ms 간격으로 미끄러져 들어온다. 움직임 줄이기면 바로 바뀐다.
 *
 * @param courses 줄에 올릴 코스 — [HomeTripFloatRules.courses] 를 거친 것.
 * @param onNew 「+ 새 코스」·코스 0개의 「여행 코스 짜기」 — 「새 여행 코스」 시트(F1, `TabRouter.startNewCourse`).
 * @param initiallyOpen 확인용 뒷문(`-e homeFloatOpen true`) — 펼친 채로 뜬다.
 */
@Composable
fun HomeTripFloat(
    courses: List<CourseSummary>,
    courseList: RouteStore.CourseListState,
    onOpenCourse: (Long) -> Unit,
    onAll: () -> Unit,
    onNew: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
    coachCovered: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    // 처음 안내(MZ2AZ-547 C2) — 여행 중이 아니고 줄이 접혀 있고 홈이 가려져 있지 않을 때 「여행 코스 짜기」 를 짚는다.
    CoachTourEffect(
        CoachTour.homePlanTrip,
        active =
            CoachRules.homeActive(
                running = HomeTripFloatRules.collapsed(courses) is HomeTripFloatRules.Collapsed.Running,
                floatOpen = open,
                covered = coachCovered,
            ),
    )
    val reduceMotion = rememberReduceMotion()
    // 줄은 펼쳐 있거나 접히는 중에만 그린다 — 안 보이는 가로 목록이 밑의 홈 목록 터치를 가로채지 않게.
    var rowMounted by remember { mutableStateOf(open) }
    LaunchedEffect(open) {
        if (open) {
            rowMounted = true
        } else {
            if (!reduceMotion) delay(ITEM_MILLIS.toLong())
            rowMounted = false
        }
    }
    val collapsedProgress by animateFloatAsState(
        targetValue = if (open) 0f else 1f,
        animationSpec = tween(if (reduceMotion) 0 else COLLAPSE_MILLIS, easing = FLOAT_EASING),
        label = "floatCollapsed",
    )
    Box(modifier = modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.CenterEnd) {
        if (collapsedProgress > 0.01f) {
            Box(
                modifier =
                    Modifier
                        .padding(end = 14.dp)
                        .graphicsLayer {
                            alpha = collapsedProgress
                            val scale = 0.8f + 0.2f * collapsedProgress
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(1f, 0.5f)
                        },
            ) {
                Collapsed(courses, courseList, enabled = !open, onExpand = { open = true }, onOpenCourse = onOpenCourse, onNew = onNew)
            }
        }
        if (rowMounted) {
            val items = HomeTripFloatRules.items(courses)
            // 오른쪽 끝(단추 자리)부터 늘어선다 — `reverseLayout` 이라 0번(✕)이 맨 오른쪽이고 처음엔 오른쪽 끝이 보인다.
            LazyRow(
                reverseLayout = true,
                // 알약이 적어 줄이 남으면 오른쪽에 붙인다(`reverseLayout` 만으로는 왼쪽에 모였다 — 실측).
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(items) { index, item ->
                    val progress by animateFloatAsState(
                        targetValue = if (open) 1f else 0f,
                        animationSpec =
                            tween(
                                durationMillis = if (reduceMotion) 0 else ITEM_MILLIS,
                                delayMillis = if (reduceMotion) 0 else HomeTripFloatRules.delay(index, open),
                                easing = FLOAT_EASING,
                            ),
                        label = "floatItem",
                    )
                    val shift = with(LocalDensity.current) { 28.dp.toPx() }
                    Box(
                        modifier =
                            Modifier.graphicsLayer {
                                alpha = progress
                                translationX = (1f - progress) * shift
                                val scale = 0.92f + 0.08f * progress
                                scaleX = scale
                                scaleY = scale
                            },
                    ) {
                        FloatItem(
                            item = item,
                            enabled = open,
                            onClose = { open = false },
                            onAll = {
                                open = false
                                onAll()
                            },
                            onNew = {
                                open = false
                                onNew()
                            },
                            onOpenCourse = {
                                open = false
                                onOpenCourse(it)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Collapsed(
    courses: List<CourseSummary>,
    courseList: RouteStore.CourseListState,
    enabled: Boolean,
    onExpand: () -> Unit,
    onOpenCourse: (Long) -> Unit,
    onNew: () -> Unit,
) {
    when (val look = HomeTripFloatRules.collapsed(courses)) {
        HomeTripFloatRules.Collapsed.Plan -> {
            MainPill(
                title = tr("여행 코스 짜기"),
                running = false,
                enabled = enabled,
                label = tr("여행 코스 짜기"),
                modifier = Modifier.coachTarget("plan-trip"),
            ) {
                when (HomeTripFloatRules.planTap(courseList, courses.size)) {
                    HomeTripFloatRules.PlanTap.NEW_COURSE -> onNew()
                    HomeTripFloatRules.PlanTap.EXPAND -> onExpand()
                }
            }
        }

        is HomeTripFloatRules.Collapsed.Running -> {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RoundButton(label = tr("내 코스 펼치기"), enabled = enabled, onClick = onExpand) {
                    ListBulletIcon(IOS.homePurple, Modifier.size(15.dp))
                }
                MainPill(
                    title = look.course.title,
                    running = true,
                    enabled = enabled,
                    label = tr("여행 중: %s 열기").format(look.course.title),
                ) { onOpenCourse(look.course.id) }
            }
        }
    }
}

/** 보라 주 단추 — 알약 전체 최대 240(아이콘·여백을 뺀 185 를 글자에). */
@Composable
private fun MainPill(
    title: String,
    running: Boolean,
    enabled: Boolean,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    PressablePill(
        shape = RoundedCornerShape(percent = 50),
        fill = IOS.homePurple,
        enabled = enabled,
        label = label,
        modifier = modifier,
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.padding(start = 14.dp, end = 16.dp),
        ) {
            if (running) {
                NorthArrowIcon(Color.White, Modifier.size(14.dp))
            } else {
                PaperPlaneIcon(Color.White, Modifier.size(14.dp))
            }
            Text(
                title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 185.dp),
            )
        }
    }
}

@Composable
private fun FloatItem(
    item: HomeTripFloatRules.Item,
    enabled: Boolean,
    onClose: () -> Unit,
    onAll: () -> Unit,
    onNew: () -> Unit,
    onOpenCourse: (Long) -> Unit,
) {
    when (item) {
        HomeTripFloatRules.Item.Close -> {
            RoundButton(label = tr("접기"), enabled = enabled, onClick = onClose) {
                XmarkIcon(IOS.homePurple, Modifier.size(14.dp))
            }
        }

        HomeTripFloatRules.Item.All -> {
            RoundButton(label = tr("전체 코스 보기"), enabled = enabled, onClick = onAll) {
                ListBulletIcon(IOS.homePurple, Modifier.size(15.dp))
            }
        }

        HomeTripFloatRules.Item.NewCourse -> {
            PressablePill(
                shape = RoundedCornerShape(percent = 50),
                fill = IOS.systemBackground,
                enabled = enabled,
                label = null,
                onClick = onNew,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    PlusIcon(IOS.homePurple, Modifier.size(13.dp))
                    Text(tr("새 코스"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = IOS.homePurple, maxLines = 1)
                }
            }
        }

        is HomeTripFloatRules.Item.Course -> {
            val running = item.course.status == CourseStatus.active
            val ink = if (running) Color.White else IOS.homePurple
            PressablePill(
                shape = RoundedCornerShape(percent = 50),
                fill = if (running) IOS.homePurple else IOS.systemBackground,
                enabled = enabled,
                label = null,
                onClick = { onOpenCourse(item.course.id) },
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    if (running) NorthArrowIcon(ink, Modifier.size(13.dp)) else PaperPlaneIcon(ink, Modifier.size(13.dp), filled = false)
                    // 알약 전체 최대 220.
                    Text(
                        item.course.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 165.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RoundButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    PressablePill(shape = CircleShape, fill = IOS.systemBackground, enabled = enabled, label = label, width = 44.dp, onClick = onClick) {
        icon()
    }
}

/** 높이 44 의 떠 있는 단추 — 웹 `shadow.float`(0 6 20 / 14%) 그림자, iOS `.plain` 처럼 누르는 동안 흐리게. */
@Composable
private fun PressablePill(
    shape: Shape,
    fill: Color,
    enabled: Boolean,
    label: String?,
    width: Dp? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .height(44.dp)
                .then(if (width != null) Modifier.size(width) else Modifier)
                .graphicsLayer { alpha = if (pressed) 0.7f else 1f }
                .shadow(elevation = 8.dp, shape = shape, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(shape)
                .background(fill)
                .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
                .then(if (label != null) Modifier.semantics { contentDescription = label } else Modifier),
    ) {
        content()
    }
}
