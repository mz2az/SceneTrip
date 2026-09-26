package com.mz2az.scenetrip.profiletab

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.FootprintStore
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.LikeStore
import com.mz2az.scenetrip.data.OnboardingFlag
import com.mz2az.scenetrip.data.VisitStamp
import com.mz2az.scenetrip.data.collectVisitStamps
import com.mz2az.scenetrip.onboarding.OnboardingView
import com.mz2az.scenetrip.onboarding.PinoMascot
import com.mz2az.scenetrip.sceneapi.client.api.CartApi
import com.mz2az.scenetrip.sceneapi.client.api.ContentsApi
import com.mz2az.scenetrip.sceneapi.client.api.CoursesApi
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SYSTEM_ORANGE = Color(0xFFFF9500)
private val SYSTEM_GREEN = Color(0xFF34C759)
private val SYSTEM_INDIGO = Color(0xFF5856D6)
private val SYSTEM_GRAY = Color(0xFF8E8E93)
private val SYSTEM_BLUE = IOS.accent

/**
 * 마이페이지 — 임시판. iOS `ProfileTab/ProfileTabView.swift`를 옮긴 것이다.
 *
 * 로그인이 아직 없다. 그래서 이 화면은 "내 계정"이 아니라 "이 설치본에 쌓인 것"을
 * 보여 준다 — 찜한 작품, 내 코스, 사용법. 지어낸 숫자는 없다.
 *
 * [onClose]는 홈이 덮개로 띄울 때 넘긴다 — 있으면 왼쪽 위에 닫기 단추가 생긴다.
 */
