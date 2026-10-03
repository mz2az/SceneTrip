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
 *
 * **`getInstance`로만 얻는 앱 전역 싱글턴이다** — iOS `LikeStore.shared`와 짝을
 * 맞추려고 홈 탭 작업(2026-09-27)에서 승격했다. 화면마다 `LikeStore(context)`를 새로
 * 만들면 검색 탭에서 누른 하트가 홈의 "찜한 작품 수"에 바로 반영되지 않는다 — 각
 * 인스턴스가 SharedPreferences를 자기 생성 시점에만 한 번 읽기 때문이다.
 */
class LikeStore private constructor(
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

    companion object {
        private const val KEY = "likedContents"

        @Volatile
        private var instance: LikeStore? = null

        fun getInstance(context: Context): LikeStore =
            instance ?: synchronized(this) {
                instance ?: LikeStore(context.applicationContext).also { instance = it }
            }
    }
}
