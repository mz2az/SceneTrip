package com.mz2az.scenetrip.profiletab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.sceneapi.client.api.CoursesApi
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 두 팝업이 같은 머리줄을 쓴다 — 제목 가운데, 오른쪽 작은 X. */
@Composable
fun ProfileSheetHeader(
    title: String,
    onClose: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(top = 14.dp, bottom = 6.dp),
    ) {
        Text(title, style = IOS.headline, color = IOS.label, modifier = Modifier.align(Alignment.Center))
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(32.dp)
                    .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = "닫기", tint = IOS.secondaryLabel, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    description: String,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = IOS.headline, color = IOS.secondaryLabel)
        Text(description, style = IOS.footnote, color = IOS.tertiaryLabel, textAlign = TextAlign.Center)
    }
}

/**
 * 내 코스 팝업. iOS `ProfileTab/ProfileSheets.swift`의 `MyCoursesSheet`를 옮긴 것이다.
 * 행을 누르면 바로 아래 상세(일차별 장소)가 펼쳐진다 — 아코디언.
 */
@Composable
fun MyCoursesSheet(
    courses: List<CourseSummary>,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf<Long?>(null) }
    val details = remember { mutableStateOf(mapOf<Long, CourseDetail>()) }

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
        ProfileSheetHeader("내 코스", onClose)
        if (courses.isEmpty()) {
            EmptyState("아직 코스가 없습니다", "경로여정 탭에서 첫 코스를 만들어 보세요")
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(courses, key = { it.id }) { course ->
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expanded = if (expanded == course.id) null else course.id
                                    },
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(course.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = IOS.label)
                                val startDate = course.startDate?.let { " · ${formatSimpleDate(it.toString())}" } ?: ""
                                Text("${course.dayCount}일$startDate", fontSize = 12.sp, color = IOS.secondaryLabel)
                            }
                            if (course.status == CourseStatus.active) {
                                Text(
                                    "여행 중",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF34C759),
                                    modifier =
                                        Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(Color(0xFF34C759).copy(alpha = 0.15f))
                                            .padding(horizontal = 7.dp, vertical = 3.dp),
                                )
                            }
                            Icon(
                                if (expanded == course.id) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = IOS.tertiaryLabel,
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        if (expanded == course.id) {
                            val detail = details.value[course.id]
                            LaunchedEffect(course.id) {
                                if (details.value[course.id] == null) {
                                    val deviceId = InstallIdentity.of(context)
                                    val api = CoursesApi(API_BASE)
                                    val loaded =
                                        withContext(Dispatchers.IO) {
                                            runCatching { api.getCourse(deviceId, course.id) }.getOrNull()
                                        }
                                    if (loaded != null) {
                                        details.value = details.value + (course.id to loaded)
                                    }
                                }
                            }
                            if (detail != null) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(IOS.systemGray6)
                                            .padding(10.dp),
                                ) {
                                    detail.days.forEach { day ->
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text(
                                                "${day.dayNumber}일차",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = IOS.pinDeep,
                                                modifier = Modifier.width(38.dp),
                                            )
                                            Text(
                                                day.items.joinToString(" → ") { it.name },
                                                fontSize = 11.sp,
                                                color = IOS.secondaryLabel,
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(IOS.accent.copy(alpha = 0.12f))
                                                .clickable {
                                                    TabRouter.openCourse(course.id)
                                                    onClose()
                                                }.padding(vertical = 8.dp),
                                    ) {
                                        Text(
                                            "경로여정에서 열기 ↗",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = IOS.accent,
                                        )
                                    }
                                }
                            } else {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(top = 6.dp),
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    Text("일정을 받아오는 중입니다", fontSize = 11.sp, color = IOS.secondaryLabel)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 찜한 작품 팝업. */
@Composable
fun LikedWorksSheet(
    works: List<ContentSummary>,
    loading: Boolean,
    failure: String?,
    onClose: () -> Unit,
) {
    var expanded by remember { mutableStateOf<Long?>(null) }
    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
        ProfileSheetHeader("찜한 작품", onClose)
        when {
            failure != null -> {
                EmptyState("작품 목록을 받지 못했습니다", failure)
            }

            loading && works.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text("작품 목록을 받아오는 중입니다", fontSize = 12.sp, color = IOS.secondaryLabel)
                }
            }

            works.isEmpty() -> {
                EmptyState("찜한 작품이 없습니다", "작품검색 탭에서 하트를 눌러 보세요")
            }

            else -> {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(works, key = { it.id }) { work ->
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { expanded = if (expanded == work.id) null else work.id },
                            ) {
                                RemoteImage(
                                    url = work.posterUrl?.toString(),
                                    modifier = Modifier.size(width = 34.dp, height = 46.dp).clip(RoundedCornerShape(5.dp)),
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(work.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = IOS.label)
                                    val meta =
                                        listOfNotNull(work.broadcaster, work.releaseYear?.toString()).joinToString(" · ")
                                    if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = IOS.secondaryLabel)
                                }
                                Icon(
                                    Icons.Filled.Favorite,
                                    contentDescription = null,
                                    tint = IOS.systemRed,
                                    modifier = Modifier.size(13.dp),
                                )
                                Icon(
                                    if (expanded == work.id) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = IOS.tertiaryLabel,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            if (expanded == work.id) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(IOS.systemGray6)
                                            .padding(10.dp),
                                ) {
                                    work.genres?.takeIf { it.isNotEmpty() }?.let {
                                        Text(it.joinToString(" · "), fontSize = 11.sp, color = IOS.accent)
                                    }
                                    Text("촬영지 ${work.placeCount}곳", fontSize = 11.sp, color = IOS.secondaryLabel)
                                    Text(
                                        "촬영지는 작품검색 탭에서 지도로 볼 수 있어요",
                                        fontSize = 11.sp,
                                        color = IOS.tertiaryLabel,
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

/** 장바구니 팝업 — 검색 탭에서 담아 둔 촬영지. 보는 자리다. */
@Composable
fun ProfileCartSheet(
    items: List<CartItem>,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
        ProfileSheetHeader("장바구니", onClose)
        if (items.isEmpty()) {
            EmptyState("장바구니가 비었습니다", "작품검색 탭에서 촬영지를 담아 보세요")
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(items, key = { it.placeId }) { item ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    ) {
                        RemoteImage(url = item.imageUrl?.toString(), modifier = Modifier.size(40.dp).clip(RoundedCornerShape(7.dp)))
                        Column {
                            Text(item.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = IOS.label)
                            val meta = listOfNotNull(item.sourceContentTitle, item.address).joinToString(" · ")
                            if (meta.isNotEmpty()) Text(meta, fontSize = 12.sp, color = IOS.secondaryLabel, maxLines = 1)
                        }
                    }
                }
            }
            Text(
                "담고 빼는 것은 작품검색 탭의 장바구니에서",
                fontSize = 11.sp,
                color = IOS.tertiaryLabel,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 내가 쓴 글. 커뮤니티의 기기 저장 글을 마이페이지에서 되짚는다 — 누르면 커뮤니티와
 * 같은 전문 화면이 열린다.
 */
@Composable
fun MyPostsSheet(
    posts: List<CommunityPost>,
    onRemove: (CommunityPost) -> Unit,
    onClose: () -> Unit,
) {
    var reading by remember { mutableStateOf<CommunityPost?>(null) }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground)) {
            ProfileSheetHeader("내가 쓴 글", onClose)
            if (posts.isEmpty()) {
                EmptyState("아직 쓴 글이 없습니다", "커뮤니티 탭에서 첫 글을 남겨 보세요")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(posts, key = { it.id }) { post ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { reading = post }.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        post.board.label,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = IOS.accent,
                                        modifier =
                                            Modifier
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(IOS.accent.copy(alpha = 0.13f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                    Text(post.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, color = IOS.label)
                                }
                                Text(formatDateTimeShort(post.createdAt), fontSize = 10.sp, color = IOS.tertiaryLabel)
                            }
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "지우기",
                                tint = IOS.tertiaryLabel,
                                modifier = Modifier.size(16.dp).clickable { onRemove(post) },
                            )
                        }
                    }
                }
            }
        }
        reading?.let { post ->
            com.mz2az.scenetrip.communitytab
                .CommunityPostView(post = post, onDismiss = { reading = null })
        }
    }
}

private fun formatSimpleDate(isoDate: String): String = isoDate.take(10)

private fun formatDateTimeShort(millis: Long): String = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(Date(millis))