@Composable
fun ProfileTabView(onClose: (() -> Unit)? = null) {
    val context = LocalContext.current
    val likes = remember { LikeStore.getInstance(context) }
    val posts = remember { CommunityStore.getInstance(context) }
    val footprints = remember { FootprintStore.getInstance(context) }
    val deviceId = remember { InstallIdentity.of(context) }

    var courses by remember { mutableStateOf<List<CourseSummary>>(emptyList()) }
    var courseCount by remember { mutableStateOf<Int?>(null) }
    var allWorks by remember { mutableStateOf<List<ContentSummary>>(emptyList()) }
    var likesLoading by remember { mutableStateOf(true) }
    var likesFailure by remember { mutableStateOf<String?>(null) }
    var cartItems by remember { mutableStateOf<List<CartItem>>(emptyList()) }
    var stamps by remember { mutableStateOf<List<VisitStamp>>(emptyList()) }

    var showingCart by remember { mutableStateOf(false) }
    var showingStamps by remember { mutableStateOf(false) }
    var replaying by remember { mutableStateOf(false) }
    var showingReels by remember { mutableStateOf(false) }
    var showingCourses by remember { mutableStateOf(false) }
    var showingLikes by remember { mutableStateOf(false) }
    var showingPosts by remember { mutableStateOf(false) }
    var clearingFootprints by remember { mutableStateOf(false) }

    val likedWorks = allWorks.filter { likes.contentIds.contains(it.id) }

    suspend fun load() =
        coroutineScope {
            // 넷을 나란히 받는다 — 코스 상세(스탬프용)가 느려도 찜 목록은 바로 뜬다.
            likesLoading = true
            val worksDeferred =
                async(Dispatchers.IO) {
                    runCatching { ContentsApi(API_BASE).listContents(limit = 100) }
                }
            val cartDeferred =
                async(Dispatchers.IO) {
                    runCatching { CartApi(API_BASE).getCart(deviceId) }.getOrNull()
                }
            val coursesDeferred =
                async(Dispatchers.IO) {
                    runCatching { CoursesApi(API_BASE).listCourses(deviceId) }.getOrNull()
                }

            worksDeferred.await().fold(
                onSuccess = {
                    allWorks = it.items ?: emptyList()
                    likesFailure = null
                },
                onFailure = { likesFailure = it.message?.take(300) ?: "알 수 없는 오류" },
            )
            likesLoading = false

            cartDeferred.await()?.let { cartItems = it.items ?: emptyList() }
            coursesDeferred.await()?.let {
                courses = it.items ?: emptyList()
                courseCount = courses.size
            }

            // 방문 스탬프 — 코스마다 상세를 받아 visitedAt이 찍힌 것만 모은다.
            stamps = runCatching { collectVisitStamps(courses, deviceId) }.getOrDefault(emptyList())
        }

    LaunchedEffect(Unit) { load() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(IOS.systemGray6)) {
            Box(modifier = Modifier.fillMaxWidth().statusBarsPadding().height(44.dp)) {
                Text("마이페이지", style = IOS.headline, color = IOS.label, modifier = Modifier.align(Alignment.Center))
                if (onClose != null) {
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 12.dp)
                                .size(32.dp)
                                .clickable(onClick = onClose),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "닫기", tint = IOS.label, modifier = Modifier.size(16.dp))
                    }
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item { ProfileHeader() }

                item {
                    ProfileSection("내 여행") {
                        ProfileRow(
                            tint = IOS.pinDeep,
                            title = "내 코스",
                            value = courseCount?.let { "${it}개" } ?: "…",
                            onClick = { showingCourses = true },
                        )
                        ProfileRow(
                            tint = IOS.systemRed,
                            title = "찜한 작품",
                            value = "${likes.contentIds.size}개",
                            onClick = { showingLikes = true },
                        )
                        ProfileRow(
                            tint = SYSTEM_ORANGE,
                            title = "장바구니",
                            value = "${cartItems.size}곳",
                            onClick = { showingCart = true },
                        )
                    }
                }

                item {
                    ProfileSection("방문 스탬프") {
                        if (stamps.isEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(44.dp)
                                            .clip(CircleShape)
                                            .background(IOS.systemBackground),
                                )
                                Text(
                                    "여행 중 성지 100m 안에 들어가면 도장이 찍혀요",
                                    fontSize = 12.sp,
                                    color = IOS.secondaryLabel,
                                )
                            }
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth().clickable { showingStamps = true }.padding(vertical = 6.dp),
                            ) {
                                items(stamps.take(12), key = { it.id }) { stamp -> StampBadge(stamp, size = 62.dp) }
                                if (stamps.size > 12) {
                                    item {
                                        Text(
                                            "+${stamps.size - 12}",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = IOS.secondaryLabel,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    ProfileSection("커뮤니티") {
                        ProfileRow(
                            tint = SYSTEM_INDIGO,
                            title = "내가 쓴 글",
                            value = "${posts.posts.size}개",
                            onClick = { showingPosts = true },
                        )
                    }
                }

                item {
                    ProfileSection("AI 여행 릴스") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().clickable { showingReels = true }.padding(vertical = 6.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Brush.linearGradient(listOf(IOS.pinLight, IOS.pinDeep))),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.Filled.Star, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("내 여행으로 릴스 만들기", fontSize = 14.sp, color = IOS.label)
                                Text(
                                    "다녀온 코스와 사진을 AI 가 15초 영상으로",
                                    fontSize = 11.sp,
                                    color = IOS.secondaryLabel,
                                )
                            }
                            Text("곧", fontSize = 12.sp, color = IOS.tertiaryLabel)
                        }
                    }
                }

                item {
                    ProfileSection("발자취") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ProfileRow(
                                tint = IOS.pinDeep,
                                title = "기록한 거리",
                                value = "%.1f km · %d점".format(footprints.kilometers, footprints.points.size),
                                onClick = null,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("지도에 발자취 보기", fontSize = 14.sp, color = IOS.label, modifier = Modifier.weight(1f))
                                Switch(checked = footprints.enabled, onCheckedChange = { footprints.updateEnabled(it) })
                            }
                            Text(
                                "발자취 지우기",
                                fontSize = 14.sp,
                                color = if (footprints.points.isEmpty()) IOS.tertiaryLabel else IOS.systemRed,
                                modifier =
                                    Modifier.clickable(enabled = footprints.points.isNotEmpty()) {
                                        clearingFootprints = true
                                    },
                            )
                        }
                    }
                }

                item {
                    ProfileSection("도움") {
                        ProfileRow(
                            tint = SYSTEM_BLUE,
                            title = "사용법 다시 보기",
                            value = "",
                            onClick = { replaying = true },
                        )
                    }
                }

                item {
                    ProfileSection("준비 중") {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ProfileRow(SYSTEM_GRAY, "로그인 · 계정", "준비 중", null, dimmed = true)
                            ProfileRow(SYSTEM_GRAY, "언어 (English · 日本語)", "준비 중", null, dimmed = true)
                            ProfileRow(SYSTEM_GRAY, "알림", "준비 중", null, dimmed = true)
                        }
                    }
                }

                item {
                    Text(
                        "설치 식별자 ${deviceId.toString().take(8)}…",
                        fontSize = 10.sp,
                        color = IOS.tertiaryLabel,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        if (showingCourses) MyCoursesSheet(courses, onClose = { showingCourses = false })
        if (showingLikes) LikedWorksSheet(likedWorks, likesLoading, likesFailure, onClose = { showingLikes = false })
        if (showingCart) ProfileCartSheet(cartItems, onClose = { showingCart = false })
        if (showingStamps) StampsSheet(stamps, onClose = { showingStamps = false })
        if (showingPosts) {
            MyPostsSheet(posts.posts, onRemove = { posts.remove(it) }, onClose = { showingPosts = false })
        }
        if (showingReels) ReelsTeaserView(onClose = { showingReels = false })
        if (replaying) {
            val flag = remember { OnboardingFlag(context) }
            OnboardingView(onboardingFlag = flag, onDone = { replaying = false })
        }

        if (clearingFootprints) {
            AlertDialog(
                onDismissRequest = { clearingFootprints = false },
                title = { Text("발자취를 모두 지울까요?") },
                text = { Text("복구할 수 없어요.") },
                confirmButton = {
                    TextButton(onClick = {
                        footprints.clear()
                        clearingFootprints = false
                    }) { Text("지우기", color = IOS.systemRed) }
                },
                dismissButton = {
                    TextButton(onClick = { clearingFootprints = false }) { Text("취소") }
                },
            )
        }
    }
}

