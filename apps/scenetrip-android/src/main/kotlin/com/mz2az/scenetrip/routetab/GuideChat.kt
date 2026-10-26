package com.mz2az.scenetrip.routetab

import android.os.SystemClock
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.CoordinateSource
import com.mz2az.scenetrip.data.CoordinateSources
import com.mz2az.scenetrip.data.LimitLedger
import com.mz2az.scenetrip.data.ResendEvents
import com.mz2az.scenetrip.data.UsageQuota
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.api.GuideApi
import com.mz2az.scenetrip.sceneapi.client.model.GuideChatReply
import com.mz2az.scenetrip.sceneapi.client.model.GuideChatRequest
import com.mz2az.scenetrip.sceneapi.client.model.GuideContext
import com.mz2az.scenetrip.sceneapi.client.model.GuideEffect
import com.mz2az.scenetrip.sceneapi.client.model.GuideMessage
import com.mz2az.scenetrip.sceneapi.client.model.GuidePlace
import com.mz2az.scenetrip.sceneapi.client.model.GuideUiDirective
import com.mz2az.scenetrip.sceneapi.client.model.Lang
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
 * 여행 가이드 대화의 **규칙** — Compose 없이 도는 순수한 부분이다(시험이 가짜 전송으로 돌린다). 화면이 보는 상태는
 * [RouteGuideSession] 이 [publish] 를 받아 옮겨 든다.
 *
 * 여기서만 서버(`POST /guide/chat`)를 부른다. iOS
 * `RouteTab/RouteGuide.swift`(`ask`)·`RouteGuideSession.swift`를 옮긴 것이다.
 *
 * ## 화면(챗봇 창)보다 오래 산다
 *
 * iOS 는 앱 공용 싱글턴(`RouteGuideSession.shared`)이라 창을 닫아도, 편집 화면을 나갔다 같은 코스를 다시 열어도 대화가
 * 남는다. 여기도 앱 공용 [shared] 하나다 — **대화는 코스의 것이다**([bind]). 다른 코스를 열면 대화·AI 장소·방 번호를 새로
 * 시작하고, 같은 코스를 다시 열면 그대로 이어진다. 창구가 하나라고 대화까지 하나면 새 코스를 열었는데 앞 코스에서 추천받은
 * 「AI 장소」가 지도에 찍힌다.
 *
 * ## 방 번호([sessionId])도 여기 있다
 *
 * 서버가 이 값으로 「앞 턴에 보여 준 장소」를 기억한다. 창이 들고 있으면 닫을 때마다 새 방이 되어, 「거기 어떻게 가요」가
 * 무엇을 가리키는지 서버도 잊는다.
 *
 * **`effects`·`ui`는 여기서 만들지 않는다** — 서버가 보낸 것을 [lastUi]·[lastEffects]로 그대로 들고 있을 뿐이고, 실제로
 * 지도를 맞추고 일차를 넘기고 장바구니를 새로고침하는 일은 [RouteEditorView]가 한다.
 *
 * ## 실패와 한도 (MZ2AZ-366·481)
 *
 * 실패는 계약 응답별로 갈라 말한다([RouteGuideFailure]). 한도(429 `GUIDE_LIMIT_REACHED`)는 실패 줄이 아니라 [limit] 로
 * 따로 든다 — 대화를 지우거나 코스를 바꿔도 잠겨 있는 이유가 보여야 해서다. 풀리는 때를 알면 그때까지 전송을 잠그고,
 * **자동으로 다시 보내지 않는다.**
 */
