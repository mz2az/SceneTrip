package com.mz2az.scenetrip.communitytab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.PostCourse
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.api.CoursesApi
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.RouteCurveIcon
import com.mz2az.scenetrip.ui.XMarkIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PHOTO_LIMIT = 8

/**
 * 여행후기 쓰기 — **블로그 글쓰기처럼** (MZ2AZ-351). iOS
 * `CommunityTab/CommunityComposeView.swift`를 옮긴 것이다.
 *
 * 위에서부터 사진 → 제목 → 본문 → 다녀온 코스. 폼의 칸을 채우는 느낌이 아니라 **글을 쓰는
 * 종이**여야 한다 — 그래서 테두리 없는 큰 제목과 넓은 본문이 화면의 대부분이다.
 *
 * 코스를 붙이면 일차·장소까지 글에 사본으로 담긴다. 읽는 사람이 그 코스를 보고 내 코스로
 * 담을 수 있다([CommunityPostView]).
 */
@Composable
fun CommunityComposeView(
    onDismiss: () -> Unit,
    onSubmit: (String, String, List<Bitmap>, PostCourse?) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var title by remember { mutableStateOf("") }
    var story by remember { mutableStateOf("") }
    var photos by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var course by remember { mutableStateOf<PostCourse?>(null) }
    var pickingCourse by remember { mutableStateOf(false) }

    val canPost = title.trim().isNotEmpty()

    val pickPhotos =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(PHOTO_LIMIT)) { uris ->
            scope.launch {
                photos =
                    withContext(Dispatchers.IO) {
                        uris.mapNotNull { uri ->
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                            }.getOrNull()
                        }
                    }
            }
        }

    IOSSheet(detents = listOf(com.mz2az.scenetrip.ui.SheetDetent.LARGE), onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
            IOSSheetToolbar(
                title = tr("여행후기 쓰기"),
                leading = tr("취소"),
                onLeading = onDismiss,
                trailing = tr("올리기"),
                trailingEnabled = canPost,
                onTrailing = {
                    onSubmit(title.trim(), story.trim(), photos, course)
                },
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    androidx.compose.foundation.layout
                        .PaddingValues(vertical = 16.dp),
            ) {
                item {
                    PhotoStrip(
                        photos = photos,
                        onAdd = { pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onRemove = { index -> photos = photos.filterIndexed { i, _ -> i != index } },
                    )
                }
                item {
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it },
                        textStyle = IOS.body.copy(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IOS.label),
                        cursorBrush = SolidColor(IOS.accent),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                        decorationBox = { inner ->
                            if (title.isEmpty()) {
                                Text(tr("제목"), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IOS.tertiaryLabel)
                            }
                            inner()
                        },
                    )
                }
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .height(0.5.dp)
                            .background(IOS.separator),
                    )
                }
                item {
                    BasicTextField(
                        value = story,
                        onValueChange = { story = it },
                        textStyle = IOS.body.copy(color = IOS.label, lineHeight = 22.sp),
                        cursorBrush = SolidColor(IOS.accent),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).height(160.dp),
                        decorationBox = { inner ->
                            if (story.isEmpty()) {
                                Text(
                                    tr("어디를 다녀왔나요? 장면 속 그 자리에 선 이야기를 들려주세요"),
                                    fontSize = 17.sp,
                                    color = IOS.tertiaryLabel,
                                )
                            }
                            inner()
                        },
                    )
                }
                item {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                        CourseCard(
                            course = course,
                            onAttach = { pickingCourse = true },
                            onDetach = { course = null },
                        )
                    }
                }
            }
        }
    }

    if (pickingCourse) {
        CoursePickSheet(
            onDismiss = { pickingCourse = false },
            onPick = {
                course = it
                pickingCourse = false
            },
        )
    }
}

/** 가로로 넘기는 사진 줄. 첫 칸은 늘 「사진 추가」다 — 첫 장이 대표 사진이 된다. */
@Composable
private fun PhotoStrip(
    photos: List<Bitmap>,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding =
            androidx.compose.foundation.layout
                .PaddingValues(horizontal = 20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier =
                    Modifier
                        .size(92.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(IOS.systemGray6)
                        .clickable(onClick = onAdd),
            ) {
                Icon(Icons.Filled.Add, contentDescription = tr("사진 추가"), tint = IOS.secondaryLabel, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(6.dp))
                Text("${photos.size}/$PHOTO_LIMIT", fontSize = 11.sp, color = IOS.secondaryLabel)
            }
        }
        itemsIndexed(photos) { index, photo ->
            Box(modifier = Modifier.size(92.dp)) {
                Image(
                    bitmap = photo.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                )
                if (index == 0) {
                    Text(
                        tr("대표"),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier =
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable { onRemove(index) },
                ) {
                    XMarkIcon(Color.White, Modifier.size(10.dp))
                }
            }
        }
    }
}

