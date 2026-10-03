package com.mz2az.scenetrip.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.sceneapi.client.api.FavoritesApi
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
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
    suspend fun refresh() {
        val server = runCatching { withContext(Dispatchers.IO) { fetchAll() } }.getOrNull()?.toMutableSet() ?: return
        val migrated = prefs.getBoolean(MIGRATED_KEY, false)
        var allUploaded = true
        for (id in pendingUploads(contentIds, server, migrated)) {
            val result = runCatching { withContext(Dispatchers.IO) { api.addFavoriteContent(installId, FavoriteContentCreate(id)) } }
            val gone = (result.exceptionOrNull() as? ClientException)?.statusCode == 404
            when {
                result.isSuccess -> {
                    server += id
                }

                gone -> {
                    Unit
                }

                // 사라진 작품 — 올릴 것이 없다. 다시 시도해도 같다.
                else -> {
                    allUploaded = false
                    server += id // 다음 실행에 다시 올린다. 그때까지 하트는 유지한다.
                }
            }
        }
        if (allUploaded) prefs.edit().putBoolean(MIGRATED_KEY, true).apply()
        contentIds = server
        save()
    }

    /** 하트는 **누르는 즉시** 바뀐다. 서버가 거절하면 되돌린다. */
    fun toggle(contentId: Long) {
        val liked = contentId !in contentIds
        apply(contentId, liked)
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
            if (result.isFailure) apply(contentId, !liked)
        }
    }

    private fun apply(
        contentId: Long,
        liked: Boolean,
    ) {
        contentIds = if (liked) contentIds + contentId else contentIds - contentId
        save()
    }

    private fun save() {
        prefs.edit().putStringSet(KEY, contentIds.map(Long::toString).toSet()).apply()
    }

    /** 계약의 한 번 최대가 100 이라 끝까지 넘긴다. */
    private fun fetchAll(): Set<Long> {
        val ids = mutableSetOf<Long>()
        var offset = 0
        while (true) {
            val page = api.listFavoriteContents(installId, limit = 100, offset = offset)
            ids += page.items.map { it.id }
            offset += page.items.size
            if (page.items.isEmpty() || offset >= page.total) return ids
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

        @Volatile
        private var instance: LikeStore? = null

        fun getInstance(context: Context): LikeStore =
            instance ?: synchronized(this) {
                instance ?: LikeStore(context.applicationContext).also { instance = it }
            }
    }
}
