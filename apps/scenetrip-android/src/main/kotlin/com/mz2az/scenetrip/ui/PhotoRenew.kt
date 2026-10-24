package com.mz2az.scenetrip.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 이 안의 사진들이 못 받았을 때 **글·리뷰를 다시 읽어 새 주소를 받는 길** (MZ2AZ-477). iOS `\.photoRenew` 환경값.
 * 인자는 사람이 「다시 받기」를 눌렀는지(`forced`) — 아니면 30초에 한 번만 나간다. 없으면(null) 갱신할 길이 없는 사진이다.
 */
val LocalPhotoRenew = staticCompositionLocalOf<((forced: Boolean) -> Unit)?> { null }
