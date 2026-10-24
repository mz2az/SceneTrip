package com.mz2az.scenetrip.reviews

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.sceneapi.client.model.ReviewPhoto
import com.mz2az.scenetrip.sceneapi.client.model.UploadPurpose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 쓰는 중인 사진 한 칸. 상태는 [ReviewPhotoState] 가 정하고 나머지는 거기서 읽는다. */
data class DraftPhoto(
    val id: UUID = UUID.randomUUID(),
    val uri: Uri? = null,
    val url: String? = null,
    val state: ReviewPhotoState = ReviewPhotoState.Waiting,
    /** 몇 번째 고르기에 든 사진인가 — 진행 수(「2/3」)를 그 묶음으로만 센다. */
    val batch: Int = 0,
) {
    /** 저장할 때 보낼 키 — 붙어 있던 사진이거나 올라간 사진만 가진다. */
    val key: String?
        get() =
            when (state) {
                is ReviewPhotoState.Attached -> state.key
                is ReviewPhotoState.Uploaded -> state.key
                else -> null
            }

    val uploading: Boolean get() = state == ReviewPhotoState.Uploading
    val failure: PhotoUploadFailure? get() = (state as? ReviewPhotoState.Failed)?.failure

    /** 못 올린 이유의 한 줄. */
    val problem: String? get() = failure?.let(PhotoUploadRules::text)
}

/**
 * 쓰는 중인 리뷰·여행후기의 사진 줄 (iOS `ReviewPhotoDraft`) — 붙어 있던 사진과 새로 고른 사진을 화면 순서대로 쥔다.
 *
 * **고르는 즉시 올린다.** 저장을 눌렀을 때 열 장을 기다리지 않게 하고, 서명된 주소가 10분짜리라 받아 둔 채 묵히지 않는다. **한 번에 한
 * 장씩** 올린다 — 열 장을 한꺼번에 줄이면 메모리가 튀고 분당 요청 한도에도 닿는다.
 *
 * **한 장이 한도·네트워크처럼 다음 사진도 겪을 이유로 실패하면 줄을 멈춘다**([PhotoUploadRules.stopsQueue]) — 남은 사진은 기다리는
 * 채로 두고, 실패한 칸을 「다시 시도」 하거나 빼면 잇는다. 줄여 둔 JPEG 는 칸에 보관해 다시 시도할 때 또 읽고 줄이지 않는다.
 */