/** 피노와 비회원 안내. 로그인이 서면 이 자리가 계정 카드가 된다. */
@Composable
private fun ProfileHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    ) {
        PinoMascot(width = 96.dp)
        Text("비회원으로 여행 중", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
        Text(
            "로그인이 생기면 코스와 찜을 계정으로 옮겨 드릴게요",
            fontSize = 12.sp,
            color = IOS.secondaryLabel,
        )
    }
}

@Composable
private fun ProfileSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp)) {
        Text(title, fontSize = 12.sp, color = IOS.secondaryLabel, modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(IOS.systemBackground)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            content()
        }
    }
}

/**
 * 목록 줄. iOS 는 SF Symbol 을 쓰지만 그와 1:1로 맞는 Material 코어 아이콘이 갈래마다
 * 있는 게 아니라(Material 은 core/extended 로 나뉘고, 아이콘 하나 때문에 수천 개짜리
 * extended 를 넣을 것은 아니다 — `RootTabs.kt`의 같은 판단), 갈래를 가리키는 **색
 * 점**으로 대신한다.
 */
@Composable
private fun ProfileRow(
    tint: Color,
    title: String,
    value: String,
    onClick: (() -> Unit)?,
    dimmed: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 6.dp),
    ) {
        Box(modifier = Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size(9.dp).clip(CircleShape).background(tint))
        }
        Text(title, fontSize = 14.sp, color = if (dimmed) IOS.tertiaryLabel else IOS.label, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = IOS.secondaryLabel)
        if (onClick != null) {
            Text("›", fontSize = 14.sp, color = IOS.tertiaryLabel)
        }
    }
}
