package com.mz2az.scenetrip.communitytab

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.CommunityArticle
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.PhotoRenewalRules
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.reviews.DraftPhoto
import com.mz2az.scenetrip.reviews.PhotoUploader
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlock
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBlockKind
import com.mz2az.scenetrip.searchtab.ImageCache
import com.mz2az.scenetrip.ui.ArrowsExpandIcon
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.LocalPhotoRenew
import com.mz2az.scenetrip.ui.PhotoZoomViewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val COVER_HEIGHT_DP = 220
private const val PLACEHOLDER_ASPECT_RATIO = 1.5f

sealed interface CommunityPhotoSource {
    data class Remote(
        val url: String,
    ) : CommunityPhotoSource

    data class Local(
        val name: String,
    ) : CommunityPhotoSource

    data class Picked(
        val uri: Uri,
    ) : CommunityPhotoSource

    data class Preview(
        val image: ImageBitmap,
    ) : CommunityPhotoSource

    data object Pending : CommunityPhotoSource

    data object Failed : CommunityPhotoSource

    val retryable get() = this is Remote || this is Local

    /**
     * 같은 사진인지 가르는 열쇠. 원격 사진은 서명(쿼리)을 뗀 주소다 — 주소를 새로 받아도(MZ2AZ-477) 이미 그린 사진을 지우지 않는다.
     * iOS `CommunityPhotoSource.objectKey`.
     */
    val objectKey: Any get() = if (this is Remote) "remote:${PhotoRenewalRules.unsigned(url)}" else this

    companion object {
        fun from(photo: DraftPhoto): CommunityPhotoSource =
            when {
                photo.url != null -> Remote(photo.url)
                photo.uri != null -> Picked(photo.uri)
                photo.uploading || photo.problem == null -> Pending
                else -> Failed
            }
    }
}

@Composable
internal fun CommunityArticleBlock(
    block: TripPostBlock,
    photos: List<CommunityPhotoSource>,
    modifier: Modifier = Modifier,
) {
    when (block.kind) {
        TripPostBlockKind.text -> {
            block.text?.let { text ->
                SelectionContainer(modifier) { Text(text, color = IOS.label, fontSize = 17.sp, lineHeight = 23.sp) }
            }
        }

        TripPostBlockKind.photo -> {
            block.photoIndex?.let { index ->
                photos
                    .getOrNull(
                        index,
                    )?.let { CommunityArticlePhoto(it, block, modifier = modifier.testTag("community-body-photo-$index")) }
            }
        }
    }
}

/**
 * 글 끝의 「References」·「Photo credits」 같은 덧붙임 구간 — 한 줄로 접어 두고 누르면 펼친다. 길고 주소가 섞여 본문 끝이 지저분해 보였다.
 * 줄 이름은 글의 제목 줄을 그대로 쓴다(글 언어를 따른다).
 */
@Composable
internal fun CommunityReferences(
    blocks: List<TripPostBlock>,
    modifier: Modifier = Modifier,
) {
    var open by remember(blocks) { mutableStateOf(false) }
    val label = blocks.mapNotNull(CommunityArticle::referenceHeading).joinToString(" · ")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.clickable { open = !open }.padding(vertical = 6.dp).testTag("community-references-toggle"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, style = IOS.caption, color = IOS.secondaryLabel)
            ChevronRightIcon(IOS.secondaryLabel, Modifier.size(14.dp).rotate(if (open) -90f else 90f))
        }
        if (open) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    blocks.forEach { block -> block.text?.let { Text(it, style = IOS.caption, color = IOS.secondaryLabel) } }
                }
            }
        }
    }
}

