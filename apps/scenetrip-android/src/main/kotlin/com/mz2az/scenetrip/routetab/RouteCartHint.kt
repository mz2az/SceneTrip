package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.CartLoadState
import com.mz2az.scenetrip.data.tr
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException

/** 첫 안내를 이미 보였는지 기억하는 곳. 기기당 한 번이다. */
interface CartHintStorage {
    fun seen(): Boolean

    fun markSeen()
}

/** [RouteCartHint]가 화면에 보이는 값. */
data class RouteCartHintState(
    /** 장바구니 버튼의 링을 켤 것인가. 조회가 끝나기 전에도 켜진다. */
    val isHighlighted: Boolean = false,
    /** 링 아래 안내 문구. 장바구니 조회가 끝난 뒤에야 채워진다. */
    val message: String? = null,
)

/** 장바구니 조회의 결과 — 문구를 정하는 데 쓴다. */
data class CartOutcome(
    val loadState: CartLoadState,
    val isEmpty: Boolean,
)

/**
 * 직접 짜기의 새 빈 코스에서 장바구니 버튼을 한 번 알려 준다 (MZ2AZ-444). iOS `RouteCartHintStore`를 옮긴 것이다.
 *
 * - 표시할 때 기록한다. 닫기·시간 만료·화면 재진입도 같은 한 번이다.
 * - 링은 즉시 켜지만 **문구·낭독·기록·노출 시간은 장바구니 조회가 끝난 뒤** 함께 시작한다. 조회가 느려도
 *   2.5초 노출 시간이 먼저 깎이지 않는다.
 * - 성공한 조회가 비었을 때만 빈 상태 문구를 쓴다. 조회가 실패하면 빈 목록은 증거가 아니므로 일반 문구다.
 *
 * Compose 상태를 직접 들지 않는다(JVM 단위 시험) — 값이 바뀔 때마다 [changed]로 알린다.
 */
class RouteCartHint(
    private val storage: CartHintStorage,
    private val changed: (RouteCartHintState) -> Unit = {},
) {
    var state = RouteCartHintState()
        private set

    private fun publish(value: RouteCartHintState) {
        state = value
        changed(value)
    }

    /**
     * 새 초안인지는 화면의 표시 방식(`isNew`)이 아니라 실제 저장 여부로 판단한다 — 서버에 없고, AI 초안이
     * 아니고, 담은 장소가 없는 코스.
     */
    fun canPresent(course: RouteCourse): Boolean = course.serverId == null && !course.madeByAI && course.stops.isEmpty() && !storage.seen()

    fun claim(course: RouteCourse): Boolean {
        if (!canPresent(course)) return false
        storage.markSeen()
        return true
    }

    fun dismiss() = publish(RouteCartHintState())

    /**
     * 링을 켜고, [cart]가 결과를 낼 때까지 기다렸다가 문구를 보여 주고, [DURATION_MILLIS] 뒤에 거둔다.
     * 취소되면(화면을 떠남) 링과 문구를 거둔다.
     */
    suspend fun present(
        course: RouteCourse,
        cart: suspend () -> CartOutcome,
        announce: (String) -> Unit,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ) {
        if (state.isHighlighted || !canPresent(course)) return
        publish(RouteCartHintState(isHighlighted = true))
        try {
            val outcome = cart()
            if (!state.isHighlighted || !claim(course)) return
            // 실패한 조회의 빈 배열은 비어 있다는 증거가 아니므로 일반 안내를 쓴다.
            val text = tr(messageKey(cartIsEmpty = outcome.loadState == CartLoadState.LOADED && outcome.isEmpty))
            publish(state.copy(message = text))
            announce(text)
            sleep(DURATION_MILLIS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            dismiss()
        }
    }

    companion object {
        const val DURATION_MILLIS = 2500L

        fun messageKey(cartIsEmpty: Boolean): String = if (cartIsEmpty) "장소를 담으면 여기서 코스에 넣을 수 있어요" else "담아둔 장소로 코스를 시작해 보세요"
    }
}
