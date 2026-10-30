package com.mz2az.scenetrip.profiletab

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.DateText
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.LikeStore
import com.mz2az.scenetrip.data.TabRouter
import com.mz2az.scenetrip.data.UnavailableItem
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.api.CoursesApi
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.searchtab.CartItemRow
import com.mz2az.scenetrip.searchtab.FilmIcon
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.BagIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSEmptyState
import com.mz2az.scenetrip.ui.IOSGlassSheetColor
import com.mz2az.scenetrip.ui.IOSListDivider
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.LikeFailureNotice
import com.mz2az.scenetrip.ui.RouteCurveIcon
import com.mz2az.scenetrip.ui.SheetDetent
import com.mz2az.scenetrip.ui.SquarePencilIcon
import com.mz2az.scenetrip.ui.UnavailableItemNotice
import com.mz2az.scenetrip.ui.XMarkIcon
import com.mz2az.scenetrip.ui.sheetListBottom
import com.mz2az.scenetrip.ui.unavailableAlpha
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Locale

/** 두 팝업이 같은 머리줄을 쓴다 — 제목 가운데, 오른쪽 작은 X. */
@Composable
fun ProfileSheetHeader(
    title: String,
    onClose: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                // 시트 손잡이 바로 아래 — iOS 는 제목 중심이 카드 윗변에서 약 23.
                .padding(top = 2.dp, bottom = 6.dp),
    ) {
        Text(title, style = IOS.headline, color = IOS.label, modifier = Modifier.align(Alignment.Center))
        Box(
            modifier =
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(32.dp)
                    .semantics { contentDescription = tr("닫기") }
                    .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            // iOS `xmark` 12pt semibold — 보이는 글리프 약 9.7.
            XMarkIcon(IOS.secondaryLabel, Modifier.size(11.dp))
        }
    }
}

/**
 * 내 코스 팝업. iOS `ProfileTab/ProfileSheets.swift`의 `MyCoursesSheet`를 옮긴 것이다.
 * 행을 누르면 바로 아래 상세(일차별 장소)가 펼쳐진다 — 아코디언.
 */
@Composable
private fun MyCoursesSheetBody(
    courses: List<CourseSummary>,
    loading: Boolean,
    failed: Boolean,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf<Long?>(null) }
    val details = remember { mutableStateOf(mapOf<Long, CourseDetail>()) }

    // iOS 시트는 `.regularMaterial` 유리 — 뒤 화면을 비쳐 균일한 옅은 회색(236)으로 보인다. 뒤를 흐리는 것은 Compose 가
    // 시트 뒤 화면을 읽을 수 없어 못 하고, 그 색만 옮긴다.
    Column(modifier = Modifier.fillMaxSize().background(IOSGlassSheetColor)) {
        ProfileSheetHeader(tr("내 코스"), onClose)
        if (courses.isEmpty() && failed) {
            IOSEmptyState(
                tr("코스 목록을 받지 못했습니다"),
                tr("서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요"),
            ) { Icon(Icons.Outlined.Warning, contentDescription = null, tint = IOS.secondaryLabel, modifier = it) }
        } else if (courses.isEmpty() && loading) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator()
                Text(tr("코스 목록을 받아오는 중입니다"), fontSize = 12.sp, color = IOS.secondaryLabel)
            }
        } else if (courses.isEmpty()) {
            IOSEmptyState(tr("아직 코스가 없습니다"), tr("경로여정 탭에서 첫 코스를 만들어 보세요"), yOffset = (-24).dp) { RouteCurveIcon(IOS.secondaryLabel, it) }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = sheetListBottom()) {
                items(courses, key = { it.id }) { course ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 15.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            expanded = if (expanded == course.id) null else course.id
                                        },
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(course.title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = IOS.label)
                                    val startDate = course.startDate?.let { " · ${DateText.date(it)}" } ?: ""
                                    Text(tr("%d일").format(course.dayCount) + startDate, fontSize = 12.sp, color = IOS.secondaryLabel)
                                }
                                if (course.status == CourseStatus.active) {
                                    Text(
                                        tr("여행 중"),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF34C759),
                                        modifier =
                                            Modifier
                                                .clip(RoundedCornerShape(50))
                                                .background(Color(0xFF34C759).copy(alpha = 0.15f))
                                                .padding(horizontal = 7.dp, vertical = 3.dp),
                                    )
                                }
                                Icon(
                                    if (expanded == course.id) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = IOS.tertiaryLabel,
                                    modifier = Modifier.size(16.dp),
                                )
                            }

                            if (expanded == course.id) {
                                val detail = details.value[course.id]
                                LaunchedEffect(course.id) {
                                    if (details.value[course.id] == null) {
                                        val deviceId = InstallIdentity.of(context)
                                        val api = CoursesApi(API_BASE)
                                        val loaded =
                                            withContext(Dispatchers.IO) {
                                                runCatching { api.getCourse(deviceId, course.id) }.getOrNull()
                                            }
                                        if (loaded != null) {
                                            details.value = details.value + (course.id to loaded)
                                        }
                                    }
                                }
                                if (detail != null) {
                                    Column(
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(top = 6.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(IOS.systemGray6)
                                                .padding(10.dp),
                                    ) {
                                        detail.days.forEach { day ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(
                                                    tr("%d일차").format(day.dayNumber),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = IOS.pinDeep,
                                                    modifier = Modifier.width(38.dp),
                                                )
                                                // 목록에서 빠진 촬영지·폐업한 편의시설은 지우지 않고 이유를 붙인다(MZ2AZ-458).
                                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    day.items.forEach { item ->
                                                        val notice = UnavailableItem.notice(item)
                                                        Text(
                                                            item.name,
                                                            fontSize = 11.sp,
                                                            color = IOS.label,
                                                            modifier = Modifier.alpha(unavailableAlpha(notice)),
                                                        )
                                                        if (notice != null) UnavailableItemNotice(notice)
                                                    }
                                                }
                                            }
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(IOS.accent.copy(alpha = 0.12f))
                                                    .clickable {
                                                        TabRouter.openCourse(course.id)
                                                        onClose()
                                                    }.padding(vertical = 8.dp),
                                        ) {
                                            Text(
                                                tr("경로여정에서 열기") + " ↗",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = IOS.accent,
                                            )
                                        }
                                    }
                                } else {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(top = 6.dp),
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                        Text(tr("일정을 받아오는 중입니다"), fontSize = 11.sp, color = IOS.secondaryLabel)
                                    }
                                }
                            }
                        }
                        // iOS `List(.plain)` 행 구분선 — 글자 시작(16)부터 오른쪽 끝에서 15 들어와 끝난다. 유리 위 선 색은 실측 (214,214,215).
                        androidx.compose.material3.HorizontalDivider(
                            modifier = Modifier.padding(start = 16.dp, end = 15.dp),
                            thickness = 1.dp,
                            color = Color(0xFFD6D6D7),
                        )
                    }
                }
            }
        }
    }
}

