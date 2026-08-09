package com.mz2az.scenetrip

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * 첫 화면. 지금은 모듈이 서는지 확인하는 자리표시자다.
 *
 * 작품검색 탭은 계획서 §3 을 기준으로 iOS 와 **같은 규칙**으로 만든다 — 여기 없는
 * 동작을 어느 쪽에서도 임의로 만들지 않는다. 화면 구조·검색 범위·자동완성·칩·오류
 * 화면이 모두 그 문서에 확정돼 있다.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "SceneTrip"
                textSize = 24f
            },
        )
    }
}
