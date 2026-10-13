package com.mz2az.scenetrip.reviews

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Dialog 밖에서 입력·저장을 유지하고, 새 계정에 이전 요청의 닫기·오류를 적용하지 않는다. */
class NicknameEditor(
    private val initialName: String,
    private val firstTime: Boolean,
    private val epoch: () -> Int,
    private val save: suspend (String) -> String?,
) {
    data class State(
        val text: String,
        val busy: Boolean = false,
        val error: String? = null,
    )

    private val state = MutableStateFlow(State(initialName))
    val updates = state.asStateFlow()
    var text: String
        get() = state.value.text
        set(value) {
            state.update { it.copy(text = value, error = null) }
        }
    val busy get() = state.value.busy
    val error get() = state.value.error
    val normalized get() = ReviewRules.nickname(text)
    val epochSnapshot get() = epoch()
    val count get() = normalized.length
    private val unchanged get() = normalized == initialName
    val shownProblem get() = if (unchanged) null else ReviewRules.nicknameProblem(text)
    val canSave get() =
        when {
            busy -> false
            unchanged -> firstTime && normalized.isNotEmpty()
            else -> shownProblem == null
        }

    suspend fun submit(
        requestEpoch: Int = epoch(),
        onSaved: () -> Unit,
    ) {
        if (!canSave || requestEpoch != epoch()) return
        if (unchanged) {
            onSaved()
            return
        }
        state.update { it.copy(busy = true) }
        try {
            val failure = save(normalized)
            if (epoch() != requestEpoch) return
            if (failure == null) onSaved() else state.update { it.copy(error = failure) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (epoch() == requestEpoch) state.update { it.copy(error = "저장하지 못했어요. 잠시 뒤 다시 해 주세요") }
        } finally {
            state.update { it.copy(busy = false) }
        }
    }
}
