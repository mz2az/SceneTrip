package com.mz2az.scenetrip.reviews

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.routetab.RoutePlaceCard
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.api.PoisApi
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlaceSource
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.ReviewTarget
import com.mz2az.scenetrip.sceneapi.client.model.ReviewTargetType
import com.mz2az.scenetrip.sceneapi.client.model.Scene
import com.mz2az.scenetrip.searchtab.PlaceDetailView
import com.mz2az.scenetrip.searchtab.ScenePopup
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.SheetDetent
import kotlinx.coroutines.launch

/** 내 리뷰가 가리킨 종류와 원래 ID로 상세를 연다. 이름으로 ID를 추정하지 않는다. */
@Composable
fun MyReviewTargetSheet(
    target: ReviewTarget,
    onClose: () -> Unit,
) {
    var place by remember(target) { mutableStateOf<PlaceSummary?>(null) }
    var poi by remember(target) { mutableStateOf<GuidePlace?>(null) }
    var failure by remember(target) { mutableStateOf<String?>(null) }
    var retry by remember(target) { mutableStateOf(0) }
    var scene by remember(target) { mutableStateOf<Scene?>(null) }
    val context = LocalContext.current
    val cart = remember(context) { CartStore(context) }
    val scope = rememberCoroutineScope()
    val repository = remember { MyReviewDetails(ServerReviewDetailClient()) }
    LaunchedEffect(target, retry) {
        failure = null
        repository
            .load(target)
            .onSuccess { loaded ->
                when (loaded) {
                    is MyReviewDetail.Place -> {
                        val detail = loaded.value
                        place =
                            PlaceSummary(
                                id = detail.id,
                                name = detail.name,
                                latitude = detail.latitude,
                                longitude = detail.longitude,
                                type = detail.type,
                                address = detail.address,
                                imageUrl = detail.imageUrl,
                                contents = detail.contents,
                            )
                    }

                    is MyReviewDetail.Poi -> {
                        val detail = loaded.value
                        poi =
                            GuidePlace(
                                id = detail.id,
                                name = detail.name,
                                source = GuidePlaceSource.poi,
                                category = detail.category,
                                categoryGroup = detail.categoryGroup,
                                latitude = detail.latitude,
                                longitude = detail.longitude,
                                placeId = detail.placeId,
                                address = detail.address,
                                displayName = detail.displayName,
                                nameRoman = detail.nameRoman,
                                categoryLabel = detail.categoryLabel,
                                displayAddress = detail.displayAddress,
                            )
                    }
                }
            }.onFailure { failure = NetworkFailure.of(it).message }
        if (place != null) cart.refresh()
    }
    IOSSheet(detents = listOf(SheetDetent.LARGE), onDismiss = onClose) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            place?.let { summary ->
                PlaceDetailView(
                    summary = summary,
                    saved = cart.contains(summary.id),
                    detailOf = { id -> apiResult { PlacesApi(API_BASE).getPlace(id, acceptLanguage = AppLanguage.current) } },
                    onBack = onClose,
                    onToggleSave = {
                        scope.launch {
                            if (cart.contains(summary.id)) cart.remove(summary.id) else cart.add(summary.id)
                        }
                    },
                    onOpenScene = { scene = it },
                )
            } ?: run {
                IOSSheetToolbar(title = target.name, leading = tr("닫기"), onLeading = onClose)
                poi?.let {
                    RoutePlaceCard(
                        it,
                        added = false,
                        onAdd = null,
                        onRemove = null,
                        onClose = onClose,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                    ?: run {
                        failure?.let { message ->
                            Text(message, color = IOS.secondaryLabel, modifier = Modifier.padding(16.dp))
                            Text(tr("다시 시도"), color = IOS.accent, modifier = Modifier.clickable { retry++ }.padding(16.dp))
                        } ?: CircularProgressIndicator(Modifier.padding(16.dp))
                    }
            }
        }
    }
    scene?.let { ScenePopup(it, place?.name ?: target.name, onClose = { scene = null }) }
}
