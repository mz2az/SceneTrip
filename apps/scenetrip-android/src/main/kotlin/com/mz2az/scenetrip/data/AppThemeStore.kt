package com.mz2az.scenetrip.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 화면 테마 선택은 계정과 별개의 기기 설정이다 (MZ2AZ-419). */
class AppThemeStore private constructor(
    context: Context,
) {
    private val prefs = context.applicationContext.getSharedPreferences("scenetrip", Context.MODE_PRIVATE)
    var mode by mutableStateOf(AppThemeMode.from(prefs.getString(KEY, null)))
        private set

    fun choose(value: AppThemeMode) {
        prefs.edit().putString(KEY, value.key).apply()
        mode = value
    }

    companion object {
        private const val KEY = "scenetrip.theme"
        private var instance: AppThemeStore? = null

        fun getInstance(context: Context): AppThemeStore =
            instance ?: synchronized(this) { instance ?: AppThemeStore(context).also { instance = it } }

        fun label(mode: AppThemeMode): String =
            tr(
                when (mode) {
                    AppThemeMode.SYSTEM -> "시스템 설정"
                    AppThemeMode.LIGHT -> "라이트 모드"
                    AppThemeMode.DARK -> "다크 모드"
                },
            )
    }
}
