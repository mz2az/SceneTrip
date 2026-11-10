package com.mz2az.scenetrip.searchtab

import java.security.MessageDigest

/**
 * 작품 포스터의 기기 보관 규칙 (MZ2AZ-507, 계획 docs/project/plans/poster-disk-cache.md).
 *
 * 포스터는 방송사·OTT 서버에 있어 우리가 줄일 수 없다 — 앱이 받아 둔다. 우리 서버 사진은 여기 쓰지 않는다.
 */
data class PosterDiskEntry(
    val name: String,
    val bytes: Long,
    /** 마지막으로 쓴 때(밀리초). */
    val lastUsedMillis: Long,
)

object PosterDiskRules {
    /** 보관 기간(밀리초) — 저장한 지 이만큼 지나면 다시 받는다. */
    const val MAX_AGE_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    /** 보관 합계 상한(바이트). */
    const val MAX_BYTES: Long = 100L * 1024 * 1024

    /** 화면에 그릴 때 줄이는 긴 변(px). */
    const val EDGE: Int = 360

    /** 저장할 응답의 상한(바이트) — 이보다 크면 그리기만 하고 저장하지 않는다. */
    const val MAX_DOWNLOAD_BYTES: Int = 24 * 1024 * 1024

    /** 파일 이름 — 포스터 주소의 SHA-256 16진수(소문자 64자). */
    fun fileName(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    /** 저장한 지 [MAX_AGE_MILLIS] 를 넘었나. */
    fun expired(
        savedAtMillis: Long,
        nowMillis: Long,
    ): Boolean = nowMillis - savedAtMillis > MAX_AGE_MILLIS

    /** 합계가 [maxBytes] 이하가 될 때까지 지울 이름들 — 마지막으로 쓴 때가 오래된 것부터, 같으면 이름순. */
    fun evictions(
        entries: List<PosterDiskEntry>,
        maxBytes: Long,
    ): List<String> {
        var total = entries.sumOf { it.bytes }
        val out = mutableListOf<String>()
        for (entry in entries.sortedWith(compareBy({ it.lastUsedMillis }, { it.name }))) {
            if (total <= maxBytes) break
            out += entry.name
            total -= entry.bytes
        }
        return out
    }
}
