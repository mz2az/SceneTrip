package com.mz2az.scenetrip.communitytab

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.PostCourse
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlock
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlockKind
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.SheetDetent

@Composable
fun CommunityArticlePreview(
    title: String,
    blocks: List<TripPostBlock>,
    photos: List<CommunityPhotoSource>,
    course: PostCourse?,
    onClose: () -> Unit,
) {
    IOSSheet(listOf(SheetDetent.LARGE), onDismiss = onClose) {
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            IOSSheetToolbar(tr("미리보기"), trailing = tr("닫기"), onTrailing = onClose)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item {
                    photos.firstOrNull()?.let { photo ->
                        CommunityArticlePhoto(
                            photo,
                            blocks.coverBlock(),
                            representative = true,
                            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 14.dp),
                        )
                    }
                }
                item {
                    Text(
                        title,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = IOS.label,
                        modifier = Modifier.padding(horizontal = 20.dp),
                    )
                }
                itemsIndexed(blocks) { _, block -> CommunityArticleBlock(block, photos, Modifier.padding(horizontal = 20.dp)) }
                course?.let { value ->
                    item {
                        Box(
                            Modifier
                                .padding(20.dp)
                                .fillMaxWidth()
                                .background(IOS.systemGray6, RoundedCornerShape(14.dp))
                                .padding(14.dp),
                        ) {
                            Text(value.title, style = IOS.headline, color = IOS.label)
                        }
                    }
                }
                item { Box(Modifier.padding(bottom = 20.dp)) }
            }
        }
    }
}

internal fun List<TripPostBlock>.coverBlock(): TripPostBlock =
    firstOrNull { it.kind == TripPostBlockKind.photo && it.photoIndex == 0 } ?: TripPostBlock(TripPostBlockKind.photo, photoIndex = 0)
