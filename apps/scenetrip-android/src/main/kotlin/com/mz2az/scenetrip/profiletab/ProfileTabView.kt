package com.mz2az.scenetrip.profiletab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.AccountDataReset
import com.mz2az.scenetrip.data.AccountDeletionCopy
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.FootprintStore
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.LegalLinks
import com.mz2az.scenetrip.data.LikeStore
import com.mz2az.scenetrip.data.OnboardingFlag
import com.mz2az.scenetrip.data.VisitStamp
import com.mz2az.scenetrip.data.collectVisitStamps
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.data.trCount
import com.mz2az.scenetrip.onboarding.OnboardingView
import com.mz2az.scenetrip.onboarding.PinoMascot
import com.mz2az.scenetrip.reviews.StarBubbleIcon
import com.mz2az.scenetrip.sceneapi.client.api.CoursesApi
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.ui.BagFillIcon
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.DocTextIcon
import com.mz2az.scenetrip.ui.GlobeIcon
import com.mz2az.scenetrip.ui.HandRaisedIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSAction
import com.mz2az.scenetrip.ui.IOSAlert
import com.mz2az.scenetrip.ui.IOSCloseButton
import com.mz2az.scenetrip.ui.IOSConfirmPopover
import com.mz2az.scenetrip.ui.IOSRole
import com.mz2az.scenetrip.ui.IOSToggle
import com.mz2az.scenetrip.ui.NoSignIcon
import com.mz2az.scenetrip.ui.PencilIcon
import com.mz2az.scenetrip.ui.QuestionCircleIcon
import com.mz2az.scenetrip.ui.RouteCurveIcon
import com.mz2az.scenetrip.ui.ShoeprintsIcon
import com.mz2az.scenetrip.ui.SignOutIcon
import com.mz2az.scenetrip.ui.SparklesRectangleStackIcon
import com.mz2az.scenetrip.ui.SquarePencilIcon
import com.mz2az.scenetrip.ui.iosLineBox
import com.mz2az.scenetrip.ui.openExternalUrl
import com.mz2az.scenetrip.ui.sfLine
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
 * 마이페이지. iOS `ProfileTab/ProfileTabView.swift`와 같은 계정·여행 기록을 보여 준다.
 * Google 로그인·닉네임·내 리뷰와 찜한 작품·내 코스·사용법을 연결한다.
 *
 * [onClose]는 홈이 덮개로 띄울 때 넘긴다 — 있으면 왼쪽 위에 닫기 단추가 생긴다.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ProfileTabView(onClose: (() -> Unit)? = null) {
    val context = LocalContext.current
    val likes = remember { LikeStore.getInstance(context) }
    val posts = remember { CommunityStore.getInstance(context) }
    val footprints = remember { FootprintStore.getInstance(context) }
    val language = remember { AppLanguage.getInstance(context) }
    val deviceId = remember { InstallIdentity.of(context) }

    var courses by remember { mutableStateOf<List<CourseSummary>>(emptyList()) }
    var courseCount by remember { mutableStateOf<Int?>(null) }
    // 코스 목록을 아직 못 받았거나 못 받았을 때 「아직 코스가 없습니다」 로 속이지 않는다 — 코스 탭의 [CourseListPhase] 와 같은 기준.
    var coursesLoading by remember { mutableStateOf(true) }
    var coursesFailed by remember { mutableStateOf(false) }
    var likesLoading by remember { mutableStateOf(true) }
    var likesFailure by remember { mutableStateOf<String?>(null) }
    // 검색 장바구니와 같은 저장소·행을 쓴다 — 마이페이지에서도 숨긴 장소를 안내하고 뺄 수 있다(MZ2AZ-458, iOS 와 같다).
    val cart = remember { CartStore(context) }
    var stamps by remember { mutableStateOf<List<VisitStamp>>(emptyList()) }

    var showingCart by remember { mutableStateOf(false) }
    var showingStamps by remember { mutableStateOf(false) }
    var replaying by remember { mutableStateOf(false) }
    var showingReels by remember { mutableStateOf(false) }
    var showingCourses by remember { mutableStateOf(false) }
    var showingLikes by remember { mutableStateOf(false) }
    var showingPosts by remember { mutableStateOf(false) }
    var clearingFootprints by remember { mutableStateOf(false) }
    var consentingToFootprints by remember { mutableStateOf(false) }
    var choosingLanguage by remember { mutableStateOf(false) }
    var showingReviews by remember { mutableStateOf(false) }
    // 차단한 사용자 시트 (MZ2AZ-461).
    var showingBlocked by remember { mutableStateOf(false) }
    var showingNickname by remember { mutableStateOf(false) }

    // 하드웨어 뒤로 (MZ2AZ-513) — iOS 의 X 와 같이 마이페이지를 닫는다. 시트(내 코스·찜·장바구니 등)는 자기 창이 먼저 받아 닫는다.
    if (onClose != null) BackHandler(onBack = onClose)

    // 찜 목록은 서버가 준 찜한 작품의 요약으로 그린다 — 작품 목록(`GET /contents`)에는 목록에서 빠진 작품이 없어
    // 숨긴 작품을 찜해 둔 사람의 목록에서 그 줄이 사라진다(MZ2AZ-458, iOS `LikeStore.likedWorks`).
    val likedWorks = likes.likedWorks

    suspend fun load() =
        coroutineScope {
            val generation = AccountDataReset.generation
            // 넷을 나란히 받는다 — 코스 상세(스탬프용)가 느려도 찜 목록은 바로 뜬다.
            likesLoading = true
            coursesLoading = true
            val likesDeferred = async { likes.refresh() }
            val cartDeferred = async { cart.refresh() }
            val coursesDeferred =
                async(Dispatchers.IO) {
                    runCatching { CoursesApi(API_BASE).listCourses(deviceId) }.getOrNull()
                }

            // 못 받았어도 앞서 받아 둔 목록이 있으면 그것을 보인다 — 찜은 있는데 목록이 빌 때만 「못 받음」 이다.
            // 오류 원문을 그대로 보이지 않는다 — 서버 문장은 한국어이고 내부 정보가 섞인다 (MZ2AZ-345).
            val reached = likesDeferred.await()
            if (generation != AccountDataReset.generation) return@coroutineScope
            likesFailure =
                if (reached || likes.works.isNotEmpty() || likes.contentIds.isEmpty()) {
                    null
                } else {
                    tr("찜한 작품을 불러오지 못했어요. 잠시 뒤 다시 해 주세요")
                }
            likesLoading = false

            cartDeferred.await()
            val list = coursesDeferred.await()
            if (generation != AccountDataReset.generation) return@coroutineScope
            list?.let {
                courses = it.items ?: emptyList()
                courseCount = courses.size
            }
            coursesFailed = list == null && courses.isEmpty()
            coursesLoading = false

            // 방문 스탬프 — 코스마다 상세를 받아 visitedAt이 찍힌 것만 모은다.
            val collected = runCatching { collectVisitStamps(courses, deviceId) }.getOrDefault(emptyList())
            if (generation == AccountDataReset.generation) stamps = collected
        }

    // 로그아웃·탈퇴 — 앞 계정의 코스·스탬프·찜 실패 문구를 먼저 비운다(다시 읽기가 실패해도 남지 않게, iOS `clearAccountData`).
    DisposableEffect(Unit) {
        val stop =
            AccountDataReset.watch {
                courses = emptyList()
                courseCount = 0
                coursesLoading = false
                coursesFailed = false
                stamps = emptyList()
                likesFailure = null
                likesLoading = false
            }
        onDispose { stop() }
    }
    // 세션이 풀리면 닉네임 바꾸기 시트를 내린다 — 그 밑에서는 로그인 화면이 못 뜬다(차단 목록은 시트가 스스로 닫는다).
    LaunchedEffect(AuthStore.signedIn) { if (!AuthStore.signedIn) showingNickname = false }
    var refreshing by remember { mutableStateOf(false) }
    val refreshScope = rememberCoroutineScope()

    // 계정이 바뀌면(로그인·로그아웃·탈퇴) 다시 읽는다 — iOS `.onAccountChange`.
    LaunchedEffect(AuthStore.epoch) {
        load()
        posts.refresh(mine = true)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(IOS.systemGray6)) {
            // iOS `.navigationBarTitleDisplayMode(.inline)` 는 44pt (7차 감사 P1: 56dp 라 X·제목이 6dp 아래로 앉았다).
            Box(modifier = Modifier.fillMaxWidth().statusBarsPadding().height(44.dp)) {
                Text(tr("마이페이지"), style = IOS.headline, color = IOS.label, modifier = Modifier.align(Alignment.Center))
                if (onClose != null) {
                    IOSCloseButton(
                        onClick = onClose,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                        // iOS: 시트(내 코스·찜·장바구니·내가 쓴 글 …)가 떠 있는 동안은 유리 원이 없다.
                        plain =
                            showingCart || showingStamps || showingReels || showingCourses || showingLikes || showingPosts ||
                                showingReviews || showingBlocked || showingNickname,
                    )
                }
            }

            // iOS insetGrouped List 는 첫 섹션 위에 약 38 을 비운다(2차 대조: 제목→해태 75.5 대 38).
            // 당겨서 새로고침 — 내 정보(닉네임)와 찜·코스·스탬프를 다시 읽는다 (iOS `.refreshable`).
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshScope.launch {
                        refreshing = true
                        AuthStore.loadMe()
                        load()
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                // 맨 아래는 제스처 막대만큼 더 비운다 — 설치 식별자가 막대에 맞닿아 가려졌다(MZ2AZ-513).
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 38.dp, bottom = navBottom + 20.2.dp)) {
                    item { ProfileHeader(onNickname = { showingNickname = true }) }
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
                                value = trCount("%d곳", cart.items.size),
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
                                    // iOS 카드 81pt = 44 + 위아래 18.5(41-profile 실측).
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 19.dp),
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
                                value = tr("%d개").format(posts.mineTotal),
                                onClick = { showingPosts = true },
                                chevron = true,
                                icon = { SquarePencilIcon(SYSTEM_INDIGO, it) },
                            )
                            // 내 리뷰 — 가입한 사람에게만 보인다(리뷰는 가입해야 쓴다, MZ2AZ-363). iOS 는 이 절 안에 둔다.
                            if (AuthStore.signedIn) {
                                ProfileDivider()
                                ProfileRow(
                                    title = tr("내 리뷰", "목록"),
                                    value = "",
                                    onClick = { showingReviews = true },
                                    chevron = true,
                                    icon = { StarBubbleIcon(SYSTEM_ORANGE, it) },
                                )
                            }
                            // 차단한 사용자 — 가입한 사람에게만 보인다(차단은 로그인해야 한다, MZ2AZ-461).
                            if (AuthStore.signedIn) {
                                ProfileDivider()
                                ProfileRow(
                                    title = tr("차단한 사용자"),
                                    value = "",
                                    onClick = { showingBlocked = true },
                                    chevron = true,
                                    icon = { NoSignIcon(Color(0xFF8E8E93), it) },
                                )
                            }
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
                                    SparklesRectangleStackIcon(Color.White, Modifier.size(26.dp))
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
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().height(PROFILE_ROW_HEIGHT).padding(horizontal = 16.dp),
                            ) {
                                Text(tr("발자취 기록하기"), fontSize = 15.sp, color = IOS.label, modifier = Modifier.weight(1f))
                                IOSToggle(checked = footprints.recording, onCheckedChange = {
                                    if (it) consentingToFootprints = true else footprints.updateRecording(false)
                                })
                            }
                            if (consentingToFootprints) {
                                IOSAlert(
                                    tr("발자취를 기록할까요?"),
                                    tr("여행 모드에서 지나간 길을 이 기기에만 저장해요. 서버로 보내지 않고, 로그아웃하거나 탈퇴하면 지워집니다."),
                                    listOf(
                                        IOSAction(tr("취소"), IOSRole.CANCEL) {},
                                        IOSAction(tr("기록하기")) { footprints.updateRecording(true) },
                                    ),
                                    onDismiss = { consentingToFootprints = false },
                                )
                            }
                            ProfileDivider(afterIcon = false)
                            ProfileRow(
                                title = tr("기록한 거리"),
                                value = tr("%.1f km · %d점").format(footprints.kilometers, footprints.points.size),
                                onClick = null,
                                icon = { ShoeprintsIcon(IOS.pinDeep, it) },
                            )
                            ProfileDivider()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().height(PROFILE_ROW_HEIGHT).padding(horizontal = 16.dp),
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
                                        .height(PROFILE_ROW_HEIGHT)
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
                        // 꼬리말 — 이동 경로는 개인정보라 어디에 저장되는지 한 줄로 알린다(iOS `footprintSection` 의 footer).
                        ProfileFooter(tr("이 기기에만 저장돼요. 서버로 보내지 않아요."))
                    }

                    item {
                        ProfileSection(tr("도움"), afterFooter = true) {
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
                            ProfileDivider()
                            // 연락처 공개(App Store 1.2, MZ2AZ-461) — 비회원에게도 보인다.
                            SupportContactRow(SYSTEM_BLUE)
                        }
                    }

                    // 이용 통계 수집 토글 (MZ2AZ-475) — iOS `statisticsSection`. 「도움」 바로 아래, AI 동의 위다.
                    item { ProfileStatisticsSettings() }

                    // AI 기능 동의 토글 (MZ2AZ-468). iOS 는 이용 통계 설정 바로 아래, 약관 위에 둔다.
                    item { ProfileAISettings() }

                    // 처리방침·약관 (MZ2AZ-455, App Store 5.1.1 — 앱 안에서 찾을 수 있어야 한다). 비회원에게도 보인다.
                    // iOS 와 같이 계정 바로 위에 두고, 앱의 다른 외부 페이지처럼 브라우저로 연다.
                    item {
                        ProfileSection(tr(LegalLinks.SECTION_TITLE_KO), afterFooter = true) {
                            ProfileRow(
                                title = tr(LegalLinks.PRIVACY_TITLE_KO),
                                value = "",
                                onClick = { openExternalUrl(context, LegalLinks.PRIVACY) },
                                chevron = true,
                                icon = { HandRaisedIcon(SYSTEM_BLUE, it) },
                            )
                            ProfileDivider()
                            ProfileRow(
                                title = tr(LegalLinks.TERMS_TITLE_KO),
                                value = "",
                                onClick = { openExternalUrl(context, LegalLinks.TERMS) },
                                chevron = true,
                                icon = { DocTextIcon(SYSTEM_BLUE, it) },
                            )
                        }
                    }

                    if (AuthStore.signedIn) {
                        item { AccountSection(onDeleted = { onClose?.invoke() }) }
                    }

                    // 비회원의 「이 기기의 기록 지우기」 (MZ2AZ-471) — iOS 는 계정 절 바로 아래에 둔다. 가입자에게는 보이지 않는다.
                    item { ProfileDeviceDataSection() }

                    item {
                        ProfileSection(tr("준비 중"), afterFooter = true) {
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
                        // iOS 는 마지막 카드 아래 35.1 + 52.3 칸의 가운데 (7차 감사 P6: 카드→글 중심 60.6).
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 35.1.dp).height(52.3.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                // iOS `UUID.uuidString` 은 대문자다 — Java 의 `UUID.toString()` 은 소문자라 같은 기기인데 다르게 보였다.
                                tr("설치 식별자 %s…").format(deviceId.toString().take(8).uppercase(java.util.Locale.ROOT)),
                                fontSize = 11.sp,
                                color = IOS.tertiaryLabel,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }

        if (showingCourses) MyCoursesSheet(courses, loading = coursesLoading, failed = coursesFailed, onClose = { showingCourses = false })
        if (showingLikes) LikedWorksSheet(likedWorks, likes, likesLoading, likesFailure, onClose = { showingLikes = false })
        if (showingCart) ProfileCartSheet(cart, onClose = { showingCart = false })
        if (showingStamps) StampsSheet(stamps, onClose = { showingStamps = false })
        if (showingPosts) {
            MyPostsSheet(onClose = { showingPosts = false })
        }
        if (showingReels) ReelsTeaserView(onClose = { showingReels = false })
        if (showingReviews) {
            com.mz2az.scenetrip.reviews
                .MyReviewsView(onClose = { showingReviews = false })
        }
        if (showingBlocked && AuthStore.signedIn) BlockedUsersView(onClose = { showingBlocked = false })
        if (showingNickname) {
            com.mz2az.scenetrip.reviews
                .NicknameView(onClose = { showingNickname = false })
        }
        if (replaying) {
            // 사용법 다시 보기는 덮개라 창이 없다 — 뒤로는 「건너뛰기」처럼 닫는다. 위 처리보다 나중에 등록돼 먼저 받는다.
            BackHandler { replaying = false }
            val flag = remember { OnboardingFlag(context) }
            OnboardingView(onboardingFlag = flag, onDone = { replaying = false })
        }
    }
}

