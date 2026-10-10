package com.mz2az.scenetrip.reviews

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr

/** 업로드 전에도 고른 사진을 확인한다. 메타데이터 없는 축소본만 메모리에 둔다. */
@Composable
fun LocalPhoto(
    uri: Uri,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap =
            apiResult {
                val bytes = PhotoUploader.shrink(context, uri)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap()
            }.getOrNull()
    }
    bitmap?.let { Image(it, tr("고른 사진"), modifier, contentScale = ContentScale.Crop) }
}
