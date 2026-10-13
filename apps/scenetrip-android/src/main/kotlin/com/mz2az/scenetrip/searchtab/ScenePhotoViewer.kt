package com.mz2az.scenetrip.searchtab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Scene
import com.mz2az.scenetrip.ui.DisableDialogDim
import com.mz2az.scenetrip.ui.FullScreenDialogWindow
import com.mz2az.scenetrip.ui.IOS
import kotlin.math.roundToInt

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
    var zoom by remember(url) { mutableStateOf(SceneZoom()) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(url, attempt) {
        failed = false
        image = ImageCache.load(url, edge = 1600)
        failed = image == null
    }

    fun changeScale(
        scale: Float,
        panX: Float = 0f,
        panY: Float = 0f,
        anchorX: Float = 0f,
        anchorY: Float = 0f,
    ) {
        val bitmap = image ?: return
        zoom =
            zoom.transformed(
                scale,
                panX,
                panY,
                bitmap.width.toFloat(),
                bitmap.height.toFloat(),
                viewport.width.toFloat(),
                viewport.height.toFloat(),
                anchorX,
                anchorY,
            )
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DisableDialogDim()
        FullScreenDialogWindow()
        Column(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(scene.contentTitle, color = Color.White, style = IOS.headline, maxLines = 2, modifier = Modifier.weight(1f))
                Box(Modifier.size(44.dp).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, contentDescription = tr("닫기"), tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .onSizeChanged { viewport = it },
                contentAlignment = Alignment.Center,
            ) {
                val bitmap = image
                if (bitmap != null) {
                    Canvas(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .semantics {
                                    contentDescription = tr("%s 장면 사진").format(scene.contentTitle)
                                    stateDescription = "${(zoom.scale * 100).toInt()}%"
                                }.pointerInput(url, viewport) {
                                    detectTransformGestures { centroid, pan, scale, _ ->
                                        changeScale(
                                            zoom.scale * scale,
                                            pan.x,
                                            pan.y,
                                            centroid.x - viewport.width / 2f,
                                            centroid.y - viewport.height / 2f,
                                        )
                                    }
                                }.pointerInput(url) { detectTapGestures(onDoubleTap = { changeScale(zoom.doubleTapScale()) }) },
                    ) {
                        val (width, height) =
                            ScenePhotoRules.fittedSize(
                                bitmap.width.toFloat(),
                                bitmap.height.toFloat(),
                                size.width,
                                size.height,
                            )
                        val drawnWidth = width * zoom.scale
                        val drawnHeight = height * zoom.scale
                        // 확대된 레이어 전체를 만들지 않고 뷰포트 안에서 사진만 그린다.
                        // 4배 확대에서도 헤더·닫기·조작 버튼을 덮지 않는다.
                        clipRect {
                            drawImage(
                                bitmap,
                                dstOffset =
                                    IntOffset(
                                        ((size.width - drawnWidth) / 2 + zoom.x).roundToInt(),
                                        ((size.height - drawnHeight) / 2 + zoom.y).roundToInt(),
                                    ),
                                dstSize = IntSize(drawnWidth.roundToInt().coerceAtLeast(1), drawnHeight.roundToInt().coerceAtLeast(1)),
                            )
                        }
                    }
                } else if (failed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Text(tr("사진을 불러오지 못했어요"), color = Color.White)
                        Text(
                            tr("다시 시도"),
                            color = Color.White,
                            modifier = Modifier.clickable { attempt++ }.padding(16.dp),
                        )
                    }
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(placeName, color = Color.White, style = IOS.caption, maxLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ZoomControl("−", tr("축소", "사진"), image != null && zoom.scale > 1f) { changeScale(zoom.scale / 2) }
                    Text("${(zoom.scale * 100).toInt()}%", color = Color.White)
                    ZoomControl("+", tr("확대", "사진"), image != null && zoom.scale < 4f) { changeScale(zoom.scale * 2) }
                    ZoomControl("↺", tr("초기화"), image != null) { zoom = SceneZoom() }
                }
            }
        }
    }
}

@Composable
private fun ZoomControl(
    text: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(44.dp).semantics { contentDescription = label }.clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White.copy(alpha = if (enabled) 1f else 0.3f)) }
}