/** 피노와 비회원 안내. 로그인이 서면 이 자리가 계정 카드가 된다. */
@Composable
private fun ProfileHeader(onNickname: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    ) {
        PinoMascot(width = 96.dp)
        // iOS `header` — 비회원이면 로그인 단추, 로그인했으면 이름과 메일 (MZ2AZ-336).
        if (AuthStore.signedIn) {
            val nickname = AuthStore.me?.nickname
            if (nickname != null) {
                // 닉네임이 남에게 보이는 이름이다(MZ2AZ-363). 눌러서 바꾼다 — 연필이 그 표시다.
                val changeLabel = tr("닉네임 바꾸기")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    modifier =
                        Modifier
                            .semantics(mergeDescendants = true) {
                                contentDescription = changeLabel
                                stateDescription = nickname
                            }.clickable(onClick = onNickname),
                ) {
                    Text(nickname, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                    PencilIcon(IOS.secondaryLabel, Modifier.size(12.dp))
                }
            } else {
                // 옛 서버는 닉네임이 없어 전처럼 계정 이름을 보인다 — 바꿀 수 없다.
                Text(AuthStore.me?.displayName ?: tr("여행자"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            }
            AuthStore.me?.email?.let { Text(it, fontSize = 12.sp, color = IOS.secondaryLabel) }
        } else {
            Text(tr("비회원으로 여행 중"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            Text(tr("로그인하면 코스와 찜이 계정에 저장돼요"), fontSize = 12.sp, color = IOS.secondaryLabel)
            Text(
                tr("로그인"),
                fontSize = 15.sp,
                // iOS 15pt 글줄 17.5 + 위아래 8 = 33.5(감사 실측). 글줄 `lineHeight` 는 한 줄 글에 안 먹어 37 이었다 — 높이 칸으로 고정.
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier =
                    Modifier
                        .padding(top = 4.dp)
                        .clip(CircleShape)
                        .background(IOS.accent)
                        .clickable(enabled = !AuthStore.busy) { AuthStore.promptSignIn() }
                        .padding(horizontal = 21.dp)
                        .iosLineBox(33.5.dp),
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
                    .height(PROFILE_ROW_HEIGHT)
                    .clickable(enabled = !AuthStore.busy) { confirmingDelete = true }
                    .padding(horizontal = 16.dp),
        ) {
            Text(tr("회원 탈퇴"), fontSize = 15.sp, color = IOS.systemRed)
        }
    }
    if (confirmingDelete) {
        IOSAlert(
            title = tr("정말 탈퇴할까요?"),
            // 서버는 탈퇴 시 작성한 리뷰·여행후기와 사진도 삭제한다(MZ2AZ-466, MZ2AZ-452) — iOS 문구 그대로.
            // 「탈퇴한 사용자」 표시는 작성자가 없는 옛 글을 위해 그대로 둔다(ReviewsSheet·CommunityModels).
            message = tr(AccountDeletionCopy.CONFIRM_KO),
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

/** iOS List 한 줄 — 52.33pt. 줄 사이 선은 이 높이 안에 들어간다(7차 감사 P3: 피치 52.33, 세 줄 카드 157.0). */
internal val PROFILE_ROW_HEIGHT = 52.33.dp

@Composable
internal fun ProfileSection(
    title: String?,
    afterFooter: Boolean = false,
    content: @Composable () -> Unit,
) {
    // 간격은 iOS 목록 실측(7차 감사 P5): 카드 아래→다음 카드 윗 = 머리 없음 35.4 · 꼬리말 뒤 머리 없음 17.6(꼬리말 29.7 포함 47.3) ·
    // 머리 있음 17.4 + 머리 칸 40.3 = 57.7 · 꼬리말 뒤 머리 있음 꼬리말 29.7 바로 아래 머리 칸 40.3 = 70.0. 머리 글 중심은 카드 윗에서 20.3 위.
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        if (title != null) {
            Spacer(Modifier.height(if (afterFooter) 0.dp else 17.4.dp))
            Box(modifier = Modifier.fillMaxWidth().height(40.3.dp), contentAlignment = Alignment.BottomStart) {
                Text(
                    title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = IOS.secondaryLabel,
                    modifier = Modifier.padding(start = 16.dp, bottom = 11.35.dp).sfLine(15f),
                )
            }
        } else {
            // 머리 없는 섹션(iOS `Section { } footer: { }`)은 제목 줄이 없다.
            Spacer(Modifier.height(if (afterFooter) 17.6.dp else 35.4.dp))
        }
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

/** 카드 아래 꼬리말 — iOS 한 줄 29.7pt (13pt 글 15.5 + 위 7.9 + 아래 6.3), 두 줄 45.7. */
@Composable
internal fun ProfileFooter(text: String) {
    var lines by remember { mutableIntStateOf(1) }
    Text(
        text,
        fontSize = 13.sp,
        color = IOS.secondaryLabel,
        onTextLayout = { lines = it.lineCount },
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .padding(top = 7.9.dp, bottom = 6.3.dp)
                .iosLineBox((15.5f + (lines - 1) * 16f).dp),
    )
}

/**
 * 줄 사이 선. 아이콘 줄 아래는 아이콘 칸 뒤(16 + 26 + 12 = 54)부터, 아이콘 없는 줄(토글·지우기) 아래는
 * 16 부터 오른쪽 16 앞까지. iOS 26 목록 선은 머리카락이 아니라 **1pt 의 연한 회색(232)** 이다(실측).
 */
@Composable
private fun ProfileDivider(afterIcon: Boolean = true) {
    // 칸을 차지하지 않고 앞 줄 맨 아래 1dp 에 겹쳐 그린다 — iOS 줄 52.33 안에 선이 들어 있다.
    androidx.compose.material3.HorizontalDivider(
        thickness = 1.dp,
        color = Color(0xFFE8E8E8),
        modifier =
            Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, 0) { placeable.place(0, -placeable.height) }
                }.padding(start = if (afterIcon) 54.dp else 16.dp, end = 16.dp),
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
                .height(PROFILE_ROW_HEIGHT)
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