/** 찜한 작품 팝업. */
@Composable
private fun LikedWorksSheetBody(
    works: List<ContentSummary>,
    likes: LikeStore,
    loading: Boolean,
    failure: String?,
    onClose: () -> Unit,
) {
    var expanded by remember { mutableStateOf<Long?>(null) }
    Column(modifier = Modifier.fillMaxSize().background(IOSGlassSheetColor)) {
        ProfileSheetHeader(tr("찜한 작품"), onClose)
        when {
            failure != null -> {
                IOSEmptyState(
                    tr("작품 목록을 받지 못했습니다"),
                    failure,
                ) { Icon(Icons.Outlined.Warning, contentDescription = null, tint = IOS.secondaryLabel, modifier = it) }
            }

            loading && works.isEmpty() -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text(tr("작품 목록을 받아오는 중입니다"), fontSize = 12.sp, color = IOS.secondaryLabel)
                }
            }

            works.isEmpty() -> {
                IOSEmptyState(tr("찜한 작품이 없습니다"), tr("작품검색 탭에서 하트를 눌러 보세요"), yOffset = (-24).dp) {
                    Icon(Icons.Filled.FavoriteBorder, contentDescription = null, tint = IOS.secondaryLabel, modifier = it)
                }
            }

            else -> {
                LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = sheetListBottom()) {
                    items(works, key = { it.id }) { work ->
                        val notice = UnavailableItem.notice(work)
                        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .clickable { expanded = if (expanded == work.id) null else work.id },
                                ) {
                                    RemoteImage(
                                        url = work.posterUrl?.toString(),
                                        contentScale = ContentScale.Fit,
                                        // 받는 중이거나 못 받았을 때 회색 바탕에 필름 아이콘이 보인다 (iOS `RemoteImage(symbol: "film")`).
                                        placeholder = { FilmIcon(IOS.tertiaryLabel, Modifier.size(18.dp)) },
                                        modifier =
                                            Modifier
                                                .size(width = 34.dp, height = 46.dp)
                                                .clip(RoundedCornerShape(5.dp))
                                                .alpha(unavailableAlpha(notice)),
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            work.title,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = IOS.label,
                                            modifier = Modifier.alpha(unavailableAlpha(notice)),
                                        )
                                        val meta =
                                            listOfNotNull(work.broadcaster, work.releaseYear?.toString()).joinToString(" · ")
                                        // iOS 는 비어도 부제 줄을 그려 행 높이가 같다(2차 대조).
                                        Text(meta, fontSize = 12.sp, color = IOS.secondaryLabel)
                                    }
                                    Icon(
                                        Icons.Filled.Favorite,
                                        contentDescription = null,
                                        tint = IOS.systemRed,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Icon(
                                        if (expanded == work.id) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = IOS.tertiaryLabel,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                // 찜 빼기 — 숨긴 작품도 사용자가 직접 뺀다(MZ2AZ-458). 누르는 자리는 48dp 이상이다.
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier =
                                        Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .clickable(role = Role.Button) {
                                                if (likes.contains(work.id)) likes.toggle(work.id)
                                            },
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = tr("찜 빼기"),
                                        tint = IOS.label,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                            if (notice != null) UnavailableItemNotice(notice)
                            if (expanded == work.id) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(IOS.systemGray6)
                                            .padding(10.dp),
                                ) {
                                    work.genres?.takeIf { it.isNotEmpty() }?.let {
                                        Text(it.joinToString(" · "), fontSize = 11.sp, color = IOS.accent)
                                    }
                                    Text(tr("촬영지 %d곳").format(work.placeCount), fontSize = 11.sp, color = IOS.secondaryLabel)
                                    Text(
                                        tr("촬영지는 작품검색 탭에서 지도로 볼 수 있어요"),
                                        fontSize = 11.sp,
                                        color = IOS.tertiaryLabel,
                                    )
                                }
                            }
                        }
                        // iOS `List` 행 구분선 — 포스터 뒤 글자 시작(16+34+10)에서.
                        IOSListDivider(start = 60.dp)
                    }
                }
            }
        }
        // 찜을 바꾸지 못했을 때(예: 숨긴 작품은 새로 찜할 수 없다)의 안내.
        LikeFailureNotice(likes.failure, onClose = { likes.clearFailure() })
    }
}

