package com.mz2az.scenetrip.profiletab

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Notifications
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.FootprintStore
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.LikeStore
import com.mz2az.scenetrip.data.OnboardingFlag
import com.mz2az.scenetrip.data.VisitStamp
import com.mz2az.scenetrip.data.collectVisitStamps
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.onboarding.OnboardingView
import com.mz2az.scenetrip.onboarding.PinoMascot
import com.mz2az.scenetrip.sceneapi.client.api.CartApi
import com.mz2az.scenetrip.sceneapi.client.api.ContentsApi
import com.mz2az.scenetrip.sceneapi.client.api.CoursesApi
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.ui.BagFillIcon
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.GlobeIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSAlert
import com.mz2az.scenetrip.ui.IOSCloseButton
import com.mz2az.scenetrip.ui.IOSConfirmPopover
import com.mz2az.scenetrip.ui.IOSRole
import com.mz2az.scenetrip.ui.IOSToggle
import com.mz2az.scenetrip.ui.QuestionCircleIcon
import com.mz2az.scenetrip.ui.RouteCurveIcon
import com.mz2az.scenetrip.ui.ShoeprintsIcon
import com.mz2az.scenetrip.ui.SignOutIcon
import com.mz2az.scenetrip.ui.SparklesIcon
import com.mz2az.scenetrip.ui.SquarePencilIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val SYSTEM_ORANGE = Color(0xFFFF9500)
private val SYSTEM_GREEN = Color(0xFF34C759)
private val SYSTEM_INDIGO = Color(0xFF5856D6)
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
    val language = remember { AppLanguage.getInstance(context) }
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
    var choosingLanguage by remember { mutableStateOf(false) }

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
                onFailure = { likesFailure = it.message?.take(300) ?: tr("알 수 없는 오류") },
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

    // 계정이 바뀌면(로그인·로그아웃·탈퇴) 다시 읽는다 — iOS `.onAccountChange`.
    LaunchedEffect(AuthStore.epoch) { load() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(IOS.systemGray6)) {
            Box(modifier = Modifier.fillMaxWidth().statusBarsPadding().height(56.dp)) {
                Text(tr("마이페이지"), style = IOS.headline, color = IOS.label, modifier = Modifier.align(Alignment.Center))
                if (onClose != null) {
                    IOSCloseButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp))
                }
            }

            // iOS insetGrouped List 는 첫 섹션 위에 약 38 을 비운다(2차 대조: 제목→해태 75.5 대 38).
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 38.dp)) {
                item { ProfileHeader() }

                item {
                    ProfileSection(tr("내 여행")) {
                        ProfileRow(
                            title = tr("내 코스"),
                            value = courseCount?.let { tr("%d개").format(it) } ?: "…",
                            onClick = { showingCourses = true },
                            chevron = true,
                            icon = { RouteCurveIcon(IOS.pinDeep, it) },
                        )
                        ProfileDivider()
                        ProfileRow(
                            title = tr("찜한 작품"),
                            value = tr("%d개").format(likes.contentIds.size),
                            onClick = { showingLikes = true },
                            chevron = true,
                            icon = { Icon(Icons.Filled.Favorite, contentDescription = null, tint = IOS.systemRed, modifier = it) },
                        )
                        ProfileDivider()
                        ProfileRow(
                            title = tr("장바구니"),
                            value = tr("%d곳").format(cartItems.size),
                            onClick = { showingCart = true },
                            chevron = true,
                            icon = { BagFillIcon(SYSTEM_ORANGE, it) },
                        )
                    }
                }

                item {
                    ProfileSection(tr("방문 스탬프")) {
                        if (stamps.isEmpty()) {
                            // iOS: 회색 점선 원 44 + caption 안내문.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            ) {
                                androidx.compose.foundation.Canvas(Modifier.size(44.dp)) {
                                    val stroke = 2.dp.toPx()
                                    drawCircle(
                                        IOS.systemGray4,
                                        radius = size.minDimension / 2 - stroke / 2,
                                        style =
                                            androidx.compose.ui.graphics.drawscope.Stroke(
                                                width = stroke,
                                                pathEffect =
                                                    androidx.compose.ui.graphics.PathEffect
                                                        .dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                                            ),
                                    )
                                }
                                Text(
                                    tr("여행 중 성지 100 m 안에 들어가면 도장이 찍혀요"),
                                    fontSize = 12.sp,
                                    color = IOS.secondaryLabel,
                                )
                            }
                        } else {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp),
                                modifier = Modifier.fillMaxWidth().clickable { showingStamps = true }.padding(vertical = 12.dp),
                            ) {
                                items(stamps.take(12), key = { it.id }) { stamp -> StampBadge(stamp, size = 62.dp) }
                                if (stamps.size > 12) {
                                    item {
                                        Text(
                                            "+${stamps.size - 12}", // 숫자뿐이라 언어가 바뀌어도 같다.
                                            fontSize = 12.sp,
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
                    ProfileSection(tr("커뮤니티")) {
                        ProfileRow(
                            title = tr("내가 쓴 글"),
                            value = tr("%d개").format(posts.mine.size),
                            onClick = { showingPosts = true },
                            chevron = true,
                            icon = { SquarePencilIcon(SYSTEM_INDIGO, it) },
                        )
                    }
                }

                item {
                    ProfileSection(tr("AI 여행 릴스")) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier =
                                Modifier.fillMaxWidth().clickable { showingReels = true }.padding(
                                    horizontal = 16.dp,
                                    vertical = 11.dp,
                                ),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(Brush.linearGradient(listOf(IOS.pinLight, IOS.pinDeep))),
                                contentAlignment = Alignment.Center,
                            ) {
                                SparklesIcon(Color.White, Modifier.size(14.dp))
                            }
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(tr("내 여행으로 릴스 만들기"), fontSize = 15.sp, color = IOS.label)
                                Text(
                                    tr("다녀온 코스와 사진을 AI 가 15초 영상으로"),
                                    fontSize = 11.sp,
                                    color = IOS.secondaryLabel,
                                )
                            }
                            Text(tr("곧"), fontSize = 12.sp, color = IOS.tertiaryLabel)
                        }
                    }
                }

                item {
                    ProfileSection(tr("발자취")) {
                        ProfileRow(
                            title = tr("기록한 거리"),
                            value = tr("%.1f km · %d점").format(footprints.kilometers, footprints.points.size),
                            onClick = null,
                            icon = { ShoeprintsIcon(IOS.pinDeep, it) },
                        )
                        ProfileDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 16.dp),
                        ) {
                            Text(tr("지도에 발자취 보기"), fontSize = 15.sp, color = IOS.label, modifier = Modifier.weight(1f))
                            IOSToggle(checked = footprints.enabled, onCheckedChange = { footprints.updateEnabled(it) })
                        }
                        ProfileDivider(afterIcon = false)
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(52.dp)
                                    .clickable(enabled = footprints.points.isNotEmpty()) { clearingFootprints = true }
                                    .padding(horizontal = 16.dp),
                        ) {
                            // iOS 26 은 확인을 이 글자 **옆에 붙는 팝오버**로 띄운다(아래 시트·어두운 막 없음).
                            var labelWidth by remember { mutableStateOf(0.dp) }
                            val density = LocalDensity.current
                            Box {
                                Text(
                                    tr("발자취 지우기"),
                                    fontSize = 15.sp,
                                    color = if (footprints.points.isEmpty()) IOS.tertiaryLabel else IOS.systemRed,
                                    modifier = Modifier.onSizeChanged { labelWidth = with(density) { it.width.toDp() } },
                                )
                                if (clearingFootprints) {
                                    IOSConfirmPopover(
                                        title = tr("발자취를 모두 지울까요? 복구할 수 없어요."),
                                        actions = listOf(IOSAction(tr("지우기"), IOSRole.DESTRUCTIVE) { footprints.clear() }),
                                        anchorX = labelWidth,
                                        onDismiss = { clearingFootprints = false },
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    ProfileSection(tr("도움")) {
                        Box {
                            ProfileRow(
                                title = tr("언어"),
                                value = AppLanguage.name(of = language.lang),
                                onClick = { choosingLanguage = true },
                                chevron = true,
                                icon = { GlobeIcon(SYSTEM_BLUE, it) },
                            )
                            if (choosingLanguage) {
                                IOSConfirmPopover(
                                    title = tr("언어"),
                                    // 언어 이름은 그 언어로 적는다 — 번역하지 않는다.
                                    actions =
                                        language.choices.map { lang ->
                                            IOSAction(AppLanguage.name(of = lang)) { language.choose(lang) }
                                        },
                                    anchorX = 0.dp,
                                    onDismiss = { choosingLanguage = false },
                                    below = true,
                                )
                            }
                        }
                        ProfileDivider()
                        // iOS 는 이 줄에 chevron 이 없다(`row(..., chevron: false)`).
                        ProfileRow(
                            title = tr("사용법 다시 보기"),
                            value = "",
                            onClick = { replaying = true },
                            icon = { QuestionCircleIcon(SYSTEM_BLUE, it) },
                        )
                    }
                }

                if (AuthStore.signedIn) {
                    item { AccountSection(onDeleted = { onClose?.invoke() }) }
                }

                item {
                    ProfileSection(tr("준비 중")) {
                        ProfileRow(
                            tr("알림"),
                            tr("준비 중"),
                            null,
                            dimmed = true,
                            icon = {
                                Icon(
                                    Icons.Outlined.Notifications,
                                    contentDescription = null,
                                    tint = IOS.tertiaryLabel,
                                    modifier = it,
                                )
                            },
                        )
                    }
                }

                item {
                    Text(
                        tr("설치 식별자 %s…").format(deviceId.toString().take(8)),
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
            MyPostsSheet(posts.mine, onRemove = { posts.remove(it) }, onClose = { showingPosts = false })
        }
        if (showingReels) ReelsTeaserView(onClose = { showingReels = false })
        if (replaying) {
            val flag = remember { OnboardingFlag(context) }
            OnboardingView(onboardingFlag = flag, onDone = { replaying = false })
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
        // iOS `header` — 비회원이면 로그인 단추, 로그인했으면 이름과 메일 (MZ2AZ-336).
        if (AuthStore.signedIn) {
            Text(AuthStore.me?.displayName ?: tr("여행자"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            AuthStore.me?.email?.let { Text(it, fontSize = 12.sp, color = IOS.secondaryLabel) }
        } else {
            Text(tr("비회원으로 여행 중"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            Text(tr("로그인하면 코스와 찜이 계정에 저장돼요"), fontSize = 12.sp, color = IOS.secondaryLabel)
            Text(
                tr("로그인"),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .clip(CircleShape)
                        .background(IOS.accent)
                        .clickable { AuthStore.promptSignIn() }
                        .padding(horizontal = 22.dp, vertical = 8.dp),
            )
        }
    }
}

/** 계정 — 로그아웃과 탈퇴. iOS `accountSection`. 탈퇴는 스토어 요건이라 앱 안에 있어야 한다. */
@Composable
private fun AccountSection(onDeleted: () -> Unit) {
    var confirmingSignOut by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var deleteFailed by remember { mutableStateOf(false) }
    ProfileSection(tr("계정")) {
        Box {
            ProfileRow(
                title = tr("로그아웃"),
                value = "",
                onClick = { if (!AuthStore.busy) confirmingSignOut = true },
                icon = { SignOutIcon(IOS.secondaryLabel, it) },
            )
            if (confirmingSignOut) {
                IOSConfirmPopover(
                    title = tr("로그아웃할까요? 이 기기는 비회원으로 돌아가요."),
                    actions = listOf(IOSAction(tr("로그아웃"), IOSRole.DESTRUCTIVE) { AuthStore.signOut() }),
                    anchorX = 0.dp,
                    onDismiss = { confirmingSignOut = false },
                    below = true,
                )
            }
        }
        ProfileDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clickable(enabled = !AuthStore.busy) { confirmingDelete = true }
                    .padding(horizontal = 16.dp),
        ) {
            Text(tr("회원 탈퇴"), fontSize = 15.sp, color = IOS.systemRed)
        }
    }
    if (confirmingDelete) {
        IOSAlert(
            title = tr("정말 탈퇴할까요?"),
            // iOS 가 발자취까지 적는 문구로 고쳤다(2026-10-05) — 그대로 옮긴다.
            message = tr("장바구니·코스·찜과 이 기기의 발자취가 모두 지워지고 되돌릴 수 없어요."),
            actions =
                listOf(
                    IOSAction(tr("취소"), IOSRole.CANCEL) {},
                    IOSAction(tr("탈퇴"), IOSRole.DESTRUCTIVE) {
                        AuthStore.deleteAccount { deleted -> if (deleted) onDeleted() else deleteFailed = true }
                    },
                ),
            onDismiss = { confirmingDelete = false },
        )
    }
    if (deleteFailed) {
        IOSAlert(
            title = tr("탈퇴하지 못했어요. 잠시 뒤 다시 해 주세요"),
            message = null,
            actions = listOf(IOSAction(tr("확인"), IOSRole.CANCEL) {}),
            onDismiss = { deleteFailed = false },
        )
    }
}

// iOS `List(.insetGrouped)`(iOS 26): 섹션 머리 15 semibold 회색(카드 안쪽 16 에 맞춤), 카드 모서리 26,
// 좌우 여백 16. 줄은 카드에 바로 붙고 줄 사이에 구분선이 있다(2026-09-28 대조 #4).
@Composable
private fun ProfileSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 26.dp)) {
        Text(
            title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = IOS.secondaryLabel,
            modifier = Modifier.padding(start = 16.dp, bottom = 10.dp),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(26.dp))
                    .background(IOS.systemBackground),
        ) {
            content()
        }
    }
}

/**
 * 줄 사이 선. 아이콘 줄 아래는 아이콘 칸 뒤(16 + 26 + 12 = 54)부터, 아이콘 없는 줄(토글·지우기) 아래는
 * 16 부터 오른쪽 16 앞까지. iOS 26 목록 선은 머리카락이 아니라 **1pt 의 연한 회색(232)** 이다(실측).
 */
@Composable
private fun ProfileDivider(afterIcon: Boolean = true) {
    androidx.compose.material3.HorizontalDivider(
        thickness = 1.dp,
        color = Color(0xFFE8E8E8),
        modifier = Modifier.padding(start = if (afterIcon) 54.dp else 16.dp, end = 16.dp),
    )
}

/** 목록 줄 — iOS `row(symbol:tint:title:value:chevron:)`. 높이 52, 아이콘 칸 26, 글자 15. */
@Composable
private fun ProfileRow(
    title: String,
    value: String,
    onClick: (() -> Unit)?,
    dimmed: Boolean = false,
    chevron: Boolean = false,
    icon: @Composable (Modifier) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp),
    ) {
        Box(modifier = Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            icon(Modifier.size(18.dp))
        }
        Text(title, fontSize = 15.sp, color = if (dimmed) IOS.tertiaryLabel else IOS.label, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, fontSize = 15.sp, color = IOS.secondaryLabel, maxLines = 1)
        if (chevron) {
            ChevronRightIcon(IOS.tertiaryLabel, Modifier.size(11.dp))
        }
    }
}
