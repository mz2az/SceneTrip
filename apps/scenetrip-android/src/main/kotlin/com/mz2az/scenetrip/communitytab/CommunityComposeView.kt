package com.mz2az.scenetrip.communitytab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.CommunityBoard
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.sceneapi.client.api.CoursesApi
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 글쓰기. iOS `CommunityTab/CommunityComposeView.swift`를 옮긴 것이다.
 *
 * 말머리를 고르고, 제목·본문을 적고, 내 코스를 붙인다. **사진은 아직 못 붙인다** —
 * 올릴 서버가 없는데 붙이는 시늉만 하면 글과 함께 사라진다.
 */
@Composable
fun CommunityComposeView(
    onDismiss: () -> Unit,
    onSubmit: (CommunityBoard, String, String, String?) -> Unit,
) {
    val context = LocalContext.current
    var board by remember { mutableStateOf(CommunityBoard.COURSE) }
    var title by remember { mutableStateOf("") }
    var story by remember { mutableStateOf("") }
    var courseTitle by remember { mutableStateOf<String?>(null) }
    var myCourses by remember { mutableStateOf<List<CourseSummary>>(emptyList()) }

    LaunchedEffect(Unit) {
        val deviceId = InstallIdentity.of(context)
        val api = CoursesApi(API_BASE)
        myCourses =
            withContext(Dispatchers.IO) {
                runCatching { api.listCourses(deviceId) }.getOrNull()
            }?.items ?: emptyList()
    }

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("취소", fontSize = 16.sp, color = IOS.secondaryLabel, modifier = Modifier.clickable(onClick = onDismiss))
            Text(
                "글쓰기",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.label,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            val canSubmit = title.trim().isNotEmpty()
            Text(
                "올리기",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (canSubmit) IOS.accent else IOS.tertiaryLabel,
                modifier =
                    Modifier.clickable(enabled = canSubmit) {
                        onSubmit(board, title.trim(), story.trim(), courseTitle)
                    },
            )
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(IOS.separator))

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("말머리", style = IOS.footnote, color = IOS.secondaryLabel)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CommunityBoard.entries.forEach { item ->
                            val active = board == item
                            Text(
                                item.label,
                                fontSize = 13.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (active) IOS.label else IOS.secondaryLabel,
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(if (active) IOS.accent.copy(alpha = 0.14f) else IOS.systemGray6)
                                        .clickable { board = item }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                            )
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("제목") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = story,
                    onValueChange = { story = it },
                    label = { Text("여행 이야기를 들려주세요") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("내 코스 붙이기", style = IOS.footnote, color = IOS.secondaryLabel)
                    if (myCourses.isEmpty()) {
                        Text("붙일 코스가 없습니다", fontSize = 12.sp, color = IOS.secondaryLabel)
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            CoursePickRow("안 붙임", selected = courseTitle == null, onClick = { courseTitle = null })
                            myCourses.forEach { course ->
                                CoursePickRow(
                                    course.title,
                                    selected = courseTitle == course.title,
                                    onClick = { courseTitle = course.title },
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text("사진은 게시판 서버가 열리면 붙일 수 있어요", fontSize = 12.sp, color = IOS.tertiaryLabel)
            }
        }
    }
}

@Composable
private fun CoursePickRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        title,
        fontSize = 14.sp,
        color = if (selected) IOS.accent else IOS.label,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) IOS.accent.copy(alpha = 0.1f) else IOS.systemGray6)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}
