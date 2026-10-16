package com.mz2az.scenetrip.data

import android.content.Context
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock

/**
 * 동의 저장소를 주입해 기기 설정·네트워크 없이 시험한다 (MZ2AZ-468). iOS `Models/AIConsent.swift` 의
 * `AIConsentStorage` 를 옮긴 것이다. 값은 **정수 버전**이라 안내가 바뀌면 이전 허용이 새 동의가 되지 않는다.
 */
interface AIConsentStorage {
    fun getInt(key: String): Int

    fun setInt(
        key: String,
        value: Int,
    )
}

/** 앱의 다른 기기 설정(`scenetrip` 파일)과 같은 곳에 둔다 — 키 접두도 `scenetrip.` 로 같다. */
class SharedPrefsConsentStorage(
    context: Context,
) : AIConsentStorage {
    private val prefs = context.getSharedPreferences("scenetrip", Context.MODE_PRIVATE)

    override fun getInt(key: String): Int = prefs.getInt(key, 0)

    // 철회가 곧바로 디스크에 닿도록 commit 이 아니라 apply 를 쓰되, 같은 프로세스에서는 메모리 값이 즉시 바뀐다.
    override fun setInt(
        key: String,
        value: Int,
    ) {
        prefs.edit().putInt(key, value).apply()
    }
}

/**
 * AI·위치 동의가 **공유하는 버전 저장소**. iOS `VersionedConsentStore` 와 같다. 전송 스레드에서도 읽으므로
 * 접근을 잠근다. 위치기반서비스 동의(MZ2AZ-472)는 이 클래스를 상속해 키·버전만 바꿔 쓰면 된다 —
 * 저장·철회·전송 잠금·화면 게이트([AIConsentGate])는 그대로다.
 */
open class VersionedConsentStore(
    private val storage: AIConsentStorage,
    private val key: String,
    private val version: Int,
) {
    init {
        require(version > 0)
    }

    private val lock = ReentrantLock()
    private val inFlight = mutableSetOf<Flight>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 허용 여부가 바뀔 때 부른다(마이페이지 토글이 다시 그려지도록). 값 자체는 저장소가 정본이다.
     * 화면 상태 라이브러리에 기대지 않아 단위 시험이 그대로 돌아간다. 돌려주는 함수가 구독 해제다.
     */
    fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    val isAllowed: Boolean
        get() = locked { storage.getInt(key) == version }

    fun allow() = save(version)

    /** 철회. 이미 나가 있는 이 동의의 요청은 끊는다 — 철회가 끝난 뒤에는 어떤 요청도 새로 시작하지 않는다. */
    fun revoke() {
        val cut =
            locked {
                storage.setInt(key, 0)
                inFlight.toList().also {
                    it.forEach { flight -> flight.revoked = true }
                    inFlight.clear()
                }
            }
        // 잠금 밖에서 끊는다 — 취소가 소켓을 닫는 동안 다른 스레드의 동의 확인을 막지 않는다.
        cut.forEach { it.call.cancel() }
        listeners.forEach { it() }
    }

    /**
     * 철회와 전송 시작을 같은 잠금으로 묶는다. 허용 상태일 때만 [call] 을 등록하고 표를 돌려준다.
     * 표가 있는 동안 철회가 오면 [revoke] 가 그 호출을 끊는다. iOS `startIfAllowed` 의 짝이다 — OkHttp 의
     * `proceed` 는 끝날 때까지 막혀 있어 잠금을 쥔 채 보낼 수 없으므로 「등록 + 철회 시 취소」 로 같은 보장을 낸다.
     */
    fun begin(call: Call): Flight? =
        locked {
            if (storage.getInt(key) != version) return@locked null
            Flight(call, this).also { inFlight.add(it) }
        }

    fun <T> withConsentLock(action: () -> T): T = locked(action)

    internal fun finish(flight: Flight) {
        locked { inFlight.remove(flight) }
    }

    private fun save(value: Int) {
        locked { storage.setInt(key, value) }
        listeners.forEach { it() }
    }

    private fun <T> locked(action: () -> T): T {
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }

    /** 등록된 전송 하나. 철회에 끊겼는지 알려 준다. */
    class Flight internal constructor(
        internal val call: Call,
        private val store: VersionedConsentStore,
    ) {
        @Volatile var revoked = false
            internal set

        fun end() = store.finish(this)
    }
}

/** AI 가이드·AI 코스 만들기 동의. iOS `AIConsentStore`. */
class AIConsentStore(
    storage: AIConsentStorage,
    version: Int = CURRENT_VERSION,
) : VersionedConsentStore(storage, KEY, version) {
    companion object {
        const val KEY = "scenetrip.aiConsentVersion"

        /** 안내 내용이 바뀌면 올린다. 이전 버전의 허용은 새 안내의 동의가 아니다. */
        const val CURRENT_VERSION = 1

        @Volatile private var instance: AIConsentStore? = null

        /** 앱 전체에서 하나 — 철회가 나가 있는 요청을 끊으려면 인터셉터와 화면이 같은 객체를 써야 한다. */
        fun getInstance(context: Context): AIConsentStore =
            instance ?: synchronized(this) {
                instance ?: AIConsentStore(SharedPrefsConsentStorage(context.applicationContext)).also { instance = it }
            }

        /** 동의가 있어야 나갈 수 있는 요청. 다른 API 는 영향받지 않는다. */
        fun requiresConsent(
            method: String,
            path: String,
        ): Boolean = method.equals("POST", ignoreCase = true) && listOf("/guide/chat", "/guide/plan").any { path.endsWith(it) }
    }
}

