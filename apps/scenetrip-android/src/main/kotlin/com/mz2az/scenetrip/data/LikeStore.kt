package com.mz2az.scenetrip.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 작품 찜. **장바구니와 별개 저장소다.**
 *
 * iOS `Models/LikeStore.swift` 를 옮긴 것이다. 8/11 회의 확정 — *"작품 찜이 있고,
 * 장소에는 장바구니. 장소에는 찜 없다"*. 둘을 한 저장소로 합치면 "작품을 장바구니에
 * 담았다" 는 잘못된 모형이 코드에 박힌다.
 *
 * **아직 서버가 없다.** 찜 API 는 MZ2AZ-231 이고 계약에도 없다. 그때까지 기기에만
 * 둔다 — iOS 는 UserDefaults, 여기서는 SharedPreferences 다. 서버가 생기면
 * `CartStore` 처럼 갈아 끼운다.
 */
class LikeStore(
    context: Context,
) {
    var contentIds by mutableStateOf<Set<Long>>(emptySet())
        private set

    private val prefs = context.getSharedPreferences("scenetrip", Context.MODE_PRIVATE)

    init {
        contentIds =
            prefs
                .getStringSet(KEY, emptySet())
                .orEmpty()
                .mapNotNull { it.toLongOrNull() }
                .toSet()
    }

    fun contains(contentId: Long): Boolean = contentId in contentIds

    fun toggle(contentId: Long) {
        contentIds = if (contentId in contentIds) contentIds - contentId else contentIds + contentId
        prefs.edit().putStringSet(KEY, contentIds.map(Long::toString).toSet()).apply()
    }

    private companion object {
        const val KEY = "likedContents"
    }
}
