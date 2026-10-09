package com.mz2az.scenetrip.routetab

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.LimitLedger
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.api.GuideApi
import com.mz2az.scenetrip.sceneapi.client.model.GuideChatRequest
import com.mz2az.scenetrip.sceneapi.client.model.GuideContext
import com.mz2az.scenetrip.sceneapi.client.model.GuideEffect
import com.mz2az.scenetrip.sceneapi.client.model.GuideMessage
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.GuideUiDirective
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

/** 한 번 주고받은 말. iOS `RouteGuide.Turn`. */
data class GuideTurn(
    val id: UUID = UUID.randomUUID(),
    val role: Role,
    val text: String,
    val tools: List<String> = emptyList(),
) {
    enum class Role { USER, ASSISTANT }
}

/**
 * 여행 가이드 대화 — 여기서만 서버(`POST /guide/chat`)를 부른다. iOS
 * `RouteTab/RouteGuide.swift`(`ask`)·`RouteGuideSession.swift`를 옮긴 것이다.
 *
 * **화면(챗봇 창)보다 오래 산다** — iOS는 앱 공용 싱글턴(`RouteGuideSession.shared`)이라
 * 창을 닫아도 대화가 남는다. 여기서는 [RouteEditorView] 화면 하나가 붙잡고 있다가
 * 화면을 나가면 함께 버린다 — 코스 편집 화면 안에서만 쓰는 기능이라 화면보다 오래
 * 살려 둘 이유가 아직 없다.
 *
 * **`effects`·`ui`는 여기서 만들지 않는다** — 서버가 보낸 것을 [lastUi]·[lastEffects]로
 * 그대로 들고 있을 뿐이고, 실제로 지도를 맞추고 일차를 넘기고 장바구니를 새로고침하는
 * 일은 [RouteEditorView]가 한다(화면 상태를 아는 쪽이 화면을 바꿔야 한다). `route.draw`는
 * 아직 처리하지 않는다 — 추천 경로선을 그릴 오버레이가 없다. `place.card`(→ [picked])와
 * 핀 탭은 이제 [RoutePlaceCard]로 이어진다.
 */
class RouteGuideSession(
    context: Context,
    private val scope: CoroutineScope,
) {
    var turns by mutableStateOf<List<GuideTurn>>(emptyList())
        private set
    var places by mutableStateOf<List<GuidePlace>>(emptyList())
        private set
    var asking by mutableStateOf(false)
        private set
    var failure by mutableStateOf<String?>(null)
        private set

    /** 마지막 답의 화면·상태 명령 — 매 턴 새 리스트로 갈아 끼운다(빈 배열이어도).
     * 화면은 이 값이 바뀔 때만 `LaunchedEffect`로 한 번 적용한다. */
    var lastUi by mutableStateOf<List<GuideUiDirective>>(emptyList())
        private set
    var lastEffects by mutableStateOf<List<GuideEffect>>(emptyList())
        private set
    var replyRevision by mutableStateOf(0)
        private set

    /** 카드가 떠 있는 핀 — 지도의 점을 누르거나 `place.card` 명령이 채운다. */
    var picked by mutableStateOf<GuidePlace?>(null)
        private set

    fun pick(place: GuidePlace) {
        picked = place
    }

    fun dismiss() {
        picked = null
    }

    val isEmpty: Boolean get() = turns.isEmpty()

    private var sessionId: UUID = UUID.randomUUID()
    private var sending: Job? = null
    private val guideApi = GuideApi(API_BASE)
    private val deviceId: UUID = InstallIdentity.of(context)

    private data class Pending(
        val request: GuideChatRequest,
        val key: String,
        val epoch: Int,
    )

    private var pending: Pending? = null
    var contextOf: () -> GuideContext? = { null }
    val canRetry: Boolean get() = pending != null && !asking
    val blocked: Boolean get() = LimitLedger.guideBlock?.blocked(android.os.SystemClock.elapsedRealtime()) == true

    fun accountChanged() {
        sending?.cancel()
        sending = null
        sessionId = UUID.randomUUID()
        asking = false
        turns = emptyList()
        places = emptyList()
        picked = null
        pending = null
        failure = null
        lastUi = emptyList()
        lastEffects = emptyList()
        replyRevision += 1
    }

    /** 계약 상한 40 — 넘치면 오래된 것부터 잊는다. iOS `RouteGuide.ask`. */
    suspend fun ask(
        text: String,
        latitude: Double,
        longitude: Double,
    ) {
        val trimmed = text.trim()
        if (asking || blocked || trimmed.isEmpty() || trimmed.length > 4000) return
        if (!AuthStore.signedIn) {
            AuthStore.promptSignIn()
            return
        }
        turns = turns + GuideTurn(role = GuideTurn.Role.USER, text = trimmed)
        val request =
            GuideChatRequest(
                sessionId = sessionId,
                latitude = latitude,
                longitude = longitude,
                messages =
                    turns.takeLast(40).map {
                        GuideMessage(
                            role =
                                if (it.role ==
                                    GuideTurn.Role.USER
                                ) {
                                    GuideMessage.Role.user
                                } else {
                                    GuideMessage.Role.assistant
                                },
                            content = it.text.take(4000),
                        )
                    },
                context = contextOf(),
            )
        pending = Pending(request, UUID.randomUUID().toString(), AuthStore.epoch)
        submit(pending!!)
    }

    suspend fun retry() {
        if (asking || blocked) return
        pending?.let { submit(it) }
    }

    private fun submit(turn: Pending) {
        asking = true
        sending = scope.launch { send(turn) }
    }

    private suspend fun send(turn: Pending) {
        asking = true
        failure = null
        AppAnalytics.log(AppEvent.AskGuide)
        try {
            apiResult {
                guideApi.chatWithGuide(
                    deviceId,
                    turn.request,
                    acceptLanguage = AppLanguage.current,
                    idempotencyKey = turn.key,
                )
            }.onSuccess { reply ->
                if (turn.epoch != AuthStore.epoch) return@onSuccess
                turns = turns + GuideTurn(role = GuideTurn.Role.ASSISTANT, text = reply.reply, tools = reply.toolsUsed.map { it.tool })
                if (reply.places.isNotEmpty()) places = reply.places
                lastUi = reply.ui
                lastEffects = reply.effects
                replyRevision += 1
                failure = null
                pending = null
            }.onFailure {
                if (turn.epoch == AuthStore.epoch) failure = NetworkFailure.of(it).message
            }
        } finally {
            if (turn.epoch == AuthStore.epoch) asking = false
        }
    }
}
