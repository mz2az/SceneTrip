package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mz2az.scenetrip.data.CartStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.searchtab.SearchTabScreen
import com.mz2az.scenetrip.ui.BagIcon
import com.mz2az.scenetrip.ui.CheckCircleIcon
import com.mz2az.scenetrip.ui.ChevronLeftIcon
import com.mz2az.scenetrip.ui.ChevronRightIcon
import com.mz2az.scenetrip.ui.DisableDialogDim
import com.mz2az.scenetrip.ui.FullScreenDialogWindow
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.IOSListDivider
import com.mz2az.scenetrip.ui.IOSSheet
import com.mz2az.scenetrip.ui.IOSSheetToolbar
import com.mz2az.scenetrip.ui.LocalTabBarClearance
import com.mz2az.scenetrip.ui.MapIcon
import com.mz2az.scenetrip.ui.PlusIcon
import com.mz2az.scenetrip.ui.SheetDetent

// 계획 화면의 장소 넣기 두 갈래 (MZ2AZ-547 E8–E10, 웹 `proto/ProtoAddMenu.tsx`·`proto/protoCourseAdd.tsx`, iOS `CourseAddViews.swift`).
//
// - 「지도에서 추가」([MapAddScreen]): 검색 탭을 편집 위에 그대로 띄운다. 장소 상세의 「＋ 코스에 추가」 → 일차 고르기.
// - 「보관함에서 추가」([SavedAddSheet]): 큰 시트에서 담아 둔 곳을 고른 순서대로 체크 → 「N곳 선택 완료」 → 일차 고르기.
// 넣을 곳을 먼저 고르고 일차는 마지막에 고른다(웹 2026-11-07 사용자 결정). 규칙은 `CourseAddRules`·`CourseAddPicker`.

private val PURPLE = IOS.homePurple

// MARK: 장소 상세의 「코스에 추가」

/** 코스 편집 위에 띄운 검색 탭에서만 있다 — 장소 상세가 이것을 보고 「코스에 추가」 단추를 그린다. 없으면 아무것도 안 그린다. */
data class CourseAddContext(
    /** 이미 코스(어느 일차든)에 든 촬영지 id. */
    val taken: Set<Long>,
    /** 이 화면에서 넣은 곳 → 넣은 일차(0부터). */
    val addedTo: Map<Long, Int>,
    /** 「코스에 추가」 — 일차 고르기를 띄운다. */
    val ask: (PlaceSummary) -> Unit,
)

val LocalCourseAdd = compositionLocalOf<CourseAddContext?> { null }

/** 장소 상세의 버튼 묶음 맨 위 — 「＋ 코스에 추가」 / 「이미 코스에 있어요」 / 「✓ N일차에 넣었어요」. */
@Composable
fun CourseAddBar(
    place: PlaceSummary,
    modifier: Modifier = Modifier,
) {
    val context = LocalCourseAdd.current ?: return
    when (val state = CourseAddRules.button(place.id, context.taken, context.addedTo)) {
        is CourseAddRules.Button.Added -> {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PURPLE.copy(alpha = 0.09f)),
            ) {
                CheckCircleIcon(PURPLE, Modifier.size(16.dp))
                Text(tr("%d일차에 넣었어요").format(state.day + 1), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = PURPLE)
            }
        }

        CourseAddRules.Button.Already -> {
            AddButton(tr("이미 코스에 있어요"), enabled = false, modifier = modifier) {}
        }

        CourseAddRules.Button.Add -> {
            AddButton(tr("코스에 추가"), enabled = true, modifier = modifier) { context.ask(place) }
        }
    }
}

@Composable
private fun AddButton(
    title: String,
    enabled: Boolean,
    modifier: Modifier,
    run: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .alpha(if (enabled) 1f else 0.45f)
                .clip(RoundedCornerShape(12.dp))
                .background(PURPLE)
                .clickable(enabled = enabled, role = Role.Button, onClick = run),
    ) {
        PlusIcon(Color.White, Modifier.size(13.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White)
    }
}

// MARK: 일차 고르기

