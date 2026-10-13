package com.mz2az.scenetrip.qa

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo

/** 한국어 제품 홈의 공개 작품 진입만 검사한다. 기존 편집 초안이 없는 시험 기기에서 실행한다. */
class HomeQaInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            shell("am start -n com.mz2az.scenetrip/.MainActivity -f 0x10008000 --es initialTab home")
            await("기생충")
            click("기생충")
            await("제작사 공식 페이지")
            click("홈", descriptionOnly = true, heightFraction = 0.2f)
            await("지금 뜨는 작품", absent = "제작사 공식 페이지")
            click("전체 보기")
            await("인기 작품", absent = "제작사 공식 페이지", prefix = true)
            await("기생충")
            click("홈", descriptionOnly = true)
            await("지금 뜨는 작품", absent = "제작사 공식 페이지")
            result.putString(
                "result",
                "PASS: floating Home icon returns from a title; All works clears the previous detail; public reads only",
            )
        } catch (error: Throwable) {
            result.putString("result", "FAIL: ${error.message}")
        }
        finish(if (result.getString("result")!!.startsWith("PASS:")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun find(
        label: String,
        descriptionOnly: Boolean = false,
        prefix: Boolean = false,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        uiAutomation.rootInActiveWindow?.let(queue::add)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!node.refresh() || !node.isVisibleToUser) continue
            val textMatches = node.text?.toString()?.let { it == label || (prefix && it.startsWith("$label ")) } == true
            if (node.contentDescription?.toString() == label || (!descriptionOnly && textMatches)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return null
    }

    private fun await(
        label: String,
        absent: String? = null,
        prefix: Boolean = false,
    ) {
        val deadline = SystemClock.uptimeMillis() + 20_000

        fun ready() = find(label, prefix = prefix) != null && (absent == null || find(absent) == null)
        while (!ready() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        check(ready()) { "$label 화면으로 이동하지 못했습니다 · 이전 상세=${absent?.let { find(it) != null }}" }
    }

    private fun click(
        label: String,
        descriptionOnly: Boolean = false,
        heightFraction: Float = 0.5f,
    ) {
        uiAutomation.waitForIdle(400, 15_000)
        val node = requireNotNull(find(label, descriptionOnly)) { "터치 요소 없음: $label" }
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event =
                MotionEvent.obtain(
                    down,
                    SystemClock.uptimeMillis(),
                    action,
                    bounds.exactCenterX(),
                    bounds.top + bounds.height() * heightFraction,
                    0,
                )
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(event, true)) { "터치 주입 실패" }
            event.recycle()
        }
    }
}
