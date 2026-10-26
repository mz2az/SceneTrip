package com.mz2az.scenetrip.data

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
}
