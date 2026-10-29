package com.mz2az.scenetrip.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/*
 * 마이페이지 줄 아이콘 — iOS `ProfileTabView.row(symbol:)` 의 SF Symbols. core 아이콘 49개에 짝이
 * 없어 색 점·엉뚱한 모양(핀·카트·연필)으로 대신하던 것을 모양대로 그린다(2026-09-28 대조 #5).
 */

private fun Path.mv(p: Offset) = moveTo(p.x, p.y)

private fun Path.ln(p: Offset) = lineTo(p.x, p.y)

private fun Path.cb(
    a: Offset,
    b: Offset,
    c: Offset,
) = cubicTo(a.x, a.y, b.x, b.y, c.x, c.y)

private fun Path.qd(
    a: Offset,
    b: Offset,
) = quadraticTo(a.x, a.y, b.x, b.y)

private fun line(w: Float) = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** `point.topleft.down.to.point.bottomright.curvepath` — 두 점을 잇는 S 곡선. */
@Composable
fun RouteCurveIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = line(w)
        drawCircle(tint, radius = w * 0.11f, center = Offset(w * 0.18f, w * 0.16f), style = s)
        drawCircle(tint, radius = w * 0.11f, center = Offset(w * 0.82f, w * 0.84f), style = s)
        val p =
            Path().apply {
                // 대각선 S — 왼쪽 위 점에서 오른쪽으로 부풀었다가 왼쪽으로 돌아 오른쪽 아래 점으로.
                moveTo(w * 0.26f, w * 0.22f)
                cubicTo(w * 0.72f, w * 0.26f, w * 0.72f, w * 0.48f, w * 0.5f, w * 0.52f)
                cubicTo(w * 0.28f, w * 0.56f, w * 0.28f, w * 0.78f, w * 0.72f, w * 0.8f)
            }
        drawPath(p, tint, style = s)
    }
}

/** `bag.fill` */
@Composable
fun BagFillIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        drawRoundRect(
            tint,
            topLeft = Offset(w * 0.12f, w * 0.32f),
            size = Size(w * 0.76f, w * 0.6f),
            cornerRadius = CornerRadius(w * 0.12f),
        )
        val handle =
            Path().apply {
                moveTo(w * 0.33f, w * 0.34f)
                lineTo(w * 0.33f, w * 0.26f)
                cubicTo(w * 0.33f, w * 0.05f, w * 0.67f, w * 0.05f, w * 0.67f, w * 0.26f)
                lineTo(w * 0.67f, w * 0.34f)
            }
        drawPath(handle, tint, style = line(w))
    }
}

/** `square.and.pencil` — 오른쪽 위가 트인 네모와 비스듬한 연필. */
@Composable
fun SquarePencilIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = line(w)
        val box =
            Path().apply {
                moveTo(w * 0.5f, w * 0.14f)
                lineTo(w * 0.2f, w * 0.14f)
                quadraticTo(w * 0.1f, w * 0.14f, w * 0.1f, w * 0.24f)
                lineTo(w * 0.1f, w * 0.8f)
                quadraticTo(w * 0.1f, w * 0.9f, w * 0.2f, w * 0.9f)
                lineTo(w * 0.76f, w * 0.9f)
                quadraticTo(w * 0.86f, w * 0.9f, w * 0.86f, w * 0.8f)
                lineTo(w * 0.86f, w * 0.52f)
            }
        drawPath(box, tint, style = s)
        drawLine(tint, Offset(w * 0.42f, w * 0.58f), Offset(w * 0.86f, w * 0.14f), strokeWidth = w * 0.12f, cap = StrokeCap.Round)
    }
}

/** `shoeprints.fill` — 엇갈린 발바닥 둘. */
@Composable
fun ShoeprintsIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension

        // 발바닥(앞꿈치) 큰 타원 + 뒤꿈치, 두 발이 **붙어 겹치고 오른발은 약 20° 기운다**(iOS 확대 실측).
        fun foot(
            x: Float,
            y: Float,
        ) {
            drawOval(tint, topLeft = Offset(x, y), size = Size(w * 0.3f, w * 0.42f))
            drawOval(tint, topLeft = Offset(x + w * 0.04f, y + w * 0.45f), size = Size(w * 0.22f, w * 0.2f))
        }
        foot(w * 0.12f, w * 0.02f)
        rotate(20f, pivot = Offset(w * 0.66f, w * 0.6f)) {
            foot(w * 0.5f, w * 0.3f)
        }
    }
}

