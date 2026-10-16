package com.mz2az.scenetrip.reviews

import com.mz2az.scenetrip.sceneapi.client.model.Photo
import com.mz2az.scenetrip.sceneapi.client.model.PhotoSource
import java.net.URI
import java.text.Normalizer

/** 계약과 iOS가 함께 정한 쓰기·닉네임·사진 식별 규칙. Android 프레임워크에 의존하지 않는다. */
object ReviewRules {
    const val BODY_LIMIT = 2000
    const val PHOTO_LIMIT = 10
    const val NICKNAME_MAX_LENGTH = 16

    fun preservesPhotoFrame(photo: Photo): Boolean {
        if (photo.source == PhotoSource.review) return false
        val url = photo.url
        val tour =
            url.scheme.equals("https", true) && url.host.equals("tong.visitkorea.or.kr", true) &&
                url.port == -1 && url.userInfo == null && url.rawQuery == null && url.rawFragment == null
        return tour || photo.credit?.contains("공공누리 제3유형") == true
    }

    /**
     * 방문자 사진을 뺀 목록 — 차단·풀기가 일어난 직후(MZ2AZ-461) 다시 받기 전까지 보이는 목록이다. 다시 받기가 실패해도 차단한
     * 사람의 사진이 남지 않게 먼저 비운다. 우리 사진은 둔다. iOS `PhotoGalleryRules.Book.withoutReviewPhotos`.
     */
    fun withoutReviewPhotos(photos: List<Photo>): List<Photo> = photos.filter { it.source != PhotoSource.review }

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
        if (value.length > NICKNAME_MAX_LENGTH) return "16자 이하로 정해 주세요"
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
