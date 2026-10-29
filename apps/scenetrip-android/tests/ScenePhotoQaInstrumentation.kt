package com.mz2az.scenetrip.qa

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo

/** 실제 Android 입력 이벤트로 두 손가락·두 번 누르기·오류 화면을 확인한다. */
class ScenePhotoQaInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var phase = "load"
        try {
            val normal = open("ok")
            await("Scene photo QA 장면 사진")
            await("100%")
            val screenshot = requireNotNull(uiAutomation.takeScreenshot())
            val width = screenshot.width.toFloat()
            val height = screenshot.height.toFloat()
            val colors = listOf(1, 3, 5).map { screenshot.getPixel(screenshot.width * it / 6, screenshot.height / 2) }
            check(colors == listOf(Color.rgb(225, 45, 45), Color.rgb(40, 170, 90), Color.rgb(40, 90, 225))) { "원본 세 패널 비율 오류" }
            screenshot.recycle()
            phase = "pinch"
            pinch(width, height)
            await("400%")
            check(find("닫기") != null)
            phase = "double tap"
            tap(width / 2, height / 2)
            SystemClock.sleep(50)
            tap(width / 2, height / 2)
            await("100%")
            // 별도 두 번 누르기를 보내기 전 이전 포인터 처리와 화면 반영을 끝낸다.
            uiAutomation.waitForIdle(400, 15_000)
            tap(width / 2, height / 2)
            SystemClock.sleep(50)
            tap(width / 2, height / 2)
            await("200%")
            // 다시 맞추는 단추는 없다(iOS 와 같다) — 확대된 상태에서 두 번 누르면 100% 로 돌아온다.
            phase = "double tap reset"
            tap(width / 2, height / 2)
            SystemClock.sleep(50)
            tap(width / 2, height / 2)
            await("100%")
            phase = "close"
            click("닫기")
            SystemClock.sleep(100)
            val reopened = open("ok")
            await("Scene photo QA 장면 사진")
            await("100%")
            runOnMainSync {
                reopened.finish()
                normal.finish()
            }
            phase = "failure"
            val failure = open("failure")
            await("사진을 불러오지 못했어요")
            click("다시 시도")
            await("사진을 불러오지 못했어요")
            runOnMainSync { failure.finish() }
            phase = "missing"
            val missing = open("missing")
            await("No scene image · poster is not a fallback")
            check(find("Scene photo QA 장면 사진") == null)
            runOnMainSync { missing.finish() }
            result.putString(
                "result",
                "PASS: full frame, pinch 400%, double tap 100/200%/100%, close/reopen, 404 retry, no poster fallback",
            )
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("result", "FAIL: $phase · ${error.message}")
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun open(kind: String): Activity =
        startActivitySync(
            Intent(targetContext, ScenePhotoQaActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("photoCase", kind),
        )

    private fun find(label: String): AccessibilityNodeInfo? {
        val root = uiAutomation.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return null
    }

    private fun await(label: String) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (find(label) == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        check(find(label) != null) { "요소를 찾지 못했습니다: $label" }
    }

    private fun click(label: String) {
        val bounds = Rect()
        requireNotNull(find(label)).getBoundsInScreen(bounds)
        tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    private fun tap(
        x: Float,
        y: Float,
    ) {
        val down = SystemClock.uptimeMillis()
        event(down, MotionEvent.ACTION_DOWN, listOf(x to y))
        event(down, MotionEvent.ACTION_UP, listOf(x to y))
    }

    private fun pinch(
        width: Float,
        height: Float,
    ) {
        val down = SystemClock.uptimeMillis()
        val center = width / 2
        val y = height / 2
        event(down, MotionEvent.ACTION_DOWN, listOf(center - width * 0.04f to y))
        event(
            down,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(center - width * 0.04f to y, center + width * 0.04f to y),
        )
        for (step in 1..20) {
            val half = width * (0.04f + step * 0.022f)
            event(down, MotionEvent.ACTION_MOVE, listOf(center - half to y, center + half to y))
            SystemClock.sleep(16)
        }
        event(
            down,
            MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(width * 0.02f to y, width * 0.98f to y),
        )
        event(down, MotionEvent.ACTION_UP, listOf(width * 0.02f to y))
    }

    private fun event(
        down: Long,
        action: Int,
        points: List<Pair<Float, Float>>,
    ) {
        val properties =
            points.indices
                .map { index ->
                    MotionEvent.PointerProperties().apply {
                        id = index
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }.toTypedArray()
        val coordinates =
            points
                .map { point ->
                    MotionEvent.PointerCoords().apply {
                        x = point.first
                        y = point.second
                        pressure = 1f
                        size = 1f
                    }
                }.toTypedArray()
        val motion =
            MotionEvent.obtain(
                down,
                SystemClock.uptimeMillis(),
                action,
                points.size,
                properties,
                coordinates,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        sendPointerSync(motion)
        motion.recycle()
    }
}
