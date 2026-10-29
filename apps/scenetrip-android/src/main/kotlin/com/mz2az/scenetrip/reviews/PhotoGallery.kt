package com.mz2az.scenetrip.reviews

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.data.ModerationEvents
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.PhotoRenewalRules
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Photo
import com.mz2az.scenetrip.sceneapi.client.model.PhotoSource
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.DisableDialogDim
import com.mz2az.scenetrip.ui.FullScreenDialogWindow
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSCloseButton
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.SquareGrid2x2Icon
import com.mz2az.scenetrip.ui.XMarkIcon
import kotlinx.coroutines.launch

val Photo.galleryKey: String get() = ReviewRules.photoKey(url.toString(), reviewId.takeIf { source == PhotoSource.review })

/** 상세의 앞 20장과 이어 받은 사진을 같은 사진첩에 보관한다. */
class PhotoBook(
    val subject: ReviewSubject,
    initial: List<Photo>,
    total: Int,
    private val visitorsOnly: Boolean = false,
    private val paged: Boolean = true,
    // 쪽으로 나뉘지 않은 사진(리뷰 한 건의 사진)은 사진첩을 다시 받을 수 없다 — 그 사진이 든 목록이 대신 새 주소를 받는다
    // (MZ2AZ-477, iOS 의 `\.photoRenew` 환경값). 없으면 갱신할 길이 없다.
    private val externalRenew: (suspend (Boolean) -> Unit)? = null,
) {
    private var allPhotos by mutableStateOf(initial.distinctBy { it.galleryKey })
        private set
    val photos get() = if (visitorsOnly) allPhotos.filter { it.source == PhotoSource.review } else allPhotos
    private var serverTotal by mutableStateOf(total.coerceAtLeast(initial.size))
    val total: Int get() =
        if (!visitorsOnly) {
            maxOf(photos.size, serverTotal - (offset - allPhotos.size))
        } else {
            val firstReview = allPhotos.indexOfFirst { it.source == PhotoSource.review }
            if (firstReview < 0) photos.size else maxOf(photos.size, serverTotal - firstReview - (offset - allPhotos.size))
        }
    var loading by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    private var offset = initial.size
    private var ended = false
    private var renewing = false
    private var renewedAt: Long? = null

    suspend fun more() {
        if (!paged || loading || renewing || ended || offset >= serverTotal) return
        loading = true
        try {
            apiResult { subject.photos(offset, if (visitorsOnly && photos.isEmpty()) 100 else 20) }.fold(
                onSuccess = {
                    offset += it.items.size
                    allPhotos = (allPhotos + it.items).distinctBy { photo -> photo.galleryKey }
                    serverTotal = it.total
                    ended = it.items.isEmpty() || offset >= it.total
                    failure = null
                },
                onFailure = { failure = NetworkFailure.of(it).message },
            )
        } finally {
            loading = false
        }
    }

    suspend fun seekVisitors() {
        repeat(3) { if (photos.isEmpty() && !ended && failure == null) more() }
    }

    suspend fun renew(forced: Boolean = false) {
        if (!paged) {
            externalRenew?.invoke(forced)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!paged || renewing || loading || (!forced && renewedAt?.let { now - it < 30_000 } == true)) return
        renewing = true
        renewedAt = now
        val wanted = maxOf(offset, 20)
        try {
            val result =
                apiResult {
                    var fetched = 0
                    var freshTotal = serverTotal
                    val fresh = mutableListOf<Photo>()
                    while (fetched < wanted) {
                        val page = subject.photos(fetched, minOf(100, wanted - fetched))
                        fresh += page.items
                        fetched += page.items.size
                        freshTotal = page.total
                        if (page.items.isEmpty() || fetched >= page.total) break
                    }
                    Triple(fresh, fetched, freshTotal)
                }
            result
                .onSuccess { (fresh, fetched, count) ->
                    allPhotos = fresh.distinctBy { it.galleryKey }
                    offset = fetched
                    serverTotal = count
                    ended = fetched >= count || fresh.isEmpty()
                    failure = null
                }.onFailure { failure = NetworkFailure.of(it).message }
        } finally {
            renewing = false
        }
    }
}

