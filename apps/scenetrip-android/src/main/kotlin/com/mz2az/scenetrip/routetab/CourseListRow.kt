package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.newcourse.NewCoursePhoto
import com.mz2az.scenetrip.newcourse.NewCourseStyle
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.ui.CheckmarkIcon
import com.mz2az.scenetrip.ui.EllipsisIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSMenuItem
import com.mz2az.scenetrip.ui.IOSMenuPopover
import com.mz2az.scenetrip.ui.TrashFillIcon

// 코스 목록(「내 여행」)의 묶음·줄 (MZ2AZ-547 P7 L2–L4, iOS `RouteTab/CourseListRow.swift`, 웹 `features/proto/ProtoCourse.tsx`).
// 판단은 `CourseListRules.kt`, 여기는 그리기만 한다.

private val THUMB = 60.dp
private val PAD = 12.dp

/** 묶음 하나 — 위 작은 제목(개수), 흰 칸(모서리 12) 안에 줄들. 줄 사이 얇은 선은 줄이 그린다(사진 오른쪽부터). */
@Composable
fun CourseListGroup(
    label: String,
    courses: List<CourseSummary>,
    row: @Composable (CourseSummary, Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 4.dp).semantics(mergeDescendants = true) { heading() },
        ) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = IOS.secondaryLabel)
            Text("${courses.size}", fontSize = 13.sp, color = IOS.tertiaryLabel)
        }
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(IOS.systemBackground)) {
            courses.forEachIndexed { index, course ->
                androidx.compose.runtime.key(course.id) { row(course, index == 0) }
            }
        }
    }
}

/**
 * 코스 한 줄 — 사진 60 · 이름 16 · 「N일차 · N박 N일 · N곳」 · 작품 최대 2, 오른쪽 「⋯」.
 *
 * 누르면 열고, **꾹(0.2초) 누르면 여러 개 고르기**가 켜진다([courseRowGestures]). 고르는 동안은 누르기가 체크가 되고 「⋯」 이 숨는다.
 * 지우기는 「⋯」 메뉴에 둔다 — 밀어서 지우기는 아무도 찾지 못했다(웹 2026-11-08 사용자 결정, iOS 2026-08-24 지적).
 */
@Composable
fun CourseListRow(
    course: CourseSummary,
    first: Boolean,
    photo: String?,
    works: List<String>,
    picking: Boolean,
    checked: Boolean,
    enabled: Boolean,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
    onOpen: () -> Unit,
    onHold: () -> Unit,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    val subtitle =
        CourseListText.subtitle(
            isRunning = course.status == CourseStatus.active,
            currentDayNo = course.currentDayNo,
            dayCount = course.dayCount,
            placeCount = course.placeCount,
        )
    val holdLabel = tr("여러 개 고르기")
    Box(modifier = Modifier.fillMaxWidth().background(if (checked) NewCourseStyle.purple.copy(alpha = 0.13f) else IOS.systemBackground)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = PAD)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier =
                    Modifier
                        .weight(1f)
                        .courseRowGestures(
                            enabled = enabled,
                            onTap = { if (picking) onToggle() else onOpen() },
                            onHold = { if (!picking) onHold() },
                        ).semantics(mergeDescendants = true) {
                            role = Role.Button
                            if (picking) selected = checked
                            onClick {
                                if (picking) onToggle() else onOpen()
                                true
                            }
                            if (!picking) {
                                customActions =
                                    listOf(
                                        androidx.compose.ui.semantics.CustomAccessibilityAction(holdLabel) {
                                            onHold()
                                            true
                                        },
                                    )
                            }
                        },
            ) {
                if (picking) CheckCircle(checked)
                NewCoursePhoto(
                    photo,
                    Modifier.padding(vertical = PAD).size(THUMB).clip(RoundedCornerShape(NewCourseStyle.radiusS)),
                    edge = 240,
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f).padding(vertical = PAD)) {
                    Text(
                        course.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = IOS.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(subtitle, fontSize = 13.sp, color = IOS.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (works.isNotEmpty()) {
                        Text(
                            works.joinToString(" · "),
                            fontSize = 12.sp,
                            color = NewCourseStyle.purple,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (!picking) MenuButton(course.title, enabled, menuOpen, onMenu, onDelete)
        }
        // 줄 사이 선 — 글자 칸(사진 오른쪽)부터 끝까지.
        if (!first) {
            Box(
                Modifier
                    .padding(start = PAD + (if (picking) 36.dp else 0.dp) + THUMB + 12.dp)
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(IOS.separator),
            )
        }
    }
}

@Composable
private fun CheckCircle(checked: Boolean) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .then(
                    if (checked) {
                        Modifier.background(NewCourseStyle.purple)
                    } else {
                        Modifier.border(1.5.dp, IOS.systemGray3, CircleShape)
                    },
                ),
    ) {
        if (checked) CheckmarkIcon(androidx.compose.ui.graphics.Color.White, Modifier.size(12.dp))
    }
}

