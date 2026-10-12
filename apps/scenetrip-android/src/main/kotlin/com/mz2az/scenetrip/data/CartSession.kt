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

data class CartState(
    val items: List<CartItem> = emptyList(),
    val placeIds: Set<Long> = emptySet(),
    val toast: String? = null,
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
        apiResult { client.fetch() }.onSuccess {
            publish(state.copy(items = it, placeIds = it.map { item -> item.placeId }.toSet()))
        }
    }

    suspend fun add(
        placeId: Long,
        sourceContentId: Long? = null,
    ): Boolean {
        val result = apiResult { client.add(placeId, sourceContentId) }
        val duplicate = result.exceptionOrNull()?.let { NetworkFailure.of(it).status == 409 } == true
        val present = result.isSuccess || duplicate
        publish(
            state.copy(
                placeIds = if (present) state.placeIds + placeId else state.placeIds,
                toast =
                    tr(
                        when {
                            result.isSuccess -> "장바구니에 담았습니다"
                            duplicate -> "이미 저장된 장소입니다"
                            else -> "담지 못했습니다. 잠시 후 다시 시도해 주세요"
                        },
                    ),
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
}
