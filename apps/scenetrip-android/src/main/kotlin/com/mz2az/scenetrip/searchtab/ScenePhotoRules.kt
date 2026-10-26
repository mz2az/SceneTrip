package com.mz2az.scenetrip.searchtab

import com.mz2az.scenetrip.data.WebAddress
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Scene
import kotlin.math.min

object ScenePhotoRules {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 4f
    const val DOUBLE_TAP_SCALE = 2f

    /**
     * 서버 순서 그대로의 장면 사진 주소 (MZ2AZ-430). 같은 주소가 반복돼도 그대로 두고, HTTP(S)가 아닌
     * 주소만 순서를 건드리지 않고 건너뛴다. 배열이 비어도 구형 `sceneImageUrl`·포스터로 대신하지 않는다.
     */
    fun photos(scene: Scene): List<String> = scene.sceneImageUrls.mapNotNull { WebAddress.url(it.toString()) }

    fun photo(scene: Scene): String? = photos(scene).firstOrNull()

    /** 이전·다음 버튼이 가는 자리 — 양 끝에서는 그대로 머문다. */
    fun step(
        index: Int,
        delta: Int,
        count: Int,
    ): Int = if (count <= 0) 0 else (index + delta).coerceIn(0, count - 1)

    /** 사진 수 알약 — 한 장이면 없다. 카드·설명 시트는 첫 장을 보여 주므로 「사진 1 / N」 이다. */
    fun countBadge(scene: Scene): String? = photos(scene).size.takeIf { it > 1 }?.let { tr("사진 %d / %d").format(1, it) }

    /** 늦게 도착한 다운로드 응답은 지금 보이는 쪽(번호와 주소가 같은 쪽)일 때만 받는다. */
    fun accepts(
        requested: Pair<Int, String>,
        shown: Pair<Int, String>,
    ): Boolean = requested == shown

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
