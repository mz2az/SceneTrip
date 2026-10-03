package com.mz2az.scenetrip.data

import android.content.Context
import java.util.UUID

/**
 * 이 설치(기기+앱)를 가리키는 고정 식별자. iOS `Models/InstallIdentity.swift`를
 * 옮긴 것이다. 장바구니·코스·찜이 "누구 것인가"를 이 값으로 가른다 — 계약의
 * `X-Install-Id` 헤더에 실린다.
 *
 * ## Keystore 로 잠근 저장소에 둔다 (MZ2AZ-335)
 *
 * SharedPreferences `"scenetrip"`/`"deviceId"` 에 평문으로 있던 값을 [SecureStore] 로
 * 옮겼다. **옛 자리에서 한 번 읽어 옮기는 폴백이 있다** — 그것 없이 자리만 바꾸면 이미
 * 깔린 앱의 값이 고아가 되어 그 사람의 장바구니·코스가 끊긴다.
 *
 * iOS 와 달리 「지웠다 깔았는가」를 따로 보지 않는다 — Android 는 앱을 지우면 Keystore
 * 열쇠도 함께 지워진다.
 */
object InstallIdentity {
    private const val LEGACY_PREFS = "scenetrip"
    private const val LEGACY_KEY = "deviceId"
    private const val NAME = "installId"

    @Volatile
    private var cached: UUID? = null

    fun of(context: Context): UUID =
        cached ?: synchronized(this) {
            cached ?: resolve(context.applicationContext).also { cached = it }
        }

    /**
     * 읽는 순서: 잠근 저장소 → 옛 자리(옮기고 지운다) → 새로 만든다. iOS `resolve` 와 같다.
     * 잠근 저장소에 못 쓰면 옛 자리를 그대로 둔다(또는 거기에 쓴다) — 실행마다 값이 바뀌는
     * 것이 가장 나쁘다.
     */
    private fun resolve(context: Context): UUID {
        val secure = SecureStore(context)
        secure.read(NAME)?.let(::parse)?.let { return it }
        val legacy = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        legacy.getString(LEGACY_KEY, null)?.let(::parse)?.let { old ->
            if (secure.write(NAME, old.toString())) legacy.edit().remove(LEGACY_KEY).apply()
            return old
        }
        val fresh = UUID.randomUUID()
        if (!secure.write(NAME, fresh.toString())) legacy.edit().putString(LEGACY_KEY, fresh.toString()).apply()
        return fresh
    }

    private fun parse(text: String): UUID? = runCatching { UUID.fromString(text) }.getOrNull()
}
