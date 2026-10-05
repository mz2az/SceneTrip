package com.mz2az.scenetrip.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import java.util.Locale

/**
 * 앱의 언어 — 사람이 앱 안에서 고른다 (MZ2AZ-343). iOS `Models/AppLanguage.swift`를 옮긴 것이다.
 *
 * 기기 언어와 따로 간다. 한국에 여행 온 사람의 폰은 일본어·스페인어일 수 있는데, 그렇다고
 * 앱이 정할 일이 아니다 — 첫 실행에 묻고, 마이페이지에서 언제든 바꾼다. 고르기 전에는
 * 기기 언어를 따른다(한국어가 아니면 영어).
 *
 * 고른 언어는 두 군데에 쓰인다.
 * 1. **화면 문구** — [tr]이 [TRANSLATIONS_EN]에서 찾는다.
 * 2. **서버 내용** — `Accept-Language`([AppLocale]). 작품·장소 이름과 장면 설명이 같은 언어로 온다.
 *
 * 문구의 열쇠는 **한국어 원문**이다. 번역이 없으면 원문이 그대로 나온다 — 빈 화면보다 낫다.
 */
class AppLanguage private constructor(
    context: Context,
) {
    /** 화면에 고를 수 있게 내놓는 언어. 번역 표가 있는 것만 넣는다. */
    val choices: List<Lang> = listOf(Lang.ko, Lang.en)

    var lang: Lang by mutableStateOf(Lang.ko)
        private set

    /** 사람이 직접 고른 적이 있는가. 없으면 첫 실행에 묻는다. */
    var hasChosen: Boolean by mutableStateOf(false)
        private set

    private val prefs = context.getSharedPreferences("scenetrip", Context.MODE_PRIVATE)

    init {
        val saved = prefs.getString(KEY, null)?.let { raw -> choices.firstOrNull { it.value == raw } }
        hasChosen = saved != null
        lang = saved ?: deviceDefault()
        current = lang
    }

    fun choose(new: Lang) {
        prefs.edit().putString(KEY, new.value).apply()
        hasChosen = true
        current = new
        lang = new
        AppLocale.install()
    }

    /** 기기 언어를 내놓는 언어로 접는다 — 한국어면 한국어, 그 밖은 영어. */
    private fun deviceDefault(): Lang = if (Locale.getDefault().language == "ko") Lang.ko else Lang.en

    companion object {
        private const val KEY = "scenetrip.language"

        /**
         * 지금 언어. 화면 밖(Compose 가 아닌 곳)에서도 읽으므로 전역에 둔다 — 쓰는 곳은
         * 메인 스레드의 [choose] 하나다. iOS `AppLanguage.current`.
         */
        @Volatile
        var current: Lang = Lang.ko
            private set

        /** 언어 이름은 **그 언어로** 적는다 — 한국어를 못 읽는 사람이 「영어」를 찾을 수는 없다. */
        fun name(of: Lang): String =
            when (of) {
                Lang.ko -> "한국어"
                Lang.en -> "English"
                Lang.ja -> "日本語"
                Lang.zhMinusHant -> "繁體中文"
            }

        @Volatile
        private var instance: AppLanguage? = null

        fun getInstance(context: Context): AppLanguage =
            instance ?: synchronized(this) {
                instance ?: AppLanguage(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * 한국어 원문을 지금 언어로. **Compose 에 글자를 바로 적는 곳에도 쓴다** — Android 는 iOS 의
 * `Text("…")` 자동 로케일 탐색이 없으므로, 화면 문구는 전부 이 함수를 거친다.
 *
 * 숫자가 들어가면 원문에 `%d`·`%s` 를 두고 `tr("%d개").format(count)` 처럼 쓴다.
 */
fun tr(korean: String): String {
    if (AppLanguage.current == Lang.ko) return korean
    return TRANSLATIONS_EN[korean] ?: korean
}

/** 같은 한국어가 자리에 따라 다른 영어가 되어야 할 때 — 「코스」가 화면 제목이면 Courses, 딱지면 Course. */
fun tr(
    korean: String,
    at: String,
): String {
    if (AppLanguage.current == Lang.ko) return korean
    val key = "$korean|$at"
    return TRANSLATIONS_EN[key] ?: tr(korean)
}
