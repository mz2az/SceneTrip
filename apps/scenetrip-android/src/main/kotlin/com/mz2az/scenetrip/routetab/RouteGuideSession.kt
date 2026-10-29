package com.mz2az.scenetrip.routetab

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.data.CoordinateSource
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.ResendEvents
import com.mz2az.scenetrip.data.UsageQuota
import com.mz2az.scenetrip.sceneapi.client.model.GuideContext
import com.mz2az.scenetrip.sceneapi.client.model.GuideEffect
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.GuideUiDirective
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.UUID

/**
 * 가이드 대화를 화면이 읽는 얼굴 — iOS `RouteGuideSession.shared` (`RouteTab/RouteGuideSession.swift`).
 *
 * 규칙은 [GuideChat] 에 있고(시험이 가짜 전송으로 돌린다), 여기서는 그 상태를 Compose 상태로 옮겨 든다. **앱에 하나뿐이다**
 * ([shared]) — 창을 닫아도, 편집 화면을 나갔다 같은 코스를 다시 열어도 대화가 남는다. 대화는 코스의 것이라 다른 코스를 열면
 * ([bind]) 앞 코스의 대화·AI 장소가 비워진다.
 */
class RouteGuideSession(
    deviceId: UUID,
    scope: CoroutineScope,
    environment: GuideChat.Environment = GuideChat.Environment(),
) {
    constructor(context: Context, scope: CoroutineScope) : this(InstallIdentity.of(context), scope)

    private val chat: GuideChat = GuideChat(deviceId, scope, environment) { sync() }

    var turns by mutableStateOf<List<GuideTurn>>(emptyList())
        private set
    var places by mutableStateOf<List<GuidePlace>>(emptyList())
        private set

    /** 보내는 중이거나 다시 보낼 수 있는 턴. */
    var pending by mutableStateOf<PendingTurn?>(null)
        private set

    /** 마지막 실패. 한도(429)면 [limit] 이 안내를 맡는다. */
    var failure by mutableStateOf<RouteGuideFailure?>(null)
        private set

    /** 챗봇 한도의 상태 — 대화가 아니라 계정의 것이다. */
    var limit by mutableStateOf<GuideChat.Limit>(GuideChat.Limit.Clear)
        private set

    /** 서버가 마지막으로 알려 준 남은 양. */
    var quota by mutableStateOf<UsageQuota?>(null)
        private set
    var lastUi by mutableStateOf<List<GuideUiDirective>>(emptyList())
        private set
    var lastEffects by mutableStateOf<List<GuideEffect>>(emptyList())
        private set
    var replyRevision by mutableStateOf(0)
        private set

    /** 카드가 떠 있는 핀 — 지도의 점을 누르거나 `place.card` 명령이 채운다. */
    var picked by mutableStateOf<GuidePlace?>(null)
        private set

    init {
        sync()
    }

    private fun sync() {
        turns = chat.turns
        places = chat.places
        pending = chat.pending
        failure = chat.failure
        limit = chat.limit
        quota = chat.quota
        lastUi = chat.lastUi
        lastEffects = chat.lastEffects
        replyRevision = chat.replyRevision
        picked = chat.picked
    }

    val isEmpty: Boolean get() = turns.isEmpty()

    /** 답을 기다리는 중인가. 그동안은 전송이 잠긴다. */
    val asking: Boolean get() = pending?.stage?.isBusy == true

    /** 한도가 풀리기를 기다리는 중인가 — 시계를 한 칸 넘길 때([refreshLimit]) 다시 읽는다. */
    val isLimited: Boolean get() = limit.let { chat.isLimited }

    val canRetry: Boolean get() = pending?.stage is PendingTurn.Stage.Failed && !isLimited

    /** 「다시 시도」 로 다시 보낼 질문의 좌표 출처. */
    val pendingSource: CoordinateSource get() = chat.pendingSource

    var contextOf: () -> GuideContext?
        get() = chat.contextOf
        set(value) {
            chat.contextOf = value
        }

    val courseKey: String? get() = chat.courseKey

    fun pick(place: GuidePlace) = chat.pick(place)

    fun dismiss() = chat.dismiss()

    suspend fun ask(
        text: String,
        latitude: Double,
        longitude: Double,
        source: CoordinateSource = CoordinateSource.DEVICE,
    ) = chat.ask(text, latitude, longitude, source)

    suspend fun retry() = chat.retry()

    fun noteResend(
        key: String,
        reason: ResendEvents.Reason,
    ) = chat.noteResend(key, reason)

    /** 가이드 창이 열렸다·닫혔다 — 닫히면 기다리던 다시 보내기를 끊는다(iOS `setAttended`). 창마다 [panel] 을 따로 든다. */
    fun setAttended(
        open: Boolean,
        panel: Any,
    ) = chat.setAttended(open, panel)

    fun refreshLimit() = chat.refreshLimit()

    fun forgetLimit() = chat.forgetLimit()

    fun accountChanged() = chat.accountChanged()

    fun bind(key: String) = chat.bind(key)

    fun rekey(key: String) = chat.rekey(key)

    fun clear() = chat.clear()

    companion object {
        @Volatile
        private var instance: RouteGuideSession? = null

        /** 앱에 하나뿐인 대화 창구. 편집 화면이 오가도 대화가 이어진다. */
        fun shared(context: Context): RouteGuideSession =
            instance ?: synchronized(this) {
                instance ?: RouteGuideSession(
                    context.applicationContext,
                    CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                ).also { instance = it }
            }

        /** 아직 만든 적 없으면 null — 계정이 바뀌어도 만들지는 않는다. */
        fun sharedOrNull(): RouteGuideSession? = instance
    }
}
