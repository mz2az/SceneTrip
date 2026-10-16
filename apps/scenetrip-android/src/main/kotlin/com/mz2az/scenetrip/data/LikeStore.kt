package com.mz2az.scenetrip.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.sceneapi.client.api.FavoritesApi
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.FavoriteContentCreate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 작품 찜. **장바구니와 별개 저장소다.**
 *
 * iOS `Models/LikeStore.swift` 를 옮긴 것이다. 8/11 회의 확정 — *"작품 찜이 있고,
 * 장소에는 장바구니. 장소에는 찜 없다"*. 둘을 한 저장소로 합치면 "작품을 장바구니에
 * 담았다" 는 잘못된 모형이 코드에 박힌다.
 *
 * ## 서버가 정본이다 (MZ2AZ-335)
 *
 * 기기(SharedPreferences)에만 두던 것을 `/favorites/contents` 로 옮겼다. **기기에만 있으면
 * 로그인해도 계정에 붙지 않는다** — 서버의 합치기(MZ2AZ-256)는 서버에 있는 것만 본다.
 *
 * 기기 사본은 남긴다. 앱을 켠 직후 서버 응답 전에도 하트가 채워져 있어야 하고, 서버에
 * 못 닿아도 화면이 비지 않아야 한다. 옛 버전의 찜은 첫 [refresh] 에서 서버로 올린다.
 *
 * **`getInstance`로만 얻는 앱 전역 싱글턴이다** — iOS `LikeStore.shared`와 짝을
 * 맞추려고 홈 탭 작업(2026-09-27)에서 승격했다. 화면마다 `LikeStore(context)`를 새로
 * 만들면 검색 탭에서 누른 하트가 홈의 "찜한 작품 수"에 바로 반영되지 않는다.
 */
