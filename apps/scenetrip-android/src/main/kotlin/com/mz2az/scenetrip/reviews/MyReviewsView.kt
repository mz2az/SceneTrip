package com.mz2az.scenetrip.reviews

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.PhotoPages
import com.mz2az.scenetrip.data.PhotoRenewal
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.api.ReviewsApi
import com.mz2az.scenetrip.sceneapi.client.model.MyReview
import com.mz2az.scenetrip.sceneapi.client.model.ReviewTargetType
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.SheetDetent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MyReviewsView(onClose: () -> Unit) {
    var rows by remember { mutableStateOf<List<MyReview>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var offset by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<MyReview?>(null) }
    var revision by remember { mutableStateOf(0) }
    var generation by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // 리뷰 사진의 주소는 한 시간 뒤 죽는다 — 못 받으면 읽은 만큼을 다시 받아 새 주소로 갈아 끼운다(MZ2AZ-477, iOS `renewPhotos`).
    val renewal = remember { PhotoRenewal(scope) }
    var renewing by remember { mutableStateOf(false) }
    var renewed by remember { mutableStateOf(0) }

    suspend fun load(reset: Boolean = false) {
        if (loading || (renewing && !reset) || !AuthStore.signedIn) return
        if (reset) {
            renewal.cancel()
            renewing = false
        }
        loading = true
        val epoch = AuthStore.epoch
        val request = ++generation
        try {
            apiResult { ReviewsApi(API_BASE).listMyReviews(acceptLanguage = AppLanguage.current, offset = if (reset) 0 else offset) }.fold(
                onSuccess = {
                    if (epoch == AuthStore.epoch && request == generation) {
                        rows = ((if (reset) emptyList() else rows) + it.items).distinctBy { row -> row.id }
                        offset =
                            (if (reset) 0 else offset) + it.items.size
                        total = if (it.items.isEmpty()) offset else it.total
                        failure = null
                    }
                },
                onFailure = { if (epoch == AuthStore.epoch && request == generation) failure = NetworkFailure.of(it).message },
            )
        } finally {
            if (epoch == AuthStore.epoch && request == generation) loading = false
        }
    }

    suspend fun renewPhotos(forced: Boolean) {
        if (loading || rows.isEmpty() || !AuthStore.signedIn) return
        renewal.run(forced) {
            val epoch = AuthStore.epoch
            val request = ++generation
            val count = rows.size
            renewing = true
            try {
                val page =
                    PhotoPages.refetch(count, { it.id }) { from ->
                        if (!AuthStore.signedIn || request != generation ||
                            epoch != AuthStore.epoch
                        ) {
                            throw CancellationException("목록이 바뀌었다")
                        }
                        val fresh =
                            withContext(
                                Dispatchers.IO,
                            ) { ReviewsApi(API_BASE).listMyReviews(acceptLanguage = AppLanguage.current, offset = from) }
                        PhotoPages.Page(fresh.items, fresh.total)
                    }
                currentCoroutineContext().ensureActive()
                if (!AuthStore.signedIn || request != generation || epoch != AuthStore.epoch || count != rows.size) {
                    throw CancellationException("목록이 바뀌었다")
                }
                rows = page.items
                offset = page.items.size
                total = page.total
                failure = null
            } finally {
                if (request == generation) {
                    renewing = false
                    // 새 순서에 맞춰 다음 쪽 받기가 다시 실행된다.
                    renewed++
                }
            }
        }
    }
    LaunchedEffect(AuthStore.epoch) {
        generation++
        renewal.cancel()
        renewing = false
        rows = emptyList()
        offset = 0
        total = 0
        failure = null
        selected = null
        loading = false
        if (!AuthStore.signedIn) onClose() else load(reset = true)
    }
    LaunchedEffect(revision) { if (revision > 0) load(reset = true) }
    IOSSheet(detents = listOf(SheetDetent.LARGE), onDismiss = onClose) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            IOSSheetToolbar(title = tr("내 리뷰"), leading = tr("닫기"), onLeading = onClose)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp)) {
                items(rows, key = { it.id }) { row ->
                    Column(Modifier.clickable { selected = row }.padding(vertical = 12.dp)) {
                        Text(row.target.name, style = IOS.headline, color = IOS.accent)
                        Text("★ ${row.rating} · ${row.createdAt.toLocalDate()}", color = IOS.secondaryLabel)
                        row.body?.let { Text(it, color = IOS.label) }
                        if (row.photos.isNotEmpty()) {
                            GalleryStrip(
                                ReviewSubject(
                                    row.target.id,
                                    row.target.name,
                                    row.target.type == ReviewTargetType.poi,
                                ),
                                row.photos.map {
                                    com.mz2az.scenetrip.sceneapi.client.model.Photo(
                                        it.url,
                                        com.mz2az.scenetrip.sceneapi.client.model.PhotoSource.review,
                                        reviewId = row.id,
                                    )
                                },
                                row.photos.size,
                                height = 100,
                                paged = false,
                                onRenew = { forced -> renewPhotos(forced) },
                            )
                        }
                    }
                }
                item {
                    if (loading) CircularProgressIndicator(Modifier.padding(16.dp))
                    if (!loading && rows.isEmpty() && failure == null) Text(tr("아직 쓴 리뷰가 없어요"), color = IOS.secondaryLabel)
                    if (!loading && offset < total && failure == null) LaunchedEffect(offset, renewed) { load() }
                    failure?.let {
                        Text(
                            it,
                            color = IOS.systemOrange,
                            modifier = Modifier.clickable { scope.launch { load(rows.isEmpty()) } }.padding(16.dp),
                        )
                    }
                }
            }
        }
    }
    selected?.let { row ->
        MyReviewTargetSheet(row.target, onClose = {
            selected = null
            revision++
        })
    }
}
