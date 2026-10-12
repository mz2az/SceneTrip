package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.model.TripPostCreate
import com.mz2az.scenetrip.sceneapi.client.model.TripPostDetail
import kotlinx.coroutines.CancellationException

data class SubmissionState(
    val working: Boolean = false,
    val uncertain: Boolean = false,
    val completed: Boolean = false,
    val message: String? = null,
    val photoInvalid: Boolean = false,
)

/** 게시 응답을 잃었을 때 초안을 보존하고, 같은 POST를 다시 보내지 않는다. */
class CommunitySubmission(
    private val client: CommunityClient,
    private val accountEpoch: () -> Int = { 0 },
    private val now: () -> Long = System::currentTimeMillis,
    private val onChanged: (SubmissionState) -> Unit = {},
) {
    var state = SubmissionState()
        private set
    private var submitted: TripPostCreate? = null
    private var expectedCourse: PostCourse? = null
    private var baseline = emptySet<Long>()
    private var submittedEpoch = 0
    private var submittedAt = 0L

    suspend fun submit(
        input: TripPostCreate,
        expectedCourse: PostCourse? = null,
    ): TripPostDetail? {
        if (state.working || state.uncertain || state.completed ||
            !CommunityRules.canPost(input.title, input.body, input.photoKeys.size)
        ) {
            return null
        }
        publish(state.copy(working = true, message = null, photoInvalid = false))
        submittedEpoch = accountEpoch()
        try {
            baseline =
                client
                    .list(mine = true, limit = BASELINE_LIMIT, offset = 0)
                    .items
                    .map { it.id }
                    .toSet()
            if (accountEpoch() != submittedEpoch) return null
            submitted = input
            this.expectedCourse = expectedCourse
            submittedAt = now()
            try {
                val saved = client.create(input)
                if (accountEpoch() != submittedEpoch) return null
                publish(state.copy(completed = true))
                return saved
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (accountEpoch() != submittedEpoch) return null
                val uncertain = CommunityRules.uncertain(error)
                publish(
                    state.copy(
                        uncertain = uncertain,
                        photoInvalid = NetworkFailure.of(error).code == "POST_PHOTO_INVALID",
                        message = if (uncertain) tr("게시 응답을 받지 못했어요. 중복 게시를 막기 위해 게시 여부를 확인해 주세요") else CommunityRules.failureText(error),
                    ),
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (accountEpoch() == submittedEpoch) publish(state.copy(message = CommunityRules.failureText(error)))
        } finally {
            publish(state.copy(working = false))
        }
        return null
    }

    suspend fun check(): TripPostDetail? {
        val input = submitted ?: return null
        if (!state.uncertain || state.working || state.completed || accountEpoch() != submittedEpoch) return null
        if (input.photoKeys.isEmpty()) {
            publish(state.copy(message = tr("사진 없는 글은 게시 여부를 확정할 수 없어요. 내 글 목록에서 확인해 주세요. 중복을 막기 위해 다시 보내지 않습니다")))
            return null
        }
        publish(state.copy(working = true))
        try {
            val page = client.list(mine = true, limit = BASELINE_LIMIT, offset = 0)
            val candidates =
                page.items.filter {
                    it.id !in baseline &&
                        it.createdAt.toInstant().toEpochMilli() >= submittedAt - CLOCK_MARGIN_MILLIS &&
                        it.title == input.title
                }
            val matches = mutableListOf<TripPostDetail>()
            for (candidate in candidates) {
                val detail = client.detail(candidate.id)
                if (CommunityRules.matches(detail, input, expectedCourse)) matches.add(detail)
            }
            if (accountEpoch() != submittedEpoch) return null
            if (matches.size == 1) {
                publish(state.copy(completed = true))
                return matches.single()
            }
            publish(state.copy(message = tr("게시 여부를 아직 확인하지 못했어요. 잠시 뒤 다시 확인해 주세요. 초안은 그대로 있습니다")))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (accountEpoch() == submittedEpoch) publish(state.copy(message = CommunityRules.failureText(error)))
        } finally {
            publish(state.copy(working = false))
        }
        return null
    }

    private fun publish(value: SubmissionState) {
        state = value
        onChanged(value)
    }

    companion object {
        private const val BASELINE_LIMIT = 100
        private const val CLOCK_MARGIN_MILLIS = 5000L
    }
}
