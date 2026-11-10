package com.mz2az.scenetrip.communitytab

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.CommunityFeedRules
import com.mz2az.scenetrip.data.ModerationRules
import com.mz2az.scenetrip.data.ModerationTarget
import com.mz2az.scenetrip.data.PostSocialModel
import com.mz2az.scenetrip.data.PostSocialState
import com.mz2az.scenetrip.data.RelativeTime
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.reviews.ModerationMenu
import com.mz2az.scenetrip.reviews.ModerationSheets
import com.mz2az.scenetrip.reviews.rememberModerationController
import com.mz2az.scenetrip.sceneapi.client.model.ReportTargetType
import com.mz2az.scenetrip.sceneapi.client.model.TripPostComment
import com.mz2az.scenetrip.ui.IOS
import com.mz2az.scenetrip.ui.ProtoMotion
import com.mz2az.scenetrip.ui.pressClickable
import kotlinx.coroutines.launch

/**
 * 글 끝의 좋아요 — 가운데 알약 「👍 좋아요 N」 (MZ2AZ-547 K8, iOS `CommunityLikeButton`, 웹 `PostLike`).
 * 누르면 보라로 채워지며 엄지가 톡 커진다. 비로그인이면 로그인으로 안내한다.
 */
@Composable
internal fun CommunityLikeButton(
    social: PostSocialModel,
    state: PostSocialState,
) {
    val scope = rememberCoroutineScope()
    val pop = remember { Animatable(1f) }
    val fill by animateColorAsState(if (state.liked) IOS.pinDeep else IOS.systemBackground, tween(160), label = "like-fill")
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .pressClickable {
                    if (AuthStore.signedIn) {
                        // 0.32초에 1→1.3→1, 구간마다 살짝 튀는 곡선(MZ2AZ-547 M16, 웹 `.proto-heart-pop` 40% 지점 1.3).
                        scope.launch {
                            pop.snapTo(1f)
                            pop.animateTo(1.3f, tween(128, easing = ProtoMotion.Overshoot))
                            pop.animateTo(1f, tween(192, easing = ProtoMotion.Overshoot))
                        }
                    }
                    scope.launch { if (social.toggleLike() == PostSocialModel.Outcome.NEEDS_SIGN_IN) AuthStore.promptSignIn() }
                }.heightIn(min = 44.dp)
                .background(fill, RoundedCornerShape(50))
                .border(1.dp, if (state.liked) Color.Transparent else IOS.systemGray4, RoundedCornerShape(50))
                .semantics { selected = state.liked }
                .testTag("community-like")
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ThumbsUpIcon(
                if (state.liked) Color.White else IOS.pinDeep,
                filled = state.liked,
                modifier = Modifier.size(18.dp).scale(pop.value),
            )
            Text(
                tr("좋아요 %d").format(state.likeCount),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (state.liked) Color.White else IOS.label,
            )
        }
    }
}

/**
 * 좋아요 아래 댓글 (K9, iOS `CommunityCommentsSection`) — 얇은 선 위 「댓글 N」, 줄마다 이름·시간·내용(오래된 순), 맨 끝에 입력칸
 * (글에 붙는다, 바닥 고정 아님). 내 댓글은 「지우기」, 남의 댓글은 글과 같은 「⋯」 신고·차단. 차단하면 서버가 그 사람의 댓글을 빼므로 다시 받는다.
 */
@Composable
internal fun CommunityCommentsSection(
    social: PostSocialModel,
    state: PostSocialState,
) {
    val scope = rememberCoroutineScope()
    val moderation = rememberModerationController()
    val focus = LocalFocusManager.current
    var text by remember(social.postId) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 0.dp)
                .heightIn(min = 0.5.dp)
                .background(IOS.separator),
        )
        Text(
            tr("댓글 %d").format(state.commentTotal),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = IOS.label,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() },
        )
        if (state.comments == null && !state.commentsFailed) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(22.dp), color = IOS.accent, strokeWidth = 2.dp)
            }
        }
        if (state.commentsFailed) {
            Text(
                tr("댓글을 불러오지 못했어요 — 다시 시도"),
                fontSize = 13.sp,
                color = IOS.secondaryLabel,
                modifier = Modifier.heightIn(min = 40.dp).clickable { scope.launch { social.loadComments() } }.padding(vertical = 11.dp),
            )
        }
        val comments = state.comments.orEmpty()
        if (comments.isNotEmpty()) {
            Column(Modifier.padding(top = 6.dp)) {
                comments.forEach { comment -> CommentRow(comment, moderation, onRemove = { scope.launch { social.remove(comment) } }) }
            }
        }
        CommentComposer(text, { text = it }, state, focus) {
            scope.launch {
                when (social.send(text)) {
                    PostSocialModel.Outcome.DONE -> {
                        text = ""
                        focus.clearFocus()
                    }

                    PostSocialModel.Outcome.NEEDS_SIGN_IN -> {
                        AuthStore.promptSignIn()
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }
    }
    ModerationSheets(moderation) { target ->
        if (target.type == ReportTargetType.comment) scope.launch { social.blocked() }
    }
}

@Composable
private fun CommentRow(
    comment: TripPostComment,
    moderation: com.mz2az.scenetrip.data.ModerationController,
    onRemove: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(comment.author?.nickname ?: tr("탈퇴한 사용자"), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = IOS.label)
            Text(RelativeTime.format(comment.createdAt), fontSize = 12.sp, color = IOS.tertiaryLabel)
            Spacer(Modifier.weight(1f))
            if (comment.isMine) {
                Text(
                    tr("지우기"),
                    fontSize = 12.sp,
                    color = IOS.tertiaryLabel,
                    modifier =
                        Modifier
                            .heightIn(min = 32.dp)
                            .semantics { contentDescription = tr("댓글 지우기") }
                            .clickable(onClick = onRemove)
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                )
            } else {
                ModerationMenu(
                    target = ModerationTarget.comment(comment.id),
                    canBlock = ModerationRules.canBlock(isMine = comment.isMine, hasAuthor = comment.author != null),
                    controller = moderation,
                    modifier = Modifier.size(width = 32.dp, height = 28.dp),
                )
            }
        }
        SelectionContainer { Text(comment.body, fontSize = 15.sp, color = IOS.label, modifier = Modifier.fillMaxWidth()) }
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 0.5.dp).background(IOS.separator))
}

