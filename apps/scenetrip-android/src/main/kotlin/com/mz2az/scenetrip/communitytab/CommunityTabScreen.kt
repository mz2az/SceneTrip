package com.mz2az.scenetrip.communitytab

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.CommunityFeedRules
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBoard
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.MagnifierIcon
import com.mz2az.scenetrip.ui.ProtoMotion
import com.mz2az.scenetrip.ui.SquarePencilIcon
import com.mz2az.scenetrip.ui.XmarkIcon
import com.mz2az.scenetrip.ui.appearIn
import com.mz2az.scenetrip.ui.pressClickable
import com.mz2az.scenetrip.ui.reveal
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 확인용 뒷문(iOS `-communityBoard free`·`-communitySearch 서울`·`-communityOpen post|compose`, `-e initialTab community` 와 함께) —
 * `-e communityBoard free`, `-e communitySearch 글자`, `-e communityOpen post|compose`. 한 번 쓰면 비운다.
 */
object CommunityDebug {
    @Volatile var board: String? = null

    @Volatile var search: String? = null

    @Volatile var open: String? = null
}

/**
 * 커뮤니티 — **여행후기 · 자유게시판** (MZ2AZ-547 P8, iOS `CommunityTabView`, 웹 시안 `proto/ProtoCommunity.tsx`).
 *
 * 머리줄은 왼쪽 「커뮤니티」 · 🔍 · 보라 「✎ 글쓰기」 알약, 그 밑 글자 탭으로 게시판을 고른다(서버가 거른다, `GET /posts?board=`).
 * 🔍 를 누르면 제목 줄이 검색칸이 되고, 두 글자부터 0.35초 멈추면 서버가 제목·본문에서 찾는다(`q`). 탭을 떠나면 지운다.
 * 목록은 끝에 닿기 전에 다음 쪽을 저절로 받는다(서버가 준 `nextCursor`, 없으면 옛 길 offset).
 * 글 읽기·글쓰기는 시트 대신 오른쪽에서 밀려 들어오는 새 화면이다([CommunityNav]).
 *
 * 서버 최신 글과 기기에 보관한 옛 글을 함께 보인다(MZ2AZ-396·423).
 */
@Composable
fun CommunityTabScreen() {
    val store = rememberCommunityStore()
    var board by rememberSaveable { mutableStateOf(if (CommunityDebug.board == "free") TripPostBoard.free else TripPostBoard.review) }
    // null 이면 검색 중이 아니다.
    var searchText by rememberSaveable { mutableStateOf(CommunityDebug.search) }
    val term = CommunityFeedRules.searchTerm(searchText)
    // 게시판·검색어가 바뀌면 서버에 다시 묻는다 — 검색어는 0.35초 멈춘 뒤에. 계정·언어가 바뀌어도 다시 받는다.
    LaunchedEffect(board, term, AuthStore.epoch, AppLanguage.current) {
        delay(CommunityFeedRules.delayMillis(term))
        store.setFilter(board, term)
    }
    // 탭을 떠나면 조건을 지운다 — 다음에 오면 여행후기 첫 쪽부터. 열려 있던 새 화면도 닫는다.
    DisposableEffect(Unit) {
        onDispose {
            store.resetFilterInBackground()
            CommunityNav.close()
        }
    }
    LaunchedEffect(Unit) {
        CommunityDebug.board = null
        CommunityDebug.search = null
        if (CommunityDebug.open == "compose") {
            CommunityDebug.open = null
            CommunityNav.open(CommunityPage.Compose(board))
        }
    }
    LaunchedEffect(
        store.state.posts
            .firstOrNull()
            ?.id,
    ) {
        if (CommunityDebug.open != "post") return@LaunchedEffect
        val posts = store.state.posts
        val post = posts.firstOrNull { it.remotePhotos.isNotEmpty() } ?: posts.firstOrNull() ?: return@LaunchedEffect
        CommunityDebug.open = null
        CommunityNav.open(CommunityPage.Post(post))
    }
    Column(Modifier.fillMaxSize().background(IOS.systemBackground)) {
        CommunityHeader(
            board = board,
            searchText = searchText,
            onBoard = { board = it },
            onSearch = { searchText = it },
            onCompose = { CommunityNav.open(CommunityPage.Compose(board)) },
        )
        CommunityPostList(store, board = board, term = term)
    }
}

@Composable
internal fun rememberCommunityStore(): CommunityStore {
    val context = LocalContext.current
    return remember { CommunityStore.getInstance(context) }
}

