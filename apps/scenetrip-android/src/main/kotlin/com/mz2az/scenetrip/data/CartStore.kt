package com.mz2az.scenetrip.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.sceneapi.client.api.CartApi
import com.mz2az.scenetrip.sceneapi.client.model.CartItem
import com.mz2az.scenetrip.sceneapi.client.model.CartItemCreate
import java.util.UUID

private class ServerCartClient(
    private val installId: UUID,
) : CartClient {
    private val api = CartApi(API_BASE)

    override fun fetch(): List<CartItem> = api.getCart(installId).items.orEmpty()

    override fun add(
        placeId: Long,
        sourceContentId: Long?,
    ) {
        api.addCartItem(installId, CartItemCreate(placeId = placeId, sourceContentId = sourceContentId))
    }

    override fun remove(placeId: Long) {
        api.removeCartItem(installId, placeId)
    }
}

/**
 * 장바구니. iOS `Models/CartStore.swift` 를 옮긴 것이다.
 *
 * **로그인이 없으므로 설치 식별자로 구분한다.** 계약이 `X-Install-Id` 헤더를 요구하고,
 * 그 값은 처음 한 번 만들어 기기에 저장한다 — 앱을 다시 켜도 담아 둔 것이 남아야
 * 한다. iOS 는 UserDefaults, 여기서는 SharedPreferences 다.
 */
class CartStore(
    private val client: CartClient,
) {
    constructor(context: Context) : this(ServerCartClient(InstallIdentity.of(context)))

    private var snapshot by mutableStateOf(CartState())
    private val session = CartSession(client, epoch = { AuthStore.epoch }) { snapshot = it }
    val items: List<CartItem> get() = snapshot.items
    val toast: String? get() = snapshot.toast

    init {
        // 로그아웃·탈퇴·비회원 기록 삭제 — 화면 사본(담긴 행·저장 아이콘·안내)을 먼저 비운다(iOS `CartStore.watchReset`, MZ2AZ-471).
        // 화면마다 이 객체를 따로 들므로 약한 참조로 걸어, 버려진 객체는 첫 신호에 구독을 푼다.
        val self = java.lang.ref.WeakReference(this)
        var stop: (() -> Unit)? = null
        stop = AccountDataReset.watch { self.get()?.reset() ?: stop?.invoke() }
    }

    /** 장바구니를 받아 봤는가. 첫 안내가 문구를 정할 때 쓴다(iOS `CartStore.loadState`). */
    val loadState: CartLoadState get() = snapshot.loadState

    fun contains(placeId: Long): Boolean = placeId in snapshot.placeIds

    suspend fun refresh() {
        session.refresh()
    }

    /** 계정이 바뀌었다 — 이전 계정의 담긴 것을 먼저 비운다. */
    fun reset() {
        session.reset()
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
        if (session.add(placeId, sourceContentId)) AppAnalytics.log(AppEvent.SavePlace(placeId))
    }

    suspend fun remove(placeId: Long) {
        session.remove(placeId)
    }

    fun clearToast() {
        session.clearToast()
    }
}
