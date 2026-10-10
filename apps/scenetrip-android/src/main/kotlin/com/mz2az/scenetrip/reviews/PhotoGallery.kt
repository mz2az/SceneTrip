package com.mz2az.scenetrip.reviews

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Photo
import com.mz2az.scenetrip.sceneapi.client.model.PhotoSource
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.SheetDetent
import kotlinx.coroutines.launch

val Photo.galleryKey: String get() = ReviewRules.photoKey(url.toString(), reviewId.takeIf { source == PhotoSource.review })

/** 상세의 앞 20장과 이어 받은 사진을 같은 사진첩에 보관한다. */
class PhotoBook(
    val subject: ReviewSubject,
    initial: List<Photo>,
    total: Int,
    private val visitorsOnly: Boolean = false,
    private val paged: Boolean = true,
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
) {
    val scope = rememberCoroutineScope()
    val book = remember(subject, photos, total, visitorsOnly) { PhotoBook(subject, photos, total, visitorsOnly, paged) }
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
    Column {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(height.dp)) { index ->
            val photo = book.photos.getOrNull(index) ?: return@HorizontalPager
            GalleryImage(
                photo,
                Modifier.fillMaxSize().clickable {
                    viewer = index
                },
                onFailure = { scope.launch { book.renew() } },
                onRetry = { scope.launch { book.renew(true) } },
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (book.photos.getOrNull(pager.currentPage)?.source == PhotoSource.review) Text(tr("방문자 사진"), color = IOS.secondaryLabel)
            Text(
                "▦ ${pager.currentPage + 1} / ${book.total}",
                color = IOS.accent,
                modifier = Modifier.clickable { grid = true }.padding(6.dp),
            )
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
        )
    }
    if (grid) {
        PhotoGrid(book, onClose = { grid = false }, onPick = {
            grid = false
            viewer = it
        })
    }
}

@Composable
private fun GalleryImage(
    photo: Photo,
    modifier: Modifier,
    edge: Int = 360,
    onFailure: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    Column(modifier) {
        RemoteImage(
            photo.url.toString(),
            Modifier
                .weight(
                    1f,
                ).fillMaxWidth(),
            contentScale = ContentScale.Fit,
            cacheKey = photo.galleryKey,
            edge = edge,
            retryable = true,
            onFailure = {
                if (photo.source ==
                    PhotoSource.review
                ) {
                    onFailure()
                }
            },
            onRetry = onRetry,
        )
        photo.credit
            ?.takeIf {
                it.isNotBlank()
            }?.let { Text(it, style = IOS.caption2, color = IOS.secondaryLabel, modifier = Modifier.padding(4.dp)) }
    }
}

@Composable
private fun GalleryViewer(
    book: PhotoBook,
    initial: Int,
    onClose: (Int) -> Unit,
    onReview: ((Long?) -> Unit)?,
) {
    val scope = rememberCoroutineScope()
    val pager =
        rememberPagerState(initialPage = initial.coerceAtMost((book.photos.size - 1).coerceAtLeast(0)), pageCount = { book.photos.size })
    LaunchedEffect(pager.currentPage, book.photos.size) { if (pager.currentPage >= book.photos.size - 5) book.more() }
    IOSSheet(detents = listOf(SheetDetent.LARGE), onDismiss = { onClose(pager.currentPage) }) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            IOSSheetToolbar(
                title = "${pager.currentPage + 1} / ${book.total}",
                leading = tr("닫기"),
                onLeading = { onClose(pager.currentPage) },
            )
            HorizontalPager(pager, Modifier.weight(1f)) {
                val photo = book.photos.getOrNull(it) ?: return@HorizontalPager
                GalleryImage(photo, Modifier.fillMaxSize(), edge = 1600, onFailure = {
                    scope.launch { book.renew() }
                }, onRetry = { scope.launch { book.renew(true) } })
            }
            book.photos.getOrNull(pager.currentPage)?.takeIf { it.source == PhotoSource.review }?.let { photo ->
                Text(tr("방문자 사진"), color = IOS.secondaryLabel, modifier = Modifier.padding(12.dp))
                if (onReview !=
                    null
                ) {
                    Text(tr("리뷰 보기"), color = IOS.accent, modifier = Modifier.clickable { onReview(photo.reviewId) }.padding(16.dp))
                }
            }
            book.failure?.let { Text(it, color = IOS.systemOrange, modifier = Modifier.padding(12.dp)) }
        }
    }
}

@Composable
private fun PhotoGrid(
    book: PhotoBook,
    onClose: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    IOSSheet(detents = listOf(SheetDetent.LARGE), onDismiss = onClose) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            IOSSheetToolbar(title = tr("사진 전체 보기"), leading = tr("닫기"), onLeading = onClose)
            LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f)) {
                itemsIndexed(book.photos, key = { _, photo -> photo.galleryKey }) { index, photo ->
                    LaunchedEffect(index) { if (index >= book.photos.size - 5) book.more() }
                    GalleryImage(
                        photo,
                        Modifier.height(140.dp).padding(2.dp).clickable {
                            onPick(index)
                        },
                        onFailure = { scope.launch { book.renew() } },
                        onRetry = { scope.launch { book.renew(true) } },
                    )
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
}