/** 대표 사진·카드·리뷰의 사진이 같은 크게 보기와 출처 표기를 쓴다. */
@Composable
fun GalleryStrip(
    subject: ReviewSubject,
    photos: List<Photo>,
    total: Int,
    height: Int = 180,
    onReview: ((Long?) -> Unit)? = null,
    visitorsOnly: Boolean = false,
    paged: Boolean = true,
    // 지도 카드의 사진은 서버 썸네일, 상세의 대표 사진은 원본이다 (MZ2AZ-470, iOS `.tile`/`.page`).
    thumbnail: Boolean = false,
    modifier: Modifier = Modifier,
    onRenew: (suspend (Boolean) -> Unit)? = null,
    /** 분석 이벤트의 `entry` — `detail`(촬영지 상세 대표 사진 자리)·`card`(지도 카드). */
    entry: String = "detail",
) {
    val scope = rememberCoroutineScope()
    // 차단·풀기가 일어나면(MZ2AZ-461) 책을 새로 만든다 — 나가 있던 「다음 쪽」·주소 갱신 응답이 옛 책에만 쓰이고, 차단한 사람의
    // 방문자 사진이 새 책에 섞이지 않는다. 부른 쪽은 그 사이 `ReviewRules.withoutReviewPhotos` 로 비운 목록을 준다.
    val moderation by ModerationEvents.shared.version.collectAsState()
    val renewal by rememberUpdatedState(onRenew)
    val book =
        remember(subject, photos, total, visitorsOnly, moderation) {
            PhotoBook(subject, photos, total, visitorsOnly, paged, externalRenew = { forced -> renewal?.invoke(forced) })
        }
    val pager = rememberPagerState(pageCount = { book.photos.size })
    var viewer by remember { mutableStateOf<Int?>(null) }
    var grid by remember { mutableStateOf(false) }
    LaunchedEffect(book) { if (visitorsOnly) book.seekVisitors() }
    if (book.photos.isEmpty()) {
        book.failure?.let {
            Text(
                it,
                color = IOS.systemOrange,
                modifier = Modifier.clickable { scope.launch { book.more() } }.padding(8.dp),
            )
        }
        return
    }
    LaunchedEffect(pager.currentPage, book.photos.size) {
        if (pager.currentPage >= book.photos.size - 5) book.more()
    }
    // 변경 금지(공공누리 제3유형) 사진이 하나라도 있으면 모서리를 깎지 않고, 방문자 표식·쪽 번호도 사진 위에 겹치지 않고
    // 아래 줄로 옮긴다 (MZ2AZ-470, iOS `protectedPhotos`).
    val protectedPhotos = book.photos.any(ReviewRules::isProtected)
    val pageCounter: @Composable (Modifier) -> Unit = { counterModifier ->
        val tint = if (protectedPhotos) IOS.secondaryLabel else Color.White
        // iOS: `square.grid.2x2`(10pt semibold) + 「3 / 19」(caption semibold, 고정폭 숫자), 사이 4, 안쪽 9·5.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier =
                counterModifier
                    .clickable { grid = true }
                    .background(if (protectedPhotos) IOS.systemGray5 else Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
        ) {
            SquareGrid2x2Icon(tint, Modifier.size(10.dp))
            Text(
                "${pager.currentPage + 1} / ${book.total}",
                color = tint,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                style = IOS.caption.copy(fontFeatureSettings = "tnum"),
            )
        }
    }
    val visitorBadge: @Composable (Modifier) -> Unit = { badgeModifier ->
        Text(
            tr("방문자 사진"),
            color = Color.White,
            style = IOS.caption2,
            modifier =
                badgeModifier
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(height.dp).clip(RoundedCornerShape(if (protectedPhotos) 0.dp else 10.dp))) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(height.dp)) { index ->
                val photo = book.photos.getOrNull(index) ?: return@HorizontalPager
                GalleryImage(
                    photo,
                    Modifier.fillMaxSize().clickable {
                        viewer = index
                    },
                    thumbnail = thumbnail,
                    onFailure = { scope.launch { book.renew() } },
                    onRetry = { scope.launch { book.renew(true) } },
                )
            }
            if (!protectedPhotos) {
                if (book.photos.getOrNull(pager.currentPage)?.source == PhotoSource.review) {
                    visitorBadge(Modifier.align(Alignment.BottomStart).padding(8.dp))
                }
                if (book.total > 1) pageCounter(Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
        }
        val current = book.photos.getOrNull(pager.currentPage)
        val shownCredit = current?.let { ReviewRules.credit(it) }?.takeIf { it.isNotBlank() }
        if (protectedPhotos) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(shownCredit.orEmpty(), style = IOS.caption2, color = IOS.secondaryLabel, modifier = Modifier.weight(1f))
                if (current?.source == PhotoSource.review) visitorBadge(Modifier.padding(start = 6.dp))
                if (book.total > 1) pageCounter(Modifier.padding(start = 6.dp))
            }
        } else if (shownCredit != null) {
            Text(shownCredit, style = IOS.caption2, color = IOS.secondaryLabel, modifier = Modifier.padding(top = 4.dp))
        }
        book.failure?.let {
            Text(
                it,
                color = IOS.systemOrange,
                modifier = Modifier.clickable { scope.launch { book.more() } }.padding(8.dp),
            )
        }
    }
    viewer?.let { index ->
        GalleryViewer(
            book,
            index,
            onClose = { current ->
                viewer = null
                scope.launch {
                    pager.scrollToPage(
                        current.coerceAtMost(
                            (
                                book.photos.size -
                                    1
                            ).coerceAtLeast(0),
                        ),
                    )
                }
            },
            onReview =
                onReview?.let { callback ->
                    { id ->
                        viewer = null
                        callback(id)
                    }
                },
            entry = entry,
        )
    }
    if (grid) {
        PhotoGrid(book, onClose = { grid = false }, onReview = onReview, entry = entry)
    }
}

