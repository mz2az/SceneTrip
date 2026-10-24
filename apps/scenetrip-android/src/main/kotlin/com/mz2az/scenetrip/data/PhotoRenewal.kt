package com.mz2az.scenetrip.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/**
 * 서명된 사진 주소는 한 시간 뒤 죽는다. 사진을 못 받았을 때 **글·리뷰를 다시 읽어 새 주소를 받는** 규칙 (MZ2AZ-477,
 * iOS `Reviews/PhotoRenewal.swift` 와 같은 규칙). 사진첩(`PhotoBook.renew`)은 따로 있다.
 */
object PhotoRenewalRules {
    /** 저절로 하는 갱신의 간격(ms). 여러 칸이 한꺼번에 실패해도 한 번만 나간다. */
    const val AUTOMATIC_GAP_MS = 30_000L

    /** 지금 갱신할 것인가 — 사람이 누른 것(`forced`)이면 언제나, 저절로 하는 것은 [gapMs] 에 한 번. iOS `shouldRenew`. */
    fun shouldRenew(
        forced: Boolean,
        last: Long?,
        now: Long,
        gapMs: Long = AUTOMATIC_GAP_MS,
    ): Boolean = forced || last == null || now - last >= gapMs

    /**
     * 사진을 못 받았다 — 새 주소를 청해 한 번 더 해 볼 것인가. **서명된 주소일 때만**(한 시간 뒤 죽는다), 그리고 **이 사진으로는
     * 아직 안 해 봤을 때만** — 끝없이 되풀이하지 않는다. 그 밖에는 「다시 시도」 를 보이고 사람이 누르기를 기다린다.
     * iOS `PhotoGalleryRules.renewsAfterFailure`.
     */
    fun renewsAfterFailure(
        signed: Boolean,
        alreadyRenewed: Boolean,
    ): Boolean = signed && !alreadyRenewed

    /** 쿼리·조각(서명)을 뗀 주소 — 같은 사진인지 가르는 열쇠다. iOS `PhotoGalleryRules.unsigned`. */
    fun unsigned(url: String): String = url.substringBefore('#').substringBefore('?')
}

/**
 * 한 화면의 주소 갱신을 공유하고 자동 갱신을 30초에 한 번으로 제한한다 (MZ2AZ-363·428).
 *
 * - 갱신이 나가 있는 동안 온 요청은 **새로 하지 않고 그 결과에 합류**한다.
 * - 사람이 누른 요청(`forced`)은 간격과 상관없이 나간다.
 * - [cancel] 은 나가 있는 갱신을 끊고 간격 계산도 처음으로 돌린다 — 다른 글·계정으로 옮길 때 쓴다.
 *
 * iOS 의 `Task {}` 처럼 호출한 쪽과 따로 도는 [scope] 에서 일한다. 한 화면(메인 스레드)에서만 부른다.
 */
class PhotoRenewal(
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private var last: Long? = null
    private var running: Deferred<Boolean>? = null
    private var generation = 0

    /** 갱신을 했으면 true. 간격에 걸려 건너뛰었거나, 실패했거나, 도중에 끊겼으면 false. */
    suspend fun run(
        forced: Boolean,
        work: suspend () -> Unit,
    ): Boolean {
        running?.let { return await(it) }
        if (!PhotoRenewalRules.shouldRenew(forced, last, now())) return false
        last = now()
        val asked = generation
        val task =
            scope.async {
                try {
                    work()
                    isActive
                } catch (_: Exception) {
                    false
                }
            }
        running = task
        val success = await(task)
        if (asked != generation) return false
        running = null
        return success
    }

    fun cancel() {
        generation++
        running?.cancel()
        running = null
        last = null
    }

    // 갱신이 [cancel] 로 끊긴 것은 부른 쪽의 취소가 아니다 — 실패로 돌려주고, 부른 쪽이 정말 취소됐을 때만 던진다.
    private suspend fun await(task: Deferred<Boolean>): Boolean =
        try {
            task.await()
        } catch (cancelled: CancellationException) {
            currentCoroutineContext().ensureActive()
            false
        }
}

/** 쪽으로 나뉜 목록에서 지금 읽은 범위의 새 주소를 받는다. iOS `PhotoPages`. */
object PhotoPages {
    /** 서버가 한 번에 주는 기본 줄 수(리뷰·내 리뷰). */
    const val PAGE_SIZE = 20

    data class Page<Row>(
        val items: List<Row>,
        val total: Int,
    )

    /**
     * 읽은 만큼([wanted]줄, 적어도 한 쪽) 첫 쪽부터 다시 받아 한 목록으로 합친다. 같은 줄은 한 번만 넣는다.
     * **중간에 한 쪽이라도 못 받으면 던진다** — 성공한 앞쪽만으로 잘린 목록을 돌려주면 안 된다.
     */
    suspend fun <Row> refetch(
        wanted: Int,
        id: (Row) -> Long,
        fetch: suspend (offset: Int) -> Page<Row>,
    ): Page<Row> {
        val rows = mutableListOf<Row>()
        val seen = mutableSetOf<Long>()
        var offset = 0
        var total = 0
        do {
            currentCoroutineContext().ensureActive()
            val page = fetch(offset)
            total = page.total
            for (row in page.items) if (seen.add(id(row))) rows += row
            offset += page.items.size
            if (page.items.isEmpty() || offset >= total) break
        } while (offset < maxOf(wanted, PAGE_SIZE))
        return Page(rows, total)
    }
}

/**
 * 같은 상세 응답의 사진·본문·출처·AI 표시를 함께 갱신한다. 기기 옛 글은 그대로 둔다 (MZ2AZ-428).
 * iOS `CommunityTab/CommunityPhotoRefresh.swift`.
 *
 * [post] 가 바뀔 때마다 [onChanged] 로 알린다. 늦게 온 응답은 **글이 바뀌었거나(`show`·`invalidate`·`cancelPending`)
 * 계정이 바뀌었으면** 버린다.
 */
class CommunityPhotoRefresh(
    post: CommunityPost,
    private val accountEpoch: () -> Int,
    private val detail: suspend (CommunityPost) -> CommunityPost,
    private val renewal: PhotoRenewal,
    private val onChanged: (CommunityPost?) -> Unit = {},
) {
    private var seed: CommunityPost = post
    private var generation = 0

    var post: CommunityPost? = null
        private set

    private fun publish(value: CommunityPost?) {
        post = value
        onChanged(value)
    }

    fun show(post: CommunityPost) {
        generation++
        renewal.cancel()
        seed = post
        publish(post)
    }

    fun invalidate() {
        generation++
        renewal.cancel()
        publish(null)
    }

    fun cancelPending() {
        generation++
        renewal.cancel()
    }

    /** 새 주소를 받았으면 true. 서버 글이 아니거나(기기 옛 글) 간격에 걸렸거나 실패했거나 끊겼으면 false. */
    suspend fun renew(forced: Boolean): Boolean {
        val id = seed.serverId ?: return false
        val requested = seed
        val askedGeneration = generation
        val askedEpoch = accountEpoch()
        return renewal.run(forced) {
            if (askedGeneration != generation || askedEpoch != accountEpoch()) throw CancellationException("글이나 계정이 바뀌었다")
            val fresh = detail(requested)
            currentCoroutineContext().ensureActive()
            if (askedGeneration != generation || askedEpoch != accountEpoch()) throw CancellationException("글이나 계정이 바뀌었다")
            if (fresh.serverId != id || fresh.id != requested.id) throw IllegalStateException("다른 글이 왔다")
            publish(fresh)
        }
    }
}
