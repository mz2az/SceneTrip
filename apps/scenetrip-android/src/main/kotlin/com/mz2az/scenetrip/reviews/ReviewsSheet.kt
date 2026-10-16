package com.mz2az.scenetrip.reviews

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.ModerationController
import com.mz2az.scenetrip.data.ModerationEvents
import com.mz2az.scenetrip.data.ModerationRules
import com.mz2az.scenetrip.data.ModerationTarget
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Photo
import com.mz2az.scenetrip.sceneapi.client.model.PhotoSource
import com.mz2az.scenetrip.sceneapi.client.model.RatingSummary
import com.mz2az.scenetrip.sceneapi.client.model.ReportTargetType
import com.mz2az.scenetrip.sceneapi.client.model.Review
import com.mz2az.scenetrip.sceneapi.client.model.ReviewSort
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.SheetDetent
import kotlinx.coroutines.launch
import java.time.Duration

@Composable
fun RatingLine(
    rating: RatingSummary?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (rating == null) return
    Row(
        modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        RatingStar(rating.count > 0, Modifier.size(14.dp))
        if (rating.count == 0) {
            Text(tr("아직 리뷰가 없어요"), style = IOS.caption, color = IOS.secondaryLabel)
        } else {
            Text(rating.average?.let { "%.1f".format(it) } ?: "-", style = IOS.caption, color = IOS.label)
            Text("·", style = IOS.caption, color = IOS.tertiaryLabel)
            Text(tr("리뷰 %d").format(rating.count), style = IOS.caption, color = IOS.secondaryLabel)
        }
        ChevronRightIcon(IOS.tertiaryLabel, Modifier.size(9.dp))
    }
}

