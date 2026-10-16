package com.mz2az.scenetrip.analytics

import android.content.Context

/** 설정 저장소. 기기 설정·Firebase 없이 시험하려고 뽑았다(MZ2AZ-475). */
interface UsageStatisticsStorage {
    /** 저장한 값이 없으면 null. */
    fun getEnabled(): Boolean?

    fun setEnabled(enabled: Boolean)
}

private class SharedPrefsUsageStatisticsStorage(
    context: Context,
) : UsageStatisticsStorage {
    private val prefs = context.getSharedPreferences("scenetrip", Context.MODE_PRIVATE)

    override fun getEnabled(): Boolean? = if (prefs.contains(UsageStatistics.KEY)) prefs.getBoolean(UsageStatistics.KEY, true) else null

    // 끄는 즉시 디스크에 닿도록 하되(apply), 같은 프로세스에서는 메모리 값이 곧바로 바뀐다.
    override fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(UsageStatistics.KEY, enabled).apply()
    }
}

/**
 * 이용 통계 수집 설정 (MZ2AZ-475). iOS `Analytics/UsageStatistics.swift`를 옮긴 것이다. 기본은 켜짐이고
 * 설치별로 저장한다. 회원 여부와 별개다.
 *
 * 저장을 먼저 하고 SDK를 건드린다 — SDK 호출 중 앱이 죽어도 사용자가 끈 선택은 남는다.
 * 안드로이드에는 ATT가 없고 Meta 광고 측정도 아직 붙지 않았으므로(`docs/project/plans/android-meta-ads-ad-id.md`)
 * 지금 이 설정이 제어하는 것은 Firebase Analytics뿐이다.
 */
class UsageStatistics(
    private val storage: UsageStatisticsStorage,
    private val applyCollection: (Boolean) -> Unit,
) {
    constructor(context: Context) : this(
        SharedPrefsUsageStatisticsStorage(context.applicationContext),
        { AppAnalytics.setCollectionEnabled(it) },
    )

    val isEnabled: Boolean
        get() = storage.getEnabled() ?: true

    fun setEnabled(enabled: Boolean) {
        storage.setEnabled(enabled)
        applyCollection(enabled)
    }

    companion object {
        /** iOS `UsageStatistics.key`와 같은 이름. */
        const val KEY = "scenetrip.analytics.collectionEnabled"

        /** 마이페이지 토글 줄 — iOS 번역표 문구. */
        const val TITLE_KO = "이용 통계 수집"

        /**
         * 토글 아래 꼬리말. iOS 는 「…광고 추적 허용은 설정 앱에서 바꿀 수 있어요」까지 쓰지만 그 줄(ATT)은
         * iOS 에만 있으므로 앞 문장만 쓴다. Android 는 `TranslationExtras.kt`.
         */
        const val FOOTNOTE_KO = "꺼도 앱 기능은 그대로예요."

        @Volatile private var instance: UsageStatistics? = null

        fun getInstance(context: Context): UsageStatistics =
            instance ?: synchronized(this) {
                instance ?: UsageStatistics(context.applicationContext).also { instance = it }
            }
    }
}
