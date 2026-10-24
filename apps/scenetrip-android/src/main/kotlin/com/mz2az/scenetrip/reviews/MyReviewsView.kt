package com.mz2az.scenetrip.reviews

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.RelativeTime
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.routetab.PoiLabel
import com.mz2az.scenetrip.sceneapi.client.api.PoisApi
import com.mz2az.scenetrip.sceneapi.client.api.ReviewsApi
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.MyReview
import com.mz2az.scenetrip.sceneapi.client.model.ReviewTarget
import com.mz2az.scenetrip.sceneapi.client.model.ReviewTargetType
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.SheetDetent
import kotlinx.coroutines.launch

/** 내 리뷰 목록의 순수 규칙 (iOS `MyReviewRules`). */
object MyReviewRules {
    /** 계약의 대상 → 리뷰 시트가 받는 대상. */
    fun subject(
        target: ReviewTarget,
        name: String = target.name,
    ): ReviewSubject = ReviewSubject(target.id, name, isPoi = target.type == ReviewTargetType.poi)

    /** 편의시설의 앱 언어 이름을 앱이 따로 물어야 하는가 — 한국어 화면과 촬영지는 서버가 준 이름이 곧 답이다. */
    fun needsLookup(
        target: ReviewTarget,
        korean: Boolean,
    ): Boolean = target.type == ReviewTargetType.poi && !korean

    /**
     * 줄과 리뷰 시트 머리줄에 적을 이름. 물어 온 이름은 편의시설의 영어 화면에서만 쓴다 — 영어로 받아 둔 채 한국어로 바꾸면 원본으로
     * 돌아간다.
     */
    fun name(
        target: ReviewTarget,
        lookedUp: PoiLabel?,
        korean: Boolean,
    ): Pair<String, String?> {
        if (!needsLookup(target, korean) || lookedUp == null) return target.name to null
        return lookedUp.title to lookedUp.reading
    }

    /** 아이콘만으로는 낭독에서 종류가 안 드러난다 — 이름으로 읽힌다. */
    fun kindName(target: ReviewTarget): String = if (target.type == ReviewTargetType.poi) tr("편의시설") else tr("촬영지")
}

/**
 * 내 리뷰 (MZ2AZ-363, iOS `MyReviewsView`) — 내가 쓴 리뷰 전부. 촬영지와 편의시설이 섞여 최신순으로 온다(`GET /me/reviews`).
 * 줄을 누르면 그 대상의 상세가 열린다. 고치기·지우기는 별도 단추로 바로 연다.
 */
