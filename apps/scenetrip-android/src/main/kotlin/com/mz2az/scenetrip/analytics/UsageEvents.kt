package com.mz2az.scenetrip.analytics

import android.content.Context
import com.mz2az.scenetrip.auth.AuthTokens
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.reviews.ReviewSubject
import com.mz2az.scenetrip.sceneapi.client.api.EventsApi
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.UsageEntityType
import com.mz2az.scenetrip.sceneapi.client.model.UsageEvent
import com.mz2az.scenetrip.sceneapi.client.model.UsageEventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

fun interface UsageEventSink {
    fun send(event: UsageEvent)
}

/** 원문 검색어는 인기도 집계 API로만 보낸다. Firebase에는 길이·갈래만 남긴다(MZ2AZ-408). */
object UsageEvents {
    var sink: UsageEventSink = UsageEventSink {}

    fun start(context: Context) {
        sink = ServerUsageEventSink(context.applicationContext)
    }

    fun record(event: UsageEvent) {
        sink.send(event)
    }

    fun view(
        type: UsageEntityType,
        id: Long,
    ) {
        if (id > 0) record(UsageEvent(UsageEventType.view, entityType = type, entityId = id))
    }

    fun opened(subject: ReviewSubject?) {
        if (subject == null || subject.id <= 0) return
        AppAnalytics.log(if (subject.isPoi) AppEvent.ViewPoi(subject.id) else AppEvent.ViewPlace(subject.id))
        view(if (subject.isPoi) UsageEntityType.poi else UsageEntityType.place, subject.id)
    }
}

/** 화면·인증·재전송 큐를 건드리지 않는 전용 전송기. */
object UsageTransport {
    val client: OkHttpClient =
        OkHttpClient
            .Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(10, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .cache(null)
            .build()
}

/** 생성 클라이언트가 나중 토큰을 넣더라도 기록을 만들 때 캡처한 머리말로 덮어쓴다. */
class UsageEventRequestFactory(
    private val language: Lang,
    private val token: String?,
    private val transport: Call.Factory = UsageTransport.client,
) : Call.Factory {
    override fun newCall(request: Request): Call {
        val captured = request.newBuilder().header("Accept-Language", language.value).removeHeader("Authorization")
        token?.let { captured.header("Authorization", "Bearer $it") }
        return transport.newCall(captured.build())
    }
}

private class ServerUsageEventSink(
    context: Context,
) : UsageEventSink {
    private val installId = InstallIdentity.of(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun send(event: UsageEvent) {
        val language = AppLanguage.current
        val factory = UsageEventRequestFactory(language, AuthTokens.accessToken)
        scope.launch {
            try {
                EventsApi(API_BASE, factory).recordEvent(installId, event, language)
            } catch (_: Exception) {
                // 계약의 보내고 잊기: 실패한 집계 기록은 재전송하지 않고 화면·세션에 영향을 주지 않는다.
            }
        }
    }
}
