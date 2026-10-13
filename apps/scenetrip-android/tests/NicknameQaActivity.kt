package com.mz2az.scenetrip.qa

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.AppThemeMode
import com.mz2az.scenetrip.data.AppThemeStore
import com.mz2az.scenetrip.reviews.NicknameEditor
import com.mz2az.scenetrip.reviews.NicknameView
import com.mz2az.scenetrip.ui.SceneTripTheme
import kotlinx.coroutines.delay

/** 별도 QA 설치본의 모의 이름·응답만 사용한다. 제품 AuthStore·API·계정은 시작하지 않는다. */
class NicknameQaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra("qaCase") ?: "edit"
        val language = if (intent.getStringExtra("language") == "en") "en" else "ko"
        getSharedPreferences("scenetrip", MODE_PRIVATE).edit().putString("scenetrip.language", language).apply()
        AppLanguage.getInstance(this)
        AppThemeStore.getInstance(this).choose(if (intent.getStringExtra("theme") == "dark") AppThemeMode.DARK else AppThemeMode.LIGHT)
        var epoch = 1
        val welcome = kind == "welcome"
        val editor =
            NicknameEditor(if (welcome) "여행자12345" else "서울팬", welcome, { epoch }) {
                delay(if (kind == "busy") 300_000L else 400L)
                if (kind == "stale") epoch++
                if (kind == "failure") "이미 쓰는 닉네임입니다" else null
            }
        setContent {
            var closed by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) {
                if (kind in setOf("busy", "stale", "failure")) {
                    editor.text = "Seoul_Fan"
                    editor.submit { closed = true }
                }
            }
            SceneTripTheme {
                if (closed) {
                    Text("QA closed · no account or API writes")
                } else {
                    NicknameView(onClose = {
                        closed = true
                    }, firstTime = welcome, editor = editor)
                }
            }
        }
    }
}