/** 읽기와 미리보기는 같은 메모리 사진을 확대에 넘긴다. 서명 URL은 저장하지 않는다. */
@Composable
internal fun CommunityArticlePhoto(
    source: CommunityPhotoSource,
    block: TripPostBlock,
    representative: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 주소를 새로 받으면 source 는 바뀌지만 같은 사진이다(objectKey) — 이미 그린 사진은 그대로 둔다.
    val photoKey = source.objectKey
    // 이 안의 사진은 못 받으면 글을 다시 읽어 새 주소로 해 본다(MZ2AZ-477). 없으면 갱신할 길이 없는 사진이다.
    val renew = LocalPhotoRenew.current
    var image by remember(photoKey) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(photoKey) { mutableStateOf(false) }
    var attempt by remember(photoKey) { mutableStateOf(0) }
    var loadedAttempt by remember(photoKey) { mutableStateOf(-1) }
    var renewedFor by remember(photoKey) { mutableStateOf(false) }
    var expanded by remember(photoKey) { mutableStateOf(false) }
    LaunchedEffect(source, attempt) {
        if (image != null && loadedAttempt == attempt) return@LaunchedEffect
        loadedAttempt = attempt
        image = null
        failed = false
        try {
            image = loadCommunityPhoto(context, source)
            failed = image == null && source != CommunityPhotoSource.Pending
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
        // 주소가 죽었을 수 있다 — 글을 다시 읽으면 새 주소가 오고, 주소가 바뀌면 이 칸이 다시 받는다. 사진마다 한 번만 청한다.
        if (failed && source is CommunityPhotoSource.Remote && PhotoRenewalRules.renewsAfterFailure(true, renewedFor)) {
            renewedFor = true
            renew?.invoke(false)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        ArticlePhotoCanvas(
            image,
            block,
            failed,
            source.retryable,
            representative,
            {
                attempt++
                // 서명된 주소는 새 주소부터 받는다 — 죽은 주소로는 몇 번을 해도 같다.
                if (source is CommunityPhotoSource.Remote) renew?.invoke(true)
            },
            { expanded = true },
        )
        // 덧말이 없으면 칸을 아예 두지 않는다 — 빈 Column 도 배치 대상이라 spacedBy(7) 이 사진 아래에 7dp 를 더 얹었다
        // (iOS 는 조건부 자식이라 간격이 안 생긴다, 31-post 실측: 제목·작성자·본문이 모두 아래로 밀렸다).
        if (!block.caption.isNullOrEmpty() || block.aiGenerated == true || !block.credit.isNullOrEmpty()) {
            CommunityPhotoMetadata(block)
        }
    }
    if (expanded) {
        image?.let { bitmap ->
            PhotoZoomViewer(bitmap, block.caption ?: tr("후기 사진"), block.caption ?: tr("후기 사진"), onClose = { expanded = false }) {
                CommunityPhotoMetadata(block, expanded = true)
            }
        }
    }
}

private suspend fun loadCommunityPhoto(
    context: Context,
    source: CommunityPhotoSource,
): ImageBitmap? =
    when (source) {
        is CommunityPhotoSource.Remote -> {
            ImageCache.load(source.url, key = PhotoRenewalRules.unsigned(source.url), edge = 1600)
        }

        is CommunityPhotoSource.Local -> {
            withContext(Dispatchers.IO) { CommunityStore.getInstance(context).photo(source.name)?.asImageBitmap() }
        }

        is CommunityPhotoSource.Picked -> {
            withContext(Dispatchers.IO) {
                val bytes = PhotoUploader.shrink(context, source.uri)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
        }

        is CommunityPhotoSource.Preview -> {
            source.image
        }

        CommunityPhotoSource.Pending, CommunityPhotoSource.Failed -> {
            null
        }
    }

@Composable
private fun ArticlePhotoCanvas(
    image: ImageBitmap?,
    block: TripPostBlock,
    failed: Boolean,
    retryable: Boolean,
    representative: Boolean,
    onRetry: () -> Unit,
    onExpand: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val ratio = image?.let { it.width.toFloat() / it.height } ?: PLACEHOLDER_ASPECT_RATIO
        val naturalHeight = maxWidth / ratio
        val frameHeight = if (representative) minOf(naturalHeight, COVER_HEIGHT_DP.dp) else naturalHeight
        // 부모의 실제 높이를 정하고 이미지도 그 칸으로 제한한다. aspectRatio를 두 번
        // 적용하면 세로 이미지가 부모를 넘어 제목·출처 위까지 그려질 수 있다.
        Box(
            Modifier
                .fillMaxWidth()
                .height(frameHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(IOS.systemGray6),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                Image(
                    image,
                    block.caption ?: tr("후기 사진"),
                    Modifier.fillMaxSize().clickable(onClick = onExpand),
                    contentScale = ContentScale.Fit,
                )
                ArticleExpandButton(Modifier.align(Alignment.BottomEnd).padding(6.dp), onExpand)
            } else {
                ArticlePhotoPlaceholder(failed, retryable, onRetry)
            }
        }
    }
}

@Composable
private fun ArticleExpandButton(
    modifier: Modifier,
    onExpand: () -> Unit,
) {
    Box(
        modifier
            .size(44.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
            .semantics { contentDescription = tr("사진 크게 보기") }
            .clickable(onClick = onExpand),
        contentAlignment = Alignment.Center,
    ) {
        // iOS `arrow.up.left.and.arrow.down.right`(본문 17pt) — 글자 ⤢ 는 Noto 에서 작은 안쪽 화살표로 그려졌다(2026-10-29 감사 #18).
        ArrowsExpandIcon(Color.White, Modifier.size(17.dp))
    }
}

@Composable
private fun ArticlePhotoPlaceholder(
    failed: Boolean,
    retryable: Boolean,
    onRetry: () -> Unit,
) {
    if (failed) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tr(if (retryable) "사진을 불러오지 못했어요" else "사진을 다시 골라 주세요"), style = IOS.caption, color = IOS.secondaryLabel)
            if (retryable) Text(tr("사진 다시 받기"), color = IOS.accent, modifier = Modifier.clickable(onClick = onRetry).padding(12.dp))
        }
    } else {
        CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = tr("사진을 불러오는 중") }, color = IOS.accent)
    }
}

