package com.mz2az.scenetrip.communitytab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.CommunityBoard
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.sceneapi.client.api.CoursesApi
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.searchtab.SegmentedControl
import com.mz2az.scenetrip.ui.CameraIcon
import com.mz2az.scenetrip.ui.ChevronUpDownIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSConfirmPopover
import com.mz2az.scenetrip.ui.IOSFormSection
import com.mz2az.scenetrip.ui.IOSListDivider
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.sheetListBottom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 글쓰기. iOS `CommunityTab/CommunityComposeView.swift`를 옮긴 것이다.
 *
 * 말머리를 고르고, 제목·본문을 적고, 내 코스를 붙인다. **사진은 아직 못 붙인다** —
 * 올릴 서버가 없는데 붙이는 시늉만 하면 글과 함께 사라진다.
 *
 * iOS 는 `.sheet` 안의 `Form` 이다 — 말머리는 세그먼트, 「글」 카드 안에 테두리 없는 제목·본문,
 * 코스는 메뉴 `Picker`, 카메라 안내. 탭 안의 페이지·칩·Material 테두리 입력창이던 것을 맞췄다(2차 대조).
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
    var pickingCourse by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val deviceId = InstallIdentity.of(context)
        val api = CoursesApi(API_BASE)
        myCourses =
            withContext(Dispatchers.IO) {
                runCatching { api.listCourses(deviceId) }.getOrNull()
            }?.items ?: emptyList()
    }

    IOSSheet(detents = listOf(SheetDetent.LARGE), onDismiss = onDismiss) {
        // iOS 큰 시트의 `Form` 은 회색(F2F2F7) 바탕 — 흰 툴바 캡슐이 그 위에서 보인다.
        Column(modifier = Modifier.fillMaxSize().background(FORM_GRAY)) {
            val canSubmit = title.trim().isNotEmpty()
            IOSSheetToolbar(
                title = "글쓰기",
                leading = "취소",
                onLeading = onDismiss,
                trailing = "올리기",
                trailingEnabled = canSubmit,
                onTrailing = { onSubmit(board, title.trim(), story.trim(), courseTitle) },
            )
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = sheetListBottom()) {
                item {
                    IOSFormSection("말머리", card = IOS.systemBackground) {
                        SegmentedControl(
                            options = CommunityBoard.entries.toList(),
                            selected = board,
                            label = { it.label },
                            onSelect = { board = it },
                            // 세그먼트가 좌우 여백(16)을 스스로 둔다.
                            modifier = Modifier.padding(vertical = 10.dp),
                        )
                    }
                }
                item {
                    IOSFormSection("글", card = IOS.systemBackground) {
                        FormField(title, { title = it }, "제목", Modifier.heightIn(min = 50.dp), singleLine = true)
                        IOSListDivider(start = 16.dp)
                        // iOS `axis: .vertical` + `lineLimit(5...10)` — 다섯 줄 높이에서 시작해 열 줄까지 는다.
                        FormField(story, { story = it }, "여행 이야기를 들려주세요", Modifier.heightIn(min = 124.dp), singleLine = false)
                    }
                }
                item {
                    IOSFormSection("내 코스 붙이기", card = IOS.systemBackground) {
                        if (myCourses.isEmpty()) {
                            Text(
                                "붙일 코스가 없습니다",
                                fontSize = 12.sp,
                                color = IOS.secondaryLabel,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        } else {
                            Box {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 50.dp)
                                            .clickable { pickingCourse = true }
                                            .padding(horizontal = 16.dp),
                                ) {
                                    Text("코스", fontSize = 17.sp, color = IOS.label, modifier = Modifier.weight(1f))
                                    Text(courseTitle ?: "안 붙임", fontSize = 17.sp, color = IOS.secondaryLabel, maxLines = 1)
                                    ChevronUpDownIcon(IOS.secondaryLabel, Modifier.size(13.dp))
                                }
                                if (pickingCourse) {
                                    IOSConfirmPopover(
                                        title = "코스",
                                        actions =
                                            listOf(IOSAction("안 붙임") { courseTitle = null }) +
                                                myCourses.map { course -> IOSAction(course.title) { courseTitle = course.title } },
                                        anchorX = 0.dp,
                                        onDismiss = { pickingCourse = false },
                                        below = true,
                                    )
                                }
                            }
                        }
                    }
                }
                item {
                    IOSFormSection(null, card = IOS.systemBackground) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        ) {
                            CameraIcon(IOS.tertiaryLabel, Modifier.size(16.dp))
                            Text("사진은 게시판 서버가 열리면 붙일 수 있어요", fontSize = 12.sp, color = IOS.tertiaryLabel)
                        }
                    }
                }
            }
        }
    }
}

/** `Form` 안의 테두리 없는 `TextField` — 자리표시 글은 옅은 회색 17. */
@Composable
private fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier,
    singleLine: Boolean,
) {
    Box(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), contentAlignment = Alignment.TopStart) {
        if (value.isEmpty()) Text(placeholder, fontSize = 17.sp, color = IOS.tertiaryLabel)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 10,
            textStyle = IOS.body.copy(color = IOS.label),
            cursorBrush = SolidColor(IOS.accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val FORM_GRAY =
    androidx.compose.ui.graphics
        .Color(0xFFF2F2F7)
