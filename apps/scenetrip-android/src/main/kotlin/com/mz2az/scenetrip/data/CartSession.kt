package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.model.CartItem

interface CartClient {
    fun fetch(): List<CartItem>

    fun add(
        placeId: Long,
        sourceContentId: Long?,
    )

    fun remove(placeId: Long)
}

/** 장바구니를 서버에서 받아 봤는가 — 빈 목록이 「비어 있다」인지 「아직 모른다」인지 가른다. iOS `CartStore.LoadState`. */
enum class CartLoadState { NOT_LOADED, LOADING, LOADED, FAILED }

data class CartState(
    val items: List<CartItem> = emptyList(),
    val placeIds: Set<Long> = emptySet(),
    val toast: String? = null,
    val loadState: CartLoadState = CartLoadState.NOT_LOADED,
)

/** Android 화면 없이도 검증하는 장바구니 요청·오류·목록 보존 흐름. */
class CartSession(
    private val client: CartClient,
    private val changed: (CartState) -> Unit = {},
) {
    var state = CartState()
        private set

    fun contains(placeId: Long): Boolean = placeId in state.placeIds

    val items: List<CartItem> get() = state.items
    val toast: String? get() = state.toast

    private fun publish(value: CartState) {
        state = value
        changed(value)
    }

    suspend fun refresh() {
        publish(state.copy(loadState = CartLoadState.LOADING))
        try {
            apiResult { client.fetch() }
                .onSuccess {
                    publish(state.copy(items = it, placeIds = it.map { item -> item.placeId }.toSet(), loadState = CartLoadState.LOADED))
                }.onFailure {
                    // 실패한 조회의 빈 목록은 「비어 있다」는 증거가 아니다 — 받아 둔 것은 그대로 두고 실패만 알린다.
                    publish(state.copy(loadState = CartLoadState.FAILED))
                }
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            // 중간에 끊긴 조회가 「조회 중」으로 남으면 기다리는 쪽이 영영 풀리지 않는다.
            publish(state.copy(loadState = CartLoadState.NOT_LOADED))
            throw cancelled
        }
    }

    suspend fun add(
        placeId: Long,
        sourceContentId: Long? = null,
    ): Boolean {
        val result = apiResult { client.add(placeId, sourceContentId) }
        val failure = result.exceptionOrNull()?.let { ApiFailure.of(it) }
        // 409 중 `DUPLICATE_CART_ITEM` 만 「이미 담긴 상태」다. 숨긴 장소(`PLACE_DELISTED`)는 담기지 않았다 —
        // 담긴 것으로 표시하면 목록에 없는 장소가 담긴 것처럼 보인다(MZ2AZ-458, iOS `CartStore.add`).
        val duplicate = failure?.statusCode == 409 && failure.apiCode == DUPLICATE_ITEM_CODE
        val present = result.isSuccess || duplicate
        publish(
            state.copy(
                placeIds = if (present) state.placeIds + placeId else state.placeIds,
                toast =
                    when {
                        result.isSuccess -> tr("장바구니에 담았습니다")
                        duplicate -> tr("이미 저장된 장소입니다")
                        else -> failure?.let { UnavailableItem.rejection(it.statusCode, it.apiCode) } ?: tr("담지 못했습니다. 잠시 후 다시 시도해 주세요")
                    },
            ),
        )
        refresh()
        return result.isSuccess
    }

    suspend fun remove(placeId: Long) {
        if (apiResult { client.remove(placeId) }.isFailure) {
            publish(state.copy(toast = tr("빼지 못했습니다. 잠시 후 다시 시도해 주세요")))
            return
        }
        publish(
            state.copy(
                items = state.items.filterNot { it.placeId == placeId },
                placeIds = state.placeIds - placeId,
                toast = tr("장바구니에서 뺐습니다"),
            ),
        )
        refresh()
    }

    fun clearToast() {
        publish(state.copy(toast = null))
    }

    companion object {
        /** 이미 담긴 장소를 또 담았다(409). 이것만 「이미 저장된 장소입니다」다. */
        const val DUPLICATE_ITEM_CODE = "DUPLICATE_CART_ITEM"
    }
}
