package com.mz2az.scenetrip.searchtab

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Scene
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.PhotoZoomViewer

/** 포스터 대체 없이 실제 씬 사진 한 장을 전체 화면으로 연다 (MZ2AZ-420). */
@Composable
fun ScenePhotoViewer(
    scene: Scene,
    placeName: String,
    onClose: () -> Unit,
) {
    val url = ScenePhotoRules.photo(scene) ?: return
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    var attempt by remember(url) { mutableIntStateOf(0) }
    LaunchedEffect(url, attempt) {
        failed = false
        image = ImageCache.load(url, edge = 1600)
        failed = image == null
    }
    PhotoZoomViewer(
        image,
        scene.contentTitle,
        tr("%s 장면 사진").format(scene.contentTitle),
        onClose,
        failed = failed,
        onRetry = { attempt++ },
    ) {
        Text(placeName, color = Color.White, style = IOS.caption, maxLines = 2)
    }
}
