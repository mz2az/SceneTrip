package com.mz2az.scenetrip

import com.mz2az.scenetrip.searchtab.PosterDiskEntry
import com.mz2az.scenetrip.searchtab.PosterDiskRules
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 작품 포스터 기기 보관 규칙 (MZ2AZ-507, 계획 docs/project/plans/poster-disk-cache.md 「규칙」·「순수 규칙」).
 * 파일 이름, 30일 기간, 100MB 넘는 몫 지우기.
 */
class PosterDiskRulesTests {
    private val day = 24L * 60 * 60 * 1000
    private val thirtyDays = 30L * day
    private val mb = 1024L * 1024

    private fun entry(
        name: String,
        bytes: Long,
        lastUsed: Long,
    ) = PosterDiskEntry(name = name, bytes = bytes, lastUsedMillis = lastUsed)

    // ---- 정해진 값 ----

    /** 보관 기간은 정확히 30일 */
    @Test
    fun maxAgeIsExactlyThirtyDays() {
        assertEquals(30L * 24 * 60 * 60 * 1000, PosterDiskRules.MAX_AGE_MILLIS)
        assertEquals(2_592_000_000L, PosterDiskRules.MAX_AGE_MILLIS)
    }

    /** 보관 합계 상한은 100MB */
    @Test
    fun maxBytesIs100MiB() {
        assertEquals(100L * 1024 * 1024, PosterDiskRules.MAX_BYTES)
    }

    /** 그릴 때 긴 변은 360px */
    @Test
    fun edgeIs360px() {
        assertEquals(360, PosterDiskRules.EDGE)
    }

    /** 저장할 응답 상한은 24MB */
    @Test
    fun maxDownloadBytesIs24MiB() {
        assertEquals(24 * 1024 * 1024, PosterDiskRules.MAX_DOWNLOAD_BYTES)
    }

    // ---- 파일 이름 ----

    /** 파일 이름은 주소의 SHA256 소문자 16진수 */
    @Test
    fun fileNameIsSha256HexOfUrl() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            PosterDiskRules.fileName("abc"),
        )
    }

    /** 파일 이름은 소문자 16진수 64자 */
    @Test
    fun fileNameIs64LowercaseHexChars() {
        val name = PosterDiskRules.fileName("https://img.example.com/poster/JTBC-드라마.jpg?w=1200")
        assertEquals(64, name.length)
        assertTrue(name.all { it in '0'..'9' || it in 'a'..'f' }, name)
    }

    /** 같은 주소는 같은 이름 다른 주소는 다른 이름 */
    @Test
    fun sameUrlSameNameDifferentUrlDifferentName() {
        val a = "https://img.example.com/poster/1.jpg"
        val b = "https://img.example.com/poster/2.jpg"
        assertEquals(PosterDiskRules.fileName(a), PosterDiskRules.fileName(a))
        assertNotEquals(PosterDiskRules.fileName(a), PosterDiskRules.fileName(b))
        assertEquals(64, PosterDiskRules.fileName(b).length)
    }

    // ---- 기간 ----

    /** 정확히 30일이면 아직 만료가 아니다 */
    @Test
    fun notExpiredAtExactlyThirtyDays() {
        val saved = 1_700_000_000_000L
        assertFalse(PosterDiskRules.expired(saved, saved + thirtyDays))
    }

    /** 30일에서 조금 모자라면 만료가 아니다 */
    @Test
    fun notExpiredJustUnderThirtyDays() {
        val saved = 1_700_000_000_000L
        assertFalse(PosterDiskRules.expired(saved, saved + thirtyDays - 1))
        assertFalse(PosterDiskRules.expired(saved, saved))
    }

    /** 30일을 조금이라도 넘으면 만료 */
    @Test
    fun expiredJustOverThirtyDays() {
        val saved = 1_700_000_000_000L
        assertTrue(PosterDiskRules.expired(saved, saved + thirtyDays + 1))
        assertTrue(PosterDiskRules.expired(saved, saved + 31 * day))
    }

    // ---- 용량 넘는 몫 지우기 ----

    /** 상한 아래면 지울 것이 없다 */
    @Test
    fun noEvictionsUnderLimit() {
        val entries = listOf(entry("a", 10 * mb, 1), entry("b", 20 * mb, 2))
        assertEquals(emptyList<String>(), PosterDiskRules.evictions(entries, 100 * mb))
    }

    /** 정확히 상한이면 지울 것이 없다 */
    @Test
    fun noEvictionsAtExactLimit() {
        val entries = listOf(entry("a", 40 * mb, 1), entry("b", 60 * mb, 2))
        assertEquals(emptyList<String>(), PosterDiskRules.evictions(entries, 100 * mb))
    }

    /** 비어 있으면 지울 것이 없다 */
    @Test
    fun noEvictionsWhenEmpty() {
        assertEquals(emptyList<String>(), PosterDiskRules.evictions(emptyList(), 100 * mb))
    }

    /** 넘으면 마지막으로 쓴 때가 오래된 것부터 상한 이하가 될 때까지 */
    @Test
    fun evictsOldestLastUsedFirstUntilUnderLimit() {
        // 합계 130, 상한 100 → 30 이상을 오래된 순으로: old(10, t=1) → mid(25, t=2) 까지 지우면 95.
        val entries =
            listOf(
                entry("new", 50, 30),
                entry("mid", 25, 20),
                entry("old", 10, 10),
                entry("recent", 45, 25),
            )
        assertEquals(listOf("old", "mid"), PosterDiskRules.evictions(entries, 100))
    }

    /** 넘는 만큼만 지우고 멈춘다 */
    @Test
    fun stopsOnceUnderLimit() {
        // 합계 101, 상한 100 → 가장 오래된 하나만.
        val entries = listOf(entry("b", 50, 2), entry("a", 1, 1), entry("c", 50, 3))
        assertEquals(listOf("a"), PosterDiskRules.evictions(entries, 100))
    }

    /** 마지막으로 쓴 때가 같으면 이름순 */
    @Test
    fun tiesBrokenByName() {
        val entries =
            listOf(
                entry("zeta", 40, 5),
                entry("alpha", 40, 5),
                entry("mike", 40, 5),
            )
        // 합계 120, 상한 100 → 하나만, 같은 때라 이름순 첫째.
        assertEquals(listOf("alpha"), PosterDiskRules.evictions(entries, 100))
        // 상한 50 → 둘, 이름순.
        assertEquals(listOf("alpha", "mike"), PosterDiskRules.evictions(entries, 50))
    }

    /** 혼자서 상한보다 큰 파일은 그것을 지운다 */
    @Test
    fun singleEntryOverLimitIsEvicted() {
        val entries = listOf(entry("huge", 200 * mb, 100))
        assertEquals(listOf("huge"), PosterDiskRules.evictions(entries, 100 * mb))
    }
}