@Composable
internal fun GalleryImage(
    photo: Photo,
    modifier: Modifier,
    edge: Int = 360,
    onFailure: () -> Unit = {},
    onRetry: () -> Unit = {},
    fits: Boolean = ReviewRules.preservesPhotoFrame(photo),
    dark: Boolean = false,
    // 작은 칸이면 서버 썸네일을 받는다. 썸네일이 없으면 원본이다(MZ2AZ-470).
    thumbnail: Boolean = false,
) {
    // 서명된 주소(리뷰어가 올린 사진)만 한 시간 뒤 죽는다. 저절로 새 주소를 청하는 것은 **사진마다 한 번**이다(iOS `renewsAfterFailure`).
    val signed = photo.source == PhotoSource.review
    var renewedFor by remember(photo.galleryKey) { mutableStateOf(false) }
    val shown = ReviewRules.imageUrl(photo, thumbnail)
    RemoteImage(
        shown,
        modifier,
        contentScale = if (fits) ContentScale.Fit else ContentScale.Crop,
        backgroundColor = if (dark) Color.Black else IOS.systemGray6,
        // 썸네일과 원본이 같은 열쇠로 섞이지 않게 한다.
        cacheKey = if (shown == photo.url.toString()) photo.galleryKey else "${photo.galleryKey}#thumb",
        edge = edge,
        retryable = true,
        onFailure = {
            if (PhotoRenewalRules.renewsAfterFailure(signed, renewedFor)) {
                renewedFor = true
                onFailure()
            }
        },
        // 사람이 눌렀다 — 서명된 주소면 새 주소부터 받는다. 죽은 주소로는 몇 번을 해도 같다.
        onRetry = { if (signed) onRetry() },
    )
}

