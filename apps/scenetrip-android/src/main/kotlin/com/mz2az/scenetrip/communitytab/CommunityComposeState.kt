package com.mz2az.scenetrip.communitytab

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.CommunityArticle
import com.mz2az.scenetrip.data.CommunityDraft
import com.mz2az.scenetrip.data.CommunityDraftItem
import com.mz2az.scenetrip.data.CommunityPhotoPlacement
import com.mz2az.scenetrip.data.CommunityRules
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.PostCourse
import com.mz2az.scenetrip.data.SubmissionState
import com.mz2az.scenetrip.reviews.ReviewDraft
import com.mz2az.scenetrip.sceneapi.client.model.TripPostBoard
import com.mz2az.scenetrip.sceneapi.client.model.TripPostCreate
import com.mz2az.scenetrip.sceneapi.client.model.TripPostDetail
import com.mz2az.scenetrip.sceneapi.client.model.UploadPurpose
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/**
 * 화면이 닫힐 때까지 초안과 게시 확인 상태를 보존한다. 다른 계정에서 재전송하지 않는다.
 *
 * 본문은 블로그형이다(MZ2AZ-547 K11) — 글 조각과 사진을 쓴 순서대로 [items] 에 두고, 본문([story])과 사진 위치([placements])는
 * 거기서 계산한다([CommunityDraft]). 저장 형식은 그대로(body + bodyBlocks). 올릴 게시판은 [board](처음 값은 보던 게시판, K10).
 */
class CommunityComposeState(
    context: Context,
    scope: CoroutineScope,
    private val store: CommunityStore,
    board: TripPostBoard = TripPostBoard.review,
) {
    var title by mutableStateOf("")
    var board by mutableStateOf(board)

    /** 글 조각과 사진을 쓴 순서대로. */
    var items by mutableStateOf<List<CommunityDraftItem>>(listOf(CommunityDraftItem.Text(UUID.randomUUID(), "")))

    /** 사진마다 설명·출처·AI 표시. 위치(`afterParagraph`)는 [items] 가 정한다. */
    var meta by mutableStateOf<Map<UUID, CommunityPhotoPlacement>>(emptyMap())

    /** 사진을 고르는 동안 정해 둔 넣을 자리(그 항목 뒤). 사진 칸이 생기면 그 자리에 놓는다. */
    var insertAfter: Int? = null
    val story: String get() = CommunityDraft.story(items)
    val placements: Map<UUID, CommunityPhotoPlacement> get() = CommunityDraft.placements(items, meta)
    var course by mutableStateOf<PostCourse?>(null)
    var pickingCourse by mutableStateOf(false)
    var checkingPosts by mutableStateOf(false)
    var confirmingDiscard by mutableStateOf(false)
    var previewing by mutableStateOf(false)
    var status by mutableStateOf(SubmissionState())
        private set
    val composingEpoch = AuthStore.epoch
    val photos = ReviewDraft(context, scope, UploadPurpose.post, CommunityRules.PHOTO_LIMIT)
    private val submission = store.submission { status = it }
    val sameAccount get() = composingEpoch == AuthStore.epoch
    val locked get() = status.working || status.uncertain || !sameAccount
    val hasDraft get() = title.isNotEmpty() || story.isNotEmpty() || photos.photos.isNotEmpty() || course != null
    val photoIds get() = photos.photos.map { it.id }
    val retainedPlacements get() = CommunityArticle.retained(placements, photoIds)
    val articleBlocks get() = CommunityArticle.blocks(story, photoIds, retainedPlacements)
    val canPreview get() = CommunityArticle.canCompose(story)

    /** 내용이 올릴 만한가 — 로그인은 따로 본다(비로그인이면 「올리기」 가 로그인으로 안내한다, K10). */
    val canPost get() =
        !locked && !status.completed &&
            photos.ready == photos.photos.size &&
            CommunityRules.canPost(CommunityArticle.normalized(title), CommunityArticle.normalized(story), photos.photos.size) &&
            canPreview && retainedPlacements.values.all { it.metadataValid }

    fun close(onDismiss: () -> Unit) {
        if (status.working) return
        if (hasDraft) confirmingDiscard = true else onDismiss()
    }

    /**
     * 사진 칸과 목록을 맞춘다(iOS `CommunityArticleEditor.sync`) — 새로 생긴 칸은 정해 둔 자리에, 빠진 칸은 목록에서도 뺀다.
     * 그리고 사진 칸 순서를 본문에 놓인 순서와 맞춘다 — 첫 사진이 목록의 대표 사진이고 bodyBlocks 의 photoIndex 가 이 순서를 가리킨다.
     */
    fun syncPhotos(slotIds: List<UUID>) {
        val present = CommunityDraft.photoOrder(items).toSet()
        val added = slotIds.filterNot { it in present }
        var next = CommunityDraft.dropMissingPhotos(items, slotIds)
        if (added.isNotEmpty()) {
            next = CommunityDraft.insertPhotos(next, minOf(insertAfter ?: (next.size - 1), next.size - 1), added)
            insertAfter = null
        }
        next = CommunityDraft.normalize(next) { UUID.randomUUID() }
        if (next != items) items = next
        meta = meta.filterKeys { it in slotIds }
        alignPhotoOrder()
    }

    /** 목록을 바꾼 뒤(옮기기) — 사진 칸 순서를 본문 순서에 맞춘다. */
    fun alignPhotoOrder() {
        val order = CommunityDraft.photoOrder(items)
        if (order.isNotEmpty()) photos.reorder(order)
    }

    suspend fun submit(onDismiss: () -> Unit) {
        if (!AuthStore.signedIn) {
            AuthStore.promptSignIn()
            return
        }
        if (!canPost) return
        val input =
            TripPostCreate(
                CommunityArticle.normalized(title),
                CommunityArticle.plainBody(story),
                photos.keys,
                course?.serverId,
                bodyBlocks = articleBlocks,
                board = board,
            )
        val saved = submission.submit(input, course)
        if (saved != null) {
            finish(saved, onDismiss)
        } else if (status.photoInvalid && sameAccount) {
            photos.restoreInvalidPhotos()
        }
    }

    suspend fun check(onDismiss: () -> Unit) {
        submission.check()?.let { finish(it, onDismiss) }
    }

    private fun finish(
        saved: TripPostDetail,
        onDismiss: () -> Unit,
    ) {
        if (!sameAccount) return
        store.accept(saved)
        AppAnalytics.log(AppEvent.PostReview(photoCount = saved.photoUrls.size, hasCourse = saved.course != null))
        onDismiss()
    }
}
