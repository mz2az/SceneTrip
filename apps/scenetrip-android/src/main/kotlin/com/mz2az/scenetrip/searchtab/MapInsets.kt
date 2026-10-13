package com.mz2az.scenetrip.searchtab

enum class MapInsetMode { SEARCH, ROUTE }

/** 화면·패널 단위는 호출자가 같은 px로 맞춘다. 잘못된 높이도 보이는 영역을 음수로 만들지 않는다. */
object MapInsets {
    const val SEARCH_BOTTOM_FRACTION = 0.48f

    fun cameraBottom(
        height: Int,
        sheet: Int,
        top: Int,
        minimumVisible: Int,
        mode: MapInsetMode,
    ): Int {
        val available = (height.coerceAtLeast(0) - top.coerceAtLeast(0) - minimumVisible.coerceAtLeast(0)).coerceAtLeast(0)
        val cap = if (mode == MapInsetMode.SEARCH) (height.coerceAtLeast(0) * SEARCH_BOTTOM_FRACTION).toInt() else available
        return minOf(sheet.coerceAtLeast(0), available, cap)
    }
}