@Composable
fun MyReviewsView(onClose: () -> Unit) {
    var rows by remember { mutableStateOf<List<MyReview>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var phase by remember { mutableStateOf(ReviewListPhase.LOADING) }
    var moreFailed by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf<MyReview?>(null) }
    var editing by remember { mutableStateOf<MyReview?>(null) }
    var generation by remember { mutableStateOf(0) }
    // 편의시설의 앱 언어 이름 (MZ2AZ-367). 서버는 편의시설을 한국어 원본으로만 준다 — 영어 화면에서는 줄이 보일 때 상세를 한 번 물어
    // 카드와 같은 이름으로 바꾼다. 못 받으면 원본 그대로.
    val poiNames = remember { mutableStateMapOf<Long, PoiLabel>() }
    val scope = rememberCoroutineScope()
    val korean = AppLanguage.current == Lang.ko

    suspend fun reload() {
        if (!AuthStore.signedIn) return
        val asked = ++generation
        val epoch = AuthStore.epoch
        phase = ReviewListPhase.LOADING
        apiResult { ReviewsApi(API_BASE).listMyReviews(acceptLanguage = AppLanguage.current, offset = 0) }.fold(
            onSuccess = { page ->
                if (asked == generation && epoch == AuthStore.epoch && AuthStore.signedIn) {
                    rows = page.items
                    total = page.total
                    moreFailed = false
                    phase = ReviewListPhase.LOADED
                }
            },
            onFailure = { if (asked == generation && epoch == AuthStore.epoch) phase = ReviewListPhase.FAILED },
        )
    }

    suspend fun loadMore() {
        if (phase != ReviewListPhase.LOADED || loadingMore || !AuthStore.signedIn || !ReviewRules.hasMore(rows.size, total)) return
        val asked = generation
        val epoch = AuthStore.epoch
        val from = rows.size
        loadingMore = true
        try {
            apiResult { ReviewsApi(API_BASE).listMyReviews(acceptLanguage = AppLanguage.current, offset = from) }.fold(
                onSuccess = { page ->
                    if (asked == generation && epoch == AuthStore.epoch && from == rows.size) {
                        val known = rows.map { it.id }.toSet()
                        val fresh = page.items.filter { it.id !in known }
                        rows = rows + fresh
                        total = if (fresh.isEmpty()) rows.size else page.total
                    }
                },
                onFailure = { if (asked == generation && epoch == AuthStore.epoch) moreFailed = true },
            )
        } finally {
            loadingMore = false
        }
    }

    // 계정이 바뀌면 앞 계정의 줄을 치우고 다시 받는다. 세션이 풀리면 닫는다 — 내 리뷰는 로그인한 사람의 것이고, 남겨 두면
    // 「불러오지 못했어요」 만 반복된다.
    LaunchedEffect(AuthStore.epoch) {
        generation++
        rows = emptyList()
        total = 0
        poiNames.clear()
        opening = null
        editing = null
        if (!AuthStore.signedIn) onClose() else reload()
    }
    LaunchedEffect(AuthStore.signedIn) { if (!AuthStore.signedIn) onClose() }

    IOSSheet(detents = listOf(SheetDetent.LARGE), onDismiss = onClose) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            // 「내 리뷰」 는 리뷰 줄의 딱지(My review)이기도 하다 — 목록의 제목은 자리를 달리 적는다.
            ReviewSheetHeader(title = tr("내 리뷰", "목록"), onClose = onClose)
            HorizontalDivider(thickness = 0.5.dp, color = IOS.separator.copy(alpha = 0.25f))
            when (phase) {
                ReviewListPhase.LOADING -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }

                ReviewListPhase.FAILED -> {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ReviewLoadFailure(onRetry = { scope.launch { reload() } })
                    }
                }

                ReviewListPhase.LOADED -> {
                    LazyColumn(Modifier.weight(1f)) {
                        if (rows.isEmpty()) {
                            item(key = "empty") {
                                ReviewEmptyState(tr("아직 쓴 리뷰가 없어요"), tr("다녀온 촬영지와 가게에 별점을 남겨 보세요"))
                            }
                        }
                        items(rows, key = { it.id }) { review ->
                            LaunchedEffect(review.target.id) {
                                val target = review.target
                                if (MyReviewRules.needsLookup(target, korean) && poiNames[target.id] == null) {
                                    val epoch = AuthStore.epoch
                                    val looked = apiResult { PoisApi(API_BASE).getPoi(target.id, acceptLanguage = AppLanguage.current) }
                                    looked.onSuccess { detail ->
                                        if (epoch == AuthStore.epoch) {
                                            poiNames[target.id] =
                                                PoiLabel.make(
                                                    detail.name,
                                                    detail.displayName,
                                                    detail.nameRoman,
                                                    detail.category,
                                                    detail.categoryLabel,
                                                    detail.address,
                                                    detail.displayAddress,
                                                    korean = false,
                                                )
                                        }
                                    }
                                }
                            }
                            val name = MyReviewRules.name(review.target, poiNames[review.target.id], korean)
                            Column {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { opening = review }
                                        .padding(horizontal = 16.dp)
                                        .padding(top = 14.dp, bottom = if (review.photos.isEmpty()) 14.dp else 8.dp),
                                ) {
                                    MyReviewRow(review, name)
                                }
                                Text(
                                    tr("내 리뷰 고치기·지우기"),
                                    style = IOS.caption,
                                    color = IOS.accent,
                                    modifier =
                                        Modifier
                                            .heightIn(min = 44.dp)
                                            .clickable { editing = review }
                                            .padding(horizontal = 16.dp)
                                            .padding(vertical = 12.dp),
                                )
                                // 사진은 줄의 단추 밖에 둔다 — 줄은 대상 상세로, 사진은 크게 보기로 간다.
                                if (review.photos.isNotEmpty()) {
                                    Box(Modifier.padding(horizontal = 16.dp).padding(bottom = 14.dp)) {
                                        ReviewPhotoThumbs(
                                            MyReviewRules.subject(review.target, name.first),
                                            review.id,
                                            review.photos,
                                            size = 64.dp,
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    Modifier.padding(start = 16.dp),
                                    thickness = 0.5.dp,
                                    color = IOS.separator.copy(alpha = 0.25f),
                                )
                            }
                        }
                        if (ReviewRules.hasMore(rows.size, total)) {
                            item(key = "more") {
                                ReviewMoreFooter(moreFailed, onRetry = { moreFailed = false })
                                if (!moreFailed) LaunchedEffect(generation, rows.size) { loadMore() }
                            }
                        }
                    }
                }
            }
        }
    }
    opening?.let { row -> MyReviewTargetSheet(row.target, onClose = { opening = null }) }
    editing?.let { row ->
        val name = MyReviewRules.name(row.target, poiNames[row.target.id], korean)
        val heading = if (name.second != null) "${name.first} · ${name.second}" else name.first
        // 거기서 고치거나 지웠다 — 내 목록도 다시 읽는다.
        ReviewsSheet(
            subject = MyReviewRules.subject(row.target, heading),
            onClose = { editing = null },
            onChanged = { scope.launch { reload() } },
        )
    }
}

@Composable
private fun MyReviewRow(
    review: MyReview,
    name: Pair<String, String?>,
) {
    val whenText = remember(review.createdAt) { RelativeTime.format(review.createdAt) }
    val dateText = if (ReviewRules.edited(review.createdAt, review.updatedAt)) "$whenText · ${tr("수정됨")}" else whenText
    Column(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val kind = MyReviewRules.kindName(review.target)
            Box(Modifier.clearAndSetSemantics { contentDescription = kind }) {
                if (review.target.type == ReviewTargetType.poi) {
                    StorefrontIcon(IOS.secondaryLabel, Modifier.size(14.dp))
                } else {
                    ClapperIcon(IOS.secondaryLabel, Modifier.size(14.dp))
                }
            }
            // 카드와 같은 모양 — 제목, 그 아래 작은 줄. 한 줄로 이으면 뒤쪽(한국어 이름)이 잘렸다.
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(name.first, style = IOS.subheadlineSemibold, color = IOS.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                name.second?.let {
                    Text(
                        it,
                        style = IOS.caption,
                        color = IOS.secondaryLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ChevronRightIcon(IOS.tertiaryLabel, Modifier.size(10.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StarRow(review.rating, 11.dp)
            Text(dateText, style = IOS.caption2, color = IOS.tertiaryLabel)
        }
        review.body?.takeIf { it.isNotEmpty() }?.let {
            Text(
                it,
                style = IOS.subheadline.copy(fontWeight = FontWeight.Normal),
                color = IOS.secondaryLabel,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
