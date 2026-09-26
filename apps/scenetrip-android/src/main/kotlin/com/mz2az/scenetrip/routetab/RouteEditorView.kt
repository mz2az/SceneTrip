package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 코스 편집. iOS `RouteTab/RouteEditorView.swift`(+Controls/+Parts/+Trip, 합쳐
 * 1760줄)의 **최소 이식**이다.
 *
 * 드래그 재정렬·AI 안내 띠·코스마켓 올리기·챗봇 가이드 연동은 아직 없다 — 위/아래
 * 화살표로 순서를 바꾸고, 장소는 검색으로 하나씩 담는다. 코스를 만들고·채우고·
 * 저장하고·여행을 시작/종료하고·지우는 핵심 흐름은 전부 된다.
 */
@Composable
fun RouteEditorView(
    store: RouteStore,
    initial: RouteCourse,
    onClose: (RouteCourse?) -> Unit,
) {
    var course by remember { mutableStateOf(initial) }
    var selectedDay by remember { mutableStateOf(0) }
    var searching by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun updateDay(
        index: Int,
        transform: (RouteDay) -> RouteDay,
    ) {
        val days = course.days.toMutableList()
        if (index in days.indices) {
            days[index] = transform(days[index])
            course = course.copy(days = days)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemGray6).statusBarsPadding()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp),
        ) {
            Box(modifier = Modifier.size(32.dp).clickable { onClose(null) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Close, contentDescription = "닫기", tint = IOS.label, modifier = Modifier.size(16.dp))
            }
            OutlinedTextField(
                value = course.title,
                onValueChange = { course = course.copy(title = it) },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textStyle =
                    androidx.compose.ui.text
                        .TextStyle(fontSize = 16.sp),
                singleLine = true,
            )
            if (saving) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    "저장",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = IOS.accent,
                    modifier =
                        Modifier.clickable {
                            saving = true
                            scope.launch {
                                val saved = store.save(course)
                                saving = false
                                onClose(saved ?: course)
                            }
                        },
                )
            }
        }

        if (course.days.size > 1) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                items(course.days.size) { index ->
                    val active = index == selectedDay
                    Text(
                        "${index + 1}일차",
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (active) IOS.label else IOS.secondaryLabel,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (active) IOS.systemBackground else Color.Transparent)
                                .clickable { selectedDay = index }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }

        val day = course.days.getOrNull(selectedDay)
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            if (day != null) {
                itemsIndexed(day.stops) { index, stop ->
                    StopRow(
                        stop = stop,
                        onRemove = {
                            updateDay(selectedDay) { d -> d.copy(stops = d.stops.filterNot { it.id == stop.id }) }
                        },
                        onMoveUp =
                            if (index > 0) {
                                {
                                    updateDay(selectedDay) { d ->
                                        val list = d.stops.toMutableList()
                                        val tmp = list[index - 1]
                                        list[index - 1] = list[index]
                                        list[index] = tmp
                                        d.copy(stops = list)
                                    }
                                }
                            } else {
                                null
                            },
                        onMoveDown =
                            if (index < day.stops.lastIndex) {
                                {
                                    updateDay(selectedDay) { d ->
                                        val list = d.stops.toMutableList()
                                        val tmp = list[index + 1]
                                        list[index + 1] = list[index]
                                        list[index] = tmp
                                        d.copy(stops = list)
                                    }
                                }
                            } else {
                                null
                            },
                    )
                }
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(IOS.accent.copy(alpha = 0.1f))
                            .clickable { searching = true }
                            .padding(vertical = 12.dp),
                ) {
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.Filled.Add, contentDescription = null, tint = IOS.accent, modifier = Modifier.size(16.dp))
                    Text("장소 추가", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = IOS.accent)
                    Spacer(Modifier.weight(1f))
                }
            }

            if (course.serverId != null) {
                item {
                    val running = course.isRunning
                    Text(
                        if (running) "여행 종료" else "코스 시작",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (running) IOS.systemRed else IOS.accent,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(IOS.systemBackground)
                                .clickable {
                                    scope.launch {
                                        store.setRunning(course, !running, dayNo = 1)
                                        course = course.copy(isRunning = !running)
                                    }
                                }.padding(vertical = 12.dp),
                    )
                }
                item {
                    Text(
                        "코스 삭제",
                        fontSize = 14.sp,
                        color = IOS.systemRed,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp, bottom = 24.dp)
                                .clickable { confirmingDelete = true }
                                .padding(vertical = 12.dp),
                    )
                }
            } else {
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (searching) {
        PlaceSearchOverlay(
            onDismiss = { searching = false },
            onPick = { place ->
                updateDay(selectedDay) { d -> d.copy(stops = d.stops + RouteStop(place = place)) }
                searching = false
            },
        )
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("\"${course.title}\"을 지울까요?") },
            text = { Text("되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    scope.launch {
                        store.delete(course)
                        onClose(null)
                    }
                }) { Text("삭제", color = IOS.systemRed) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun StopRow(
    stop: RouteStop,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(IOS.systemBackground)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stop.place.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = IOS.label)
            Text("${stop.stayLabel} 머묾", fontSize = 11.sp, color = IOS.secondaryLabel)
        }
        if (onMoveUp != null) {
            Text(
                "▲",
                fontSize = 12.sp,
                color = IOS.secondaryLabel,
                modifier = Modifier.clickable(onClick = onMoveUp).padding(4.dp),
            )
        }
        if (onMoveDown != null) {
            Text(
                "▼",
                fontSize = 12.sp,
                color = IOS.secondaryLabel,
                modifier = Modifier.clickable(onClick = onMoveDown).padding(4.dp),
            )
        }
        Icon(
            Icons.Filled.Delete,
            contentDescription = "빼기",
            tint = IOS.tertiaryLabel,
            modifier = Modifier.size(16.dp).clickable(onClick = onRemove).padding(4.dp),
        )
    }
}

/** 장소 검색 — RouteSearchSheet의 최소 이식. 이름으로 찾아 하나 고른다. */
@Composable
private fun PlaceSearchOverlay(
    onDismiss: () -> Unit,
    onPick: (PlaceSummary) -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlaceSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        loading = true
        val api = PlacesApi(API_BASE)
        results =
            withContext(Dispatchers.IO) {
                runCatching { api.listPlaces(q = query, limit = 30) }.getOrNull()
            }?.items ?: emptyList()
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().background(IOS.systemBackground).statusBarsPadding()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("촬영지 이름으로 검색") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Text(
                "취소",
                fontSize = 15.sp,
                color = IOS.accent,
                modifier = Modifier.clickable(onClick = onDismiss).padding(start = 10.dp),
            )
        }
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.padding(20.dp))
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(results, key = { it.id }) { place ->
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(place) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(place.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = IOS.label)
                    place.address?.let { Text(it, fontSize = 12.sp, color = IOS.secondaryLabel, maxLines = 1) }
                }
            }
        }
    }
}
