package com.mz2az.scenetrip.reviews

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.sceneapi.client.model.ReviewPhoto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.util.UUID

data class DraftPhoto(
    val id: UUID = UUID.randomUUID(),
    val uri: Uri? = null,
    val key: String? = null,
    val url: String? = null,
    val uploading: Boolean = false,
    val problem: String? = null,
)

/** 장마다 차례로 줄이고 올린다. 뺀 칸의 늦은 응답은 다시 붙이지 않는다. */
class ReviewDraft(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    var photos by mutableStateOf<List<DraftPhoto>>(emptyList())
        private set
    private var worker: Job? = null
    private var paused = false

    val ready: Int get() = photos.count { it.key != null }
    val remaining: Int get() = (ReviewRules.PHOTO_LIMIT - photos.size).coerceAtLeast(0)
    val keys: List<String> get() = photos.mapNotNull { it.key }

    fun load(existing: List<ReviewPhoto>) {
        photos = existing.map { DraftPhoto(key = it.key, url = it.url.toString()) }
    }

    fun add(uris: List<Uri>) {
        photos = photos + uris.take(remaining).map { DraftPhoto(uri = it) }
        start()
    }

    fun remove(id: UUID) {
        photos = photos.filterNot { it.id == id }
        paused = false
        start()
    }

    fun retry(id: UUID) {
        photos = photos.map { if (it.id == id && it.uri != null) it.copy(key = null, problem = null) else it }
        paused = false
        start()
    }

    fun restoreInvalidPhotos() {
        photos = photos.map { if (it.uri != null) it.copy(key = null, uploading = false, problem = null) else it }
        paused = false
        start()
    }

    private fun update(
        id: UUID,
        transform: (DraftPhoto) -> DraftPhoto,
    ) {
        photos = photos.map { if (it.id == id) transform(it) else it }
    }

    private fun start() {
        if (worker?.isActive == true || paused) return
        worker =
            scope.launch {
                while (!paused) {
                    val next = photos.firstOrNull { it.uri != null && it.key == null && it.problem == null } ?: break
                    update(next.id) { it.copy(uploading = true) }
                    val result = apiResult { PhotoUploader.upload(context, next.uri!!) }
                    ensureActive()
                    result.fold(
                        onSuccess = { key -> update(next.id) { it.copy(key = key, uploading = false) } },
                        onFailure = { error ->
                            val failure = NetworkFailure.of(error)
                            update(next.id) {
                                it.copy(
                                    uploading = false,
                                    problem =
                                        if (failure.status ==
                                            null
                                        ) {
                                            error.message?.takeIf { value -> value in listOf("사진을 읽을 수 없어요", "사진이 너무 커요") }
                                                ?: failure.message
                                        } else {
                                            failure.message
                                        },
                                )
                            }
                            val localFailure = error.message in listOf("사진을 읽을 수 없어요", "사진이 너무 커요")
                            paused =
                                photos.any { it.id == next.id } && !localFailure &&
                                failure.code !in listOf("UPLOAD_TOO_LARGE", "UPLOAD_TYPE_UNSUPPORTED")
                        },
                    )
                }
            }
    }
}
