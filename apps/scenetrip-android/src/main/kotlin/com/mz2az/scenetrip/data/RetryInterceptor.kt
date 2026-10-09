package com.mz2az.scenetrip.data

import android.os.SystemClock
import com.mz2az.scenetrip.auth.AuthRules
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException

/** 생성 클라이언트 공용 계층. 몸통을 재사용하며 응답을 닫은 뒤에만 다음 요청을 보낸다. */
class RetryInterceptor(
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val ledger: RetryLedger = LimitLedger,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.url.host != API_BASE.toHttpUrl().host) return chain.proceed(request)
        val policy =
            RetryRules.policy(
                request.method,
                request.url.encodedPath,
                request.header("Idempotency-Key") != null && ledger.knowsLimits,
            )
        val started = clock()
        val generation = ledger.generation
        val keyStarted = request.header("Idempotency-Key")?.let { ledger.keyStarted(it, started) } ?: started
        var history = RetryRules.History()
        var waited = 0L
        while (true) {
            val elapsed = clock() - started - waited
            val timeout = minOf(policy.timeoutSeconds * 1000L, (policy.budgetSeconds * 1000L - elapsed).coerceAtLeast(1)).toInt()
            val attempt =
                chain
                    .withReadTimeout(
                        timeout,
                        TimeUnit.MILLISECONDS,
                    ).withConnectTimeout(minOf(timeout, 15_000), TimeUnit.MILLISECONDS)
                    .withWriteTimeout(timeout, TimeUnit.MILLISECONDS)
            var response: Response? = null
            var failed: IOException? = null
            try {
                response = attempt.proceed(request)
            } catch (error: IOException) {
                failed = error
            }
            if (chain.call().isCanceled()) {
                response?.close()
                throw InterruptedIOException("cancelled")
            }
            val status = response?.code
            val code = response?.takeIf { !it.isSuccessful }?.let { AuthRules.apiCode(it.peekBody(16_384).string()) }
            val retryAfter = response?.header("Retry-After")?.toIntOrNull()
            if (response != null) ledger.observe(response, generation, clock())
            if (response?.isSuccessful == true) return response
            if (status == 404 && request.method == "DELETE" && history.mayHaveReached) {
                response!!.close()
                return response
                    .newBuilder()
                    .code(204)
                    .message("No Content")
                    .body("".toResponseBody())
                    .build()
            }
            if (failed is SSLHandshakeException ||
                (failed is InterruptedIOException && failed !is java.net.SocketTimeoutException)
            ) {
                throw failed
            }
            val unreached = failed is ConnectException || failed is UnknownHostException
            val spent = clock() - started - waited
            val step =
                RetryRules.next(
                    policy,
                    history,
                    status,
                    code,
                    retryAfter,
                    unreached,
                    spent,
                    clock() - keyStarted,
                )
            if (step == null) {
                if (response != null) return response
                throw failed ?: IOException("No response")
            }
            response?.close()
            history = RetryRules.record(history, step, status, unreached)
            val jitter = if (step.forLimit) (Math.random() * 1000).toLong() else (step.waitMillis * (Math.random() * 0.4 - 0.2)).toLong()
            val delay = step.waitMillis + jitter
            val until = clock() + delay
            while (clock() < until) {
                if (chain.call().isCanceled() || Thread.currentThread().isInterrupted) throw InterruptedIOException("cancelled")
                pause(minOf(100, (until - clock()).coerceAtLeast(1)))
            }
            if (step.forLimit) waited += delay
        }
    }
}
