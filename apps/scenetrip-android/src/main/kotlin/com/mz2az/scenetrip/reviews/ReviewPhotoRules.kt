package com.mz2az.scenetrip.reviews

import com.mz2az.scenetrip.data.tr

/**
 * 사진 한 장을 올리지 못한 이유 (MZ2AZ-363·476, iOS `PhotoUploadFailure`). 서버 문장이 아니라 `code` 로 가른다(MZ2AZ-345).
 */
enum class PhotoUploadFailure {
    /** 고른 파일을 그림으로 읽지 못했다(깨진 파일, 클라우드에서 못 받음). */
    UNREADABLE,

    /** 줄여도 10 MB 를 넘는다 — `UPLOAD_TOO_LARGE`. */
    TOO_LARGE,

    /** `UPLOAD_TYPE_UNSUPPORTED`. 앱은 늘 JPEG 로 올리므로 보통은 볼 일이 없다. */
    UNSUPPORTED_TYPE,

    /** `429 RATE_LIMITED` — 분당 요청 상한. */
    RATE_LIMITED,

    /** `503 UPLOAD_UNAVAILABLE` — 이 서버에 사진 저장소가 없다. 사진 없이는 쓸 수 있다. */
    UNAVAILABLE,

    /** 세션이 풀렸다 — 로그인 화면이 위로 올라온다. */
    SIGNED_OUT,

    /** 리뷰 저장 때 서버가 이 키를 거절했다(`REVIEW_PHOTO_INVALID`) — 올린 지 하루가 지났거나 파일이 없다. */
    EXPIRED,

    /** 저장소가 PUT 을 거절했다(서명 만료·크기 불일치 등). */
    STORAGE,

    /** 네트워크, 그 밖의 서버 오류. */
    NETWORK,
}

/** 사진 올리기의 순수 규칙 — 오류 가르기와 문구. iOS `PhotoUploadRules`. */
object PhotoUploadRules {
    /** `POST /uploads` 가 실패한 이유. */
    fun failure(
        status: Int?,
        code: String?,
    ): PhotoUploadFailure {
        when (code) {
            "UPLOAD_TOO_LARGE" -> return PhotoUploadFailure.TOO_LARGE
            "UPLOAD_TYPE_UNSUPPORTED" -> return PhotoUploadFailure.UNSUPPORTED_TYPE
            "UPLOAD_UNAVAILABLE" -> return PhotoUploadFailure.UNAVAILABLE
            "RATE_LIMITED" -> return PhotoUploadFailure.RATE_LIMITED
        }
        return when (status) {
            401 -> PhotoUploadFailure.SIGNED_OUT
            429 -> PhotoUploadFailure.RATE_LIMITED
            else -> PhotoUploadFailure.NETWORK
        }
    }

    /** 같은 사진으로 다시 해 볼 만한가. 너무 크거나 못 읽는 사진은 다시 해도 같다 — 빼는 길만 준다. */
    fun retryable(failure: PhotoUploadFailure): Boolean =
        when (failure) {
            PhotoUploadFailure.UNREADABLE, PhotoUploadFailure.TOO_LARGE, PhotoUploadFailure.UNSUPPORTED_TYPE -> false
            else -> true
        }

    /**
     * 이 실패 뒤에 **줄 선 사진을 멈추는가.** 한도·저장소 없음·네트워크·세션은 다음 사진도 똑같이 실패한다 — 연달아 실패시키지 않고
     * 멈춰 두었다가 「다시 시도」 에서 잇는다. 사진 자체의 문제는 그 칸만의 일이다.
     */
    fun stopsQueue(failure: PhotoUploadFailure): Boolean =
        when (failure) {
            PhotoUploadFailure.RATE_LIMITED,
            PhotoUploadFailure.UNAVAILABLE,
            PhotoUploadFailure.SIGNED_OUT,
            PhotoUploadFailure.STORAGE,
            PhotoUploadFailure.NETWORK,
            -> true

            else -> false
        }

    /** 사용자에게 보일 한 줄. iOS 와 같은 문구다. */
    fun text(failure: PhotoUploadFailure): String =
        when (failure) {
            PhotoUploadFailure.UNREADABLE -> tr("사진을 읽지 못했어요. 다른 사진을 골라 주세요")
            PhotoUploadFailure.TOO_LARGE -> tr("사진이 너무 커요(10MB 까지). 다른 사진을 골라 주세요")
            PhotoUploadFailure.UNSUPPORTED_TYPE -> tr("올릴 수 없는 사진 형식이에요")
            PhotoUploadFailure.RATE_LIMITED -> tr("요청이 많아요. 잠시 뒤 다시 시도해 주세요")
            PhotoUploadFailure.UNAVAILABLE -> tr("지금은 사진을 올릴 수 없어요. 사진을 빼면 저장할 수 있어요")
            PhotoUploadFailure.SIGNED_OUT -> tr("로그인이 풀렸어요. 다시 로그인해 주세요")
            PhotoUploadFailure.EXPIRED -> tr("사진을 다시 올려 주세요")
            PhotoUploadFailure.STORAGE, PhotoUploadFailure.NETWORK -> tr("사진을 올리지 못했어요. 다시 시도해 주세요")
        }
}