/**
 * 장바구니 팝업 — 검색 탭에서 담아 둔 촬영지. 안내와 빼기는 검색 장바구니와 같은 행([CartItemRow])을 쓴다
 * (MZ2AZ-458, iOS `ProfileCartSheet`).
 */
@Composable
private fun ProfileCartSheetBody(
    cart: CartStore,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 열 때 낡은 알림은 치우고 서버 값을 다시 받는다.
    LaunchedEffect(Unit) {
        cart.clearToast()
        cart.refresh()
    }
    Column(modifier = Modifier.fillMaxSize().background(IOSGlassSheetColor)) {
        ProfileSheetHeader(tr("장바구니"), onClose)
        if (cart.items.isEmpty()) {
            IOSEmptyState(tr("장바구니가 비었습니다"), tr("작품검색 탭에서 촬영지를 담아 보세요"), yOffset = (-24).dp) { BagIcon(IOS.secondaryLabel, it) }
        } else {
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = sheetListBottom()) {
                itemsIndexed(cart.items, key = { _, item -> item.placeId }) { index, item ->
                    CartItemRow(index = index, item = item, onRemove = { scope.launch { cart.remove(item.placeId) } })
                    IOSListDivider(start = 16.dp)
                }
            }
        }
        cart.toast?.let { toast ->
            Text(
                toast,
                fontSize = 12.sp,
                color = IOS.secondaryLabel,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
        }
    }
}

/** iOS 에서 `.sheet` 로 뜬다 — 아래에서 올라오는 시트([IOSSheet]). */
@Composable
fun MyCoursesSheet(
    courses: List<CourseSummary>,
    loading: Boolean,
    failed: Boolean,
    onClose: () -> Unit,
) {
    IOSSheet(detents = listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onClose, containerColor = IOSGlassSheetColor) {
        MyCoursesSheetBody(courses = courses, loading = loading, failed = failed, onClose = onClose)
    }
}

/** iOS 에서 `.sheet` 로 뜬다 — 아래에서 올라오는 시트([IOSSheet]). */
@Composable
fun LikedWorksSheet(
    works: List<ContentSummary>,
    likes: LikeStore,
    loading: Boolean,
    failure: String?,
    onClose: () -> Unit,
) {
    IOSSheet(detents = listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onClose, containerColor = IOSGlassSheetColor) {
        LikedWorksSheetBody(works = works, likes = likes, loading = loading, failure = failure, onClose = onClose)
    }
}

/** iOS 에서 `.sheet` 로 뜬다 — 아래에서 올라오는 시트([IOSSheet]). */
@Composable
fun ProfileCartSheet(
    cart: CartStore,
    onClose: () -> Unit,
) {
    IOSSheet(detents = listOf(SheetDetent.MEDIUM, SheetDetent.LARGE), onDismiss = onClose, containerColor = IOSGlassSheetColor) {
        ProfileCartSheetBody(cart = cart, onClose = onClose)
    }
}

/** iOS 에서 `.sheet` 로 뜬다 — 아래에서 올라오는 시트([IOSSheet]). */
@Composable
fun MyPostsSheet(onClose: () -> Unit) {
    com.mz2az.scenetrip.communitytab
        .MyPostsSheet(onClose)
}
