package com.mz2az.scenetrip.newcourse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.mz2az.scenetrip.data.RouteStore

/**
 * 새 코스 만들기 덮개의 뿌리 — 무엇으로 시작했는지에 따라 흐름 하나를 띄운다 (MZ2AZ-547 P3, iOS `NewCourseFlowView`).
 *
 * 홈(`RootTabs`)이 `Cover.NewCourse` 로 띄운다. 도시로 만든 코스는 저장 뒤 `TabRouter.courseCreated` 가 덮개를
 * 경로여정으로 갈아 끼워 계획 화면을 연다. 작품으로·담은 곳으로는 이 덮개 안에서 저장 전 초안 편집(isNew)으로 바뀐다 —
 * 편집 화면의 닫기가 이 덮개를 닫는다.
 */
@Composable
fun NewCourseFlowView(
    start: NewCourseStart,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val routes = remember { RouteStore(context) }
    when (start) {
        is NewCourseStart.City -> CityQuestionView(startCity = start.key, routes = routes, onClose = onClose)
        NewCourseStart.Works -> WorksPlanView(routes = routes, onClose = onClose)
        NewCourseStart.Saved -> SavedPlanView(routes = routes, onClose = onClose)
    }
}
