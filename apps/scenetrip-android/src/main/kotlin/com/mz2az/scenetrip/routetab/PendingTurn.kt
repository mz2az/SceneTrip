package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.CoordinateSource
import com.mz2az.scenetrip.data.ResendEvents
import com.mz2az.scenetrip.sceneapi.client.model.GuideChatRequest
import com.mz2az.scenetrip.sceneapi.client.model.Lang

/**
 * 가이드에게 **보내는 중이거나, 실패해서 다시 보낼 수 있는 턴** (MZ2AZ-366, iOS `PendingTurn`).
 *
 * 「전송」 한 번이 턴 하나고, 턴 하나에 **멱등 키 하나**다. 자동 재시도도 「다시 시도」 단추도 이 턴을 그대로 다시 보낸다 —
 * 같은 키, 같은 요청. 서버는 같은 키를 한 번만 처리하고 그 뒤에는 저장한 답을 준다(모델을 두 번 부르지 않고 한도도 한 번만
 * 깎인다).
 *
 * **요청은 만들 때의 것을 든다.** 서버가 같은 키에 같은 내용인지를 본문 전체로 가리는데, 본문에 위치와 화면 상태가 들어
 * 있어 다시 만들면 달라진다 — 그러면 `422` 다. 메모리에만 있다. 앱을 끄면 사라진다(대화도 그렇다).
 */
data class PendingTurn(
    /** 이 턴의 멱등 키 — `Idempotency-Key` 헤더로 나간다. */
    val key: String,
    /** 처음 만든 요청. 다시 보낼 때 이것을 그대로 보낸다. */
    val request: GuideChatRequest,
    /** 요청을 만들 때의 앱 언어. `Accept-Language` 도 서버 지문에 들어간다 — 바뀌었으면 같은 키로는 422 다. */
    val lang: Lang,
    /**
     * 보낼 때 **서버가 멱등 키를 아는 것이 확인됐나**(이번 실행에서 `RateLimit-*` 를 본 적이 있나). 아니면 자동 재시도도
     * 「다시 시도」 도 없다 — 옛 서버는 같은 턴을 두 번 처리한다.
     */
    val keyed: Boolean,
    /** 좌표가 기기 위치인지 사용자가 고른 지도·목적지인지. 「다시 시도」 도 최초 출처를 그대로 쓴다(MZ2AZ-472). */
    val source: CoordinateSource = CoordinateSource.DEVICE,
    val stage: Stage = Stage.Sending,
) {
    sealed class Stage {
        /** 보냈고 답을 기다린다. */
        object Sending : Stage()

        /** 실패해서 공통 계층이 같은 키로 다시 보내는 중이다(자동 한 번). */
        object Reconnecting : Stage()

        /** 서버가 이 키를 아직 처리 중이라(409) 같은 키로 다시 묻는 중이다 — 답은 만들어지고 있다. */
        object AwaitingServer : Stage()

        /** 분당 요청 한도에 걸려 공통 계층이 풀리기를 기다리는 중이다(길면 1 분) — 연결 문제가 아니다. */
        object WaitingForLimit : Stage()

        /** 실패했고 사람이 「다시 시도」 를 누를 수 있다. */
        data class Failed(
            val failure: RouteGuideFailure,
        ) : Stage()

        /** 답을 기다리는 중인가 — 그동안은 전송도 「다시 시도」 도 잠긴다. */
        val isBusy: Boolean get() = this !is Failed

        /** 첫 요청이 아니라 **다시 보내기·되묻기를 기다리는 중**인가. 가이드 창을 닫으면 이것은 끊는다(iOS `isResending`). */
        val isResending: Boolean get() = this is Reconnecting || this is AwaitingServer || this is WaitingForLimit
    }

    /** 공통 계층이 다시 보내려고 쉬기 시작했다. */
    fun resending(reason: ResendEvents.Reason): PendingTurn {
        if (!stage.isBusy) return this
        return copy(
            stage =
                when (reason) {
                    ResendEvents.Reason.FAILED -> Stage.Reconnecting
                    ResendEvents.Reason.IN_PROGRESS -> Stage.AwaitingServer
                    ResendEvents.Reason.RATE_LIMITED -> Stage.WaitingForLimit
                },
        )
    }

    /** 실패로 끝났다. **남기면 「다시 시도」 가 뜬다** — 다시 해 볼 만한 실패이고 서버가 키를 알 때만. */
    fun failing(failure: RouteGuideFailure): PendingTurn? {
        if (!keyed || failure.retry == RouteGuideFailure.Retry.NONE) return null
        return copy(stage = Stage.Failed(failure))
    }

    /**
     * 「다시 시도」 를 눌렀다. 같은 요청을 **같은 키로** — 다만 그 키로는 안 되는 둘은 새 키로 보낸다: 서버가 「키 재사용」(422)이라
     * 한 턴, 그리고 그 사이 앱 언어가 바뀐 턴(지문이 달라진다).
     */
    fun retried(
        now: Lang,
        newKey: () -> String,
    ): PendingTurn? {
        val failed = stage as? Stage.Failed ?: return null
        val fresh = failed.failure.retry == RouteGuideFailure.Retry.NEW_KEY || now != lang
        return PendingTurn(if (fresh) newKey() else key, request, now, keyed, source)
    }
}
