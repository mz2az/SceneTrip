package com.mz2az.scenetrip.data

/** 알 수 없는 저장값은 시스템을 따른다 (MZ2AZ-419). */
enum class AppThemeMode(
    val key: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    fun isDark(systemDark: Boolean): Boolean =
        when (this) {
            SYSTEM -> systemDark
            LIGHT -> false
            DARK -> true
        }

    companion object {
        fun from(raw: String?): AppThemeMode = entries.firstOrNull { it.key == raw } ?: SYSTEM
    }
}
