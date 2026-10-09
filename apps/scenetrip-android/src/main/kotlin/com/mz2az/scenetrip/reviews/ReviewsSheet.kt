package com.mz2az.scenetrip.reviews

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Photo
import com.mz2az.scenetrip.sceneapi.client.model.PhotoSource
import com.mz2az.scenetrip.sceneapi.client.model.RatingSummary
import com.mz2az.scenetrip.sceneapi.client.model.Review
import com.mz2az.scenetrip.sceneapi.client.model.ReviewSort
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
    val label =
        if (rating.count ==
            0
        ) {
            tr("아직 리뷰가 없어요 · 첫 리뷰 쓰기")
        } else {
            "★ ${rating.average?.let { "%.1f".format(it) } ?: "-"} · ${tr("리뷰 %d").format(rating.count)}"
        }
    Text(label, style = IOS.subheadline, color = IOS.accent, modifier = modifier.clickable(onClick = onClick).padding(vertical = 10.dp))
}

@Composable
fun ReviewRow(
    review: Review,
    subject: ReviewSubject,
    onEdit: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(review.author?.nickname ?: tr("탈퇴한 사용자"), style = IOS.headline, color = IOS.label)
            if (review.isMine &&
                onEdit != null
            ) {
                Text(tr("고치기"), color = IOS.accent, modifier = Modifier.clickable(onClick = onEdit).padding(4.dp))
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
    val listState =
        androidx.compose.foundation.lazy
            .rememberLazyListState()
    var reviews by remember(subject) { mutableStateOf<List<Review>>(emptyList()) }
    var summary by remember(subject) { mutableStateOf<RatingSummary?>(null) }
    var total by remember(subject) { mutableStateOf(0) }
    var offset by remember(subject) { mutableStateOf(0) }
    var sort by remember(subject) { mutableStateOf(ReviewSort.recent) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var composing by remember { mutableStateOf(false) }
    var waitingSignIn by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var visitorPhotos by remember(subject) { mutableStateOf<List<Photo>>(emptyList()) }
    var photoTotal by remember(subject) { mutableStateOf(0) }
    var focusedReview by remember(subject, focusReviewId) { mutableStateOf(focusReviewId) }

    fun startWriting() {
        if (AuthStore.signedIn) {
            composing = true
        } else {
            waitingSignIn = true
            AuthStore.promptSignIn()
        }
    }

    suspend fun load(reset: Boolean = false) {
        if (loading) return
        loading = true
        val epoch = AuthStore.epoch
        try {
            apiResult { subject.list(sort, if (reset) 0 else offset) }.fold(
                onSuccess = {
                    if (epoch == AuthStore.epoch) {
                        reviews = ((if (reset) emptyList() else reviews) + it.items).distinctBy { row -> row.id }
                        offset = (if (reset) 0 else offset) + it.items.size
                        total = if (it.items.isEmpty()) offset else it.total
                        summary = it.summary
                        error = null
                    }
                },
                onFailure = { if (epoch == AuthStore.epoch) error = NetworkFailure.of(it).message },
            )
        } finally {
            loading = false
        }
    }
    LaunchedEffect(subject, sort, revision, AuthStore.epoch) {
        load(reset = true)
        apiResult { subject.photos(0) }.onSuccess { page ->
            visitorPhotos = page.items
            photoTotal = page.total
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
                    if (photoTotal >
                        0
                    ) {
                        GalleryStrip(subject, visitorPhotos, photoTotal, height = 100, visitorsOnly = true, onReview = { id ->
                            focusedReview = id
                        })
                    }
                }
                items(reviews, key = { it.id }) { row ->
                    ReviewRow(row, subject, onEdit = { composing = true })
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
    if (composing) {
        ReviewComposeView(subject, onClose = { composing = false }, onChanged = {
            revision += 1
            onChanged()
        })
    }
}
