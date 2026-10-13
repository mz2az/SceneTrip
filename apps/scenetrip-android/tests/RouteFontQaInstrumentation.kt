package com.mz2az.scenetrip.qa

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo

/** 제품 앱의 저장하지 않은 시험 초안만 조작한다. 가이드·DB 쓰기·OAuth를 호출하지 않는다. */
class RouteFontQaInstrumentation : Instrumentation() {
    private var phase = "wizard"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val previousScale = shell("settings get system font_scale").toFloatOrNull() ?: 1f
        var completed = false
        try {
            shell("settings put system font_scale 1.0")
            shell("am start -n com.mz2az.scenetrip/.MainActivity -f 0x10008000 --es initialTab route")
            createDraft()
            verifyEditedDraft()
            phase = "discard"
            discardDraft()
            completed = true
            result.putString("result", "PASS: wizard step/three days and edited draft survive fontScale 1.0/1.6; discarded without saving")
        } catch (error: Throwable) {
            result.putString("result", "FAIL: $phase · ${error.message}")
        } finally {
            shell("settings put system font_scale $previousScale")
            if (!completed && find(TEST_TITLE) != null) {
                try {
                    discardDraft()
                } catch (error: Throwable) {
                    result.putString("result", "${result.getString("result")} · 초안 정리 실패: ${error.message}")
                }
            }
        }
        finish(if (result.getString("result")!!.startsWith("PASS:")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private fun createDraft() {
        await("직접 짜기")
        click("직접 짜기")
        await("1 / 2")
        click("2박 3일")
        click("다음")
        await("2 / 2")
        val stepHeight = height("2 / 2")
        shell("settings put system font_scale 1.6")
        awaitResize("2 / 2", stepHeight, grew = true)
        click("코스 만들기")
        await("3일차")
    }

    private fun verifyEditedDraft() {
        phase = "title"
        val title = requireNotNull(find("내 코스", editable = true))
        check(
            title.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, TEST_TITLE)
                },
            ),
        ) { "시험 제목을 입력하지 못했습니다" }
        await(TEST_TITLE)
        phase = "choose day"
        selectDay("2일차")
        awaitSelected("2일차")
        val dayHeight = height("3일차")
        phase = "editor font resize"
        shell("settings put system font_scale 1.0")
        awaitResize("3일차", dayHeight, grew = false)
        await(TEST_TITLE)
        awaitSelected("2일차")
    }

    private fun discardDraft() {
        shell("input keyevent 4")
        await("버리기")
        click("버리기")
        await("직접 짜기")
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }

    private fun find(
        label: String,
        editable: Boolean = false,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        uiAutomation.rootInActiveWindow?.let(queue::add)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!node.refresh()) continue
            if ((node.text?.toString() == label || node.contentDescription?.toString() == label) &&
                (!editable || node.isEditable)
            ) {
                return node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return null
    }

    private fun await(label: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (find(label) == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        check(find(label) != null) { "요소를 찾지 못했습니다: $label" }
    }

    private fun height(label: String): Int {
        val bounds = Rect()
        find(label)?.getBoundsInScreen(bounds)
        return bounds.height()
    }

    private fun awaitResize(
        label: String,
        before: Int,
        grew: Boolean,
    ) {
        val deadline = SystemClock.uptimeMillis() + 15_000

        fun resized(): Boolean = height(label).let { it > 0 && if (grew) it > before else it < before }
        while (!resized() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        check(resized()) { "글자 배율 적용 뒤 요소/크기 보존 실패: $label" }
    }

    private fun selected(label: String): Boolean {
        var node = find(label)
        while (node != null) {
            node.refresh()
            if (node.isSelected) return true
            node = node.parent
        }
        return false
    }

    private fun awaitSelected(label: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!selected(label) && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        check(selected(label)) { "선택 일차 보존 실패: $label" }
    }

    private fun selectDay(label: String) {
        uiAutomation.waitForIdle(400, 15_000)
        var node = find(label)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "일차 선택 동작 실패: $label" }
    }

    private fun click(label: String) {
        await(label)
        uiAutomation.waitForIdle(400, 15_000)
        val bounds = Rect()
        requireNotNull(find(label)).getBoundsInScreen(bounds)
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
            check(uiAutomation.injectInputEvent(event, true)) { "터치 주입 실패" }
            event.recycle()
        }
    }

    companion object {
        const val TEST_TITLE = "Android Font QA draft"
    }
}