/**
 * `chart.bar` — 속이 빈 둥근 막대 셋(이용 통계 수집 줄). 좌표는 iOS 15pt 글리프를 감사 스크린샷(2026-10-29)에서 쟀다.
 * 선 1.3·막대 모서리 1.2, 글리프 폭 18.3 × 높이 13.4 가 18 칸의 가운데에 놓인다.
 */
@Composable
fun ChartBarIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val k = size.minDimension / 18f
        val stroke = Stroke(width = 1.3f * k, join = StrokeJoin.Round)
        // (왼쪽, 위, 오른쪽, 바닥) — 막대 가운데선 좌표.
        val bars =
            listOf(
                floatArrayOf(0.5f, 7.15f, 4.0f, 15.05f),
                floatArrayOf(7.1f, 5.15f, 10.6f, 15.05f),
                floatArrayOf(13.6f, 2.95f, 17.1f, 15.05f),
            )
        bars.forEach { b ->
            drawRoundRect(
                tint,
                topLeft = Offset(b[0] * k, b[1] * k),
                size = Size((b[2] - b[0]) * k, (b[3] - b[1]) * k),
                cornerRadius = CornerRadius(1.2f * k),
                style = stroke,
            )
        }
    }
}

/** `questionmark.circle` */
@Composable
fun QuestionCircleIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = line(w)
        drawCircle(tint, radius = w * 0.44f, center = Offset(w / 2, w / 2), style = s)
        val q =
            Path().apply {
                moveTo(w * 0.36f, w * 0.38f)
                cubicTo(w * 0.36f, w * 0.2f, w * 0.64f, w * 0.2f, w * 0.64f, w * 0.38f)
                cubicTo(w * 0.64f, w * 0.5f, w * 0.5f, w * 0.5f, w * 0.5f, w * 0.6f)
            }
        drawPath(q, tint, style = s)
        drawCircle(tint, radius = w * 0.055f, center = Offset(w * 0.5f, w * 0.74f))
    }
}

/** `person.crop.circle.badge.plus` — 원 안의 사람 + 왼쪽 아래 더하기 배지. */
@Composable
fun PersonBadgePlusIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = line(w)
        drawCircle(tint, radius = w * 0.4f, center = Offset(w * 0.56f, w * 0.44f), style = s)
        drawCircle(tint, radius = w * 0.13f, center = Offset(w * 0.56f, w * 0.36f), style = s)
        drawArc(tint, 200f, 140f, false, topLeft = Offset(w * 0.32f, w * 0.56f), size = Size(w * 0.48f, w * 0.34f), style = s)
        drawCircle(tint, radius = w * 0.2f, center = Offset(w * 0.22f, w * 0.76f))
        drawLine(Color.White, Offset(w * 0.13f, w * 0.76f), Offset(w * 0.31f, w * 0.76f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
        drawLine(Color.White, Offset(w * 0.22f, w * 0.67f), Offset(w * 0.22f, w * 0.85f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
    }
}

/** `globe` — 원 + 세로 타원 + 가로줄. */
@Composable
fun GlobeIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = line(w)
        drawCircle(tint, radius = w * 0.44f, center = Offset(w / 2, w / 2), style = s)
        drawOval(tint, topLeft = Offset(w * 0.3f, w * 0.06f), size = Size(w * 0.4f, w * 0.88f), style = s)
        drawLine(tint, Offset(w * 0.06f, w * 0.5f), Offset(w * 0.94f, w * 0.5f), strokeWidth = s.width)
        drawLine(tint, Offset(w * 0.14f, w * 0.3f), Offset(w * 0.86f, w * 0.3f), strokeWidth = s.width)
        drawLine(tint, Offset(w * 0.14f, w * 0.7f), Offset(w * 0.86f, w * 0.7f), strokeWidth = s.width)
    }
}

