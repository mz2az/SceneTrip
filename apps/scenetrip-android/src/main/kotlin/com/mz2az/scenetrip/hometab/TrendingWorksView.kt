package com.mz2az.scenetrip.hometab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.api.ContentsApi
import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary
import com.mz2az.scenetrip.searchtab.FilmIcon
import com.mz2az.scenetrip.searchtab.RemoteImage
import com.mz2az.scenetrip.ui.ChevronLeftIcon
import com.mz2az.scenetrip.ui.IOS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 전체 보기 격자의 상태. */
private sealed class TrendingPhase {
    data object Loading : TrendingPhase()

    data object Failed : TrendingPhase()

    data class Loaded(
        val works: List<ContentSummary>,
    ) : TrendingPhase()
}

/**
 * 홈 「지금 뜨는 작품」 의 「전체 보기」 — 인기순 작품 전체를 포스터 격자로 (MZ2AZ-547 H6, 웹 MZ2AZ-534 `TrendingWorks.tsx`,
 * iOS `TrendingWorksView.swift`).
 *
 * 전에는 「전체 보기」 가 작품검색 탭의 목록을 열었다. 이제 홈 위에 덮이는 화면이고 ‹·하드웨어 뒤로가 홈으로 돌아간다.
 * 맨 앞은 고정 작품(케이팝 데몬 헌터스, [PinnedWorks]), 나머지는 서버 인기순 그대로다. 포스터를 누르면 홈 선반과 같은 길로
 * 작품검색 탭의 그 작품 상세가 열린다(덮개를 닫고 탭으로 간다).
 */
@Composable
fun TrendingWorksView(
    onClose: () -> Unit,
    onOpen: (ContentSummary) -> Unit,
) {
    BackHandler(onBack = onClose)
    val api = remember { ContentsApi(API_BASE) }
    var phase by remember { mutableStateOf<TrendingPhase>(TrendingPhase.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(attempt) {
        if (phase is TrendingPhase.Failed) phase = TrendingPhase.Loading
        phase =
            runCatching {
                TrendingRules.load(
                    fetchPage = { offset ->
                        val page = withContext(Dispatchers.IO) { api.listContents(limit = TrendingRules.PAGE_LIMIT, offset = offset) }
                        page.items to page.total
                    },
                    fetchDetail = { id -> withContext(Dispatchers.IO) { runCatching { api.getContent(id) }.getOrNull() } },
                )
            }.fold(
                onSuccess = { TrendingPhase.Loaded(it) },
                // 들고 있는 목록이 있으면 그대로 둔다 — 새로 고침 실패로 보던 격자를 지우지 않는다.
                onFailure = { if (phase is TrendingPhase.Loaded) phase else TrendingPhase.Failed },
            )
    }

    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Column(modifier = Modifier.fillMaxSize().background(IOS.systemGray6).statusBarsPadding()) {
        Header(onClose)
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 28.dp + navBar),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(tr("인기순"), fontSize = 13.sp, color = IOS.secondaryLabel)
            }
            when (val now = phase) {
                TrendingPhase.Loading -> {
                    items(6) { SkeletonTile() }
                }

                TrendingPhase.Failed -> {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 48.dp),
                        ) {
                            Text(
                                tr("서버에 연결하지 못했어요. 잠시 뒤 다시 시도해 주세요"),
                                fontSize = 14.sp,
                                color = IOS.secondaryLabel,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                tr("다시 시도"),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = IOS.accent,
                                modifier =
                                    Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(IOS.accent.copy(alpha = 0.12f))
                                        .clickable { attempt += 1 }
                                        .padding(horizontal = 14.dp, vertical = 7.dp),
                            )
                        }
                    }
                }

                is TrendingPhase.Loaded -> {
                    if (now.works.isEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                tr("검색된 작품이 없어요"),
                                fontSize = 14.sp,
                                color = IOS.secondaryLabel,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                            )
                        }
                    } else {
                        items(now.works, key = { it.id }) { work -> Tile(work) { onOpen(work) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(onClose: () -> Unit) {
    val back = tr("이전")
    Box(modifier = Modifier.fillMaxWidth().height(44.dp)) {
        Text(
            tr("지금 뜨는 작품"),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = IOS.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 60.dp).semantics { heading() },
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .size(44.dp)
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = back },
        ) {
            ChevronLeftIcon(IOS.accent, Modifier.size(20.dp))
        }
    }
}

private const val POSTER_RATIO = 108f / 144f

@Composable
private fun SkeletonTile() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(POSTER_RATIO)
                .clip(RoundedCornerShape(14.dp))
                .background(IOS.systemGray5),
        )
        Box(
            Modifier
                .width(60.dp)
                .height(11.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(IOS.systemGray5),
        )
    }
}

@Composable
private fun Tile(
    work: ContentSummary,
    onClick: () -> Unit,
) {
    val label = "${work.title}, ${tr("촬영지 %d곳").format(work.placeCount)}"
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clickable(onClick = onClick).semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(POSTER_RATIO)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(IOS.pinDeep.copy(alpha = 0.7f), IOS.pinDeep))),
        ) {
            RemoteImage(
                url = work.posterUrl?.toString(),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = { FilmIcon(IOS.tertiaryLabel, Modifier.size(28.dp)) },
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f))),
            )
            Text(
                work.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
        Text(tr("촬영지 %d곳").format(work.placeCount), fontSize = 11.sp, color = IOS.secondaryLabel)
    }
}
