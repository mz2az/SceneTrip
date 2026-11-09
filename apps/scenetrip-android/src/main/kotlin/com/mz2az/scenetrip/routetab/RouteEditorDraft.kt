package com.mz2az.scenetrip.routetab

import androidx.compose.runtime.mutableStateOf

/** 원본과 편집 결과를 분리하고, 구성 변경이 선택 일차·제목·장소를 지우지 않게 한다. */
class RouteEditorDraft(
    val initial: RouteCourse,
) {
    val course = mutableStateOf(initial)
    val dayIndex = mutableStateOf(0)
    val saving = mutableStateOf(false)
    val discarding = mutableStateOf(false)
    val saveFailure = mutableStateOf<com.mz2az.scenetrip.data.ApiFailure?>(null)
}
