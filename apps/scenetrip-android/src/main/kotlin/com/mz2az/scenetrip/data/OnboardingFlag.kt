package com.mz2az.scenetrip.data

import android.content.Context

/**
 * 사용법 온보딩(넉 장)을 본 적이 있는지.
 *
 * iOS `Onboarding/AppRoot.swift`의 `OnboardingFlag`를 옮긴 것이다.
 *
 * ## 판(version)으로 두는 이유
 *
 * Boolean 으로 두면 튜토리얼 내용을 크게 바꿔도 **이미 깔린 사람은 영영 못 본다.**
 * 판 번호를 올리면 그 사람들에게 한 번 더 보인다. 문구를 다듬는 정도로는 올리지
 * 않고, 장이 늘거나 기능이 바뀌었을 때만 올린다.
 *
 * ## 저장소를 인터페이스로 뽑아 둔 이유
 *
 * `Context` 없이(=Robolectric 등 새 의존성 없이) 순수 로직을 유닛 테스트하기
 * 위해서다. 실제 사용은 [OnboardingFlag]의 [Context] 생성자.
 */
interface OnboardingFlagStorage {
    fun getVersion(): Int

    fun setVersion(version: Int)

    fun clear()
}

private class SharedPrefsOnboardingFlagStorage(
    context: Context,
) : OnboardingFlagStorage {
    private val prefs = context.getSharedPreferences("scenetrip", Context.MODE_PRIVATE)

    override fun getVersion(): Int = prefs.getInt(KEY, 0)

    override fun setVersion(version: Int) {
        prefs.edit().putInt(KEY, version).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        // InstallIdentity 와 같은 "scenetrip." 접두를 쓴다.
        const val KEY = "scenetrip.onboarding.seenVersion"
    }
}

class OnboardingFlag(
    private val storage: OnboardingFlagStorage,
) {
    constructor(context: Context) : this(SharedPrefsOnboardingFlagStorage(context))

    val hasSeen: Boolean
        get() = storage.getVersion() >= VERSION

    fun markSeen() {
        storage.setVersion(VERSION)
    }

    /** 마이페이지에서 다시 보기. 에뮬레이터에서 앱을 지웠다 깔지 않고 확인하는 길이기도 하다. */
    fun reset() {
        storage.clear()
    }

    companion object {
        /** 1 = 첫 판 (넉 장: 검색 / AI 코스 / 길찾기 / 반경 POI·챗봇). */
        const val VERSION = 1
    }
}
