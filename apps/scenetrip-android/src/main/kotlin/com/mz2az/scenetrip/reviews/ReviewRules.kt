package com.mz2az.scenetrip.reviews

import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Photo
import com.mz2az.scenetrip.sceneapi.client.model.PhotoSource
import com.mz2az.scenetrip.sceneapi.client.model.RatingSummary
import com.mz2az.scenetrip.sceneapi.client.model.Review
import com.mz2az.scenetrip.sceneapi.client.model.ReviewAuthor
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

    /** 별점 줄의 세 상태. **서버가 별점을 안 실어 주면 줄 자체가 없다** — 없는 기능을 있는 척하지 않는다. */
    sealed class Line {
        object Hidden : Line()

        /** 「아직 리뷰가 없어요」 */
        object Empty : Line()

        /** 「★4.6 · 리뷰 128」 */
        data class Rated(
            val average: String,
            val count: Int,
        ) : Line()
    }

    fun line(rating: RatingSummary?): Line =
        when {
            rating == null -> Line.Hidden
            rating.count <= 0 -> Line.Empty
            else -> Line.Rated(average(rating.average), rating.count)
        }

    /** 평균을 소수 한 자리로. 리뷰가 있는데 평균이 비어 오면 「-」. */
    fun average(value: Double?): String = value?.let { "%.1f".format(java.util.Locale.US, it) } ?: "-"

    /** 「리뷰 128」. 영어는 하나일 때 「1 review」 — 번역 표의 `리뷰 %d|하나`. */
    fun countText(count: Int): String = (if (count == 1) tr("리뷰 %d", "하나") else tr("리뷰 %d")).format(count)

    /** 낭독용 별점 — 리뷰 한 건의 정수 별점은 「4」, 평균은 「4.6」. */
    fun starValue(value: Double): String = if (value == Math.rint(value)) value.toInt().toString() else average(value)

    /** 작성자 이름 — 탈퇴하면 서버가 `author` 를 비워 보낸다. */
    fun authorName(author: ReviewAuthor?): String = author?.nickname ?: tr("탈퇴한 사용자")

    /** 고친 적이 있는가 — 쓴 때와 고친 때가 다르다. 같은 초 안의 차이는 고친 것으로 치지 않는다. */
    fun edited(
        createdAt: java.time.OffsetDateTime,
        updatedAt: java.time.OffsetDateTime,
    ): Boolean =
        java.time.Duration
            .between(createdAt, updatedAt)
            .toMillis() >= 1000

    /** 점수 막대 한 줄. */
    data class Bar(
        val score: Int,
        val count: Int,
        val share: Float,
    )

    /** 5점부터 1점까지, 각 점수가 전체에서 차지하는 몫(0~1). `distribution` 은 길이 5, 첫 칸이 1점이다. 길이가 다르면 빈 막대로 둔다. */
    fun bars(distribution: List<Int>): List<Bar> {
        val counts = if (distribution.size == 5) distribution else List(5) { 0 }
        val total = maxOf(counts.sumOf { it.coerceAtLeast(0).toLong() }, 1L)
        return (5 downTo 1).map { score ->
            val count = counts[score - 1].coerceAtLeast(0)
            Bar(score, count, (count.toDouble() / total).toFloat())
        }
    }

    enum class Star { FULL, HALF, EMPTY }

    /** 별 다섯 개의 모양 — 반 개 단위로 가장 가까운 쪽. 4.6 → 넷 + 반, 4.8 → 다섯, 4.2 → 넷. */
    fun stars(value: Double): List<Star> =
        (1..5).map { position ->
            when {
                value >= position - 0.25 -> Star.FULL
                value >= position - 0.75 -> Star.HALF
                else -> Star.EMPTY
            }
        }

    /** 화면 낭독용 한 문장 — 「별점 4.6점, 리뷰 128」. */
    fun spoken(rating: RatingSummary): String {
        if (rating.count <= 0) return tr("아직 리뷰가 없어요")
        return tr("별점 %s점").format(average(rating.average)) + ", " + countText(rating.count)
    }

    /** 「방문자 사진 10」 — 수를 아직 모르면 수 없이. */
    fun visitorTitle(count: Int?): String =
        when {
            count == null -> tr("방문자 사진", "모음")
            count == 1 -> tr("방문자 사진 %d", "하나").format(count)
            else -> tr("방문자 사진 %d").format(count)
        }

    /** 격자의 머리줄 「사진 19」. 영어는 하나일 때 「1 photo」 — 번역 표의 `사진 %d|전체 하나`. */
    fun allTitle(count: Int): String = (if (count == 1) tr("사진 %d", "전체 하나") else tr("사진 %d", "전체")).format(count)

    /** 다음 쪽이 있는가. */
    fun hasMore(
        loaded: Int,
        total: Int,
    ): Boolean = loaded < total

    /** 고칠 것이 있는가 — 이미 쓴 리뷰와 별점·글이 같으면 다시 보낼 이유가 없다. */
    fun changed(
        existing: Review?,
        rating: Int,
        body: String,
    ): Boolean = existing == null || existing.rating != rating || existing.body != normalizedBody(body)

    /**
     * 저장이 「사진을 다시 올려 주세요」(`REVIEW_PHOTO_INVALID`)로 끝났는데 **사실은 저장돼 있는가** (MZ2AZ-366).
     *
     * 사진 붙은 리뷰의 PUT 은 두 번 보내면 안 된다 — 첫 요청이 임시 사진을 제자리로 옮기므로, 응답만 잃고 다시 보낸 두 번째는
     * 「그런 사진 없다」 로 거절된다. 그때 서버의 내 리뷰를 다시 읽어 방금 보낸 것과 같으면 저장된 것이다 — 별점과 글이 같고
     * **사진이 같은 순서로 같다.** 사진은 장수가 아니라 **파일 이름**으로 견준다(서버는 `uploads/tmp/<이름>` 을 `reviews/<이름>` 으로
     * 옮기므로 키의 마지막 조각이 그대로 남는다).
     */
    fun landed(
        saved: Review?,
        rating: Int,
        body: String?,
        photoKeys: List<String>,
    ): Boolean =
        saved != null && saved.rating == rating && normalizedBody(saved.body.orEmpty()) == body &&
            saved.photos.map { photoName(it.key) } == photoKeys.map(::photoName)

    /** 사진 키의 파일 이름 — 마지막 경로 조각. */
    fun photoName(key: String): String = key.split('/').lastOrNull { it.isNotEmpty() } ?: key

    fun canSave(
        rating: Int,
        body: String,
        readyPhotos: Int,
        totalPhotos: Int,
    ): Boolean =
        rating in 1..5 && (normalizedBody(body)?.length ?: 0) <= BODY_LIMIT &&
            totalPhotos in 0..PHOTO_LIMIT && readyPhotos == totalPhotos

    /** 저장할 수 있는가 — 별점은 필수(1~5), 글은 선택이되 상한을 넘지 않는다. 사진은 [ReviewPhotoRules] 가 본다. */
    fun canSave(
        rating: Int,
        body: String,
    ): Boolean = rating in 1..5 && bodyLength(body) <= BODY_LIMIT

    /** 글의 길이 — **서버가 세는 법**(UTF-16 단위)으로 센다. 글자 덩이로 세면 이모지 1,001개가 「1001/2000」으로 보이는데 서버는 거절한다. */
    fun bodyLength(raw: String): Int = normalizedBody(raw)?.length ?: 0

    /** 리뷰 저장·삭제가 실패했을 때 보일 한 줄 — 서버 문장이 아니라 code 로 가른다(MZ2AZ-345). iOS `ReviewComposeView.failureText`. */
    fun failureText(
        status: Int?,
        code: String?,
    ): String {
        // 세션이 풀렸다 — 로그인 화면이 위로 올라온다. 쓰던 글은 그대로 둔다. 로그인 상태 값이 아니라 code 로 가른다.
        if (status != null && com.mz2az.scenetrip.auth.AuthRules
                .action(status, code) == com.mz2az.scenetrip.auth.AuthAction.SIGN_OUT
        ) {
            return tr("로그인이 풀렸어요. 다시 로그인해 주세요")
        }
        return when {
            status == null -> tr("저장하지 못했어요. 잠시 뒤 다시 해 주세요")

            code == "SIGN_IN_REQUIRED" -> tr("로그인한 뒤에 쓸 수 있어요")

            code == "REVIEW_PHOTO_INVALID" -> tr("사진을 다시 올려 주세요")

            code == "RATE_LIMITED" -> tr("요청이 많아요. 잠시 뒤 다시 시도해 주세요")

            // 금칙어(MZ2AZ-461) — 어떤 말인지는 서버가 알려 주지 않는다.
            com.mz2az.scenetrip.data.ModerationRules.isTextNotAllowed(
                code,
            ) -> com.mz2az.scenetrip.data.ModerationRules.textNotAllowedMessage

            else -> tr("저장하지 못했어요. 잠시 뒤 다시 해 주세요")
        }
    }

    /** 닉네임 저장이 실패했을 때 보일 한 줄 — 서버 문장이 아니라 code 로 가른다(MZ2AZ-345). iOS `NicknameView.save` 의 `NicknameOutcome`. */
    fun nicknameFailureText(code: String?): String =
        when {
            code == "NICKNAME_INVALID" -> tr("쓸 수 없는 닉네임이에요. 2~16자, 한글·영문·숫자·밑줄(_)만 쓸 수 있어요")

            code == "NICKNAME_TAKEN" -> tr("이미 쓰는 닉네임입니다")

            com.mz2az.scenetrip.data.ModerationRules.isTextNotAllowed(
                code,
            ) -> com.mz2az.scenetrip.data.ModerationRules.textNotAllowedMessage

            else -> tr("저장하지 못했어요. 잠시 뒤 다시 해 주세요")
        }

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

    /** 사진 키의 파일 이름 열이 같은가 — 서버가 임시 키를 옮겨도 마지막 조각은 그대로다. [landed] 가 같은 규칙으로 견준다. */
    fun samePhotoFiles(
        left: List<String>,
        right: List<String>,
    ): Boolean = left.map(::photoName) == right.map(::photoName)
}
