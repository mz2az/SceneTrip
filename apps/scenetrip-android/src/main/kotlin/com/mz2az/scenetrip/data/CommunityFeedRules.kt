package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.model.TripPostBoard

/**
 * 커뮤니티 목록의 순수 규칙 — 게시판·검색·끊김 없는 목록·빈 화면·좋아요/댓글 수 (MZ2AZ-547 P8, iOS `CommunityFeedRules`,
 * 웹 `ProtoCommunity.tsx`·`CommunityTab.tsx`). 화면 없이 시험한다.
 *
 * 「글자 수」 는 iOS `String.count`(보이는 글자) 대신 **코드 포인트**로 센다 — 한글·영문은 같고, 이모지 하나를 반으로 자르지 않는다.
 */
object CommunityFeedRules {
    /** 게시판 탭 순서 — 여행후기 · 자유게시판. */
    val boards: List<TripPostBoard> = listOf(TripPostBoard.review, TripPostBoard.free)

    /** 서버가 게시판을 주지 않은 글(옛 서버·기기 옛 글)은 여행후기다. */
    fun board(raw: TripPostBoard?): TripPostBoard = raw ?: TripPostBoard.review

    /** 게시판 이름의 번역 열쇠(한국어 원문) — 탭·글 화면 머리·글쓰기 칩이 같이 쓴다. */
    fun boardLabelKey(board: TripPostBoard): String =
        when (board) {
            TripPostBoard.review -> "여행후기"
            TripPostBoard.free -> "자유게시판"
        }

    /** 검색어는 두 글자부터 보낸다. */
    const val SEARCH_MIN_LENGTH = 2

    /** 입력이 이만큼(0.35초) 멈추면 보낸다. */
    const val SEARCH_DELAY_MILLIS = 350L

    /** 보낼 검색어 — 앞뒤 빈칸을 떼고 두 글자 미만이면 null. 검색 중이 아니면(null) null. */
    fun searchTerm(raw: String?): String? {
        val term = raw?.trim() ?: return null
        return term.takeIf { length(it) >= SEARCH_MIN_LENGTH }
    }

    /** 조건을 바꾼 뒤 보내기까지 기다릴 시간(밀리초) — 검색어가 있으면 [SEARCH_DELAY_MILLIS], 없으면 0. */
    fun delayMillis(term: String?): Long = if (term == null) 0L else SEARCH_DELAY_MILLIS

    /** 다음 쪽을 받는 방법 — 커서와 건너뛸 줄 수. */
    data class Page(
        val cursor: String?,
        val offset: Int,
    )

    /**
     * 첫 쪽이면 커서 없이 0. 더 받기면 서버가 준 커서가 있으면 커서(건너뛰기 0), 없으면 받은 줄 수만큼 건너뛰기(옛 길).
     */
    fun page(
        more: Boolean,
        nextCursor: String?,
        loaded: Int,
    ): Page =
        when {
            !more -> Page(null, 0)
            nextCursor != null -> Page(nextCursor, 0)
            else -> Page(null, loaded)
        }

    /** 끝에서 몇 줄 앞에 닿으면 다음 쪽을 받는가. */
    const val PREFETCH_DISTANCE = 5

    /**
     * 줄 하나가 화면에 들어왔다 — 다음 쪽을 받을까. 그 줄이 끝에서 [PREFETCH_DISTANCE] 줄 안(appearedIndex >= loaded - 5)이고,
     * 더 있고(loaded < total), 받는 중이 아니고, 실패 문구가 떠 있지 않을 때.
     */
    fun shouldLoadMore(
        appearedIndex: Int,
        loaded: Int,
        total: Int,
        loading: Boolean,
        failed: Boolean,
    ): Boolean = !loading && !failed && loaded < total && appearedIndex >= loaded - PREFETCH_DISTANCE

    sealed interface EmptyState {
        /** 「「q」 에 맞는 글이 없어요」 */
        data class NoMatch(
            val term: String,
        ) : EmptyState

        /** 자유게시판 — 「아직 글이 없어요 / 여행 이야기·질문을 자유롭게 남겨 보세요」 */
        data object FreeBoard : EmptyState

        /** 여행후기 — 「아직 후기가 없습니다」 */
        data object Reviews : EmptyState
    }

    /**
     * 목록이 비었을 때 보일 것. 받는 중이거나 실패 문구가 있거나 보일 글이 있으면 null.
     * 검색 중(term 이 null 아님)이면 기기 옛 글과 무관하게 서버 글이 0 일 때 NoMatch(term).
     * 검색 중이 아니면 서버 글·옛 글이 모두 0 일 때 게시판에 따라 FreeBoard / Reviews.
     */
    fun emptyState(
        board: TripPostBoard,
        term: String?,
        posts: Int,
        legacy: Int,
        loading: Boolean,
        failed: Boolean,
    ): EmptyState? {
        if (loading || failed || posts != 0) return null
        if (term != null) return EmptyState.NoMatch(term)
        if (legacy != 0) return null
        return if (board == TripPostBoard.free) EmptyState.FreeBoard else EmptyState.Reviews
    }

    data class Counts(
        val likes: Int,
        val comments: Int,
    )

    /** 목록 줄의 「👍 N 💬 M」. 서버가 수를 둘 다 주지 않았으면 그리지 않는다(null). 하나만 없으면 그쪽은 0. */
    fun counts(
        likeCount: Int?,
        commentCount: Int?,
    ): Counts? = if (likeCount == null && commentCount == null) null else Counts(likeCount ?: 0, commentCount ?: 0)

    data class Like(
        val liked: Boolean,
        val count: Int,
    )

    /** 좋아요를 누른 직후(낙관적) 값 — 뒤집고 수를 ±1. 수는 0 밑으로 내려가지 않는다. */
    fun toggledLike(
        liked: Boolean,
        count: Int,
    ): Like {
        val next = !liked
        return Like(next, maxOf(0, count + if (next) 1 else -1))
    }

    const val COMMENT_LIMIT = 500

    /** 보낼 댓글 — 앞뒤 빈칸을 떼고, 비면 null, 길면 500자로 자른다. */
    fun commentBody(raw: String): String? {
        val body = raw.trim()
        if (body.isEmpty()) return null
        return if (length(body) <= COMMENT_LIMIT) body else body.substring(0, body.offsetByCodePoints(0, COMMENT_LIMIT))
    }

    /** 댓글 입력칸의 안내 문구 열쇠 — 댓글이 없으면 「첫 댓글을 남겨 보세요」, 있으면 「댓글을 남겨 보세요」. */
    fun commentPlaceholderKey(total: Int): String = if (total == 0) "첫 댓글을 남겨 보세요" else "댓글을 남겨 보세요"

    /** 머리줄 「✎ 글쓰기」를 눌렀을 때 할 일 — 글쓰기 화면 열기(Open) / 열지 않고 로그인 창만 띄우기(SignIn). */
    enum class ComposeTap { Open, SignIn }

    /**
     * 「글쓰기」를 누르면 — 로그인했으면 글쓰기 화면을 열고, 아니면 로그인 창만 띄운다(웹 `CommunityTab.tsx` `onCompose`, iOS `composeTap(signedIn:)`).
     * 로그인을 마쳐도 글쓰기 화면을 저절로 열지 않는다 — 다시 누른다.
     */
    fun composeTap(signedIn: Boolean): ComposeTap = if (signedIn) ComposeTap.Open else ComposeTap.SignIn

    /** 글자 수 — 코드 포인트로 센다(이모지 하나 = 1). */
    private fun length(text: String): Int = text.codePointCount(0, text.length)
}
