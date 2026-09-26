package com.mz2az.scenetrip.routetab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.RouteStore
import com.mz2az.scenetrip.sceneapi.client.model.MarketCourseSummary
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.launch

/**
 * 남이 올린 코스를 보고 내 것으로 담는 화면. iOS `RouteTab/RouteMarketView.swift`를
 * 옮긴 것이다.
 *
 * **미확정·데모용이다** — 이름·정렬 기준이 아직 안 정해졌다. 목록과 담기까지만
 * 만든다. 좋아요·담긴 수는 지어낸 값이 아니라 서버 값이다.
 */
@Composable
fun RouteMarketView(
    store: RouteStore,
    marketLoaded: Boolean,
) {
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(setOf<Long>()) }
    var busy by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) { store.refreshMarket() }

    if (store.marketCourses.isEmpty()) {
        if (!marketLoaded) {
            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(modifier = Modifier.padding(top = 40.dp))
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("아직 올라온 코스가 없습니다", style = IOS.headline, color = IOS.secondaryLabel)
                Text(
                    "코스를 만들고 \"마켓에 올리기\"를 누르면 여기 보입니다",
                    style = IOS.footnote,
                    color = IOS.tertiaryLabel,
                )
            }
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            items(store.marketCourses, key = { it.id }) { course ->
                MarketCourseRow(
                    course = course,
                    isSaved = course.saved || saved.contains(course.id),
                    isBusy = busy == course.id,
                    onToggleLike = { scope.launch { store.toggleMarketLike(course) } },
                    onSave = {
                        busy = course.id
                        scope.launch {
                            if (store.saveFromMarket(course)) saved = saved + course.id
                            busy = null
                        }
                    },
                )
            }
        }
    }

    if (store.failure != null) {
        val unauthorized = store.failure?.statusCode == 401
        AlertDialog(
            onDismissRequest = { store.clearFailure() },
            title = { Text(if (unauthorized) "가입이 필요합니다" else "하지 못했습니다") },
            text = {
                Text(
                    if (unauthorized) {
                        "코스를 담고 좋아요를 누르려면 가입해야 합니다."
                    } else {
                        store.failure?.message ?: "잠시 후 다시 시도해 주세요."
                    },
                )
            },
            confirmButton = { TextButton(onClick = { store.clearFailure() }) { Text("확인") } },
        )
    }
}

@Composable
private fun MarketCourseRow(
    course: MarketCourseSummary,
    isSaved: Boolean,
    isBusy: Boolean,
    onToggleLike: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(IOS.systemBackground)
                .padding(14.dp),
    ) {
        Text(course.title, style = IOS.headline, color = IOS.label)
        // 남의 코스에는 날짜가 없다 — N일로만 보여 준다.
        Text("${course.dayCount}일 · ${course.placeCount}곳", fontSize = 12.sp, color = IOS.secondaryLabel)
        if (course.description.isNotEmpty()) {
            Text(course.description, fontSize = 12.sp, color = IOS.secondaryLabel, maxLines = 2)
        }
        course.contents?.map { it.title }?.takeIf { it.isNotEmpty() }?.let { works ->
            Text(
                works.take(2).joinToString(" · "),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = IOS.pinDeep,
                maxLines = 1,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.clickable(onClick = onToggleLike),
            ) {
                Icon(
                    if (course.liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = null,
                    tint = if (course.liked) IOS.systemRed else IOS.secondaryLabel,
                    modifier = Modifier.size(14.dp),
                )
                Text("${course.likeCount}", fontSize = 12.sp, color = IOS.secondaryLabel)
            }
            Text("담기 ${course.saveCount}", fontSize = 12.sp, color = IOS.secondaryLabel)
            Spacer(Modifier.weight(1f))
            if (isBusy) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    if (isSaved) "담았습니다" else "내 코스로 담기",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSaved) IOS.tertiaryLabel else IOS.accent,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background((if (isSaved) IOS.tertiaryLabel else IOS.accent).copy(alpha = 0.12f))
                            .clickable(enabled = !isSaved, onClick = onSave)
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}