class GuideChat(
    private val deviceId: UUID,
    private val scope: CoroutineScope,
    private val environment: Environment = Environment(),
    /** 상태가 바뀔 때마다 부른다 — 화면용 래퍼([RouteGuideSession])가 Compose 상태로 옮긴다. */
    private val publish: () -> Unit = {},
) {
    /** 밖과 닿는 자리들 — 시험이 가짜로 바꿔 끼운다. 실제 모델·서버는 시험에서 부르지 않는다. */
    class Environment(
        /** 만들어 둔 턴을 보낸다. */
        val send: suspend (UUID, PendingTurn) -> Result<GuideChatReply> = { device, turn -> sendToServer(device, turn) },
        /** 서버가 멱등 키를 아는가 — 이번 실행에서 `RateLimit-*` 를 본 적이 있는가. */
        val serverKeepsKeys: () -> Boolean = { LimitLedger.knowsLimits },
        val lang: () -> Lang = { AppLanguage.current },
        val newKey: () -> String = { UUID.randomUUID().toString() },
        /** 사람이 질문을 보냈다(분석). 「다시 시도」 는 같은 질문이라 세지 않는다. */
        val asked: () -> Unit = { AppAnalytics.log(AppEvent.AskGuide) },
        /** **연속 시계**의 지금(밀리초) — 한도의 잠금과 「N분 뒤」 는 이것으로 잰다. 기기 시계를 돌려도 변하지 않는다. */
        val now: () -> Long = { SystemClock.elapsedRealtime() },
        /** 서버가 마지막 챗봇 응답에 실어 준 남은 양(`RateLimit-*`). 없으면(옛 서버) null. */
        val quota: () -> UsageQuota? = { LimitLedger.guideQuota },
        val signedIn: () -> Boolean = { AuthStore.signedIn },
        val promptSignIn: () -> Unit = { AuthStore.promptSignIn() },
    )

    /** 챗봇 한도(429 `GUIDE_LIMIT_REACHED`)에 걸렸나. */
    sealed class Limit {
        object Clear : Limit()

        /** 걸려 있다. 풀리는 때를 알면 그때까지 전송이 잠긴다. */
        data class Reached(
            val block: GuideLimit.Block,
        ) : Limit()

        /** 풀렸다 — 「이제 다시 물어볼 수 있어요」. 걸렸던 질문이 남아 있으면 「다시 시도」 가 같은 키로 보낸다. */
        object Lifted : Limit()
    }

    var turns: List<GuideTurn> = emptyList()
        private set(value) {
            field = value
            publish()
        }
    var places: List<GuidePlace> = emptyList()
        private set(value) {
            field = value
            publish()
        }

    /** 보내는 중이거나 다시 보낼 수 있는 턴. 답이 왔거나 다시 해도 같은 실패면 비운다. */
    var pending: PendingTurn? = null
        private set(value) {
            field = value
            publish()
        }

    /** 마지막 실패. 한도(429)면 [limit] 이 안내를 맡는다. */
    var failure: RouteGuideFailure? = null
        private set(value) {
            field = value
            publish()
        }

    /** 챗봇 한도의 상태. **대화가 아니라 계정의 것이다** — 코스를 바꿔도([bind]·[clear]) 남는다. */
    var limit: Limit = Limit.Clear
        private set(value) {
            field = value
            publish()
        }

    /** 서버가 마지막으로 알려 준 남은 양 — 입력창 위 한 줄의 재료([GuideLimit.remaining]). 이것도 계정의 것이다. */
    var quota: UsageQuota? = null
        private set(value) {
            field = value
            publish()
        }

    /** 마지막 답의 화면·상태 명령 — 매 턴 새 리스트로 갈아 끼운다(빈 배열이어도).
     * 화면은 이 값이 바뀔 때만 `LaunchedEffect`로 한 번 적용한다. */
    var lastUi: List<GuideUiDirective> = emptyList()
        private set(value) {
            field = value
            publish()
        }
    var lastEffects: List<GuideEffect> = emptyList()
        private set(value) {
            field = value
            publish()
        }
    var replyRevision: Int = 0
        private set(value) {
            field = value
            publish()
        }

    /** 카드가 떠 있는 핀 — 지도의 점을 누르거나 `place.card` 명령이 채운다. */
    var picked: GuidePlace? = null
        private set(value) {
            field = value
            publish()
        }

    fun pick(place: GuidePlace) {
        picked = place
    }

    fun dismiss() {
        picked = null
    }

    val isEmpty: Boolean get() = turns.isEmpty()

    /** 답을 기다리는 중인가. 그동안은 전송이 잠긴다 — **두 번 누름은 여기서 막힌다.** */
    val asking: Boolean get() = pending?.stage?.isBusy == true

    /**
     * 한도가 풀리기를 기다리는 중인가. 그동안은 전송이 잠긴다 — 뻔히 429 인 요청을 보내지 않는다. 풀리는 때를 모르면
     * (서버가 `Retry-After` 를 안 줌) 잠그지 않는다.
     */
    val isLimited: Boolean get() = (limit as? Limit.Reached)?.block?.stopsSending(environment.now()) == true

    /** 실패한 턴을 다시 보낼 수 있나 — 「다시 시도」 단추를 보일지. 한도에 걸린 턴은 **풀린 뒤에만.** */
    val canRetry: Boolean get() = pending?.stage is PendingTurn.Stage.Failed && !isLimited

    /** 「다시 시도」 로 다시 보낼 질문의 좌표 출처. 없으면 기기 위치로 취급한다. */
    val pendingSource: CoordinateSource get() = pending?.source ?: CoordinateSource.DEVICE

    var contextOf: () -> GuideContext? = { null }

    /** 대화 하나의 열쇠. 계약이 UUID 를 요구한다. 코스가 바뀌면 새로 뽑는다 — 서버가 기억하는 장소도 앞 코스의 것이다. */
    private var sessionId: UUID = UUID.randomUUID()

    /** 지금 대화가 묶인 코스. `course-<서버 id>` 또는 저장 전이면 `draft-<화면 id>`. */
    var courseKey: String? = null
        private set

    /** 계정이 바뀐 횟수 — 보낸 뒤에 계정이 바뀐 요청의 결과는 버린다. */
    private var accountGeneration = 0
    private var sending: Job? = null

    private val resendListener: (String, ResendEvents.Reason) -> Unit = { key, reason -> noteResend(key, reason) }

    init {
        ResendEvents.add(resendListener)
    }

    /** 계약 상한 40 — 넘치면 오래된 것부터 잊는다. iOS `RouteGuide.ask`. */
    suspend fun ask(
        text: String,
        latitude: Double,
        longitude: Double,
        source: CoordinateSource = CoordinateSource.DEVICE,
    ) {
        val trimmed = text.trim()
        refreshLimit()
        if (trimmed.isEmpty() || asking || isLimited || trimmed.length > 4000) return
        if (!environment.signedIn()) {
            environment.promptSignIn()
            return
        }
        failure = null
        limit = Limit.Clear // 새 질문이다 — 한도 안내는 답(또는 또 한 번의 429)이 다시 정한다
        turns = turns + GuideTurn(role = GuideTurn.Role.USER, text = trimmed)
        val request =
            GuideChatRequest(
                sessionId = sessionId,
                latitude = latitude,
                longitude = longitude,
                messages =
                    turns.takeLast(40).map {
                        GuideMessage(
                            role = if (it.role == GuideTurn.Role.USER) GuideMessage.Role.user else GuideMessage.Role.assistant,
                            content = it.text.take(4000),
                        )
                    },
                context = contextOf(),
            )
        // 요청은 여기서 한 번 만든다 — 위치·화면 상태·이력이 박힌 채로. 다시 보낼 때 이것을 그대로 쓴다.
        pending =
            PendingTurn(
                key = environment.newKey(),
                request = request,
                lang = environment.lang(),
                keyed = environment.serverKeepsKeys(),
                source = source,
            )
        environment.asked()
        fly()
    }

    /** 「다시 시도」. **같은 턴을 같은 키로** 다시 보낸다 — 질문을 대화에 또 적지 않는다. */
    suspend fun retry() {
        refreshLimit()
        if (!canRetry) return
        val turn = pending?.retried(environment.lang(), environment.newKey) ?: return
        failure = null
        limit = Limit.Clear
        pending = turn
        fly()
    }

    /** 공통 계층이 이 턴을 다시 보내려고(또는 다시 물으려고) 쉬기 시작했다 — 화면 말을 바꾼다. */
    fun noteResend(
        key: String,
        reason: ResendEvents.Reason,
    ) {
        val turn = pending ?: return
        if (turn.key != key || !turn.stage.isBusy) return
        pending = turn.resending(reason)
    }

    private fun fly() {
        val turn = pending ?: return
        val generation = accountGeneration
        sending?.cancel()
        sending = scope.launch { land(turn, generation, environment.send(deviceId, turn)) }
    }

    private fun land(
        turn: PendingTurn,
        generation: Int,
        result: Result<GuideChatReply>,
    ) {
        // 기다리는 사이에 코스가 바뀌었거나 대화를 지웠다 — 늦게 온 답을 남의 대화에 적지 않는다.
        if (pending?.key != turn.key) return
        sending = null
        // 기다리는 사이에 **계정이 바뀌었다** — 앞 계정의 답도 한도(429)도 지금 계정의 것이 아니다. 통째로 버린다.
        if (generation != accountGeneration) {
            pending = null
            return
        }
        quota = environment.quota()
        result
            .onSuccess { reply ->
                pending = null
                limit = Limit.Clear
                // **장소를 새로 찾아 왔을 때만 목록을 갈아 끼운다.** 「어디 기준이야?」 같은 되물음에는 장소가 안 실려 오는데,
                // 그때 목록까지 지우면 방금 받은 추천과 담기 단추가 채팅 한 번에 사라진다. 고른 것을 놓는 것도 그때만이다.
                if (reply.places.isNotEmpty()) {
                    places = reply.places
                    picked = null
                }
                turns =
                    turns +
                    GuideTurn(
                        role = GuideTurn.Role.ASSISTANT,
                        text = reply.reply.ifEmpty { tr("답을 받지 못했습니다.") },
                        tools = reply.toolsUsed.map { it.tool },
                    )
                lastUi = reply.ui
                lastEffects = reply.effects
                replyRevision += 1
            }.onFailure { error ->
                // 계약 응답별로 갈라 말한다 — 401 가입 · 503 잠시 뒤 · 시간 초과 · 오프라인 · 연결 실패.
                val reason = RouteGuideFailure.of(error)
                failure = reason
                // 다시 해 볼 만한 실패면 턴을 남긴다 — 「다시 시도」 가 같은 키로 보낸다.
                pending = turn.failing(reason)
                limit =
                    if (reason is RouteGuideFailure.LimitReached) {
                        Limit.Reached(GuideLimit.Block(reason.retryAfter, environment.now()))
                    } else {
                        Limit.Clear
                    }
            }
    }

    // 챗봇 한도

    /** 풀리는 때가 지났으면 잠금을 푼다. 창을 열 때와 보내기 직전, 화면이 시계를 한 칸 넘길 때 본다. */
    fun refreshLimit() {
        val reached = limit as? Limit.Reached ?: return
        if (reached.block.isOver(environment.now())) limit = Limit.Lifted
    }

    /**
     * 계정이 바뀌었다 — 한도와 남은 양은 앞 계정의 것이다. 떠 있는 요청도 앞 계정의 것이라 끊고, 늦게 돌아온 결과는
     * [land] 가 버린다.
     */
    fun forgetLimit() {
        accountGeneration += 1
        sending?.cancel()
        sending = null
        limit = Limit.Clear
        quota = null
        if (failure is RouteGuideFailure.LimitReached) {
            failure = null
            pending = null
        }
    }

    /** 계정이 바뀌었다 — 한도뿐 아니라 앞 계정의 대화·AI 장소도 비운다. */
    fun accountChanged() {
        forgetLimit()
        sessionId = UUID.randomUUID()
        clear()
        replyRevision += 1
    }

    /**
     * 편집 화면이 뜰 때 부른다. **다른 코스면 대화·AI 장소·방 번호를 새로 시작한다.** 같은 코스를 다시 열면 그대로 이어진다.
     */
    fun bind(key: String) {
        if (key == courseKey) return
        courseKey = key
        clear()
        sessionId = UUID.randomUUID()
    }

    /** 저장 전 코스가 서버 id 를 얻었다 — **같은 코스다.** 열쇠만 바꾸고 대화는 둔다. */
    fun rekey(key: String) {
        courseKey = key
    }

    /** 대화를 처음부터 다시. **방 번호는 그대로 둔다** — 서버가 기억하는 장소까지 지울 이유는 없다. */
    fun clear() {
        // 보내던 턴도 버린다 — 떠 있는 요청을 끊고, 늦게 온 답은 [land] 가 적지 않는다.
        sending?.cancel()
        sending = null
        pending = null
        turns = emptyList()
        places = emptyList()
        picked = null
        failure = null
        lastUi = emptyList()
        lastEffects = emptyList()
        // 한도는 계정의 것이라 남는다. 다만 「이제 다시 물어볼 수 있어요」 는 지운 대화의 말이다.
        if (limit == Limit.Lifted) limit = Limit.Clear
    }

    companion object {
        private suspend fun sendToServer(
            deviceId: UUID,
            turn: PendingTurn,
        ): Result<GuideChatReply> =
            apiResult {
                CoordinateSources.with(turn.source) {
                    GuideApi(API_BASE).chatWithGuide(
                        deviceId,
                        turn.request,
                        acceptLanguage = AppLanguage.current,
                        idempotencyKey = turn.key,
                    )
                }
            }
    }
}
