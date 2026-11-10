package com.mz2az.scenetrip.searchtab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.ImageRequests
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * 서버가 준 URL 로 이미지를 그린다. 실패하면 자리표시자로 떨어진다.
 *
 * iOS `RemoteImage.swift` 와 같은 자리다. 그쪽 주석이 이유를 적어 뒀다 — **이미지
 * 주소는 우리 것이 아니다.** 작품 제공 플랫폼의 공식 포스터와 장소 사진은 수집 시점의
 * 외부 URL이 그대로 온다. 그래서 **깨지는 것을 정상 경로로 다룬다.**
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
    private val client by lazy { ImageRequests.client(API_BASE) }
    private const val MEMORY_BYTES = 80 * 1024 * 1024
    private const val MAX_DOWNLOAD_BYTES = 24 * 1024 * 1024
    private val memory =
        object : LruCache<String, Bitmap>(MEMORY_BYTES) {
            override fun sizeOf(
                key: String,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }

    /**
     * 받아서 줄여 푼다. [diskDir] 를 주면(작품 포스터만, MZ2AZ-507) 원본을 그 폴더에 30일 보관하고 100MB 를 넘으면 오래 안 쓴 것부터 지운다
     * (`PosterDiskRules`). 포스터는 방송사·OTT 서버에 있어 우리가 줄일 수 없다 — 우리 서버 사진은 서버 쪽에서 줄인다(사용자 결정).
     */
    suspend fun load(
        url: String,
        key: String = url,
        edge: Int = 360,
        diskDir: File? = null,
    ): ImageBitmap? {
        val cacheKey = "$key:$edge"
        memory.get(cacheKey)?.let { return it.asImageBitmap() }
        val bitmap =
            withContext(Dispatchers.IO) {
                runCatching {
                    val bytes =
                        diskDir?.let { PosterDisk.read(it, url) }
                            ?: download(url).also { bytes -> diskDir?.let { PosterDisk.write(it, url, bytes) } }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                }.getOrNull()
            }
        if (bitmap != null) memory.put(cacheKey, bitmap)
        return bitmap?.asImageBitmap()
    }

    private fun download(url: String): ByteArray =
        client.newCall(ImageRequests.request(url)).execute().use { response ->
            require(response.isSuccessful)
            val stream = requireNotNull(response.body).byteStream()
            stream.use {
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var count = stream.read(buffer)
                while (count != -1) {
                    require(out.size() + count <= MAX_DOWNLOAD_BYTES)
                    out.write(buffer, 0, count)
                    count = stream.read(buffer)
                }
                out.toByteArray()
            }
        }
}

/**
 * 작품 포스터의 기기 보관(MZ2AZ-507). 파일 앞 8바이트에 저장한 때(밀리초)를 적고 뒤에 원본을 둔다 — 안드로이드 파일에는 만든 때가 없다.
 * 「마지막으로 쓴 때」 는 파일의 수정 시각으로 둔다(읽을 때 지금으로).
 */
object PosterDisk {
    private const val HEADER = 8

    fun read(
        dir: File,
        url: String,
        now: Long = System.currentTimeMillis(),
    ): ByteArray? {
        val file = File(dir, PosterDiskRules.fileName(url))
        val raw = runCatching { file.readBytes() }.getOrNull() ?: return null
        if (raw.size <= HEADER) return null
        val savedAt = ByteBuffer.wrap(raw, 0, HEADER).long
        if (PosterDiskRules.expired(savedAt, now)) {
            file.delete()
            return null
        }
        file.setLastModified(now)
        return raw.copyOfRange(HEADER, raw.size)
    }

    fun write(
        dir: File,
        url: String,
        bytes: ByteArray,
        now: Long = System.currentTimeMillis(),
    ) {
        runCatching {
            dir.mkdirs()
            val file = File(dir, PosterDiskRules.fileName(url))
            val temp = File(dir, "${file.name}.tmp")
            temp.writeBytes(ByteBuffer.allocate(HEADER).putLong(now).array() + bytes)
            temp.renameTo(file)
            val entries =
                dir
                    .listFiles()
                    .orEmpty()
                    .filter { it.isFile }
                    .map { PosterDiskEntry(it.name, it.length(), it.lastModified()) }
            PosterDiskRules.evictions(entries, PosterDiskRules.MAX_BYTES).forEach { File(dir, it).delete() }
        }
    }
}

@Composable
fun RemoteImage(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    cacheKey: String? = url,
    edge: Int = 360,
    retryable: Boolean = false,
    onFailure: () -> Unit = {},
    onRetry: () -> Unit = {},
    placeholder: (@Composable () -> Unit)? = null,
    backgroundColor: Color = IOS.systemGray6,
    // 작품 포스터면 기기에 30일 보관한다(MZ2AZ-507). 다른 사진은 메모리만.
    keepOnDisk: Boolean = false,
) {
    val diskDir = if (keepOnDisk) File(LocalContext.current.cacheDir, "posters") else null
    var bitmap by remember(cacheKey, edge) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(cacheKey) { mutableStateOf(false) }
    var retry by remember(cacheKey) { mutableStateOf(0) }

    LaunchedEffect(url, edge, retry) {
        if (url != null) {
            failed = false
            bitmap = ImageCache.load(url, cacheKey ?: url, edge, diskDir)
            failed = bitmap == null
            if (failed) onFailure()
        }
    }

    Box(modifier = modifier.background(backgroundColor)) {
        if (bitmap == null && placeholder != null) {
            Box(Modifier.fillMaxSize().background(IOS.systemGray5), contentAlignment = Alignment.Center) { placeholder() }
        }
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                // 프레임을 채우고 넘치는 부분은 자른다 — iOS 의 `scaledToFill` + clip.
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (failed && retryable) {
            Text(
                tr("다시 시도"),
                color = IOS.accent,
                modifier =
                    Modifier.align(Alignment.Center).clickable {
                        retry += 1
                        onRetry()
                    },
            )
        }
    }
}