/** 「⋯」 — 누르면 작은 메뉴(「삭제」 하나). 확인 창은 목록이 띄운다. */
@Composable
private fun MenuButton(
    title: String,
    enabled: Boolean,
    open: Boolean,
    onMenu: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val label = tr("%s 메뉴").format(title)
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier
                .padding(end = 2.dp)
                .size(44.dp)
                .semantics { contentDescription = label }
                .clickable(enabled = enabled) { onMenu(true) },
    ) {
        EllipsisIcon(IOS.tertiaryLabel, Modifier.size(20.dp, 18.dp))
        if (open) {
            IOSMenuPopover(
                items =
                    listOf(
                        IOSMenuItem(tr("삭제"), destructive = true, icon = {
                            tint,
                            mod,
                            ->
                            TrashFillIcon(
                                tint,
                                androidx.compose.ui.graphics
                                    .Color(0xFFF5F5F6),
                                mod,
                            )
                        }) { onDelete() },
                    ),
                onDismiss = { onMenu(false) },
            )
        }
    }
}

/**
 * 누르기와 **꾹(0.2초) 누르기**를 가른다(L4). 손가락이 [CourseListRules.HOLD_SLOP_DP] 넘게 움직이면(스크롤) 둘 다 아니다.
 * Compose 의 `combinedClickable` 은 꾹 누르기가 0.4초 이상이라 웹·iOS 의 0.2초와 다르다 — 그래서 직접 잰다.
 */
private fun Modifier.courseRowGestures(
    enabled: Boolean,
    onTap: () -> Unit,
    onHold: () -> Unit,
): Modifier {
    if (!enabled) return this
    return composed {
        val tap by rememberUpdatedState(onTap)
        val hold by rememberUpdatedState(onHold)
        val haptic = LocalHapticFeedback.current
        pointerInput(Unit) {
            val slop = CourseListRules.HOLD_SLOP_DP.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // 0.2초 안에 끝났나 — 뗐으면 RELEASED(누르기), 움직였으면 MOVED(스크롤). 시간이 다 되면 null(꾹 누르기).
                val ended: Int? =
                    withTimeoutOrNull(CourseListRules.HOLD_MILLIS) {
                        var result = 0
                        while (result == 0) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            result =
                                when {
                                    change == null -> MOVED
                                    !change.pressed -> RELEASED
                                    change.isConsumed || (change.position - down.position).getDistance() > slop -> MOVED
                                    else -> 0
                                }
                        }
                        result
                    }
                when (ended) {
                    RELEASED -> {
                        tap()
                    }

                    MOVED -> {
                        Unit
                    }

                    else -> {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        hold()
                        // 꾹 누른 손가락을 뗄 때까지 나머지 움직임을 삼킨다 — 뗄 때 누르기로 또 치지 않게.
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                        } while (event.changes.any { it.pressed })
                    }
                }
            }
        }
    }
}

private const val RELEASED = 1
private const val MOVED = 2
