package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Scene
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.PhotoZoomViewer
import com.mz2az.scenetrip.ui.ZoomControl

/**
 * 상세 화면을 유지하고 서버 순서대로 씬 사진을 넘긴다 (MZ2AZ-420·430).
 *
 * 확대·이동과 페이지 넘기기 제스처가 부딪치지 않도록 넘기기는 이전·다음 버튼으로만 한다(iOS 와 같다).
 */
@Composable
fun ScenePhotoViewer(
    scene: Scene,
    placeName: String,
    onClose: () -> Unit,
) {
    val photos = ScenePhotoRules.photos(scene)
    var index by remember { mutableIntStateOf(0) }
    val url = photos.getOrNull(index)
    if (url == null) {
        // 사진이 없으면 열어 둘 이유가 없다(iOS `dismiss()`).
        LaunchedEffect(Unit) { onClose() }
        return
    }
    // 페이지를 바꾸면 이미지·오류 상태와 이전 다운로드가 함께 폐기된다. 같은 주소가 반복돼도 번호가 달라 새 쪽이다.
    val page = index to url
    val shown by rememberUpdatedState(page)
    var image by remember(page) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(page) { mutableStateOf(false) }
    var attempt by remember(page) { mutableIntStateOf(0) }
    LaunchedEffect(page, attempt) {
        failed = false
        val loaded = ImageCache.load(url, edge = 1600)
        // 이미 넘어간 쪽의 늦은 응답은 버린다.
        if (!ScenePhotoRules.accepts(page, shown)) return@LaunchedEffect
        image = loaded
        failed = loaded == null
    }
    PhotoZoomViewer(
        image,
        scene.contentTitle,
        tr("%s 장면 사진").format(scene.contentTitle),
        onClose,
        failed = failed,
        onRetry = { attempt++ },
        pageKey = page,
        bottom = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(placeName, color = Color.White, style = IOS.caption, maxLines = 2)
                if (photos.size > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        ZoomControl("‹", tr("이전 사진"), index > 0) { index = ScenePhotoRules.step(index, -1, photos.size) }
                        Text(tr("사진 %d / %d").format(index + 1, photos.size), color = Color.White, style = IOS.subheadline)
                        ZoomControl("›", tr("다음 사진"), index < photos.size - 1) { index = ScenePhotoRules.step(index, 1, photos.size) }
                    }
                }
            }
        },
    )
}
