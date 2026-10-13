package com.mz2az.scenetrip.routetab

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDate

/** Dialog가 글자 배율을 다시 적용해도 마법사의 답변과 초안은 바깥에서 보존한다. */
class RouteWizardState {
    var stepIndex by mutableStateOf(0)
    var span by mutableStateOf(RouteSpan.ONE_NIGHT)
    var pickedDate by mutableStateOf(LocalDate.now())
    var hasDate by mutableStateOf(false)
    var selectedWorkIds by mutableStateOf(emptySet<Long>())
    var pace by mutableStateOf(RoutePace.TIGHT)
    var draft by mutableStateOf<RouteEditorDraft?>(null)
    var planning by mutableStateOf(false)
    var planFailed by mutableStateOf(false)
    var here by mutableStateOf<Pair<Double, Double>?>(null)
}

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