/**
 * HTTP 실패가 아닌 로컬 차단이다. 네트워크 전송을 시작하기 전에 던진다(iOS `AIConsentRequired`).
 * 재시도·인증 재전송 계층이 되풀이하지 않는다.
 */
class AIConsentRequired : IOException("AI consent required") {
    companion object {
        /** [NetworkFailure]·[ApiFailure] 가 화면 문구를 가르는 열쇠. 서버 코드가 아니다. */
        const val CODE = "AI_CONSENT_REQUIRED"

        /** iOS `RouteGuideFailure.consentRequired` 의 문구. */
        const val MESSAGE_KO = "AI 기능 사용을 허용한 뒤 다시 시도해 주세요."

        fun isIn(error: Throwable?): Boolean {
            var current = error
            var depth = 0
            while (current != null && depth++ < 8) {
                if (current is AIConsentRequired) return true
                current = current.cause
            }
            return false
        }
    }
}

/**
 * `/guide/chat`·`/guide/plan` 의 **매 전송** 직전에 동의를 검사한다 — 인증 갱신 뒤 재전송과 자동 재시도도 이
 * 계층을 지난다(재시도 계층 안쪽에 둔다). 미동의는 [AIConsentRequired] 로 끝나며 요청은 나가지 않는다.
 * iOS `RetryTask.send` 의 동의 검사와 같다.
 *
 * 첫 검사를 통과한 뒤 철회되면 [AIConsentStore.begin] 이 잠금 안에서 다시 검사해 시작을 막고,
 * 이미 나간 호출은 [VersionedConsentStore.revoke] 가 끊는다.
 *
 * [afterFirstCheck] 는 시험이 「첫 검사와 시작 사이」 에 철회를 끼워 넣는 뒷문이다(iOS `fault`).
 */
class AIConsentInterceptor(
    private val store: VersionedConsentStore,
    private val afterFirstCheck: () -> Unit = {},
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!AIConsentStore.requiresConsent(request.method, request.url.encodedPath)) return chain.proceed(request)
        if (!store.isAllowed) throw AIConsentRequired()
        afterFirstCheck()
        val flight = store.begin(chain.call()) ?: throw AIConsentRequired()
        try {
            return chain.proceed(request)
        } catch (error: IOException) {
            // 철회가 끊은 호출은 「취소」 가 아니라 동의 오류로 올린다.
            if (flight.revoked) throw AIConsentRequired()
            throw error
        } finally {
            flight.end()
        }
    }
}

/**
 * 화면마다 대기 중 동작만 들고, 동의 자체는 공통 저장소에서 읽는다. iOS `AIConsentGate`.
 * 첫 질문·첫 AI 코스 만들기·설정 토글이 같은 흐름을 쓴다.
 */
class AIConsentGate(
    private val store: VersionedConsentStore,
) {
    /** 안내 시트를 보여야 하는가. 바뀌면 [addListener] 구독자를 부른다(화면이 시트를 띄우고 거둔다). */
    var isPresented = false
        private set(value) {
            if (field == value) return
            field = value
            listeners.forEach { it() }
        }
    private var pending: (() -> Unit)? = null
    private var approved = false
    private var denied = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * 시트가 **허용 없이** 닫혔을 때(취소·바깥 누르기·끌어 내려 닫기) 시트가 사라진 뒤 한 번 부른다. 위치기반서비스 동의가
     * 「위치 없이 계속」 하는 화면(AI 코스 만들기)과 길 안내 실패 표시에 쓴다. iOS `locationConsentSheet(onCancel:)`.
     */
    var onCancel: (() -> Unit)? = null

    fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** 허용돼 있으면 바로 [action], 아니면 안내를 띄우고 허용된 뒤 한 번 실행한다. */
    fun request(action: () -> Unit) {
        if (pending != null) return
        if (!store.isAllowed) {
            pending = action
            approved = false
            denied = false
            isPresented = true
            return
        }
        action()
    }

    fun allow() {
        if (!isPresented || pending == null) return
        store.allow()
        approved = true
        isPresented = false
    }

    fun cancel() {
        if (isPresented && !approved) denied = true
        pending = null
        approved = false
        isPresented = false
    }

    /** 끌어 내려 닫기도 취소다. 명시적으로 허용한 경우에만 닫힌 뒤 한 번 진행한다. */
    fun didDismiss() {
        val action = if (approved && store.isAllowed) pending else null
        val wasDenied = denied
        denied = false
        cancel()
        action?.invoke()
        if (wasDenied) onCancel?.invoke()
    }

    /** 설정에서 켤 때도 안내를 읽고 허용해야 한다. */
    fun setEnabled(enabled: Boolean) {
        if (enabled) {
            request {}
        } else {
            cancel()
            store.revoke()
        }
    }
}
