package com.mz2az.scenetrip.searchtab

import com.mz2az.scenetrip.data.WebAddress
import com.mz2az.scenetrip.sceneapi.client.model.Scene
import kotlin.math.min

object ScenePhotoRules {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4f
    const val DOUBLE_TAP_SCALE = 2f

    fun photo(scene: Scene): String? = WebAddress.url(scene.sceneImageUrl?.toString())

    fun scale(value: Float): Float = if (value.isFinite()) value.coerceIn(MIN_SCALE, MAX_SCALE) else MIN_SCALE

    fun fittedSize(
        width: Float,
        height: Float,
        viewportWidth: Float,
        viewportHeight: Float,
    ): Pair<Float, Float> {
        if (listOf(width, height, viewportWidth, viewportHeight).any { !it.isFinite() || it <= 0f }) return 0f to 0f
        val ratio = min(viewportWidth / width, viewportHeight / height)
        return width * ratio to height * ratio
    }
}

/** 확대 범위와 사진 밖으로 밀리지 않는 이동 한도 (MZ2AZ-420). */
data class SceneZoom(
    val scale: Float = 1f,
    val x: Float = 0f,
    val y: Float = 0f,
) {
    fun transformed(
        requestedScale: Float,
        panX: Float,
        panY: Float,
        imageWidth: Float,
        imageHeight: Float,
        viewportWidth: Float,
        viewportHeight: Float,
        anchorX: Float = 0f,
        anchorY: Float = 0f,
    ): SceneZoom {
        val nextScale = ScenePhotoRules.scale(requestedScale)
        if (nextScale == 1f) return SceneZoom()
        val (width, height) = ScenePhotoRules.fittedSize(imageWidth, imageHeight, viewportWidth, viewportHeight)
        val maxX = ((width * nextScale - viewportWidth) / 2f).coerceAtLeast(0f)
        val maxY = ((height * nextScale - viewportHeight) / 2f).coerceAtLeast(0f)
        val ratio = nextScale / ScenePhotoRules.scale(scale)

        fun bounded(
            value: Float,
            max: Float,
        ) = if (max == 0f || !value.isFinite()) 0f else value.coerceIn(-max, max)
        return SceneZoom(
            nextScale,
            bounded(x * ratio + anchorX * (1f - ratio) + panX, maxX),
            bounded(y * ratio + anchorY * (1f - ratio) + panY, maxY),
        )
    }

    fun doubleTapScale(): Float = if (scale > 1f) 1f else ScenePhotoRules.DOUBLE_TAP_SCALE
}