/** 업로드를 마치지 못한 이유를 들고 올라가는 예외 — 사진 칸이 이것으로 문구와 재시도 가능 여부를 가른다. */
class PhotoUploadException(
    val failure: PhotoUploadFailure,
    cause: Throwable? = null,
) : java.io.IOException(failure.name, cause)

/** 리뷰 사진 한 칸의 상태 (iOS `ReviewPhotoState`). */
sealed class ReviewPhotoState {
    /** 이미 리뷰에 붙어 있던 사진 — 남기려면 이 키를 그대로 다시 보낸다. */
    data class Attached(
        val key: String,
    ) : ReviewPhotoState()

    /** 고르기만 했다. 앞 사진이 올라가기를 기다린다(한 번에 한 장씩 올린다). */
    object Waiting : ReviewPhotoState()

    object Uploading : ReviewPhotoState()

    /** 저장소에 올라갔다 — 리뷰를 저장하면 이 키로 붙는다. */
    data class Uploaded(
        val key: String,
    ) : ReviewPhotoState()

    data class Failed(
        val failure: PhotoUploadFailure,
    ) : ReviewPhotoState()
}

/** 리뷰 사진의 순수 규칙 (iOS `ReviewPhotoRules`). 화면 없이 시험한다. */
object ReviewPhotoRules {
    /** 리뷰 한 개에 붙는 사진의 상한(계약 `ReviewInput.photoKeys.maxItems`). */
    const val LIMIT = 10

    /** 더 고를 수 있는 장수. 올리는 중이거나 실패한 칸도 자리를 차지한다. */
    fun remaining(
        count: Int,
        limit: Int = LIMIT,
    ): Int = (limit - count).coerceAtLeast(0)

    /** 서버에 보낼 `photoKeys` — **화면의 순서 그대로**, 붙어 있던 사진과 새로 올라간 사진만. 여기 없는 사진은 리뷰에서 떨어진다. */
    fun keys(
        states: List<ReviewPhotoState>,
        limit: Int = LIMIT,
    ): List<String> =
        states
            .mapNotNull { state ->
                when (state) {
                    is ReviewPhotoState.Attached -> state.key
                    is ReviewPhotoState.Uploaded -> state.key
                    else -> null
                }
            }.take(limit)

    /** 올리는 중인 사진이 있는가 — 있으면 저장을 막는다(끝나기 전에 저장하면 그 사진이 빠진다). */
    fun busy(states: List<ReviewPhotoState>): Boolean = states.any { it == ReviewPhotoState.Waiting || it == ReviewPhotoState.Uploading }

    /** 못 올린 사진의 수. 있으면 저장을 막는다 — 다시 시도하거나 빼야 한다. 말없이 빼고 저장하지 않는다. */
    fun failedCount(states: List<ReviewPhotoState>): Int = states.count { it is ReviewPhotoState.Failed }

    /** 사진 쪽에서 저장을 막을 이유가 없는가. */
    fun canSave(
        states: List<ReviewPhotoState>,
        limit: Int = LIMIT,
    ): Boolean = !busy(states) && failedCount(states) == 0 && states.size <= limit

    /** 사진을 건드렸는가 — 새로 고른 칸은 올리는 중이든 실패했든 건드린 것이다. 붙어 있던 사진은 빼거나 순서가 달라졌을 때. */
    fun changed(
        existing: List<String>,
        states: List<ReviewPhotoState>,
    ): Boolean {
        val untouched = states.all { it is ReviewPhotoState.Attached }
        return !untouched || keys(states) != existing
    }

    /**
     * 멈춰 선 줄을 다시 돌릴 것인가 — 기다리는 사진이 있고, 줄을 멈추게 한 실패(한도·네트워크 등)가 더는 없을 때. 그 실패 칸을
     * 「다시 시도」 하거나 빼면 그렇게 된다. 빼기만 하고 남은 사진이 영영 기다리지 않게 한다.
     */
    fun shouldResume(states: List<ReviewPhotoState>): Boolean {
        val blocked = states.any { it is ReviewPhotoState.Failed && PhotoUploadRules.stopsQueue(it.failure) }
        return states.any { it == ReviewPhotoState.Waiting } && !blocked
    }

    /**
     * 「사진 올리는 중 2/3」 의 두 수 — **이번에 고른 묶음** 가운데 지금 올리는 것이 몇 번째이고 모두 몇 장인지. 묶음의 상태만
     * 받는다(앞서 끝난 고르기와 붙어 있던 사진은 부르는 쪽이 뺀다). 실패도 끝난 것으로 센다.
     */
    fun progress(batch: List<ReviewPhotoState>): Pair<Int, Int> {
        val done = batch.count { it != ReviewPhotoState.Waiting && it != ReviewPhotoState.Uploading }
        return minOf(done + 1, batch.size) to batch.size
    }
}