class LikeStore private constructor(
    context: Context,
) {
    var contentIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    /** 마지막 찜 변경이 거절된 이유. 화면이 닫을 때까지 안내한다(iOS `LikeStore.failure`, MZ2AZ-458). */
    var failure by mutableStateOf<ApiFailure?>(null)
        private set

    /**
     * 찜한 작품의 **요약** — 서버가 찜 목록에 실어 주는 그대로, 찜한 순서(최근 것부터)다. 숨긴 작품도 여기에는 남아
     * `delisted` 로 알린다(작품 목록 `GET /contents` 에는 숨긴 작품이 없다). 기기에 저장하지 않는다.
     * iOS `LikeStore.works`.
     */
    var works by mutableStateOf<List<ContentSummary>>(emptyList())
        private set

    /** 찜 목록에 그릴 줄 — [works] 가운데 지금 하트가 켜진 것(iOS `LikeSync.shown`). */
    val likedWorks: List<ContentSummary> get() = shown(works, contentIds)

    private val prefs = context.getSharedPreferences("scenetrip", Context.MODE_PRIVATE)
    private val api = FavoritesApi(API_BASE)
    private val installId = InstallIdentity.of(context)

    // 앱과 수명이 같다(싱글턴) — 화면이 사라져도 서버 호출은 끝까지 간다.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        contentIds =
            prefs
                .getStringSet(KEY, emptySet())
                .orEmpty()
                .mapNotNull { it.toLongOrNull() }
                .toSet()
        scope.launch { refresh() }
    }

    fun contains(contentId: Long): Boolean = contentId in contentIds

    /**
     * 서버의 찜을 읽어 온다. 로그인으로 계정이 합쳐진 뒤에도 부른다(MZ2AZ-336).
     *
     * 서버에 못 닿으면 기기 사본을 그대로 둔다 — 빈 목록으로 덮으면 찜이 사라져 보인다.
     */
    suspend fun refresh(): Boolean {
        val fetched = runCatching { withContext(Dispatchers.IO) { fetchAll() } }.getOrNull() ?: return false
        val server = fetched.map { it.id }.toMutableSet()
        val migrated = prefs.getBoolean(MIGRATED_KEY, false)
        var allUploaded = true
        for (id in pendingUploads(contentIds, server, migrated)) {
            val result = runCatching { withContext(Dispatchers.IO) { api.addFavoriteContent(installId, FavoriteContentCreate(id)) } }
            val rejected = result.exceptionOrNull()?.let { ApiFailure.of(it) }
            when {
                result.isSuccess -> {
                    server += id
                }

                else -> {
                    when (uploadOutcome(rejected)) {
                        // 사라진 작품 — 올릴 것이 없다. 다시 시도해도 같다.
                        UploadOutcome.GONE -> {
                            Unit
                        }

                        // 옛 기기 찜도 새 추가다. 영구 거절을 재시도하지 않는다.
                        UploadOutcome.DELISTED -> {
                            failure = rejected
                        }

                        UploadOutcome.RETRY -> {
                            allUploaded = false
                            server += id // 다음 실행에 다시 올린다. 그때까지 하트는 유지한다.
                        }
                    }
                }
            }
        }
        if (allUploaded) prefs.edit().putBoolean(MIGRATED_KEY, true).apply()
        contentIds = server
        works = fetched
        save()
        return true
    }

    /** 하트는 **누르는 즉시** 바뀐다. 서버가 거절하면 되돌리고 이유를 [failure] 에 남긴다. */
    fun toggle(contentId: Long) {
        val liked = contentId !in contentIds
        val previous = works.firstOrNull { it.id == contentId }
        val previousIndex = works.indexOfFirst { it.id == contentId }
        apply(contentId, liked)
        AppAnalytics.log(AppEvent.LikeTitle(contentId, liked))
        scope.launch {
            val result =
                runCatching {
                    withContext(Dispatchers.IO) {
                        if (liked) {
                            api.addFavoriteContent(installId, FavoriteContentCreate(contentId))
                        } else {
                            api.removeFavoriteContent(installId, contentId)
                        }
                    }
                }
            if (result.isFailure) {
                apply(contentId, !liked)
                // 찜 빼기가 거절됐다면 목록에서 치웠던 줄을 제자리에 되돌린다.
                if (!liked && previous != null && works.none { it.id == contentId }) {
                    works = works.toMutableList().also { it.add(minOf(previousIndex.coerceAtLeast(0), it.size), previous) }
                }
                failure = result.exceptionOrNull()?.let { ApiFailure.of(it) }
            } else if (liked) {
                // 새로 찜한 작품의 요약은 서버에만 있다 — 찜 목록에 바로 보이게 다시 읽는다. **요약만** 채운다(하트는 덮지 않는다).
                reloadSummaries()
            }
        }
    }

    fun clearFailure() {
        failure = null
    }

    /** 찜한 작품의 요약만 다시 읽는다 — 하트([contentIds])는 건드리지 않는다. 못 받으면 가진 것을 둔다. */
    private suspend fun reloadSummaries() {
        val fetched = runCatching { withContext(Dispatchers.IO) { fetchAll() } }.getOrNull() ?: return
        works = fetched
    }

    private fun apply(
        contentId: Long,
        liked: Boolean,
    ) {
        contentIds = if (liked) contentIds + contentId else contentIds - contentId
        if (!liked) works = works.filterNot { it.id == contentId }
        save()
    }

    private fun save() {
        prefs.edit().putStringSet(KEY, contentIds.map(Long::toString).toSet()).apply()
    }

    /** 계약의 한 번 최대가 100 이라 끝까지 넘긴다. */
    private fun fetchAll(): List<ContentSummary> {
        val all = mutableListOf<ContentSummary>()
        var offset = 0
        while (true) {
            val page = api.listFavoriteContents(installId, limit = 100, offset = offset)
            all += page.items.filter { item -> all.none { it.id == item.id } }
            offset += page.items.size
            if (page.items.isEmpty() || offset >= page.total) return all
        }
    }

    companion object {
        private const val KEY = "likedContents"

        /** 옛 기기 찜을 서버로 다 올렸는가. */
        private const val MIGRATED_KEY = "likesOnServer"

        /**
         * 아직 옮기지 않았으면 서버에 없는 것만 올린다. **한 번 옮긴 뒤에는 올리지 않는다** —
         * 그때부터 서버가 정본이라, 기기 사본에만 있는 것은 다른 곳에서 지운 찜이다.
         * iOS `LikeSync.pendingUploads` 와 같다.
         */
        fun pendingUploads(
            local: Set<Long>,
            server: Set<Long>,
            migrated: Boolean,
        ): Set<Long> = if (migrated) emptySet() else local - server

        /** 찜 목록에 그릴 줄 — 받아 둔 요약 가운데 **지금 하트가 켜진 것만**, 받은 순서(최근 찜부터)로. iOS `LikeSync.shown`. */
        fun shown(
            works: List<ContentSummary>,
            liked: Set<Long>,
        ): List<ContentSummary> = works.filter { liked.contains(it.id) }

        /** 옛 기기 찜을 올리다 거절됐을 때의 처리. iOS `LikeStore.refresh` 의 catch. */
        enum class UploadOutcome { GONE, DELISTED, RETRY }

        fun uploadOutcome(rejected: ApiFailure?): UploadOutcome =
            when {
                rejected?.statusCode == 404 -> UploadOutcome.GONE
                rejected?.statusCode == 409 && rejected.apiCode == "CONTENT_DELISTED" -> UploadOutcome.DELISTED
                else -> UploadOutcome.RETRY
            }

        @Volatile
        private var instance: LikeStore? = null

        fun getInstance(context: Context): LikeStore =
            instance ?: synchronized(this) {
                instance ?: LikeStore(context.applicationContext).also { instance = it }
            }
    }
}
