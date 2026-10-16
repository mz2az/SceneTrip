package com.mz2az.scenetrip.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics

/** 이벤트를 받아 주는 곳. 진짜(Firebase)와 가짜(시험)가 같은 모양이다. */
interface AnalyticsSink {
    fun log(event: AppEvent)

    fun setUserProperty(
        name: String,
        value: String?,
    )

    /** SDK의 수집 스위치(자동 수집 포함). 시험용 가짜는 기본 구현 그대로 둔다. */
    fun setCollectionEnabled(enabled: Boolean) = Unit
}

/**
 * 앱 분석의 입구 (MZ2AZ-353). 화면 코드는 `AppAnalytics.log(AppEvent.CreateCourse(...))` 한
 * 줄만 쓴다. iOS `Analytics/AppAnalytics.swift`를 그대로 옮긴 것이다.
 *
 * ## 설정 값이 없으면 아무것도 보내지 않는다
 *
 * Firebase 는 설정 값이 있어야 켜진다(`res/values/firebase.xml` — `google-services` Gradle
 * 플러그인이 보통 `google-services.json`에서 만들어 주는 리소스를 손으로 적어 둔 값이다.
 * Bazel 에는 그 플러그인이 없다). 이 리소스는 Firebase 콘솔의 설정이라 CI 와 다른 팀원의
 * 빌드에는 없을 수 있다 — 없으면 켜지 않고(켜면 죽는다) 이벤트를 버린다. 그래서 **리소스가
 * 없어도 앱은 그대로 빌드되고 돈다.**
 *
 * **왜 `res/values/firebase.xml`인가.** 처음엔 `res/raw/google_services.json`을 직접 읽어
 * `FirebaseOptions`를 코드로 짓고 `FirebaseApp.initializeApp`을 불렀다. 그런데 Firebase
 * Analytics(그 안의 play-services-measurement)는 `FirebaseOptions`를 거치지 않고
 * **`google_app_id` 문자열 리소스를 따로 직접 읽는다** — 코드로 초기화해도 이 리소스가
 * 없으면 "Missing google_app_id. Firebase Analytics disabled."로 멈춘다(실기 확인). 이
 * 리소스가 있으면 Firebase 의 `FirebaseInitProvider`가 **[start]가 불리기도 전에 혼자
 * `[DEFAULT]` 앱을 다 초기화해 버린다** — 그 뒤에 코드로 또 초기화하면 "name [DEFAULT]
 * already exists!"로 죽는다(실기 확인). 그래서 `res/raw/google_services.json`은 값의
 * 원본 출처로만 남기고, 초기화는 전부 리소스 기반 자동 초기화에 맡긴다.
 *
 * ## 이용 통계 수집 설정 (MZ2AZ-475)
 *
 * 마이페이지 「이용 통계 수집」을 끄면 [log]와 사용자 속성을 입구에서 버리고, SDK의 자동 수집까지
 * `setAnalyticsCollectionEnabled(false)`로 끈다. 매니페스트의 `firebase_analytics_collection_enabled`가
 * false라 [start]가 저장한 값으로 명시해 주기 전에는 SDK가 아무것도 모으지 않는다 — 꺼 둔 사람의
 * 첫 자동 이벤트가 [start] 전에 나가지 않게 하려는 것이다(iOS는 OFF로 시작하면 SDK 초기화를 미룬다).
 * `FirebaseApp` 초기화는 여전히 건드리지 않는다.
 *
 * 광고 식별자(IDFA/AAID)는 쓰지 않는다. 광고를 집행하게 되면 다시 정한다 — 그때는
 * 추적 동의 창이 필요하다.
 */
object AppAnalytics {
    /** 시험에서 가짜로 갈아 끼운다. */
    var sink: AnalyticsSink = NoSink

    /** 이용 통계 수집이 켜져 있는지(MZ2AZ-475). [start]가 저장한 값으로 맞춘다. 기본은 켜짐. */
    @Volatile
    var collectionEnabled: Boolean = true
        private set

    private var language: String? = null
    private var account: String? = null

    /**
     * 앱이 뜰 때 한 번.
     *
     * **`FirebaseApp.initializeApp`을 직접 부르지 않는다.** `res/values/firebase.xml`이
     * 있으면 Firebase 의 `FirebaseInitProvider`(SDK 가 매니페스트에 등록해 두는
     * `ContentProvider`)가 이 함수가 불리기 전에, `Application`이 뜨는 과정에서 이미
     * `[DEFAULT]` 앱을 혼자 초기화한다. 여기서 또 부르면 "FirebaseApp name [DEFAULT]
     * already exists!"로 죽는다(실기 확인) — 그래서 **성공했는지만 확인한다.**
     */
    fun start(
        context: Context,
        language: String,
        member: Boolean,
    ) {
        if (FirebaseApp.getApps(context).isNotEmpty()) {
            sink = FirebaseSink(context)
        }
        this.language = language
        account = if (member) "member" else "guest"
        // 저장한 선택이 첫 이벤트보다 먼저다. ON이면 속성도 함께 밀어 넣는다.
        setCollectionEnabled(UsageStatistics.getInstance(context).isEnabled)
    }

    /**
     * 이용 통계 수집 스위치. 끄면 입구에서 이벤트·속성을 버리고 SDK 수집도 끈다. 켜면 SDK 수집을 켜고
     * 꺼 둔 동안 바뀐 언어·회원 속성을 다시 쓴다.
     */
    fun setCollectionEnabled(enabled: Boolean) {
        collectionEnabled = enabled
        sink.setCollectionEnabled(enabled)
        if (enabled) {
            language?.let { sink.setUserProperty("app_language", it) }
            account?.let { sink.setUserProperty("account", it) }
        }
    }

    fun log(event: AppEvent) {
        if (!collectionEnabled) return
        sink.log(event)
    }

    /** 사용자 속성 — 이벤트를 언어별·회원 여부별로 나눠 보려고 둔다. 사람을 가리키는 값은 아니다. */
    fun setLanguage(language: String) {
        this.language = language
        if (!collectionEnabled) return
        sink.setUserProperty("app_language", language)
    }

    fun setMember(member: Boolean) {
        account = if (member) "member" else "guest"
        if (!collectionEnabled) return
        sink.setUserProperty("account", account)
    }
}

private object NoSink : AnalyticsSink {
    override fun log(event: AppEvent) = Unit

    override fun setUserProperty(
        name: String,
        value: String?,
    ) = Unit

    override fun setCollectionEnabled(enabled: Boolean) = Unit
}

private class FirebaseSink(
    context: Context,
) : AnalyticsSink {
    private val analytics = FirebaseAnalytics.getInstance(context)

    override fun log(event: AppEvent) {
        val bundle =
            Bundle().apply {
                event.parameters.forEach { (key, value) ->
                    when (value) {
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is String -> putString(key, value)
                        else -> putString(key, value.toString())
                    }
                }
            }
        analytics.logEvent(event.name, if (event.parameters.isEmpty()) null else bundle)
    }

    override fun setUserProperty(
        name: String,
        value: String?,
    ) {
        analytics.setUserProperty(name, value)
    }

    override fun setCollectionEnabled(enabled: Boolean) {
        analytics.setAnalyticsCollectionEnabled(enabled)
    }
}