/**
 * `hand.raised` — 속이 빈 손(개인정보 처리방침 줄). 손가락 넷은 둥근 아치, 엄지는 오른쪽으로 나온다.
 * 좌표는 iOS 15pt 글리프(폭 11.8 × 높이 15.5)를 감사 스크린샷(2026-10-29)에서 쟀다 — 가는 선 1.3.
 */
@Composable
fun HandRaisedIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val k = size.minDimension / 18f
        val s = Stroke(width = 1.3f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)

        // 글리프 가운데가 18 칸 가운데에 오도록 윈도 좌표(10.3..21.8 × 7.5..23)를 옮긴다.
        fun o(
            x: Float,
            y: Float,
        ) = Offset((x - 6.9f) * k, (y - 6.25f) * k)

        // 손가락 아치 — 왼쪽 벽에서 위로, 둥글게 넘어, 오른쪽 벽까지.
        val arches = listOf(floatArrayOf(10.6f, 9.9f), floatArrayOf(12.65f, 8.5f), floatArrayOf(14.7f, 7.8f), floatArrayOf(16.75f, 8.5f))
        arches.forEach { a ->
            val left = a[0]
            val top = a[1]
            val right = left + 2.05f
            val arch =
                Path().apply {
                    mv(o(left, 15.4f))
                    ln(o(left, top + 1.0f))
                    cb(o(left, top + 0.35f), o(left + 0.45f, top), o(left + 1.025f, top))
                    cb(o(right - 0.45f, top), o(right, top + 0.35f), o(right, top + 1.0f))
                    ln(o(right, 15.4f))
                }
            drawPath(arch, tint, style = s)
        }
        // 손바닥 아래 둘레와 엄지 — 왼쪽 벽 끝에서 아래로 둥글게 돌아 오른쪽 위로 올라가 엄지로 나온다.
        val palm =
            Path().apply {
                mv(o(10.6f, 15.4f))
                ln(o(10.6f, 18.0f))
                cb(o(10.6f, 21.6f), o(12.9f, 23.0f), o(15.5f, 23.0f))
                cb(o(18.4f, 23.0f), o(20.3f, 21.4f), o(20.9f, 18.4f))
                ln(o(21.5f, 15.6f))
                cb(o(21.7f, 14.5f), o(20.6f, 14.1f), o(20.0f, 14.9f))
                ln(o(18.8f, 16.6f))
                ln(o(18.8f, 15.4f))
            }
        drawPath(palm, tint, style = s)
    }
}

/**
 * `doc.text` — 오른쪽 위가 접힌 문서와 글줄 둘(이용약관 줄). 좌표는 iOS 15pt 글리프(폭 12 × 높이 15.5)를 감사 스크린샷
 * (2026-10-29)에서 쟀다 — 가는 선 1.3.
 */
@Composable
fun DocTextIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val k = size.minDimension / 18f
        val s = Stroke(width = 1.3f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)

        fun o(
            x: Float,
            y: Float,
        ) = Offset((x - 6.5f) * k, (y - 6.25f) * k)

        // 바깥 윤곽 — 왼쪽 위 모서리에서 접힌 꼭짓점까지 위 변, 비스듬히 내려와 오른쪽 변, 둥근 아래 모서리.
        val body =
            Path().apply {
                val r = 1.9f
                mv(o(15.2f, 8.1f))
                ln(o(10.1f + r, 8.1f))
                qd(o(10.1f, 8.1f), o(10.1f, 8.1f + r))
                ln(o(10.1f, 22.4f - r))
                qd(o(10.1f, 22.4f), o(10.1f + r, 22.4f))
                ln(o(20.9f - r, 22.4f))
                qd(o(20.9f, 22.4f), o(20.9f, 22.4f - r))
                ln(o(20.9f, 13.4f))
                close()
            }
        drawPath(body, tint, style = s)
        // 접힌 모서리 — 위 변 끝에서 내려와 오른쪽으로 꺾인다.
        val fold =
            Path().apply {
                mv(o(15.2f, 8.1f))
                ln(o(15.2f, 12.6f))
                qd(o(15.2f, 13.4f), o(16.0f, 13.4f))
                ln(o(20.9f, 13.4f))
            }
        drawPath(fold, tint, style = s)
        drawLine(tint, o(12.5f, 16.5f), o(18.5f, 16.5f), strokeWidth = s.width, cap = StrokeCap.Round)
        drawLine(tint, o(12.5f, 19.2f), o(18.5f, 19.2f), strokeWidth = s.width, cap = StrokeCap.Round)
    }
}

