package com.mz2az.scenetrip.reviews

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.ModerationController
import com.mz2az.scenetrip.data.ModerationEvents
import com.mz2az.scenetrip.data.ModerationRules
import com.mz2az.scenetrip.data.ModerationTarget
import com.mz2az.scenetrip.data.RelativeTime
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Photo
import com.mz2az.scenetrip.sceneapi.client.model.RatingSummary
import com.mz2az.scenetrip.sceneapi.client.model.ReportTargetType
import com.mz2az.scenetrip.sceneapi.client.model.Review
import com.mz2az.scenetrip.sceneapi.client.model.ReviewSort
import com.mz2az.scenetrip.searchtab.SegmentedControl
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.SquarePencilIcon
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 별점 한 줄 — 「★4.6 · 리뷰 128」. 누르면 리뷰 시트가 열린다. 서버가 별점을 안 실어 주면 아무것도 그리지 않는다. */
@Composable
fun RatingLine(
    rating: RatingSummary?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val line = ReviewRules.line(rating)
    if (line == ReviewRules.Line.Hidden || rating == null) return
    val spoken = ReviewRules.spoken(rating)
    val hint = tr("리뷰 보기")
    Row(
        modifier.semantics { contentDescription = "$spoken. $hint" }.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when (line) {
            ReviewRules.Line.Empty -> {
                RatingStar(false, Modifier.size(14.dp))
                Text(tr("아직 리뷰가 없어요"), style = IOS.caption, color = IOS.secondaryLabel)
            }

            is ReviewRules.Line.Rated -> {
                RatingStar(true, Modifier.size(14.dp))
                Text(line.average, style = IOS.caption.copy(fontWeight = FontWeight.SemiBold), color = IOS.label)
                Text("·", style = IOS.caption, color = IOS.tertiaryLabel)
                Text(ReviewRules.countText(line.count), style = IOS.caption, color = IOS.secondaryLabel)
            }

            ReviewRules.Line.Hidden -> {
                Unit
            }
        }
        ChevronRightIcon(IOS.tertiaryLabel, Modifier.size(9.dp))
    }
}

@Composable
private fun RatingStar(
    filled: Boolean,
    modifier: Modifier,
) {
    StarGlyph(if (filled) ReviewRules.Star.FULL else ReviewRules.Star.EMPTY, 14.dp, modifier)
}

/** 리뷰 한 줄 — 닉네임·별점·날짜, 글, 사진. 작성자가 탈퇴했으면 「탈퇴한 사용자」. 글은 번역하지 않는다. */
@Composable
fun ReviewRow(
    review: Review,
    subject: ReviewSubject,
    onEdit: (() -> Unit)? = null,
    // 남의 리뷰에 「⋯」(신고·차단)를 둔다 — 리뷰 시트가 준다(MZ2AZ-461). 없으면 메뉴가 없다.
    moderation: ModerationController? = null,
    // 리뷰 사진을 못 받았을 때 목록을 다시 받아 새 주소를 받는 길(MZ2AZ-477) — 리뷰 시트가 준다.
    onRenewPhotos: (suspend (Boolean) -> Unit)? = null,
) {
    val menu = moderation != null && ModerationRules.showsMenu(isMine = review.isMine)
    val whenText = remember(review.createdAt) { RelativeTime.format(review.createdAt) }
    val dateText = if (ReviewRules.edited(review.createdAt, review.updatedAt)) "$whenText · ${tr("수정됨")}" else whenText
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            // 닉네임·별·날짜·글이 따로 읽히지 않게 한 덩이로 묶는다. 사진은 묶지 않는다 — 한 장씩 눌러 크게 본다.
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 이름·「내 리뷰」 묶음이 남는 폭을 다 갖고, 날짜는 그 바로 오른쪽 끝에 붙는다 — 전에는 이름과 빈칸이 남는 폭을 반씩 나눠
                // 날짜가 메뉴에서 66dp 떨어졌다(7차 감사 RV1, iOS `Spacer(minLength: 8)`).
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            ReviewRules.authorName(review.author),
                            style = IOS.subheadlineSemibold,
                            color = if (review.author == null) IOS.secondaryLabel else IOS.label,
                            maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (review.isMine) {
                            Text(
                                tr("내 리뷰"),
                                style = IOS.caption2.copy(fontWeight = FontWeight.SemiBold),
                                color = IOS.accent,
                                modifier =
                                    Modifier
                                        .clip(CircleShape)
                                        .background(IOS.accent.copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    Text(dateText, style = IOS.caption2, color = IOS.tertiaryLabel)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    StarRow(review.rating, 11.dp)
                    Spacer(Modifier.weight(1f))
                    if (review.isMine && onEdit != null) {
                        Text(
                            tr("고치기"),
                            style = IOS.caption.copy(fontWeight = FontWeight.SemiBold),
                            color = IOS.accent,
                            modifier = Modifier.clickable(onClick = onEdit).padding(vertical = 4.dp),
                        )
                    }
                }
                review.body?.takeIf { it.isNotEmpty() }?.let { Text(it, style = IOS.subheadline, color = IOS.label) }
            }
            // 묶은 줄 밖에 둔다 — 안에 두면 읽기 묶음에 삼켜져 접근성으로 누를 수 없다.
            if (menu && moderation != null) {
                ModerationMenu(
                    target = ModerationTarget.review(review.id),
                    canBlock = ModerationRules.canBlock(isMine = review.isMine, hasAuthor = review.author != null),
                    controller = moderation,
                    modifier = Modifier.size(width = 32.dp, height = 28.dp).offset(y = (-6).dp),
                )
            }
        }
        if (review.photos.isNotEmpty()) {
            ReviewPhotoThumbs(subject, review.id, review.photos, onRenew = onRenewPhotos)
        }
    }
}