/** 일차 줄 목록 — 「N일차」, 보고 있던 일차에 「지금 보는 날」, 오른쪽에 그 일차에 든 곳 수. 누르면 바로 고른다. */
@Composable
fun CourseDayList(
    dayCounts: List<Int>,
    current: Int,
    onPick: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(IOS.systemBackground)) {
        dayCounts.forEachIndexed { index, count ->
            if (index > 0) IOSListDivider(start = 16.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                        .clickable(role = Role.Button) { onPick(index) }
                        .padding(horizontal = 16.dp),
            ) {
                Text(tr("%d일차").format(index + 1), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                if (index == current) {
                    Text(tr("지금 보는 날"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = PURPLE)
                }
                Spacer(Modifier.weight(1f))
                val caption =
                    when (val c = CourseAddRules.dayCaption(count)) {
                        CourseAddRules.DayCaption.Empty -> tr("비어 있음")
                        is CourseAddRules.DayCaption.Places -> tr("%d곳").format(c.count)
                    }
                Text(caption, fontSize = 15.sp, color = IOS.secondaryLabel)
                ChevronRightIcon(IOS.tertiaryLabel, Modifier.size(12.dp))
            }
        }
    }
}

/** 아래에서 올라오는 일차 고르기 — 제목, 일차 목록, 취소. */
@Composable
fun CourseDayPickerSheet(
    title: String,
    dayCounts: List<Int>,
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val height = (minOf(dayCounts.size, 5) * 54 + 160).dp
    IOSSheet(detents = listOf(SheetDetent.MEDIUM), onDismiss = onDismiss, fixedHeight = height, containerColor = IOS.systemGray6) {
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(bottom = 12.dp),
        ) {
            Text(
                title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.label,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp, start = 8.dp, end = 8.dp),
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                CourseDayList(dayCounts, current) { day ->
                    onPick(day)
                    onDismiss()
                }
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 50.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(IOS.systemBackground)
                        .clickable(role = Role.Button, onClick = onDismiss),
            ) {
                Text(tr("취소"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = PURPLE)
            }
        }
    }
}

// MARK: 떠 있는 ＋

/**
 * 떠 있는 ＋ — 해태 동그라미(46)와 같은 크기·같은 오른쪽 여백(12)으로 그 위에 선다(E8). 누르면 위로 두 갈래 메뉴.
 * 메뉴가 열려 있으면 뒤를 옅게 덮고, 덮개를 누르면 닫힌다.
 */
@Composable
fun CourseAddFab(
    open: Boolean,
    onToggle: () -> Unit,
    onMap: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        if (open) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.12f)).clickable(onClick = onToggle))
        }
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = (76 + 46 + 10).dp),
        ) {
            if (open) {
                Column(
                    Modifier
                        .width(240.dp)
                        .shadow(12.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(IOS.systemBackground),
                ) {
                    MenuRow(icon = { MapIcon(PURPLE, it) }, title = tr("지도에서 추가"), caption = tr("검색 탭처럼 찾아서 넣기")) {
                        onToggle()
                        onMap()
                    }
                    IOSListDivider(start = 48.dp)
                    MenuRow(icon = { BagIcon(PURPLE, it) }, title = tr("보관함에서 추가"), caption = tr("담아 둔 곳 골라 넣기")) {
                        onToggle()
                        onSaved()
                    }
                }
            }
            val label = tr("장소 추가")
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(46.dp)
                        .shadow(8.dp, CircleShape)
                        .clip(CircleShape)
                        .background(PURPLE)
                        .semantics { contentDescription = label }
                        .clickable(role = Role.Button, onClick = onToggle),
            ) {
                PlusIcon(Color.White, Modifier.size(19.dp).rotate(if (open) 45f else 0f))
            }
        }
    }
}

@Composable
private fun MenuRow(
    icon: @Composable (Modifier) -> Unit,
    title: String,
    caption: String,
    run: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = run).padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { icon(Modifier.size(18.dp)) }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            Text(caption, fontSize = 12.sp, color = IOS.secondaryLabel)
        }
    }
}

// MARK: 보관함에서 추가

