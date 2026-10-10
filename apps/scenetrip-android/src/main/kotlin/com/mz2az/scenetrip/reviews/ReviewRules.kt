package com.mz2az.scenetrip.reviews

import java.net.URI
import java.text.Normalizer

/** 계약과 iOS가 함께 정한 쓰기·닉네임·사진 식별 규칙. Android 프레임워크에 의존하지 않는다. */
object ReviewRules {
    const val BODY_LIMIT = 2000
    const val PHOTO_LIMIT = 10

    fun ratingFraction(
        distribution: List<Int>,
        score: Int,
    ): Float {
        val total = distribution.sumOf { it.coerceAtLeast(0).toLong() }
        if (total == 0L) return 0f
        return ((distribution.getOrNull(score - 1) ?: 0).coerceAtLeast(0).toDouble() / total).toFloat()
    }

    fun normalizedBody(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() }

    fun canSave(
        rating: Int,
        body: String,
        readyPhotos: Int,
        totalPhotos: Int,
    ): Boolean =
        rating in 1..5 && (normalizedBody(body)?.length ?: 0) <= BODY_LIMIT &&
            totalPhotos in 0..PHOTO_LIMIT && readyPhotos == totalPhotos

    fun nickname(raw: String): String = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)

    fun nicknameProblem(raw: String): String? {
        val value = nickname(raw)
        if (!value.matches(Regex("[a-zA-Z0-9_가-힣ㄱ-ㆎ]*")) || value.contains('\u3164')) return "한글·영문·숫자·밑줄(_)만 쓸 수 있어요"
        if (value.length < 2) return "2자 이상으로 정해 주세요"
        if (value.length > 16) return "16자 이하로 정해 주세요"
        if (automaticNickname(value)) return "「여행자 + 숫자」 는 자동으로 붙는 이름이라 고를 수 없어요"
        return null
    }

    fun automaticNickname(raw: String): Boolean = nickname(raw).matches(Regex("여행자[0-9]+"))

    fun photoKey(
        url: String,
        reviewId: Long?,
    ): String {
        if (reviewId == null) return url
        val path = runCatching { URI(url).rawPath }.getOrNull() ?: url.substringBefore('?')
        return "$reviewId:$path"
    }

    fun samePhotoFiles(
        left: List<String>,
        right: List<String>,
    ): Boolean = left.map { it.substringAfterLast('/') } == right.map { it.substringAfterLast('/') }
}
