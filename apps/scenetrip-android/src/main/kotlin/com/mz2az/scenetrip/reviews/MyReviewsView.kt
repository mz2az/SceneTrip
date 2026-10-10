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
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.api.ReviewsApi
import com.mz2az.scenetrip.sceneapi.client.model.MyReview
import com.mz2az.scenetrip.sceneapi.client.model.ReviewTargetType
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.SheetDetent
import kotlinx.coroutines.launch

@Composable
fun MyReviewsView(onClose: () -> Unit) {
    var rows by remember { mutableStateOf<List<MyReview>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var offset by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<MyReview?>(null) }
    var revision by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    suspend fun load(reset: Boolean = false) {
        if (loading || !AuthStore.signedIn) return
        loading = true
        val epoch = AuthStore.epoch
        try {
            apiResult { ReviewsApi(API_BASE).listMyReviews(acceptLanguage = AppLanguage.current, offset = if (reset) 0 else offset) }.fold(
                onSuccess = {
                    if (epoch ==
                        AuthStore.epoch
                    ) {
                        rows = ((if (reset) emptyList() else rows) + it.items).distinctBy { row -> row.id }
                        offset =
                            (if (reset) 0 else offset) + it.items.size
                        total = if (it.items.isEmpty()) offset else it.total
                        failure = null
                    }
                },
                onFailure = { if (epoch == AuthStore.epoch) failure = NetworkFailure.of(it).message },
            )
        } finally {
            loading = false
        }
    }
    LaunchedEffect(AuthStore.epoch, revision) { if (!AuthStore.signedIn) onClose() else load(reset = true) }
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
                            )
                        }
                    }
                }
                item {
                    if (loading) CircularProgressIndicator(Modifier.padding(16.dp))
                    if (!loading && rows.isEmpty() && failure == null) Text(tr("아직 쓴 리뷰가 없어요"), color = IOS.secondaryLabel)
                    if (!loading && offset < total && failure == null) LaunchedEffect(offset) { load() }
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
        ReviewsSheet(ReviewSubject(row.target.id, row.target.name, row.target.type == ReviewTargetType.poi), onClose = {
            selected =
                null
        }, onChanged = { revision += 1 }, focusReviewId = row.id)
    }
}
