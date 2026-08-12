package com.mz2az.scenetrip.searchtab

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

/**
 * 서버가 준 URL 로 이미지를 그린다. 실패하면 자리표시자로 떨어진다.
 *
 * iOS `RemoteImage.swift` 와 같은 자리다. 그쪽 주석이 이유를 적어 뒀다 — **이미지
 * 주소는 우리 것이 아니다.** TMDB 포스터, 구글·위키미디어 장소 사진처럼 수집 시점의
 * 외부 URL 이 그대로 온다. 그래서 **깨지는 것을 정상 경로로 다룬다.**
 *
 * ## 왜 라이브러리를 쓰지 않는가
 *
 * iOS 는 SwiftUI 내장 `AsyncImage` 를 쓴다. Compose 에는 그것이 없고, 보통은 Coil 을
 * 넣는다. **새 의존성을 더하는 것은 팀이 정할 일이라(CLAUDE.md §9) 지금은 넣지
 * 않았다.** 대신 필요한 만큼만 여기 둔다 — 받아서 그리고, 실패하면 자리표시자.
 *
 * 캐시는 프로세스 안 맵 하나뿐이다. 목록이 길어져 스크롤이 버벅이면 그때 Coil 을
 * 다시 검토한다. 지금 데이터로는 장소 155 개이고 화면에 한 번에 예닐곱 줄이다.
 */
object ImageCache {
    private val memory = mutableMapOf<String, ImageBitmap?>()

    suspend fun load(url: String): ImageBitmap? {
        memory[url]?.let { return it }
        if (memory.containsKey(url)) return null
        val bitmap =
            withContext(Dispatchers.IO) {
                runCatching {
                    URL(url).openStream().use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
                }.getOrNull()
            }
        memory[url] = bitmap
        return bitmap
    }
}

@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        if (url != null) {
            bitmap = ImageCache.load(url)
        }
    }

    Box(modifier = modifier.background(IOS.systemGray6)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                // 프레임을 채우고 넘치는 부분은 자른다 — iOS 의 `scaledToFill` + clip.
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