/** 큰 시트. 1단계: 담아 둔 곳 체크(고른 순서 번호). 2단계(「N곳 선택 완료」): 넣을 일차를 고른다(E10). */
@Composable
fun SavedAddSheet(
    cart: CartStore,
    dayCounts: List<Int>,
    current: Int,
    taken: Set<Long>,
    onAdd: (List<PlaceSummary>, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var picker by remember { mutableStateOf(CourseAddPicker()) }
    var choosingDay by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cart.refresh() }
    val places = cart.items.filter { it.delisted != true }.mapNotNull { it.toPlaceSummary() }
    val split = CourseAddRules.split(places, taken)
    IOSSheet(detents = listOf(SheetDetent.LARGE), onDismiss = onDismiss, containerColor = IOS.systemGray6) {
        if (choosingDay) {
            IOSSheetToolbar(title = tr("몇 일차에 넣을까요?"), leading = tr("이전"), onLeading = { choosingDay = false })
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            ) {
                Text(
                    tr("고른 %d곳을 넣을 날을 골라 주세요").format(picker.picked.size),
                    fontSize = 13.sp,
                    color = IOS.secondaryLabel,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
                CourseDayList(dayCounts, current) { day ->
                    onAdd(picker.ordered(places), day)
                    onDismiss()
                }
            }
        } else {
            IOSSheetToolbar(title = tr("보관함에서 추가"), leading = tr("닫기"), onLeading = onDismiss)
            if (split.free.isEmpty() && split.inCourse.isEmpty()) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    BagIcon(IOS.tertiaryLabel, Modifier.size(30.dp))
                    Text(tr("아직 담아 둔 곳이 없어요"), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
                    Text(tr("「지도에서 추가」 로 찾아 넣어 보세요"), fontSize = 13.sp, color = IOS.secondaryLabel)
                }
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                ) {
                    if (split.free.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val ids = split.free.map { it.id }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = 4.dp),
                            ) {
                                Text(
                                    tr("담아 둔 곳 %d").format(split.free.size),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = IOS.secondaryLabel,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    if (picker.allPicked(ids)) tr("모두 풀기") else tr("모두 고르기"),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = PURPLE,
                                    modifier = Modifier.clickable(role = Role.Button) { picker = picker.toggleAll(ids) },
                                )
                            }
                            SavedRows(split.free, done = false, picker = picker) { picker = picker.toggle(it) }
                        }
                    }
                    if (split.inCourse.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                tr("이미 코스에 있는 곳 %d").format(split.inCourse.size),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = IOS.secondaryLabel,
                                modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = 4.dp),
                            )
                            Box(Modifier.alpha(0.55f)) { SavedRows(split.inCourse, done = true, picker = picker) {} }
                        }
                    }
                }
                if (split.free.isNotEmpty()) {
                    val label =
                        when (val done = picker.done) {
                            CourseAddPicker.Done.PickFirst -> tr("넣을 곳을 골라 주세요")
                            is CourseAddPicker.Done.Picked -> tr("%d곳 선택 완료").format(done.count)
                        }
                    val enabled = picker.picked.isNotEmpty()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(IOS.systemBackground)
                            .navigationBarsPadding()
                            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 50.dp)
                                    .alpha(if (enabled) 1f else 0.4f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(PURPLE)
                                    .clickable(enabled = enabled, role = Role.Button) { choosingDay = true },
                        ) {
                            Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedRows(
    list: List<PlaceSummary>,
    done: Boolean,
    picker: CourseAddPicker,
    onPress: (Long) -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(IOS.systemBackground)) {
        list.forEachIndexed { index, place ->
            if (index > 0) IOSListDivider(start = 84.dp)
            val order = if (done) null else picker.order(place.id)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(if (order != null) PURPLE.copy(alpha = 0.07f) else Color.Transparent)
                        .clickable(enabled = !done, role = Role.Checkbox) { onPress(place.id) }
                        .padding(start = 14.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            ) {
                RemoteImage(
                    url = place.imageUrl?.toString(),
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(place.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = IOS.label, maxLines = 1)
                    place.contents
                        ?.firstOrNull()
                        ?.title
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { Text(it, fontSize = 13.sp, color = PURPLE, maxLines = 1) }
                    place.address?.let { Text(it, fontSize = 12.sp, color = IOS.secondaryLabel, maxLines = 1) }
                }
                when {
                    done -> {
                        CheckCircleIcon(IOS.systemGray3, Modifier.size(24.dp))
                    }

                    order != null -> {
                        Box(Modifier.size(26.dp).clip(CircleShape).background(PURPLE), contentAlignment = Alignment.Center) {
                            Text("$order", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        }
                    }

                    else -> {
                        Box(Modifier.size(26.dp).border(1.5.dp, IOS.systemGray3, CircleShape))
                    }
                }
            }
        }
    }
}

// MARK: 지도에서 추가

/** 검색 탭을 그대로 편집 위에 띄운다(E9). 맨 위 줄은 「‹ 코스로」 · 「지도에서 추가」. 장소 상세의 「＋ 코스에 추가」 → 일차 고르기. */
@Composable
fun MapAddScreen(
    dayCounts: List<Int>,
    current: Int,
    taken: Set<Long>,
    onAdd: (PlaceSummary, Int) -> Unit,
    onClose: () -> Unit,
) {
    var asking by remember { mutableStateOf<PlaceSummary?>(null) }
    var addedTo by remember { mutableStateOf(mapOf<Long, Int>()) }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        DisableDialogDim()
        FullScreenDialogWindow()
        Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(IOS.systemBackground)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    tr("지도에서 추가"),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = IOS.label,
                    modifier = Modifier.align(Alignment.Center),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .heightIn(min = 44.dp)
                            .clickable(role = Role.Button, onClick = onClose)
                            .padding(horizontal = 8.dp),
                ) {
                    ChevronLeftIcon(IOS.accent, Modifier.size(15.dp))
                    Text(tr("코스로"), fontSize = 17.sp, color = IOS.accent)
                }
            }
            IOSListDivider(start = 0.dp)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CompositionLocalProvider(
                    LocalTabBarClearance provides 0.dp,
                    LocalCourseAdd provides CourseAddContext(taken, addedTo) { asking = it },
                ) {
                    SearchTabScreen(embedded = true)
                }
            }
        }
        asking?.let { place ->
            CourseDayPickerSheet(
                title = tr("「%s」 을 몇 일차에 넣을까요?").format(place.name),
                dayCounts = dayCounts,
                current = current,
                onPick = { day ->
                    onAdd(place, day)
                    addedTo = addedTo + (place.id to day)
                },
                onDismiss = { asking = null },
            )
        }
    }
}
