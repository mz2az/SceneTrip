package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.infrastructure.ApiClient
import okhttp3.Dispatcher
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **멱등 키를 실은 요청**을 다시 보내려고 쉬기 시작했다는 알림 (MZ2AZ-366, iOS `RetryingSession.willResend`).
 *
 * 챗봇 화면이 「다시 연결하는 중이에요」·「요청이 많아 잠시 기다리는 중이에요」 를 말하는 데 쓴다. 키 없는 요청에는
 * 내지 않는다 — 들을 곳이 없다. 공통 계층([RetryInterceptor])이 호출한 스레드에서 부르므로 듣는 쪽이 스레드를 고른다.
 */
object ResendEvents {
    enum class Reason {
        /** 실패해서(끊김·5xx) 다시 보낸다. */
        FAILED,

        /** 서버가 처리 중(409)이라 같은 키로 다시 묻는다. */
        IN_PROGRESS,

        /** 분당 한도(429 `RATE_LIMITED`)가 풀리기를 기다린다 — 길면 1 분이다. */
        RATE_LIMITED,
    }

    private val listeners = CopyOnWriteArrayList<(String, Reason) -> Unit>()

    fun add(listener: (String, Reason) -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun remove(listener: (String, Reason) -> Unit) {
        listeners.remove(listener)
    }

    fun notify(
        key: String,
        reason: Reason,
    ) {
        listeners.forEach { it(key, reason) }
    }

    /**
     * 이 멱등 키를 실은 **나가 있는 호출을 끊는다**(iOS 는 `Task.cancel()`). 코루틴만 끊으면 막힌 소켓 호출과 공통 계층의
     * 다시 보내기 대기가 그대로 돌아 뒤에서 요청을 더 보낸다 — 호출 자체를 끊어야 [RetryInterceptor] 의 대기 반복이 멈춘다.
     * 끊긴 호출은 `InterruptedIOException("cancelled")` 로 끝난다.
     */
    fun cancelCalls(
        key: String,
        dispatcher: Dispatcher = ApiClient.defaultClient.dispatcher,
    ) {
        (dispatcher.runningCalls() + dispatcher.queuedCalls())
            .filter { it.request().header("Idempotency-Key") == key }
            .forEach { it.cancel() }
    }
}