/** 코스 표시 — 피노 색 네모 안의 경로 그림. 글쓰기·글 보기·목록이 같이 쓴다. */
@Composable
fun PostCourseBadge(size: androidx.compose.ui.unit.Dp = 34.dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(RoundedCornerShape(size * 0.24f)).background(IOS.pinDeep),
    ) {
        RouteCurveIcon(Color.White, Modifier.size(size * 0.47f))
    }
}

/** 다녀온 코스 붙이기. 붙이면 일차·장소 수가 카드에 보인다. */
@Composable
private fun CourseCard(
    course: PostCourse?,
    onAttach: () -> Unit,
    onDetach: () -> Unit,
) {
    if (course != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(IOS.systemGray6)
                    .padding(14.dp),
        ) {
            PostCourseBadge()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(course.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.label, maxLines = 1)
                Text(
                    tr("%d일 · %d곳").format(course.days.size, course.placeCount),
                    fontSize = 12.sp,
                    color = IOS.secondaryLabel,
                )
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp).clickable(onClick = onDetach),
            ) {
                XMarkIcon(IOS.secondaryLabel, Modifier.size(13.dp))
            }
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(IOS.systemGray6)
                    .clickable(onClick = onAttach)
                    .padding(14.dp),
        ) {
            PostCourseBadge()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("다녀온 코스 붙이기"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                Text(
                    tr("읽는 사람이 이 코스를 보고 그대로 담아 갈 수 있어요"),
                    fontSize = 12.sp,
                    color = IOS.secondaryLabel,
                )
            }
            ChevronRightIcon(IOS.tertiaryLabel, Modifier.size(12.dp))
        }
    }
}

/** 내 코스 고르기. 고르면 **그 코스의 속(일차·장소)을 받아** 글에 붙일 사본을 만든다. */
@Composable
private fun CoursePickSheet(
    onDismiss: () -> Unit,
    onPick: (PostCourse) -> Unit,
) {
    val context = LocalContext.current
    var courses by remember { mutableStateOf<List<CourseSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var fetching by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        val deviceId = InstallIdentity.of(context)
        courses =
            withContext(Dispatchers.IO) {
                runCatching { CoursesApi(API_BASE).listCourses(deviceId) }.getOrNull()
            }?.items ?: emptyList()
        loading = false
    }

    IOSSheet(
        detents = listOf(com.mz2az.scenetrip.ui.SheetDetent.MEDIUM, com.mz2az.scenetrip.ui.SheetDetent.LARGE),
        onDismiss = onDismiss,
    ) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            IOSSheetToolbar(title = tr("내 코스"), leading = tr("닫기"), onLeading = onDismiss)
            when {
                loading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator(color = IOS.accent)
                    }
                }

                courses.isEmpty() -> {
                    Column(
                        Modifier.fillMaxSize().padding(top = 60.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        RouteCurveIcon(IOS.secondaryLabel, Modifier.size(40.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(tr("붙일 코스가 없습니다"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            tr("홈의 코스 만들기에서 먼저 코스를 만들어 보세요"),
                            fontSize = 13.sp,
                            color = IOS.secondaryLabel,
                        )
                    }
                }

                else -> {
                    val scope = rememberCoroutineScope()
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(courses, key = { it.id }) { item ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = fetching == null) {
                                            fetching = item.id
                                            scope.launch {
                                                val detail =
                                                    withContext(Dispatchers.IO) {
                                                        runCatching {
                                                            CoursesApi(API_BASE).getCourse(InstallIdentity.of(context), item.id)
                                                        }.getOrNull()
                                                    }
                                                fetching = null
                                                detail?.let {
                                                    onPick(
                                                        PostCourse.from(
                                                            com.mz2az.scenetrip.routetab.RouteBridge
                                                                .course(it),
                                                        ),
                                                    )
                                                }
                                            }
                                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                            ) {
                                PostCourseBadge(size = 30.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = IOS.label, maxLines = 1)
                                    Text(
                                        tr("%d일 · %d곳").format(item.dayCount, item.placeCount),
                                        fontSize = 12.sp,
                                        color = IOS.secondaryLabel,
                                    )
                                }
                                if (fetching == item.id) {
                                    androidx.compose.material3.CircularProgressIndicator(
                                        color = IOS.accent,
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
