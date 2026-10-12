package com.mz2az.scenetrip.communitytab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.PostCourse
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.routetab.RouteBridge
import com.mz2az.scenetrip.sceneapi.client.api.CoursesApi
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSListDivider
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.RouteCurveIcon
import com.mz2az.scenetrip.ui.SheetDetent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 목록과 코스 전문을 각각 받아 첨부한다. 실패를 빈 목록으로 바꾸지 않는다. */
@Composable
internal fun CoursePickSheet(
    onDismiss: () -> Unit,
    onPick: (PostCourse) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val epoch = remember { AuthStore.epoch }
    var courses by remember { mutableStateOf<List<CourseSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var fetching by remember { mutableStateOf<Long?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableStateOf(0) }
    LaunchedEffect(revision, AuthStore.epoch, AppLanguage.current) {
        if (epoch != AuthStore.epoch) {
            onDismiss()
            return@LaunchedEffect
        }
        loading = true
        message = null
        apiResult {
            withContext(
                Dispatchers.IO,
            ) { CoursesApi(API_BASE).listCourses(InstallIdentity.of(context), acceptLanguage = AppLanguage.current) }
        }.fold(onSuccess = { courses = it.items }, onFailure = { message = NetworkFailure.of(it).message })
        loading = false
    }
    IOSSheet(listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onDismiss) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            IOSSheetToolbar(title = tr("내 코스"), trailing = tr("닫기"), onTrailing = onDismiss)
            message?.let {
                Text(it, color = IOS.systemRed, style = IOS.footnote, modifier = Modifier.padding(16.dp))
                CommunityListButton("다시 시도", !loading && fetching == null) { revision++ }
            }
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), color = IOS.accent)
                }
            } else if (courses.isEmpty() && message == null) {
                CoursePickEmpty()
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(courses, key = { it.id }) { course ->
                    CoursePickRow(course, fetching == course.id, !loading && fetching == null) {
                        fetching = course.id
                        scope.launch {
                            val result =
                                apiResult {
                                    withContext(Dispatchers.IO) {
                                        CoursesApi(API_BASE).getCourse(InstallIdentity.of(context), course.id, AppLanguage.current)
                                    }
                                }
                            if (epoch == AuthStore.epoch) {
                                result.fold(
                                    onSuccess = { onPick(PostCourse.from(RouteBridge.course(it))) },
                                    onFailure = { message = NetworkFailure.of(it).message },
                                )
                            }
                            fetching = null
                        }
                    }
                    IOSListDivider(start = 16.dp)
                }
            }
        }
    }
}

@Composable
private fun CoursePickRow(
    course: CourseSummary,
    fetching: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PostCourseBadge(30.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(course.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = IOS.label, maxLines = 1)
            Text(tr("%d일 · %d곳").format(course.dayCount, course.placeCount), fontSize = 12.sp, color = IOS.secondaryLabel)
        }
        if (fetching) CircularProgressIndicator(Modifier.size(16.dp), color = IOS.accent, strokeWidth = 2.dp)
    }
}

@Composable
private fun CoursePickEmpty() {
    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        RouteCurveIcon(IOS.secondaryLabel, Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(tr("붙일 코스가 없습니다"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
        Spacer(Modifier.height(4.dp))
        Text(tr("홈의 코스 만들기에서 먼저 코스를 만들어 보세요"), fontSize = 13.sp, color = IOS.secondaryLabel)
    }
}