/** `xmark` — 굵은 X. */
@Composable
fun XMarkIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val sw = w * 0.13f
        drawLine(tint, Offset(w * 0.12f, w * 0.12f), Offset(w * 0.88f, w * 0.88f), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.88f, w * 0.12f), Offset(w * 0.12f, w * 0.88f), strokeWidth = sw, cap = StrokeCap.Round)
    }
}

/**
 * iOS 26 툴바의 닫기 단추 — 44pt 흰 원(옅은 그림자) 안의 굵은 X. 앞서 Android 는 16dp 얇은 X
 * 하나뿐이었다(2026-09-28 대조 #6).
 */
@Composable
fun IOSCloseButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .semantics {
                    contentDescription =
                        com.mz2az.scenetrip.data
                            .tr("닫기")
                }.size(44.dp)
                // iOS 26 툴바 단추의 그림자는 넓고 옅다 — 회색 바탕(242)에서 가장자리 근처가 233 쯤(2026-10-29 감사 #25). 고도 그림자는
                // 투명도에 플랫폼 상수가 또 곱해져 거의 안 보였다.
                .iosShadow(Color.Black.copy(alpha = 0.08f), radius = 16.dp, y = 4.dp, cornerRadius = 22.dp)
                .clip(CircleShape)
                .background(IOS.systemBackground)
                .clickable(onClick = onClick),
    ) {
        XMarkIcon(IOS.label, Modifier.size(19.dp))
    }
}

/** iOS 26 의 `Toggle` — 62×28 캡슐, 꺼지면 회색·켜지면 초록, 흰 알약 손잡이. Material `Switch` 와 생김새가 다르다. */
@Composable
fun IOSToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track by animateColorAsState(if (checked) IOS.systemGreen else Color(0xFFC6C6C8), label = "toggleTrack")
    val knobX by animateDpAsState(if (checked) 22.dp else 2.dp, label = "toggleKnob")
    Box(
        modifier =
            modifier
                .size(width = 62.dp, height = 28.dp)
                .clip(CircleShape)
                .background(track)
                .clickable { onCheckedChange(!checked) },
    ) {
        Box(
            modifier =
                Modifier
                    .padding(vertical = 2.dp)
                    .offset(x = knobX)
                    .size(width = 38.dp, height = 24.dp)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Color.White),
        )
    }
}

/**
 * `photo.on.rectangle.angled` — 뒤 액자는 **반시계로** 기울고(윗변이 오른쪽으로 올라감) 앞 사진에 가려진다.
 * 앞 사진 안에 두 봉우리 산(채움)과 해. Compose `rotate` 는 양수가 시계 방향이다(9차 대조에서 반대로 그렸다).
 */
@Composable
fun PhotosIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val s = line(w)
        val front = Rect(Offset(w * 0.22f, w * 0.36f), Size(w * 0.72f, w * 0.54f))
        clipRect(front.left - s.width, front.top - s.width, front.right + s.width, front.bottom + s.width, clipOp = ClipOp.Difference) {
            rotate(-14f, pivot = Offset(w * 0.46f, w * 0.44f)) {
                drawRoundRect(
                    tint,
                    topLeft = Offset(w * 0.08f, w * 0.14f),
                    size = Size(w * 0.7f, w * 0.52f),
                    cornerRadius =
                        CornerRadius(w * 0.08f),
                    style = s,
                )
            }
        }
        drawRoundRect(tint, topLeft = front.topLeft, size = front.size, cornerRadius = CornerRadius(w * 0.08f), style = s)
        val hills =
            Path().apply {
                moveTo(w * 0.28f, w * 0.84f)
                lineTo(w * 0.46f, w * 0.6f)
                lineTo(w * 0.6f, w * 0.74f)
                lineTo(w * 0.7f, w * 0.64f)
                lineTo(w * 0.88f, w * 0.84f)
                close()
            }
        drawPath(hills, tint)
        drawCircle(tint, radius = w * 0.065f, center = Offset(w * 0.74f, w * 0.48f))
    }
}
