package com.mz2az.scenetrip.reviews

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.ModerationEvents
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Photo
import com.mz2az.scenetrip.sceneapi.client.model.PhotoSource
import com.mz2az.scenetrip.sceneapi.client.model.RatingSummary
import com.mz2az.scenetrip.sceneapi.client.model.ReviewPhoto
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.XMarkIcon
import kotlinx.coroutines.launch

/*
 * 리뷰 화면들이 같이 쓰는 부품 (MZ2AZ-476). iOS `ReviewSummaryView`·`StarRow`·`StarPicker`·`SheetHeader`·`ReviewPhotoThumbs`·
 * `VisitorPhotoStrip` 을 옮겼다 — 별은 노란색(#FFB800), 빈 별은 systemGray3 외곽선이다.
 */

internal val ReviewStarColor = Color(0xFFFFB800)

private fun starPath(edge: Float): Path {
    val path = Path()
    repeat(10) { index ->
        val angle = index * Math.PI / 5 - Math.PI / 2
        val radius = edge * if (index % 2 == 0) 0.48f else 0.21f
        val x = edge / 2 + (kotlin.math.cos(angle) * radius).toFloat()
        val y = edge / 2 + (kotlin.math.sin(angle) * radius + edge * 0.03f).toFloat()
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()
    return path
}

/** 별 하나 — 채운 별, 왼쪽 반만 채운 별, 빈 별(외곽선). */
@Composable
fun StarGlyph(
    kind: ReviewRules.Star,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(size)) {
        val edge = this.size.minDimension
        val path = starPath(edge)
        val outline = Stroke(width = edge * 0.08f, join = StrokeJoin.Round, cap = StrokeCap.Round)
        when (kind) {
            ReviewRules.Star.FULL -> {
                drawPath(path, ReviewStarColor)
            }

            ReviewRules.Star.EMPTY -> {
                drawPath(path, IOS.systemGray3, style = outline)
            }

            ReviewRules.Star.HALF -> {
                drawPath(path, IOS.systemGray3, style = outline)
                clipRect(left = 0f, top = 0f, right = edge / 2, bottom = edge) { drawPath(path, ReviewStarColor) }
            }
        }
    }
}

/** 별 다섯. 리뷰 한 건은 정수(1~5), 평균은 반 개까지 그린다 — 4.6 을 다섯 개로 채우면 5.0 으로 읽힌다. */
@Composable
fun StarRow(
    value: Double,
    size: Dp = 12.dp,
    modifier: Modifier = Modifier,
) {
    val spoken = tr("별점 %s점").format(ReviewRules.starValue(value))
    Row(modifier.clearAndSetSemantics { contentDescription = spoken }, horizontalArrangement = Arrangement.spacedBy(1.5.dp)) {
        ReviewRules.stars(value).forEach { StarGlyph(it, size) }
    }
}

@Composable
fun StarRow(
    rating: Int,
    size: Dp = 12.dp,
    modifier: Modifier = Modifier,
) = StarRow(rating.toDouble(), size, modifier)

/** 별 다섯 — 눌러서 1~5점을 고른다. */
@Composable
fun StarPicker(
    rating: Int,
    enabled: Boolean,
    onPick: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..5).forEach { index ->
            Box(
                Modifier
                    .size(44.dp)
                    .semantics { contentDescription = tr("%d점").format(index) }
                    .clickable(enabled = enabled) { onPick(index) },
                contentAlignment = Alignment.Center,
            ) {
                StarGlyph(if (index <= rating) ReviewRules.Star.FULL else ReviewRules.Star.EMPTY, 34.dp)
            }
        }
    }
}