@Composable
private fun CommunityHeader(
    board: TripPostBoard,
    searchText: String?,
    onBoard: (TripPostBoard) -> Unit,
    onSearch: (String?) -> Unit,
    onCompose: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().statusBarsPadding().background(IOS.systemBackground)) {
        if (searchText == null) {
            TitleRow(onSearch = { onSearch("") }, onCompose = onCompose)
        } else {
            SearchRow(searchText, onSearch)
        }
        BoardTabs(board, onBoard)
    }
}

@Composable
private fun TitleRow(
    onSearch: () -> Unit,
    onCompose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 12.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            tr("커뮤니티"),
            fontSize = 22.sp,
            fontWeight = FontWeight.ExtraBold,
            color = IOS.label,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(44.dp)
                .semantics { contentDescription = tr("글 검색") }
                .pressClickable(onClick = onSearch)
                .testTag("community-search"),
            contentAlignment = Alignment.Center,
        ) { MagnifierIcon(IOS.pinDeep, Modifier.size(20.dp)) }
        Box(
            Modifier.heightIn(min = 44.dp).pressClickable(onClick = onCompose).testTag("community-compose"),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(50))
                    .background(IOS.pinDeep)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                SquarePencilIcon(Color.White, Modifier.size(14.dp))
                Text(tr("글쓰기"), fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

@Composable
private fun SearchRow(
    text: String,
    onSearch: (String?) -> Unit,
) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    // 오른쪽 돋보기 자리에서 왼쪽으로 0.3초에 길어지고, 닫을 때는 0.2초에 줄어든 뒤 제목 줄로 돌아간다.
    // 「취소」 는 0.08초 늦게 떠오르고 닫을 때 0.15초에 사라진다(MZ2AZ-547 M14, 웹 `.proto-search-open-left`).
    val reveal = remember { Animatable(0f) }
    val cancelAlpha = remember { Animatable(1f) }
    var closing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(300, easing = ProtoMotion.EaseOutSheet)) }
    val close: () -> Unit = {
        if (!closing) {
            closing = true
            focusManager.clearFocus()
            scope.launch {
                launch { cancelAlpha.animateTo(0f, tween(150)) }
                reveal.animateTo(0f, tween(200, easing = ProtoMotion.EaseIn))
                onSearch(null)
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .reveal({ reveal.value }, collapsed = 38.dp, fromStart = false)
                .height(38.dp)
                .clip(RoundedCornerShape(50))
                .background(IOS.systemGray6)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MagnifierIcon(IOS.secondaryLabel, Modifier.size(16.dp))
            BasicTextField(
                text,
                { onSearch(it) },
                singleLine = true,
                textStyle = IOS.body.copy(fontSize = 16.sp, color = IOS.label),
                cursorBrush = SolidColor(IOS.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(focus)
                        .semantics { contentDescription = tr("글 검색") }
                        .testTag("community-search-field"),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(tr("제목·내용으로 찾기"), fontSize = 16.sp, color = IOS.tertiaryLabel)
                        inner()
                    }
                },
            )
            if (text.isNotEmpty()) {
                Box(
                    Modifier.size(28.dp).semantics { contentDescription = tr("지우기") }.clickable { onSearch("") },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(16.dp).clip(RoundedCornerShape(50)).background(IOS.systemGray3),
                        contentAlignment = Alignment.Center,
                    ) {
                        XmarkIcon(Color.White, Modifier.size(7.dp))
                    }
                }
            }
        }
        Text(
            tr("취소"),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = IOS.pinDeep,
            modifier =
                Modifier
                    .appearIn()
                    .graphicsLayer { alpha = cancelAlpha.value }
                    .heightIn(min = 44.dp)
                    .pressClickable(onClick = close)
                    .padding(horizontal = 8.dp, vertical = 11.dp),
        )
    }
}

/** 게시판 — 밑줄·칸 없이 글자만. 고른 것은 진한 글자, 아닌 것은 옅은 회색. */
@Composable
private fun BoardTabs(
    board: TripPostBoard,
    onBoard: (TripPostBoard) -> Unit,
) {
    Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CommunityFeedRules.boards.forEach { item ->
            val on = item == board
            // 고른 게시판 글자색이 0.18초에 옮겨 간다(MZ2AZ-547 M15, 웹 `transition: color 180ms ease-out`).
            val tint by animateColorAsState(if (on) IOS.label else IOS.systemGray3, tween(180), label = "boardTint")
            Text(
                tr(CommunityFeedRules.boardLabelKey(item)),
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = tint,
                modifier =
                    Modifier
                        .heightIn(min = 44.dp)
                        .semantics { selected = on }
                        .pressClickable(role = null) { onBoard(item) }
                        .padding(vertical = 10.dp)
                        .testTag("community-board-${item.value}"),
            )
        }
    }
}
