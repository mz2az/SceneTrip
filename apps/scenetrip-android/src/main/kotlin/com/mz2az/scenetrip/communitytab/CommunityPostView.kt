package com.mz2az.scenetrip.communitytab

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.PostCourse
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.CheckmarkIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.XMarkIcon
import kotlinx.coroutines.launch

private const val PREVIEW_STOPS = 4

/**
 * 여행후기 읽기 (MZ2AZ-351) — 블로그 글처럼 **사진이 먼저, 그 아래 글, 끝에 다녀온 코스.**
 * iOS `CommunityTab/CommunityPostView.swift`를 옮긴 것이다.
 *
 * 붙은 코스는 이름만이 아니라 **일차별 장소까지** 보인다. 「내 코스로 담기」를 누르면 같은
 * 코스가 내 것으로 하나 생긴다 — 후기를 읽고 「나도 이대로 가야지」가 한 번에 되어야 한다.
 */
@Composable
fun CommunityPostView(
    post: CommunityPost,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { CommunityStore.getInstance(context) }
    val scope = rememberCoroutineScope()

    var saving by remember { mutableStateOf(false) }
    var savedCourseId by remember { mutableStateOf<Long?>(null) }
    var saveFailed by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }

    fun shown(stops: List<PostCourse.Stop>) = if (expanded) stops else stops.take(PREVIEW_STOPS)

    Box(Modifier.fillMaxSize().background(IOS.systemBackground)) {
        LazyColumn(Modifier.fillMaxSize()) {
            val photos = post.photos
            if (!photos.isNullOrEmpty()) {
                item { PostPhotoPager(store = store, names = photos) }
            }
            item {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                ) {
                    Text(post.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = IOS.label)

                    AuthorRow(post)

                    if (post.body.isNotEmpty()) {
                        Text(post.body, fontSize = 17.sp, color = IOS.label, lineHeight = 23.sp)
                    }

                    val course = post.course
                    if (course != null) {
                        CourseCard(
                            course = course,
                            expanded = expanded,
                            shown = ::shown,
                            isMine = post.isMine,
                            saving = saving,
                            savedCourseId = savedCourseId,
                            onExpand = { expanded = true },
                            onSave = {
                                saving = true
                                scope.launch {
                                    val saved = RouteStore(context).save(course.asNewCourse())
                                    saving = false
                                    val id = saved?.serverId
                                    if (id != null) savedCourseId = id else saveFailed = true
                                }
                            },
                            onOpenSaved = {
                                savedCourseId?.let { id ->
                                    onDismiss()
                                    TabRouter.openCourse(id)
                                }
                            },
                        )
                    } else if (post.courseTitle != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(IOS.systemGray6)
                                    .padding(12.dp),
                        ) {
                            PostCourseBadge(size = 30.dp)
                            Text(post.courseTitle, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                        }
                    }
                }
            }
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.62f))
                    .clickable(onClick = onDismiss),
        ) {
            XMarkIcon(Color.White, Modifier.size(13.dp))
        }

        if (saveFailed) {
            com.mz2az.scenetrip.ui.IOSConfirmPopover(
                title = tr("코스를 담지 못했어요. 잠시 뒤 다시 해 주세요"),
                actions =
                    listOf(
                        com.mz2az.scenetrip.ui
                            .IOSAction(tr("확인")) { saveFailed = false },
                    ),
                anchorX = 0.dp,
                onDismiss = { saveFailed = false },
                below = true,
            )
        }
    }
}

@Composable
private fun AuthorRow(post: CommunityPost) {
    val name = post.author ?: tr("나")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(32.dp).clip(CircleShape).background(IOS.pinDeep),
        ) {
            Text(name.take(1), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = IOS.label)
            Text(formatDateTime(post.createdAt), fontSize = 11.sp, color = IOS.tertiaryLabel)
        }
    }
}

@Composable
private fun CourseCard(
    course: PostCourse,
    expanded: Boolean,
    shown: (List<PostCourse.Stop>) -> List<PostCourse.Stop>,
    isMine: Boolean,
    saving: Boolean,
    savedCourseId: Long?,
    onExpand: () -> Unit,
    onSave: () -> Unit,
    onOpenSaved: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(IOS.systemGray6)
                .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PostCourseBadge()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(tr("이 후기의 코스"), fontSize = 11.sp, color = IOS.secondaryLabel)
                Text(course.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label, maxLines = 2)
                Text(tr("%d일 · %d곳").format(course.days.size, course.placeCount), fontSize = 12.sp, color = IOS.secondaryLabel)
            }
        }

        course.days.forEachIndexed { dayIndex, stops ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    tr("%d일차").format(dayIndex + 1),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = IOS.pinDeep,
                )
                shown(stops).forEachIndexed { index, stop ->
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier.size(20.dp).clip(CircleShape).background(IOS.pinLight),
                        ) {
                            Text("${index + 1}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(stop.name, fontSize = 15.sp, color = IOS.label)
                            if (!stop.address.isNullOrEmpty()) {
                                Text(stop.address, fontSize = 11.sp, color = IOS.secondaryLabel, maxLines = 1)
                            }
                        }
                    }
                }
                if (!expanded && stops.size > PREVIEW_STOPS) {
                    Text(
                        tr("외 %d곳").format(stops.size - PREVIEW_STOPS),
                        fontSize = 12.sp,
                        color = IOS.secondaryLabel,
                        modifier = Modifier.padding(start = 30.dp),
                    )
                }
            }
        }
        if (!expanded && course.days.any { it.size > PREVIEW_STOPS }) {
            Text(
                tr("장소 모두 보기"),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.label,
                modifier = Modifier.clickable(onClick = onExpand),
            )
        }

        // 내 글이면 담기가 없다 — 이미 내 코스다. 눌리면 같은 코스가 하나 더 생길 뿐이다.
        if (!isMine) {
            SaveButton(saving = saving, savedCourseId = savedCourseId, onSave = onSave, onOpenSaved = onOpenSaved)
        }
    }
}

@Composable
private fun SaveButton(
    saving: Boolean,
    savedCourseId: Long?,
    onSave: () -> Unit,
    onOpenSaved: () -> Unit,
) {
    if (savedCourseId != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(50))
                    .background(IOS.systemBackground)
                    .clickable(onClick = onOpenSaved),
        ) {
            CheckmarkIcon(IOS.pinDeep, Modifier.size(14.dp))
            Text(tr("담았어요 · 코스 보기"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.pinDeep)
        }
    } else {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(50))
                    .background(IOS.pinDeep)
                    .clickable(enabled = !saving, onClick = onSave),
        ) {
            if (saving) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                com.mz2az.scenetrip.ui
                    .ArrowDownIcon(Color.White, Modifier.size(14.dp))
            }
            Text(tr("내 코스로 담기"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

/** 사진 넘겨 보기 — 화면 폭을 꽉 채우고 옆으로 넘긴다. */
@Composable
fun PostPhotoPager(
    store: CommunityStore,
    names: List<String>,
) {
    val pagerState = rememberPagerState(pageCount = { names.size })
    Box(Modifier.fillMaxWidth().height(300.dp).background(IOS.systemGray5)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val bitmap = remember(names[page]) { store.photo(names[page]) }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(tr("사진"), color = IOS.secondaryLabel)
                }
            }
        }
        if (names.size > 1) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
            ) {
                names.indices.forEach { index ->
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index == pagerState.currentPage) Color.White else Color.White.copy(alpha = 0.5f),
                                ),
                    )
                }
            }
        }
    }
}