@Composable
fun ReviewsSheet(
    subject: ReviewSubject,
    onClose: () -> Unit,
    onChanged: () -> Unit = {},
    focusReviewId: Long? = null,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val listState =
        androidx.compose.foundation.lazy
            .rememberLazyListState()
    // 목록 받기는 세대를 세는 한 곳이 맡는다 — 차단 뒤 늦게 온 응답이 지운 리뷰를 되살리지 못한다(MZ2AZ-461).
    val listing = remember(subject) { ReviewListStore({ sort, offset -> subject.list(sort, offset) }, { AuthStore.epoch }, scope) }
    val list by listing.ui.collectAsState()
    val reviews = list.reviews
    val summary = list.summary
    val total = list.total
    val offset = list.offset
    val loading = list.loading
    var sort by remember(subject) { mutableStateOf(ReviewSort.recent) }
    var composing by remember { mutableStateOf(false) }
    // 비회원이 쓰기를 눌러 로그인 화면으로 갔다 — 로그인하면 쓰기로 잇는다. 처음 가입한 사람에게는 닉네임 화면이 먼저 뜨므로
    // 그것이 닫힌 뒤에 잇는다.
    var composeAfterSignIn by remember { mutableStateOf(false) }
    var composeAfterNickname by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var visitorPhotos by remember(subject) { mutableStateOf<List<Photo>>(emptyList()) }
    var photoTotal by remember(subject) { mutableStateOf(0) }
    // 이 리뷰부터 보인다(사진첩의 「리뷰 보기」) — 찾아가는 동안 값이 남는다. 찾은 줄은 잠깐 바탕을 칠한다.
    var focusedReview by remember(subject, focusReviewId) { mutableStateOf(focusReviewId) }
    var lit by remember(subject) { mutableStateOf<Long?>(null) }
    // 남의 리뷰의 신고·차단(MZ2AZ-461).
    val moderation = rememberModerationController()
    val moderationVersion by ModerationEvents.shared.version.collectAsState()
    // 방문자 사진을 받아 둔 때의 신호 번호 — 그 뒤에 차단·풀기가 있었으면 다시 받기 전까지 비워 둔다.
    var photosAt by remember(subject) { mutableStateOf(moderationVersion) }
    val shownPhotos = if (photosAt == moderationVersion) visitorPhotos else emptyList()
    val shownPhotoTotal = if (photosAt == moderationVersion) photoTotal else 0

    fun startWriting() {
        if (AuthStore.signedIn) {
            composing = true
        } else {
            composeAfterSignIn = true
            AuthStore.promptSignIn()
        }
    }

    suspend fun load(reset: Boolean = false) = listing.load(sort, reset)

    // 열 때 한 번 — 어느 대상의 리뷰를 봤는지(글은 보내지 않는다).
    LaunchedEffect(subject) { AppAnalytics.log(AppEvent.ViewReviews(subject.kind)) }
    // 다른 화면에서도 차단·풀기가 일어난다(`ModerationEvents`) — 키에 넣어 리뷰와 방문자 사진을 다시 받는다.
    LaunchedEffect(subject, sort, revision, AuthStore.epoch, moderationVersion) {
        val asked = moderationVersion
        load(reset = true)
        apiResult { subject.photos(0) }.onSuccess { page ->
            visitorPhotos = page.items
            photoTotal = page.total
            photosAt = asked
        }
    }
    // 로그인하면 「내 리뷰」 표시가 달라진다 — 목록은 위 키(계정)가 다시 받는다. 쓰던 화면은 닫는다.
    LaunchedEffect(AuthStore.epoch) {
        if (composeAfterSignIn.not()) composing = false
        lit = null
    }
    // 로그인 화면이 내려간 뒤에 쓰기 화면을 올린다 — 처음 가입한 사람에게는 닉네임 화면이 먼저 뜬다(로그인 600ms 뒤).
    LaunchedEffect(AuthStore.signedIn) {
        if (AuthStore.signedIn && composeAfterSignIn) {
            composeAfterSignIn = false
            delay(1000)
            if (AuthStore.signedIn) {
                if (AuthStore.showingNickname) composeAfterNickname = true else composing = true
            }
        }
    }
    LaunchedEffect(AuthStore.showingNickname) {
        // 닉네임을 정했거나 건너뛰었다 — 리뷰를 쓰러 가입한 사람이 다시 누르지 않게 쓰기로 잇는다.
        if (!AuthStore.showingNickname && composeAfterNickname) {
            composeAfterNickname = false
            delay(600)
            if (AuthStore.signedIn) composing = true
        }
    }
    LaunchedEffect(AuthStore.showingSignIn) {
        // 로그인하지 않고 닫았으면 잇지 않는다.
        if (!AuthStore.showingSignIn && !AuthStore.signedIn) composeAfterSignIn = false
    }
    // 위쪽에 요약·방문자 사진·정렬이 붙는 만큼 리뷰 줄의 번호가 밀린다.
    val leading =
        (if (summary != null && summary.count > 0) 1 else 0) + (if (shownPhotoTotal > 0) 1 else 0) + (if (reviews.isNotEmpty()) 1 else 0)
    LaunchedEffect(reviews, focusedReview, list.phase) {
        val target = focusedReview ?: return@LaunchedEffect
        val index = reviews.indexOfFirst { it.id == target }
        if (index >= 0) {
            // 사진첩을 닫는 동안은 스크롤이 먹지 않는다 — 잠깐 기다렸다가 내려가 칠한다.
            delay(450)
            listState.animateScrollToItem(leading + index)
            focusedReview = null
            lit = target
            delay(2000)
            lit = null
        } else if (list.phase == ReviewListPhase.LOADED && !loading && !list.moreFailed && offset < total && offset < 100) {
            // 아직 안 받은 뒤쪽에 있으면 몇 쪽 더 받아 찾는다 — 그래도 없으면 목록은 그대로 둔다.
            load()
        }
    }
    IOSSheet(detents = listOf(SheetDetent.LARGE), onDismiss = onClose) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            ReviewSheetHeader(title = tr("리뷰"), subtitle = subject.name, onClose = onClose)
            HorizontalDivider(thickness = 0.5.dp, color = IOS.separator.copy(alpha = 0.25f))
            when (list.phase) {
                ReviewListPhase.LOADING -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }

                ReviewListPhase.FAILED -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ReviewLoadFailure(onRetry = {
                            listing.prepareRetry()
                            scope.launch { load(reset = true) }
                        })
                    }
                }

                ReviewListPhase.LOADED -> {
                    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                        // 리뷰가 없으면 요약(큰 「-」 와 빈 막대 다섯)을 그리지 않는다 — 아래 빈 상태가 같은 말을 한다.
                        if (summary != null && summary.count > 0) {
                            item(key = "summary") {
                                Column {
                                    Box(Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) { ReviewSummaryView(summary) }
                                    HorizontalDivider(thickness = 0.5.dp, color = IOS.separator.copy(alpha = 0.25f))
                                }
                            }
                        }
                        // 방문자 사진 모아 보기 — 한 장도 없으면 이 절은 없다.
                        if (shownPhotoTotal > 0) {
                            item(key = "visitors") {
                                Column {
                                    VisitorPhotoStrip(subject, shownPhotos, shownPhotoTotal, onReview = { id -> focusedReview = id })
                                    HorizontalDivider(thickness = 0.5.dp, color = IOS.separator.copy(alpha = 0.25f))
                                }
                            }
                        }
                        if (reviews.isEmpty()) {
                            item(key = "empty") {
                                ReviewEmptyState(tr("아직 리뷰가 없어요"), tr("다녀온 분의 이야기가 여기에 모여요"))
                            }
                        } else {
                            item(key = "sort") {
                                Box(Modifier.padding(top = 14.dp, bottom = 4.dp)) {
                                    SegmentedControl(
                                        options = ReviewSort.entries,
                                        selected = sort,
                                        label = { value ->
                                            when (value) {
                                                ReviewSort.recent -> tr("최신순")
                                                ReviewSort.rating_high -> tr("별점 높은 순")
                                                ReviewSort.rating_low -> tr("별점 낮은 순")
                                            }
                                        },
                                        onSelect = { value ->
                                            if (value != sort) {
                                                // 새 첫 쪽이 올 때까지 다음 쪽을 받지 않는다 — 옛 목록 끝에 새 정렬의 둘째 쪽이 붙지 않게.
                                                listing.prepareSortChange()
                                                sort = value
                                            }
                                        },
                                        modifier = Modifier.semantics { contentDescription = tr("정렬") },
                                    )
                                }
                            }
                            items(reviews, key = { it.id }) { row ->
                                Column {
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .background(IOS.accent.copy(alpha = if (lit == row.id) 0.1f else 0f))
                                            // 줄 이름 글에서 다음 줄 이름 글까지 iOS 185.3 / Android 190.4(감사 실측) — 차이 5 는 위아래 여백에서 났다. 4차 에뮬레이터 실측 186.7 → 185.3: 12 → 11.3.
                                            .padding(horizontal = 16.dp, vertical = 11.3.dp),
                                    ) {
                                        ReviewRow(
                                            row,
                                            subject,
                                            onEdit = { composing = true },
                                            moderation = moderation,
                                            onRenewPhotos = { forced -> listing.renewPhotos(forced) },
                                        )
                                    }
                                    HorizontalDivider(
                                        Modifier.padding(start = 16.dp),
                                        thickness = 0.5.dp,
                                        color = IOS.separator.copy(alpha = 0.25f),
                                    )
                                }
                            }
                            if (ReviewRules.hasMore(reviews.size, total)) {
                                item(key = "more") {
                                    ReviewMoreFooter(list.moreFailed, onRetry = { listing.retryMore() })
                                    // 열쇠가 바뀌면 앞 작업은 취소되고 새로 돈다 — 플래그로 막지 않는다.
                                    if (!list.moreFailed) {
                                        LaunchedEffect(reviews.size, sort, revision, list.revision) { load() }
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(thickness = 0.5.dp, color = IOS.separator.copy(alpha = 0.25f))
                    ReviewWriteBar(hasMine = reviews.any { it.isMine }, onClick = { startWriting() })
                }
            }
        }
    }
    ModerationSheets(moderation) { target ->
        // 작성자를 차단했다(MZ2AZ-461). 그 리뷰는 바로 빼고, 서버가 그 사람의 리뷰·사진을 뺀 목록을 다시 받는다.
        // 부른 화면(사진 줄)과 여행후기 목록도 다시 읽는다. 리뷰·사진은 위의 키(`moderationVersion`)가 다시 받는다.
        if (target.type == ReportTargetType.review) listing.drop(target.targetId)
        onChanged()
        CommunityStore.getInstance(context).invalidateAndRefreshInBackground()
    }
    if (composing) {
        ReviewComposeView(subject, onClose = { composing = false }, onChanged = {
            revision += 1
            onChanged()
        })
    }
}

/** 아래에 붙은 쓰기 단추. 내 리뷰가 목록에 보이면 「내 리뷰 고치기」 (iOS `writeBar`). */
@Composable
private fun ReviewWriteBar(
    hasMine: Boolean,
    onClick: () -> Unit,
) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(CircleShape)
                .background(IOS.accent)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            SquarePencilIcon(Color.White, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                tr(if (hasMine) "내 리뷰 고치기" else "리뷰 쓰기"),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
        }
    }
}
