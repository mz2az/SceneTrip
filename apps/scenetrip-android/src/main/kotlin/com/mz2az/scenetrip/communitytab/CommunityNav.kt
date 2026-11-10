package com.mz2az.scenetrip.communitytab

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.data.CommunityFeedRules
import com.mz2az.scenetrip.data.CommunityPost
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBoard
import com.mz2az.scenetrip.ui.ChevronLeftIcon
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.rememberReduceMotion

/** 커뮤니티 탭 안의 새 화면 — 글 읽기·글쓰기 (MZ2AZ-547 K6, iOS `CommunityRoute`). */
sealed interface CommunityPage {
    data class Post(
        val post: CommunityPost,
    ) : CommunityPage

    data class Compose(
        val board: TripPostBoard,
    ) : CommunityPage
}

/**
 * 커뮤니티 탭의 새 화면 하나를 든다. 시트 대신 **오른쪽에서 밀려 들어오는 전체 화면**이고 탭바까지 덮는다(K6) —
 * 그래서 탭 내용 안이 아니라 [com.mz2az.scenetrip.RootTabs] 의 탭바 위에 [CommunityPageHost] 로 그린다.
 * 글 화면에서 글쓰기로 가는 길은 없어 한 겹이면 된다(iOS `NavigationStack` 도 실제로는 한 겹이다).
 */
object CommunityNav {
    var page by mutableStateOf<CommunityPage?>(null)
        private set

    fun open(next: CommunityPage) {
        page = next
    }

    fun close() {
        page = null
    }
}

private const val SLIDE_MILLIS = 300

/** 탭바 위에 그리는 새 화면 자리. 비어 있으면 아무것도 그리지 않는다(손가락은 밑의 목록으로 간다). */
@Composable
fun CommunityPageHost() {
    val reduceMotion = rememberReduceMotion()
    AnimatedContent(
        targetState = CommunityNav.page,
        contentKey = { page ->
            when (page) {
                null -> "none"
                is CommunityPage.Post -> "post:" + page.post.id
                is CommunityPage.Compose -> "compose"
            }
        },
        transitionSpec = {
            val opening = targetState != null
            when {
                reduceMotion -> {
                    EnterTransition.None togetherWith ExitTransition.None
                }

                // 열 때는 새 화면이 오른쪽에서 들어오고, 닫을 때는 위에 있던 화면이 오른쪽으로 빠진다.
                opening -> {
                    slideInHorizontally(tween(SLIDE_MILLIS)) { it } togetherWith ExitTransition.None
                }

                else -> {
                    (EnterTransition.None togetherWith slideOutHorizontally(tween(SLIDE_MILLIS)) { it }).apply {
                        targetContentZIndex =
                            -1f
                    }
                }
            }
        },
        label = "community-page",
    ) { page ->
        when (page) {
            null -> Box(Modifier)
            is CommunityPage.Post -> CommunityPageFrame { CommunityPostView(page.post, onDismiss = CommunityNav::close, page = true) }
            is CommunityPage.Compose -> CommunityPageFrame { CommunityComposeView(onDismiss = CommunityNav::close, board = page.board) }
        }
    }
}

/** 새 화면의 바탕 — 밑의 목록으로 손가락이 새지 않게 받는다. */
@Composable
private fun CommunityPageFrame(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(IOS.systemBackground)
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) { content() }
}

/** 글 화면 머리 「‹ 여행후기」 — 꺾쇠와 게시판 이름이 한 단추다. 하드웨어 뒤로도 같다. */
@Composable
internal fun CommunityBackHeader(
    board: TripPostBoard?,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .heightIn(min = 44.dp)
                .semantics { contentDescription = tr("뒤로") }
                .clickable(onClick = onBack)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChevronLeftIcon(IOS.pinDeep, Modifier.size(18.dp))
            Text(
                tr(CommunityFeedRules.boardLabelKey(CommunityFeedRules.board(board))),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = IOS.label,
            )
        }
    }
}