@Composable
internal fun CommunityPhotoMetadata(
    block: TripPostBlock,
    expanded: Boolean = false,
) {
    val color = if (expanded) Color.White else IOS.secondaryLabel
    if (expanded) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 120.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (block.aiGenerated == true) Text("✦ " + tr("AI 변환 이미지"), style = IOS.caption2, color = color)
            block.credit?.takeIf(String::isNotEmpty)?.let { credit ->
                SelectionContainer { Text(credit, style = IOS.caption2, color = color) }
            }
        }
        return
    }
    // 읽는 글에서는 출처 문장(공공누리 표기 등)이 길어 사진 아래를 차지한다 — 「더 보기」 뒤로 접는다. AI 변환 표시는 고지라 늘 보인다.
    var open by remember(block) { mutableStateOf(false) }
    val credit = block.credit?.takeIf(String::isNotEmpty)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        block.caption?.takeIf(String::isNotEmpty)?.let { Text(it, style = IOS.caption, color = color) }
        if (block.aiGenerated == true || credit != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (block.aiGenerated == true) {
                    Text("✦ " + tr("AI 변환 이미지"), style = IOS.caption2, color = color)
                    Spacer(Modifier.weight(1f))
                }
                if (credit != null) {
                    Row(
                        Modifier.clickable { open = !open }.padding(vertical = 6.dp).testTag("community-photo-credit-toggle"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(tr("더 보기"), style = IOS.caption2, color = color)
                        ChevronRightIcon(color, Modifier.size(12.dp).rotate(if (open) -90f else 90f))
                    }
                }
            }
            if (open && credit != null) SelectionContainer { Text(credit, style = IOS.caption2, color = color) }
        }
    }
}