/** 요약 — 왼쪽에 평균과 리뷰 수, 오른쪽에 5~1점 막대. */
@Composable
fun ReviewSummaryView(summary: RatingSummary) {
    val spoken = ReviewRules.spoken(summary)
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(
            Modifier.width(84.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(ReviewRules.average(summary.average), fontSize = 38.sp, fontWeight = FontWeight.Bold, color = IOS.label)
            StarRow(summary.average ?: 0.0, 12.dp)
            Text(ReviewRules.countText(summary.count), style = IOS.caption, color = IOS.secondaryLabel)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            ReviewRules.bars(summary.distribution).forEach { bar ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        bar.score.toString(),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = IOS.secondaryLabel,
                        modifier = Modifier.width(10.dp),
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(IOS.systemGray5),
                    ) {
                        if (bar.share > 0f) {
                            Box(
                                Modifier
                                    .fillMaxWidth(bar.share)
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(ReviewStarColor),
                            )
                        }
                    }
                    Text(
                        bar.count.toString(),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = IOS.tertiaryLabel,
                        modifier = Modifier.width(26.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
            }
        }
    }
}

/** 시트의 머리줄 — 가운데 제목과 작은 부제, 오른쪽에 닫기. 리뷰 시트와 사진 격자가 같은 모양이다 (iOS `SheetHeader`). */
@Composable
fun ReviewSheetHeader(
    title: String,
    subtitle: String? = null,
    onClose: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp)
            .padding(top = 6.dp, bottom = 4.dp)
            .height(44.dp),
    ) {
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text(title, style = IOS.headline, color = IOS.label, maxLines = 1)
            if (!subtitle.isNullOrEmpty()) Text(subtitle, style = IOS.caption, color = IOS.secondaryLabel, maxLines = 1)
        }
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .size(44.dp)
                .semantics { contentDescription = tr("닫기") }
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            XMarkIcon(IOS.secondaryLabel, Modifier.size(14.dp))
        }
    }
}

/** `star.bubble` — 말풍선 안의 별. 빈 상태의 얼굴이다. */
@Composable
fun StarBubbleIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val stroke = Stroke(width = w * 0.06f, join = StrokeJoin.Round, cap = StrokeCap.Round)
        drawRoundRect(tint, Offset(w * 0.06f, w * 0.1f), Size(w * 0.88f, w * 0.64f), CornerRadius(w * 0.16f), style = stroke)
        val tail =
            Path().apply {
                moveTo(w * 0.3f, w * 0.74f)
                lineTo(w * 0.24f, w * 0.92f)
                lineTo(w * 0.5f, w * 0.74f)
            }
        drawPath(tail, tint, style = stroke)
        val star = Path()
        repeat(10) { index ->
            val angle = index * Math.PI / 5 - Math.PI / 2
            val radius = w * if (index % 2 == 0) 0.2f else 0.09f
            val x = w * 0.5f + (kotlin.math.cos(angle) * radius).toFloat()
            val y = w * 0.43f + (kotlin.math.sin(angle) * radius).toFloat()
            if (index == 0) star.moveTo(x, y) else star.lineTo(x, y)
        }
        star.close()
        drawPath(star, tint)
    }
}

/** 가운데 정렬 빈 상태 — 아이콘, 제목, 한 줄 설명 (iOS `ReviewsSheet.empty`·`MyReviewsView` 빈 목록). */
@Composable
fun ReviewEmptyState(
    title: String,
    caption: String,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StarBubbleIcon(IOS.tertiaryLabel, Modifier.size(34.dp))
        Text(title, style = IOS.subheadline.copy(fontWeight = FontWeight.Medium), color = IOS.label)
        Text(caption, style = IOS.caption, color = IOS.secondaryLabel)
    }
}

