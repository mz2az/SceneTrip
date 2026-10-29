package com.mz2az.scenetrip.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.searchtab.ScenePhotoRules
import com.mz2az.scenetrip.searchtab.SceneZoom
import kotlin.math.roundToInt

/** 씬과 커뮤니티가 같은 100~400% 확대·이동·실패/재시도 화면을 사용한다. */
@Composable
fun PhotoZoomViewer(
    image: ImageBitmap?,
    title: String,
    description: String,
    onClose: () -> Unit,
    failed: Boolean = false,
    onRetry: () -> Unit = {},
    // 여러 장을 넘기는 쪽이 준다 — 같은 비트맵(반복 주소)으로 넘어가도 확대가 100%로 돌아가게 한다 (MZ2AZ-430).
    pageKey: Any? = null,
    // 확대 줄 아래(iOS `ScenePhotoViewer.controls`) — 장소 이름과 이전·다음 버튼.
    bottom: @Composable () -> Unit = {},
    footer: @Composable () -> Unit = {},
) {
    var zoom by remember(image, pageKey) { mutableStateOf(SceneZoom()) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

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
            // iOS `ScenePhotoViewer.header` — `HStack(spacing: 12)` 에 제목 `.headline`(2줄)과 44×44 칸의 `xmark`(.headline).
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(title, color = Color.White, style = IOS.headline, maxLines = 2, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(44.dp).semantics { contentDescription = tr("닫기") }.clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    XmarkIcon(Color.White, Modifier.size(14.dp))
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
                                    contentDescription = description
                                    stateDescription = "${(zoom.scale * 100).toInt()}%"
                                }.pointerInput(image, viewport, pageKey) {
                                    detectTransformGestures { centroid, pan, scale, _ ->
                                        changeScale(
                                            zoom.scale * scale,
                                            pan.x,
                                            pan.y,
                                            centroid.x - viewport.width / 2f,
                                            centroid.y - viewport.height / 2f,
                                        )
                                    }
                                }.pointerInput(image, pageKey) { detectTapGestures(onDoubleTap = { changeScale(zoom.doubleTapScale()) }) },
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
                            modifier = Modifier.clickable(onClick = onRetry).padding(16.dp),
                        )
                    }
                } else {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            // iOS `ScenePhotoPage.zoomControls` — `HStack(spacing: 20)` 에 44×44 단추 · 최소 52 너비 퍼센트(.subheadline,
            // 고정폭 숫자) · 44×44 단추, `.padding(.horizontal, 16).padding(.bottom, 8)`. 다시 맞추는 단추는 없다 —
            // 두 번 누르기가 100%/200% 를 오간다.
            Column(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                footer()
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ZoomControl(tr("축소", "사진"), image != null && zoom.scale > 1f, { changeScale(zoom.scale / 2) }) { tint ->
                        MagnifierZoomIcon(tint, plus = false, modifier = Modifier.size(ZOOM_ICON))
                    }
                    Text(
                        "${(zoom.scale * 100).toInt()}%",
                        color = Color.White,
                        style = IOS.subheadline.copy(fontFeatureSettings = "tnum"),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(min = 52.dp),
                    )
                    ZoomControl(tr("확대", "사진"), image != null && zoom.scale < 4f, { changeScale(zoom.scale * 2) }) { tint ->
                        MagnifierZoomIcon(tint, plus = true, modifier = Modifier.size(ZOOM_ICON))
                    }
                }
                bottom()
            }
        }
    }
}

/** iOS 는 단추 기호를 본문 크기(17pt)로 그린다 — 돋보기 한 변이 약 20pt. */
private val ZOOM_ICON = 20.dp

/** 44×44 칸의 단추. 꺼지면 기호가 30% 로 흐려진다(iOS `.disabled`). */
@Composable
internal fun ZoomControl(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable (Color) -> Unit,
) {
    Box(
        Modifier.size(44.dp).semantics { contentDescription = label }.clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon(Color.White.copy(alpha = if (enabled) 1f else 0.3f)) }
}