@Composable
internal fun GalleryViewer(
    book: PhotoBook,
    initial: Int,
    onClose: (Int) -> Unit,
    onReview: ((Long?) -> Unit)?,
    /** 리뷰 행의 사진을 크게 볼 때는 방문자 표식이 없다 — 어차피 전부 리뷰 사진이다(iOS `marksVisitors = false`). */
    marksVisitors: Boolean = true,
    /** 분석 이벤트 `view_photos` 의 `entry`. 없으면(리뷰 행의 사진) 기록하지 않는다 — iOS 는 거기서 `GalleryViewer` 가 아니라 `PhotoViewer` 를 쓴다. */
    entry: String? = null,
) {
    val scope = rememberCoroutineScope()
    // 크게 열 때마다 한 번 (iOS `GalleryViewer.onAppear`) — 넘기거나 쪽을 더 받는 것은 다시 기록하지 않는다.
    LaunchedEffect(Unit) { entry?.let { AppAnalytics.log(AppEvent.ViewPhotos(book.subject.kind, it)) } }
    val pager =
        rememberPagerState(
            initialPage = initial.coerceIn(0, (book.photos.size - 1).coerceAtLeast(0)),
            pageCount = { book.photos.size },
        )
    LaunchedEffect(pager.currentPage, book.photos.size) {
        if (book.photos.isEmpty()) onClose(0)
        if (pager.currentPage >= book.photos.size - 5) book.more()
    }
    Dialog(
        onDismissRequest = { onClose(pager.currentPage) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DisableDialogDim()
        FullScreenDialogWindow()
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        // 변경 금지(공공누리 제3유형) 사진이 든 사진첩은 옆 쪽으로 넘기는 중에도 닫기·쪽 번호·출처가 사진에 겹치지 않게
        // 위 줄 / 사진 / 아래 줄로 쌓는다 (MZ2AZ-470, iOS `PhotoViewer`).
        val protectedPhotos = book.photos.any(ReviewRules::isProtected)
        val topBar: @Composable (Modifier) -> Unit = { barModifier ->
            Row(
                barModifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${pager.currentPage + 1} / ${book.total}",
                    color = Color.White,
                    style = IOS.subheadlineSemibold,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                Box(
                    Modifier
                        .semantics { contentDescription = tr("닫기") }
                        .size(44.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .clickable { onClose(pager.currentPage) },
                    contentAlignment = Alignment.Center,
                ) {
                    XMarkIcon(Color.White, Modifier.size(15.dp))
                }
            }
        }
        val pages: @Composable (Modifier) -> Unit = { pagesModifier ->
            HorizontalPager(pager, pagesModifier) { index ->
                book.photos.getOrNull(index)?.let { photo ->
                    GalleryImage(
                        photo,
                        Modifier.fillMaxSize(),
                        edge = 1600,
                        fits = true,
                        dark = true,
                        onFailure = { scope.launch { book.renew() } },
                        onRetry = { scope.launch { book.renew(true) } },
                    )
                }
            }
        }
        val caption: @Composable (Modifier) -> Unit = { captionModifier ->
            book.photos.getOrNull(pager.currentPage)?.let { photo ->
                val visitor = marksVisitors && photo.source == PhotoSource.review
                val credit = ReviewRules.credit(photo)
                val reviewLink = photo.source == PhotoSource.review && onReview != null
                if (visitor || credit != null || reviewLink) {
                    Row(
                        captionModifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (visitor) Text(tr("방문자 사진"), color = Color.White, style = IOS.caption)
                        credit?.let {
                            Text(
                                it,
                                color = Color.White.copy(alpha = 0.75f),
                                style = IOS.caption2,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (reviewLink) {
                            Text(
                                tr("리뷰 보기", "사진"),
                                color = Color.White,
                                style = IOS.subheadlineSemibold,
                                modifier =
                                    Modifier
                                        .background(Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                                        .clickable { onReview?.invoke(photo.reviewId) }
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
        if (protectedPhotos) {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                topBar(Modifier)
                pages(Modifier.weight(1f).fillMaxWidth())
                caption(Modifier)
            }
        } else {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                pages(Modifier.fillMaxSize())
                topBar(Modifier)
                caption(Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

@Composable
internal fun PhotoGrid(
    book: PhotoBook,
    onClose: () -> Unit,
    onReview: ((Long?) -> Unit)?,
    /** 머리줄 — 전체 격자는 「사진 19」, 방문자 사진만 모은 격자는 「방문자 사진 10」(iOS `allTitle`·`visitorTitle`). */
    title: String = ReviewRules.allTitle(book.total),
    /** 격자에서 크게 열 때의 분석 `entry` (iOS `PhotoGridView.entry`). */
    entry: String,
) {
    val scope = rememberCoroutineScope()
    var viewing by remember { mutableStateOf<Int?>(null) }
    IOSSheet(detents = listOf(SheetDetent.LARGE), onDismiss = onClose) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = IOS.headline, color = IOS.label)
                    Text(book.subject.name, style = IOS.subheadline, color = IOS.secondaryLabel)
                }
                IOSCloseButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd))
            }
            LazyVerticalGrid(
                GridCells.Fixed(3),
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(book.photos, key = { _, photo -> photo.galleryKey }) { index, photo ->
                    LaunchedEffect(index) { if (index >= book.photos.size - 5) book.more() }
                    Box(Modifier.aspectRatio(1f).clickable { viewing = index }) {
                        GalleryImage(
                            photo,
                            Modifier.fillMaxSize(),
                            thumbnail = true,
                            onFailure = { scope.launch { book.renew() } },
                            onRetry = { scope.launch { book.renew(true) } },
                        )
                        if (photo.source == PhotoSource.review) {
                            Text(
                                tr("방문자 사진"),
                                style = IOS.caption2,
                                color = Color.White,
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(3.dp)
                                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
                }
                val credits = ReviewRules.credits(book.photos)
                if (credits.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            credits.joinToString("\n"),
                            style = IOS.caption2,
                            color = IOS.secondaryLabel,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
            book.failure?.let {
                Text(
                    it,
                    color = IOS.systemOrange,
                    modifier = Modifier.clickable { scope.launch { book.more() } }.padding(12.dp),
                )
            }
        }
    }
    viewing?.let { index ->
        GalleryViewer(
            book,
            index,
            onClose = { viewing = null },
            onReview =
                onReview?.let { callback ->
                    { id ->
                        viewing = null
                        onClose()
                        callback(id)
                    }
                },
            entry = entry,
        )
    }
}
