package com.mz2az.scenetrip.qa

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/** 세로 대표 사진이 제목 위에 그려지는 회귀를 실제 화면 픽셀로 검사한다. */
class CommunityBlogQaInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        val result = Bundle()
        val originalFont = shell("settings get system font_scale").toFloatOrNull() ?: 1f
        var activity: Activity? = null
        try {
            shell("settings put system font_scale 1.0")
            activity =
                startActivitySync(
                    Intent(targetContext, CommunityBlogQaActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra("qaCase", "portrait")
                        .putExtra("language", "ko")
                        .putExtra("theme", "light"),
                )
            await("QA 사진", descriptionOnly = true)
            val title = await("QA 블로그 제목")
            uiAutomation.waitForIdle(400, 15_000)
            val bounds = Rect()
            title.getBoundsInScreen(bounds)
            val picture = requireNotNull(uiAutomation.takeScreenshot())
            val pixel = picture.getPixel(picture.width * 85 / 100, bounds.centerY())
            picture.recycle()
            check(pixel == Color.WHITE) { "세로 대표 사진이 제목 영역을 덮습니다: $pixel" }
            result.putString(
                "result",
                "PASS: portrait cover stays within its frame; title background is unobscured; original font restored",
            )
        } catch (error: Throwable) {
            result.putString("result", "FAIL: ${error.message}")
        } finally {
            activity?.let { opened -> runOnMainSync { opened.finish() } }
            shell("settings put system font_scale $originalFont")
        }
        finish(if (result.getString("result")!!.startsWith("PASS:")) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command)).bufferedReader().use {
            it.readText().trim()
        }

    private fun find(
        label: String,
        descriptionOnly: Boolean,
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        uiAutomation.rootInActiveWindow?.let(queue::add)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.contentDescription?.toString() == label || (!descriptionOnly && node.text?.toString() == label)) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
        }
        return null
    }

    private fun await(
        label: String,
        descriptionOnly: Boolean = false,
    ): AccessibilityNodeInfo {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            find(label, descriptionOnly)?.let { return it }
            SystemClock.sleep(100)
        }
        error("화면 요소 없음: $label")
    }
}
