package com.mz2az.scenetrip.data

import android.content.Context
import java.util.UUID

/**
 * 이 설치(기기+앱)를 가리키는 고정 식별자. iOS `Models/InstallIdentity.swift`를
 * 옮긴 것이다. 로그인이 없으므로 장바구니·코스 등 "누구 것인가"를 이 값으로 가른다.
 *
 * `CartStore`가 먼저 이 로직을 갖고 있었다 — 코스([RouteStore])에도 같은 값이
 * 필요해져 공용으로 뽑았다. 두 곳 다 SharedPreferences `"scenetrip"`/`"deviceId"`를
 * 같이 보므로 어느 쪽이 먼저 만들어도 같은 값을 얻는다.
 */
object InstallIdentity {
    private const val PREFS = "scenetrip"
    private const val KEY = "deviceId"

    fun of(context: Context): UUID {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { saved ->
            runCatching { return UUID.fromString(saved) }
        }
        val fresh = UUID.randomUUID()
        prefs.edit().putString(KEY, fresh.toString()).apply()
        return fresh
    }
}