@Composable
private fun RatingStar(
    filled: Boolean,
    modifier: Modifier,
) {
    Canvas(modifier) {
        val edge = size.minDimension
        val path = Path()
        repeat(10) { index ->
            val angle = index * Math.PI / 5 - Math.PI / 2
            val radius = edge * if (index % 2 == 0) 0.46f else 0.2f
            val x = edge / 2 + (kotlin.math.cos(angle) * radius).toFloat()
            val y = edge / 2 + (kotlin.math.sin(angle) * radius).toFloat()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(
            path,
            if (filled) Color(0xFFFFB800) else IOS.secondaryLabel,
            style = if (filled) Fill else Stroke(width = 1.2.dp.toPx()),
        )
    }
}

@Composable
fun ReviewRow(
    review: Review,
    subject: ReviewSubject,
    onEdit: (() -> Unit)? = null,
    // 남의 리뷰에 「⋯」(신고·차단)를 둔다 — 리뷰 시트가 준다(MZ2AZ-461). 없으면 메뉴가 없다.
    moderation: ModerationController? = null,
) {
    val menu = moderation != null && ModerationRules.showsMenu(isMine = review.isMine)
    Column(
        Modifier.fillMaxWidth().padding(top = if (menu) 4.dp else 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(review.author?.nickname ?: tr("탈퇴한 사용자"), style = IOS.headline, color = IOS.label, modifier = Modifier.weight(1f))
            if (review.isMine &&
                onEdit != null
            ) {
                Text(tr("고치기"), color = IOS.accent, modifier = Modifier.clickable(onClick = onEdit).padding(4.dp))
            }
            // 묶은 줄 밖에 둔다 — 안에 두면 읽기 묶음에 삼켜져 접근성으로 누를 수 없다.
            if (menu && moderation != null) {
                ModerationMenu(
                    target = ModerationTarget.review(review.id),
                    canBlock = ModerationRules.canBlock(isMine = review.isMine, hasAuthor = review.author != null),
                    controller = moderation,
                    modifier = Modifier.size(width = 44.dp, height = 36.dp),
                )
            }
        }
        Text("★".repeat(review.rating.coerceIn(0, 5)) + "☆".repeat((5 - review.rating).coerceIn(0, 5)), color = IOS.systemOrange)
        review.body?.let { Text(it, style = IOS.subheadline, color = IOS.label) }
        Text(
            review.createdAt.toLocalDate().toString() +
                if (Duration.between(review.createdAt, review.updatedAt).seconds >= 1) {
                    " · ${tr("수정됨")}"
                } else {
                    ""
                },
            style = IOS.caption,
            color = IOS.secondaryLabel,
        )
        if (review.photos.isNotEmpty()) {
            GalleryStrip(
                subject,
                review.photos.map {
                    Photo(url = it.url, source = PhotoSource.review, reviewId = review.id)
                },
                review.photos.size,
                height = 100,
                paged = false,
            )
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
    val listing = remember(subject) { ReviewListStore({ sort, offset -> subject.list(sort, offset) }, { AuthStore.epoch }) }
    val list by listing.ui.collectAsState()
    val reviews = list.reviews
    val summary = list.summary
    val total = list.total
    val offset = list.offset
    val loading = list.loading
    val error = list.error
    var sort by remember(subject) { mutableStateOf(ReviewSort.recent) }
    var composing by remember { mutableStateOf(false) }
    var waitingSignIn by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var visitorPhotos by remember(subject) { mutableStateOf<List<Photo>>(emptyList()) }
    var photoTotal by remember(subject) { mutableStateOf(0) }
    var focusedReview by remember(subject, focusReviewId) { mutableStateOf(focusReviewId) }
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
            waitingSignIn = true
            AuthStore.promptSignIn()
        }
    }

    suspend fun load(reset: Boolean = false) = listing.load(sort, reset)

    // 다른 화면에서도 차단·풀기가 일어난다(`ModerationEvents`) — 키에 넣어 리뷰와 방문자 사진을 다시 받는다.
    LaunchedEffect(subject, sort, revision, AuthStore.epoch, moderationVersion) {
        val asked = moderationVersion
        load(reset = true)
        apiResult { subject.photos(0) }.onSuccess { page ->
            visitorPhotos = page.items
            photoTotal = page.total
            photosAt = asked
        }
        if (waitingSignIn && AuthStore.signedIn) {
            waitingSignIn = false
            composing = true
        }
    }
    LaunchedEffect(reviews, focusedReview, loading) {
        val index = reviews.indexOfFirst { it.id == focusedReview }
        if (index >= 0) {
            listState.animateScrollToItem(index + 3)
            focusedReview = null
        } else if (focusedReview != null && !loading && error == null && offset < total && offset < 100) {
            load()
        }
    }
    IOSSheet(detents = listOf(SheetDetent.LARGE), onDismiss = onClose) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            IOSSheetToolbar(title = subject.name, leading = tr("닫기"), onLeading = onClose)
            LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                item {
                    RatingLine(summary, { startWriting() })
                    summary?.let { rating ->
                        (5 downTo 1).forEach { score ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("$score ★", color = IOS.secondaryLabel)
                                val count = (rating.distribution.getOrNull(score - 1) ?: 0).coerceAtLeast(0)
                                val fraction = ReviewRules.ratingFraction(rating.distribution, score)
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(IOS.systemGray5),
                                ) {
                                    if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction).height(6.dp).background(IOS.accent))
                                }
                                Text(count.toString(), color = IOS.secondaryLabel)
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ReviewSort.entries.forEach { value ->
                            val label =
                                when (value) {
                                    ReviewSort.recent -> "최신순"
                                    ReviewSort.rating_high -> "별점 높은 순"
                                    ReviewSort.rating_low -> "별점 낮은 순"
                                }
                            Text(
                                tr(label),
                                color =
                                    if (sort ==
                                        value
                                    ) {
                                        IOS.accent
                                    } else {
                                        IOS.secondaryLabel
                                    },
                                modifier = Modifier.clickable(enabled = !loading) { sort = value }.padding(vertical = 12.dp),
                            )
                        }
                    }
                }
                item {
                    if (shownPhotoTotal >
                        0
                    ) {
                        GalleryStrip(subject, shownPhotos, shownPhotoTotal, height = 100, visitorsOnly = true, onReview = { id ->
                            focusedReview = id
                        })
                    }
                }
                items(reviews, key = { it.id }) { row ->
                    ReviewRow(row, subject, onEdit = { composing = true }, moderation = moderation)
                }
                item {
                    if (loading) CircularProgressIndicator(Modifier.padding(12.dp))
                    error?.let {
                        Text(
                            it,
                            color = IOS.systemOrange,
                            modifier = Modifier.clickable { scope.launch { load(reset = reviews.isEmpty()) } }.padding(12.dp),
                        )
                    }
                    if (!loading && error == null && offset < total) {
                        LaunchedEffect(offset) { load() }
                    }
                    if (!loading && total == 0 &&
                        error == null
                    ) {
                        Text(tr("아직 리뷰가 없어요"), color = IOS.secondaryLabel, modifier = Modifier.padding(16.dp))
                    }
                }
            }
            Text(
                tr(if (reviews.any { it.isMine }) "내 리뷰 고치기" else "리뷰 쓰기"),
                color = IOS.accent,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            startWriting()
                        }.navigationBarsPadding()
                        .padding(16.dp),
            )
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
