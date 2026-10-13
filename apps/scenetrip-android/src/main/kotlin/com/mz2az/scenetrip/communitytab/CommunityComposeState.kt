package com.mz2az.scenetrip.communitytab

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.CommunityArticle
import com.mz2az.scenetrip.data.CommunityPhotoPlacement
import com.mz2az.scenetrip.data.CommunityRules
import com.mz2az.scenetrip.data.CommunityStore
import com.mz2az.scenetrip.data.PostCourse
import com.mz2az.scenetrip.data.SubmissionState
import com.mz2az.scenetrip.reviews.ReviewDraft
import com.mz2az.scenetrip.sceneapi.client.model.TripPostCreate
import com.mz2az.scenetrip.sceneapi.client.model.TripPostDetail
import com.mz2az.scenetrip.sceneapi.client.model.UploadPurpose
import kotlinx.coroutines.CoroutineScope
import java.util.UUID

/** 화면이 닫힐 때까지 초안과 게시 확인 상태를 보존한다. 다른 계정에서 재전송하지 않는다. */
class CommunityComposeState(
    context: Context,
    scope: CoroutineScope,
    private val store: CommunityStore,
) {
    var title by mutableStateOf("")
    var story by mutableStateOf("")
    var course by mutableStateOf<PostCourse?>(null)
    var pickingCourse by mutableStateOf(false)
    var checkingPosts by mutableStateOf(false)
    var confirmingDiscard by mutableStateOf(false)
    var placements by mutableStateOf<Map<UUID, CommunityPhotoPlacement>>(emptyMap())
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
    val canPost get() =
        !locked && !status.completed && AuthStore.signedIn &&
            photos.ready == photos.photos.size &&
            CommunityRules.canPost(CommunityArticle.normalized(title), CommunityArticle.normalized(story), photos.photos.size) &&
            canPreview && retainedPlacements.values.all { it.metadataValid }

    fun close(onDismiss: () -> Unit) {
        if (status.working) return
        if (hasDraft) confirmingDiscard = true else onDismiss()
    }

    suspend fun submit(onDismiss: () -> Unit) {
        if (!canPost) return
        val input =
            TripPostCreate(
                CommunityArticle.normalized(title),
                CommunityArticle.plainBody(story),
                photos.keys,
                course?.serverId,
                bodyBlocks = articleBlocks,
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
