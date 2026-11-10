package com.mz2az.scenetrip.routetab

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import com.mz2az.scenetrip.ui.ChevronLeftIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.MagnifierIcon
import com.mz2az.scenetrip.ui.ProtoMotion
import com.mz2az.scenetrip.ui.appearIn
import com.mz2az.scenetrip.ui.reveal
import kotlinx.coroutines.launch

/**
 * 🔍 를 누르면 칩 줄 자리를 덮는 검색칸과 그 아래 목록 (MZ2AZ-547 M6, iOS `MapSearchPanel`, 웹 `protoPoi.tsx` `SearchPanel`).
 *
 * 가게·장소(편의시설)만 이름으로 찾는다 — 촬영지는 찾지 않는다(2026-11-10 사용자 요청, 웹 72f87416).
 * 범위 없이 화면 가운데에서 가까운 순으로 찾는다(§10-2). 검색 중에는 갈래 칩을 숨긴다.
 * 글자 치는 동안은 목록만 — 지도에는 아무것도 찍지 않는다(§7-2). 목록 맨 위 줄 「‘q’ 검색」 이나 키보드 검색 →
 * 결과 서랍을 연다([MapSearchResultsView]). 가게 줄을 누르면 그 가게로.
 */
@Composable
internal fun MapSearchPanel(
    model: MapSearchModel,
    viewport: MapViewport?,
    initialText: String,
    onPick: (PoiSummary) -> Unit,
    onCommit: (query: String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 다시 열 때 채운 글자 뒤에 커서를 둔다.
    var field by remember { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
    val text = field.text
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        model.update(text, viewport)
        runCatching { focus.requestFocus() }
    }
    // 닫히면 목록을 지운다.
    DisposableEffect(Unit) { onDispose { model.reset() } }
    // 🔍 동그라미 자리에서 오른쪽으로 0.28초에 길어지고, 닫을 때는 0.2초에 줄어든 뒤 닫힌다(MZ2AZ-547 M7, 웹 `.proto-search-open/close`).
    val reveal = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(280, easing = ProtoMotion.EaseOutSheet)) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            scope.launch {
                reveal.animateTo(0f, tween(200, easing = ProtoMotion.EaseIn))
                onClose()
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .reveal({ reveal.value }, collapsed = 46.dp, fromStart = true)
                    .shadow(10.dp, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .background(IOS.systemBackground)
                    .padding(horizontal = 6.dp),
        ) {
            val closeLabel = tr("검색 닫기")
            Box(
                contentAlignment = Alignment.Center,
                modifier =
                    Modifier
                        .size(40.dp)
                        .semantics { contentDescription = closeLabel }
                        .clickable(role = Role.Button, onClick = close),
            ) { ChevronLeftIcon(IOS.label, Modifier.size(17.dp)) }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) {
                    Text(MapSearchText.placeholder, fontSize = 16.sp, color = IOS.tertiaryLabel)
                }
                BasicTextField(
                    value = field,
                    onValueChange = {
                        val changed = it.text != field.text
                        field = it
                        if (changed) model.update(it.text, viewport)
                    },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 16.sp, color = IOS.label),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions =
                        KeyboardActions(onSearch = {
                            MapSearchRules.query(text)?.let { onCommit(it) }
                        }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (text.isNotEmpty()) {
                val clearLabel = tr("검색어 지우기")
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(40.dp)
                            .semantics { contentDescription = clearLabel }
                            .clickable(role = Role.Button) {
                                field = TextFieldValue("")
                                model.update("", viewport)
                            },
                ) {
                    Icon(Icons.Filled.Clear, contentDescription = null, tint = IOS.tertiaryLabel, modifier = Modifier.size(18.dp))
                }
            }
        }
        val rows = if (closing) emptyList() else MapSearchRules.panelRows(text, model.phase)
        if (rows.isNotEmpty()) {
            // 짧으면 내용 높이만, 길면 420 에서 넘긴다(iOS — 한두 줄짜리 목록에 빈 칸이 남지 않게).
            Column(
                modifier =
                    Modifier
                        // 0.08초 뒤 6dp 내려오며 0.22초에 나타난다(M8, 웹 `.proto-search-results`).
                        .appearIn()
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .shadow(10.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(IOS.systemBackground)
                        .verticalScroll(rememberScrollState()),
            ) {
                rows.forEach { row ->
                    when (row) {
                        // 맨 위 「‘q’ 검색」 — 누르면 키보드 검색과 같이 결과 서랍을 연다(§7-2, iOS `MapSearchPanel`).
                        is MapSearchRow.Commit -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clickable(role = Role.Button) { onCommit(row.query) }
                                        .padding(horizontal = 16.dp),
                            ) {
                                MagnifierIcon(IOS.homePurple, Modifier.size(15.dp))
                                Text(
                                    tr("‘%s’ 검색").format(row.query),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = IOS.homePurple,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        MapSearchRow.Loading -> {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(strokeWidth = 2.dp, color = IOS.secondaryLabel, modifier = Modifier.size(20.dp))
                            }
                        }

                        MapSearchRow.Failed -> {
                            Text(
                                tr("찾지 못했어요. 잠시 후 다시 해 주세요"),
                                fontSize = 14.sp,
                                color = IOS.secondaryLabel,
                                modifier = Modifier.fillMaxWidth().padding(20.dp),
                            )
                        }

                        is MapSearchRow.Title -> {
                            Text(
                                MapSearchText.title(row.title),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = IOS.secondaryLabel,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .background(IOS.systemGray6)
                                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
                            )
                        }

                        is MapSearchRow.Shop -> {
                            MapShopRow(row.place, Modifier.clickable { onPick(row.place) })
                        }

                        is MapSearchRow.Empty -> {
                            Text(
                                MapSearchText.empty(row.empty),
                                fontSize = 13.sp,
                                color = IOS.secondaryLabel,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 가게 줄의 왼쪽 여백 — 구분선도 같은 들여쓰기. */
internal val MapShopRowInset = 16.dp

/** 검색 목록·결과 서랍이 함께 쓰는 문구. */
internal object MapSearchText {
    /** 검색칸 — 「가게·장소 이름」. */
    val placeholder: String get() = tr("가게·장소 이름")

    fun title(title: MapSearchTitle): String =
        when (title) {
            MapSearchTitle.SHOPS_NEARBY -> tr("가게·장소 · 가까운 순")
        }

    fun empty(empty: MapSearchEmpty): String =
        when (empty) {
            MapSearchEmpty.NO_PLACES -> tr("찾는 곳이 없어요")
        }
}

/** 가게 한 줄 — 이름 · 분류·주소. 왼쪽 칸·갈래 점은 뺐다 — 글이 줄 왼쪽 여백(16)에서 시작한다(MZ2AZ-547 §2). */
@Composable
internal fun MapShopRow(
    place: PoiSummary,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = MapShopRowInset, end = 14.dp, top = 8.dp, bottom = 8.dp),
    ) {
        val label = remember(place, com.mz2az.scenetrip.data.AppLanguage.current) { place.asGuidePlace().label() }
        Column(Modifier.weight(1f)) {
            Text(
                label.title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(label.category, label.address).joinToString(" · ")
            if (sub.isNotEmpty()) {
                Text(sub, fontSize = 12.sp, color = IOS.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