class ReviewDraft(
    private val context: Context,
    private val scope: CoroutineScope,
    private val purpose: UploadPurpose = UploadPurpose.review,
    val limit: Int = ReviewPhotoRules.LIMIT,
) {
    var photos by mutableStateOf<List<DraftPhoto>>(emptyList())
        private set

    /** 올리는 줄이 돌고 있는가. 기다리는 사진이 있는데 돌지 않으면 멈춰 선 것이다. */
    var pumping by mutableStateOf(false)
        private set

    private var worker: Job? = null
    private var batch = 0

    /** 열 때 리뷰에 붙어 있던 키 — 바뀌었는지 볼 기준. */
    private var existing: List<String> = emptyList()

    /** 줄여 둔 JPEG. 올라가면 버린다. */
    private val prepared = HashMap<UUID, ByteArray>()

    val states: List<ReviewPhotoState> get() = photos.map { it.state }
    val ready: Int get() = photos.count { it.key != null }
    val remaining: Int get() = ReviewPhotoRules.remaining(photos.size, limit)

    /** 서버에 보낼 `photoKeys` — 화면 순서 그대로. */
    val keys: List<String> get() = ReviewPhotoRules.keys(states, limit)

    /** 사진을 건드렸는가 — 저장 단추와 「쓰던 리뷰를 버릴까요?」 가 본다. */
    val changed: Boolean get() = ReviewPhotoRules.changed(existing, states)

    val canSave: Boolean get() = photos.size <= limit && ReviewPhotoRules.canSave(states, limit)

    /** 줄이 멈춰 기다리기만 하는 사진의 수 — 앞 사진이 실패해 멈췄다. 돌고 있으면 0. */
    val stalled: Int get() = if (pumping) 0 else states.count { it == ReviewPhotoState.Waiting }

    /** 이번에 고른 묶음의 진행 — 「사진 올리는 중 2/3」. */
    val progress: Pair<Int, Int> get() = ReviewPhotoRules.progress(photos.filter { it.batch == batch }.map { it.state })

    /** 고치는 화면을 열었다 — 붙어 있던 사진으로 채운다. */
    fun load(photosOnReview: List<ReviewPhoto>) {
        existing = photosOnReview.map { it.key }
        photos = photosOnReview.map { DraftPhoto(url = it.url.toString(), state = ReviewPhotoState.Attached(it.key)) }
    }

    /** 보관함에서 골랐다. 남은 자리만큼만 받는다. */
    fun add(uris: List<Uri>) {
        val accepted = uris.take(remaining)
        if (accepted.isEmpty()) return
        val joined = joinBatch()
        photos = photos + accepted.map { DraftPhoto(uri = it, batch = joined) }
        start()
    }

    /** 뺀다. 올리는 중이면 결과를 버린다. 줄을 멈추게 한 칸을 뺐으면 남은 사진을 잇는다. */
    fun remove(id: UUID) {
        photos = photos.filterNot { it.id == id }
        prepared.remove(id)
        if (ReviewPhotoRules.shouldResume(states)) start()
    }

    /** 실패한 칸을 다시 올린다. 다시 해도 같은 실패(너무 큼·못 읽음)는 단추가 없다. */
    fun retry(id: UUID) {
        val slot = photos.firstOrNull { it.id == id } ?: return
        val failure = slot.failure ?: return
        if (!PhotoUploadRules.retryable(failure)) return
        val joined = joinBatch()
        update(id) { it.copy(state = ReviewPhotoState.Waiting, batch = joined) }
        start()
    }

    /**
     * 저장 때 서버가 사진 키를 거절했다(`REVIEW_PHOTO_INVALID`) — 이번에 올린 것들을 「다시 시도」 로 돌려놓는다. 붙어 있던 사진은
     * 건드리지 않는다 (iOS `rejectUploaded`).
     */
    fun restoreInvalidPhotos() {
        photos =
            photos.map {
                if (it.state is ReviewPhotoState.Uploaded) it.copy(state = ReviewPhotoState.Failed(PhotoUploadFailure.EXPIRED)) else it
            }
    }

    /** 쓰기 화면이 사라졌다 — 올리던 것을 그만둔다. 이미 올라간 키는 그대로다(서버에 있다). */
    fun cancel() {
        worker?.cancel()
        worker = null
        pumping = false
        photos = photos.map { if (it.state == ReviewPhotoState.Uploading) it.copy(state = ReviewPhotoState.Waiting) else it }
    }

    /** 그만뒀던 줄을 잇는다 — 줄을 멈추게 한 실패가 없을 때만. */
    fun resume() {
        if (ReviewPhotoRules.shouldResume(states)) start()
    }

    /** 새 묶음인가 — 줄이 비어 있을 때 고르면 새 묶음, 도는 중에 더 고르면 같은 묶음에 얹는다. */
    private fun joinBatch(): Int {
        if (!ReviewPhotoRules.busy(states)) batch += 1
        return batch
    }

    private fun update(
        id: UUID,
        transform: (DraftPhoto) -> DraftPhoto,
    ) {
        photos = photos.map { if (it.id == id) transform(it) else it }
    }

    private fun start() {
        if (worker?.isActive == true) return
        pumping = true
        worker =
            scope.launch {
                try {
                    drain()
                } finally {
                    pumping = false
                }
            }
    }

    /** 기다리는 칸을 앞에서부터 하나씩 올린다. */
    private suspend fun drain() {
        while (true) {
            val next = photos.firstOrNull { it.state == ReviewPhotoState.Waiting } ?: return
            update(next.id) { it.copy(state = ReviewPhotoState.Uploading) }
            val result = upload(next)
            currentCoroutineContext().ensureActive()
            // 올리는 사이 뺐으면 칸이 없다 — 결과를 버린다(그 실패로 줄을 멈추지도 않는다).
            if (photos.none { it.id == next.id }) continue
            result.fold(
                onSuccess = { key ->
                    prepared.remove(next.id)
                    update(next.id) { it.copy(state = ReviewPhotoState.Uploaded(key)) }
                },
                onFailure = { error ->
                    val failure = failureOf(error)
                    update(next.id) { it.copy(state = ReviewPhotoState.Failed(failure)) }
                    // 다음 사진도 같은 이유로 실패한다 — 연달아 실패시키지 않고 멈춘다.
                    if (PhotoUploadRules.stopsQueue(failure)) return
                },
            )
        }
    }

    private suspend fun upload(slot: DraftPhoto): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = prepared[slot.id] ?: PhotoUploader.shrink(context, requireNotNull(slot.uri)).also { prepared[slot.id] = it }
                PhotoUploader.upload(bytes, purpose)
            }
        }

    private fun failureOf(error: Throwable): PhotoUploadFailure =
        when (error) {
            is PhotoUploadException -> error.failure
            else -> NetworkFailure.of(error).let { PhotoUploadRules.failure(it.status, it.code) }
        }
}