/** 첫 쪽을 못 받았을 때 — 가운데 「리뷰를 불러오지 못했어요」와 「다시 시도」. */
@Composable
fun ReviewLoadFailure(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(tr("리뷰를 불러오지 못했어요"), style = IOS.subheadline.copy(fontWeight = FontWeight.Medium), color = IOS.label)
        Text(
            tr("다시 시도"),
            style = IOS.subheadline,
            color = IOS.accent,
            modifier = Modifier.clickable(onClick = onRetry).padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** 목록 끝 — 닿으면 다음 쪽을 받는다. 못 받으면 돌아가는 표시를 남겨 두지 않고 다시 시도를 준다. */
@Composable
fun ReviewMoreFooter(
    moreFailed: Boolean,
    onRetry: () -> Unit,
) {
    if (moreFailed) {
        Text(
            tr("더 불러오지 못했어요 · 다시 시도"),
            style = IOS.footnote,
            color = IOS.accent,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onRetry).padding(vertical = 18.dp),
        )
    } else {
        Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

/**
 * 리뷰에 붙은 사진 줄 — 작은 사진을 옆으로 넘기고, 누르면 크게 본다 (iOS `ReviewPhotoThumbs`). 리뷰 목록과 「내 리뷰」 가
 * 같이 쓴다. 크게 보기는 사진첩과 같은 화면이되 방문자 표식은 달지 않는다.
 */
@Composable
fun ReviewPhotoThumbs(
    subject: ReviewSubject,
    reviewId: Long,
    photos: List<ReviewPhoto>,
    size: Dp = 84.dp,
    // 사진을 못 받았을 때 그 사진이 든 목록이 새 주소를 받는 길(MZ2AZ-477) — 이 줄은 사진첩을 따로 다시 받을 수 없다.
    onRenew: (suspend (Boolean) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val renewal by rememberUpdatedState(onRenew)
    val items = remember(photos) { photos.map { Photo(url = it.url, source = PhotoSource.review, reviewId = reviewId) } }
    val book =
        remember(subject, items) {
            PhotoBook(subject, items, items.size, paged = false, externalRenew = { forced ->
                renewal?.invoke(forced)
            })
        }
    var viewing by remember { mutableStateOf<Int?>(null) }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        itemsIndexed(items, key = { _, photo -> photo.galleryKey }) { number, photo ->
            GalleryImage(
                photo,
                Modifier
                    .size(size)
                    .clip(RoundedCornerShape(8.dp))
                    .semantics { contentDescription = tr("사진 %d").format(number + 1) }
                    .clickable { viewing = number },
                thumbnail = true,
                onFailure = { scope.launch { book.renew() } },
                onRetry = { scope.launch { book.renew(true) } },
            )
        }
    }
    viewing?.let { index ->
        GalleryViewer(book, index, onClose = { viewing = null }, onReview = null, marksVisitors = false)
    }
}

/**
 * 리뷰 시트 위쪽의 방문자 사진 모아 보기 — 한 줄로 옆으로 넘기고, 「모두 보기」 는 격자 (iOS `VisitorPhotoStrip`). 누르면 크게 보고,
 * 거기서 「리뷰 보기」 를 누르면 목록의 그 리뷰로 내려간다. 방문자 사진이 없으면 그리지 않는다.
 */
@Composable
fun VisitorPhotoStrip(
    subject: ReviewSubject,
    photos: List<Photo>,
    total: Int,
    onReview: (Long) -> Unit,
) {
    // 차단·풀기가 일어나면(MZ2AZ-461) 책을 새로 만든다 — 차단한 사람의 사진이 새 책에 섞이지 않는다.
    val moderation by ModerationEvents.shared.version.collectAsState()
    val book = remember(subject, photos, total, moderation) { PhotoBook(subject, photos, total, visitorsOnly = true) }
    val scope = rememberCoroutineScope()
    var viewing by remember { mutableStateOf<Int?>(null) }
    var browsing by remember { mutableStateOf(false) }
    LaunchedEffect(book) { book.seekVisitors() }
    val shown = book.photos
    if (shown.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                ReviewRules.visitorTitle(book.total),
                style = IOS.subheadlineSemibold,
                color = IOS.label,
                modifier = Modifier.weight(1f),
            )
            Text(
                tr("모두 보기"),
                style = IOS.caption.copy(fontWeight = FontWeight.SemiBold),
                color = IOS.accent,
                modifier = Modifier.clickable { browsing = true }.padding(vertical = 8.dp),
            )
        }
        LazyRow(
            contentPadding =
                androidx.compose.foundation.layout
                    .PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            itemsIndexed(shown, key = { _, photo -> photo.galleryKey }) { number, photo ->
                LaunchedEffect(number) { if (number >= shown.size - 5) book.more() }
                GalleryImage(
                    photo,
                    Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .semantics { contentDescription = tr("사진 %d / %d").format(number + 1, maxOf(book.total, number + 1)) }
                        .clickable { viewing = number },
                    thumbnail = true,
                    onFailure = { scope.launch { book.renew() } },
                    onRetry = { scope.launch { book.renew(true) } },
                )
            }
        }
    }
    viewing?.let { index ->
        GalleryViewer(
            book,
            index,
            onClose = { viewing = null },
            onReview = { id ->
                viewing = null
                id?.let(onReview)
            },
            marksVisitors = false,
            entry = "reviews",
        )
    }
    if (browsing) {
        PhotoGrid(
            book,
            onClose = { browsing = false },
            onReview = { id ->
                browsing = false
                id?.let(onReview)
            },
            title = ReviewRules.visitorTitle(book.total),
            entry = "reviews",
        )
    }
}

/** `trash` — 뚜껑과 손잡이가 달린 휴지통 외곽선. */
@Composable
fun TrashIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val stroke = Stroke(width = w * 0.09f, join = StrokeJoin.Round, cap = StrokeCap.Round)
        val bin =
            Path().apply {
                moveTo(w * 0.22f, w * 0.3f)
                lineTo(w * 0.28f, w * 0.88f)
                lineTo(w * 0.72f, w * 0.88f)
                lineTo(w * 0.78f, w * 0.3f)
            }
        drawPath(bin, tint, style = stroke)
        drawLine(tint, Offset(w * 0.12f, w * 0.3f), Offset(w * 0.88f, w * 0.3f), strokeWidth = w * 0.09f, cap = StrokeCap.Round)
        val handle =
            Path().apply {
                moveTo(w * 0.38f, w * 0.3f)
                lineTo(w * 0.38f, w * 0.14f)
                lineTo(w * 0.62f, w * 0.14f)
                lineTo(w * 0.62f, w * 0.3f)
            }
        drawPath(handle, tint, style = stroke)
        drawLine(tint, Offset(w * 0.42f, w * 0.5f), Offset(w * 0.43f, w * 0.72f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.58f, w * 0.5f), Offset(w * 0.57f, w * 0.72f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
    }
}

/** `movieclapper` — 촬영지 줄의 종류 아이콘. */
@Composable
fun ClapperIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val stroke = Stroke(width = w * 0.08f, join = StrokeJoin.Round, cap = StrokeCap.Round)
        drawRoundRect(tint, Offset(w * 0.1f, w * 0.38f), Size(w * 0.8f, w * 0.5f), CornerRadius(w * 0.08f), style = stroke)
        val flap =
            Path().apply {
                moveTo(w * 0.1f, w * 0.38f)
                lineTo(w * 0.8f, w * 0.14f)
                lineTo(w * 0.9f, w * 0.3f)
                lineTo(w * 0.9f, w * 0.38f)
            }
        drawPath(flap, tint, style = stroke)
        drawLine(tint, Offset(w * 0.32f, w * 0.3f), Offset(w * 0.4f, w * 0.38f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.55f, w * 0.22f), Offset(w * 0.63f, w * 0.38f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
    }
}

/** `storefront` — 편의시설 줄의 종류 아이콘. */
@Composable
fun StorefrontIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val stroke = Stroke(width = w * 0.08f, join = StrokeJoin.Round, cap = StrokeCap.Round)
        val awning =
            Path().apply {
                moveTo(w * 0.14f, w * 0.4f)
                lineTo(w * 0.22f, w * 0.14f)
                lineTo(w * 0.78f, w * 0.14f)
                lineTo(w * 0.86f, w * 0.4f)
                close()
            }
        drawPath(awning, tint, style = stroke)
        drawLine(tint, Offset(w * 0.5f, w * 0.14f), Offset(w * 0.5f, w * 0.4f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
        val shop =
            Path().apply {
                moveTo(w * 0.2f, w * 0.4f)
                lineTo(w * 0.2f, w * 0.88f)
                lineTo(w * 0.8f, w * 0.88f)
                lineTo(w * 0.8f, w * 0.4f)
            }
        drawPath(shop, tint, style = stroke)
        drawRect(tint, Offset(w * 0.34f, w * 0.56f), Size(w * 0.32f, w * 0.32f), style = Stroke(width = w * 0.07f))
    }
}
