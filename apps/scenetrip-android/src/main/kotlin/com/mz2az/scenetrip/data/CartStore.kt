package com.mz2az.scenetrip.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.sceneapi.client.api.CartApi
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.CartItemCreate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * 장바구니. iOS `Models/CartStore.swift` 를 옮긴 것이다.
 *
 * **로그인이 없으므로 기기 식별자로 구분한다.** 계약이 `X-Device-Id` 헤더를 요구하고,
 * 그 값은 처음 한 번 만들어 기기에 저장한다 — 앱을 다시 켜도 담아 둔 것이 남아야
 * 한다. iOS 는 UserDefaults, 여기서는 SharedPreferences 다.
 */
class CartStore(
    context: Context,
) {
    var items by mutableStateOf<List<CartItem>>(emptyList())
        private set
    var toast by mutableStateOf<String?>(null)
        private set

    private val api = CartApi(API_BASE)
    private val deviceId: UUID = loadOrCreateDeviceId(context)

    fun contains(placeId: Long): Boolean = items.any { it.placeId == placeId }

    suspend fun refresh() {
        runCatching { withContext(Dispatchers.IO) { api.getCart(deviceId) } }
            .onSuccess { items = it.items ?: emptyList() }
    }

    /**
     * 담는다.
     *
     * `sourceContentId` 는 **이 장소를 어느 작품 때문에 담았는지**다. 같은 장소가
     * 여러 작품에 나오므로 이것이 없으면 나중에 되짚을 수 없다 (MZ2AZ-208).
     */
    suspend fun add(
        placeId: Long,
        sourceContentId: Long? = null,
    ) {
        runCatching {
            withContext(Dispatchers.IO) {
                api.addCartItem(deviceId, CartItemCreate(placeId = placeId, sourceContentId = sourceContentId))
            }
        }.onSuccess {
            refresh()
        }.onFailure {
            // 계약이 409 에 "이미 저장된 장소입니다" 를 띄우라고 적어 뒀다.
            toast = "이미 담긴 장소입니다"
        }
    }

    suspend fun remove(placeId: Long) {
        runCatching { withContext(Dispatchers.IO) { api.removeCartItem(deviceId, placeId) } }
        refresh()
    }

    fun clearToast() {
        toast = null
    }

    private companion object {
        const val PREFS = "scenetrip"
        const val KEY = "deviceId"

        fun loadOrCreateDeviceId(context: Context): UUID {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY, null)?.let { saved ->
                runCatching { return UUID.fromString(saved) }
            }
            val fresh = UUID.randomUUID()
            prefs.edit().putString(KEY, fresh.toString()).apply()
            return fresh
        }
    }
}