@Composable
private fun CommentComposer(
    text: String,
    onText: (String) -> Unit,
    state: PostSocialState,
    focus: FocusManager,
    onSend: () -> Unit,
) {
    val canSend = CommunityFeedRules.commentBody(text) != null && !state.sending
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            text,
            { value ->
                // 500자를 넘기면 잘라 둔다 — 서버도 같은 한도다.
                onText(
                    if (value.codePointCount(0, value.length) >
                        CommunityFeedRules.COMMENT_LIMIT
                    ) {
                        value.take(value.offsetByCodePoints(0, CommunityFeedRules.COMMENT_LIMIT))
                    } else {
                        value
                    },
                )
            },
            textStyle = IOS.body.copy(fontSize = 16.sp, color = IOS.label),
            cursorBrush = SolidColor(IOS.accent),
            maxLines = 4,
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .background(IOS.systemGray6, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .semantics { contentDescription = tr("댓글") }
                    .testTag("community-comment-field"),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(tr(CommunityFeedRules.commentPlaceholderKey(state.commentTotal)), fontSize = 16.sp, color = IOS.tertiaryLabel)
                    }
                    inner()
                }
            },
        )
        Text(
            tr("등록"),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = if (canSend) IOS.pinDeep else IOS.tertiaryLabel,
            modifier =
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(enabled = canSend, onClick = onSend)
                    .padding(horizontal = 6.dp, vertical = 12.dp)
                    .testTag("community-comment-send"),
        )
    }
}

/** 목록 줄의 「👍 N 💬 M」 (K5, iOS `CommunityPostCounts`). 서버가 수를 주지 않은 글(옛 서버·기기 옛 글)은 그리지 않는다. 누른 글은 보라 엄지. */
@Composable
internal fun CommunityPostCounts(
    likeCount: Int?,
    commentCount: Int?,
    liked: Boolean?,
) {
    val counts = CommunityFeedRules.counts(likeCount, commentCount) ?: return
    val spoken = tr("좋아요 %d개, 댓글 %d개").format(counts.likes, counts.comments)
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            ThumbsUpIcon(if (liked == true) IOS.pinDeep else IOS.tertiaryLabel, filled = liked == true, modifier = Modifier.size(11.dp))
            Text("${counts.likes}", fontSize = 11.sp, color = IOS.tertiaryLabel)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            CommentBubbleIcon(IOS.tertiaryLabel, Modifier.size(11.dp))
            Text("${counts.comments}", fontSize = 11.sp, color = IOS.tertiaryLabel)
        }
    }
}

/** `hand.thumbsup` / `.fill` — 엄지와 손바닥, 왼쪽 소매. */
@Composable
internal fun ThumbsUpIcon(
    tint: Color,
    filled: Boolean,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val style = if (filled) Fill else Stroke(width = w * 0.09f, join = StrokeJoin.Round)
        // 소매(왼쪽 세로 막대)
        drawRoundRect(
            tint,
            topLeft = Offset(w * 0.06f, w * 0.42f),
            size =
                androidx.compose.ui.geometry
                    .Size(w * 0.2f, w * 0.5f),
            cornerRadius = CornerRadius(w * 0.05f),
            style = style,
        )
        // 손 — 엄지가 위로 솟고 손가락 쪽은 둥근 네모.
        val hand =
            Path().apply {
                moveTo(w * 0.34f, w * 0.44f)
                lineTo(w * 0.5f, w * 0.1f)
                cubicTo(w * 0.6f, w * 0.06f, w * 0.66f, w * 0.16f, w * 0.62f, w * 0.26f)
                lineTo(w * 0.58f, w * 0.38f)
                lineTo(w * 0.84f, w * 0.38f)
                cubicTo(w * 0.94f, w * 0.38f, w * 0.98f, w * 0.48f, w * 0.94f, w * 0.56f)
                lineTo(w * 0.86f, w * 0.84f)
                cubicTo(w * 0.83f, w * 0.9f, w * 0.78f, w * 0.92f, w * 0.72f, w * 0.92f)
                lineTo(w * 0.34f, w * 0.92f)
                close()
            }
        drawPath(hand, tint, style = style)
    }
}

/** `bubble.left` — 꼬리가 왼쪽 아래인 말풍선. */
@Composable
internal fun CommentBubbleIcon(
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val w = size.minDimension
        val bubble =
            Path().apply {
                addRoundRect(RoundRect(w * 0.06f, w * 0.1f, w * 0.94f, w * 0.74f, CornerRadius(w * 0.26f, w * 0.26f)))
                moveTo(w * 0.2f, w * 0.7f)
                lineTo(w * 0.12f, w * 0.94f)
                lineTo(w * 0.42f, w * 0.74f)
            }
        drawPath(bubble, tint, style = Stroke(width = w * 0.1f, join = StrokeJoin.Round))
    }
}
