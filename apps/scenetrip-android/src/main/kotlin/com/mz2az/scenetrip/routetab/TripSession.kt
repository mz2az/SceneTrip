package com.mz2az.scenetrip.routetab

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.FootprintStore
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.LatestRequestQueue
import com.mz2az.scenetrip.data.LimitLedger
import com.mz2az.scenetrip.data.LocationConsentRequired
import com.mz2az.scenetrip.data.LocationConsentStore
import com.mz2az.scenetrip.data.LocationDirectionsConsent
import com.mz2az.scenetrip.data.NetworkFailure
import com.mz2az.scenetrip.data.apiResult
import com.mz2az.scenetrip.data.haversineKm
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.api.NavigationApi
import com.mz2az.scenetrip.sceneapi.client.model.NextLeg
import com.mz2az.scenetrip.sceneapi.client.model.NextLegRequest
import com.mz2az.scenetrip.searchtab.hasLocationPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 여행 안내 — **이 화면(편집기) 안에서 돈다**(2026-09-03 재편과 같은 결정). 별도
 * 길찾기 창을 두지 않는다. iOS `RouteTab/TripSession.swift`를 옮긴 것이다 — 경로선을
 * 받고 도착을 판정하는 핵심에 발자취 기록까지 더했다. 재안내(경로가 바뀌면 다시 묻기)·
 * 데모 주행(가상 GPS)은 아직 없다.
 *
 * **가상 GPS 가 없어 실기 검증은 에뮬레이터의 `adb emu geo fix`로 한다** — iOS 의
 * `DemoDrive`가 하는 일(시연 영상 밖에서는 실기기가 필요한 가상 이동)을 굳이
 * 앱 안에 새로 만들지 않고 에뮬레이터 도구로 대신한다.
 */
class TripSession(
    private val context: Context,
) {
    enum class Phase { PLAN, GUIDING, ARRIVED }

    var phase by mutableStateOf(Phase.PLAN)
        private set
    var target by mutableStateOf<RouteStop?>(null)
        private set
    var leg by mutableStateOf<NextLeg?>(null)
        private set
    var here by mutableStateOf<Pair<Double, Double>?>(null)
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    var asking by mutableStateOf(false)
        private set
    var failureCode by mutableStateOf<String?>(null)
        private set

    val isActive: Boolean get() = target != null

    /** 도착했다 — 편집 화면이 코스에 방문 표시를 남긴다. */
    var onArrived: ((RouteStop) -> Unit)? = null

    private val navigationApi = NavigationApi(API_BASE)
    private val deviceId: UUID = InstallIdentity.of(context)

    /**
     * 길 안내는 **기기 좌표를 서버를 거쳐 카카오로 보낸다** — 요청마다 위치기반서비스 동의를 확인하고(미동의면 시트, 취소하면 안내 띠에
     * 동의 안내와 「다시 시도」), 성공하면 사용자 요청 하나에 한 번 알린다(MZ2AZ-472). iOS `TripSession.directionsConsent`.
     */
    val directionsConsent = LocationDirectionsConsent(LocationConsentStore.getInstance(context))
    private val legRequests = LatestRequestQueue()
    private var scope: CoroutineScope? = null
    private val footprints = FootprintStore.getInstance(context)
    private var listener: LocationListener? = null
    private var courseId: Long = 0
    private val arrival = TripArrival()
    private var request: Job? = null
    private var dwell: Job? = null
    private var revision = 0
    private var locationAt = 0L

    fun start(
        courseId: Long,
        target: RouteStop,
        scope: CoroutineScope,
    ) {
        // 숨김·폐업 행으로는 안내를 시작하지 않는다 — 경로 요청도 나가지 않는다(MZ2AZ-458, iOS `TripSession.start`).
        if (!target.canNavigate) return
        AppAnalytics.log(AppEvent.StartTrip(target.place.id))
        end()
        this.scope = scope
        directionsConsent.beginRequest()
        this.courseId = courseId
        resetTarget(target)
        startLocationUpdates(scope)
        request =
            scope.launch {
                var attempts = 0
                while (here == null && attempts < 12) {
                    delay(500)
                    attempts += 1
                }
                fetchLeg()
            }
    }

    fun advance(
        next: RouteStop,
        scope: CoroutineScope,
    ) {
        if (!next.canNavigate) return
        this.scope = scope
        directionsConsent.beginRequest()
        request?.cancel()
        resetTarget(next)
        request = scope.launch { fetchLeg() }
    }

    private fun resetTarget(next: RouteStop) {
        revision += 1
        target = next
        phase = Phase.GUIDING
        leg = null
        failure = null
        failureCode = null
        asking = false
        arrival.reset()
    }

    /** "여기 도착함" — GPS 판정을 기다리지 않고 사람이 직접 확인한다. */
    fun markArrived() {
        if (phase != Phase.GUIDING) return
        val t = target ?: return
        phase = Phase.ARRIVED
        revision += 1
        request?.cancel()
        legRequests.clearQueued() // 도착한 뒤에는 대기 중이던 요청도 보내지 않는다.
        asking = false
        onArrived?.invoke(t)
        AppAnalytics.log(AppEvent.VisitStamp(t.place.id))
    }

    fun end() {
        directionsConsent.beginRequest() // 떠 있던 동의 시트·알림을 거두고, 대기 중이던 요청도 버린다.
        legRequests.clearQueued()
        revision += 1
        request?.cancel()
        request = null
        target = null
        leg = null
        failure = null
        failureCode = null
        asking = false
        phase = Phase.PLAN
        stopLocationUpdates()
    }

    /** 실패했을 때 "다시 시도" — iOS `RouteEditorTrip.tripDetail`의 재시도 단추. */
    fun retry(scope: CoroutineScope) {
        if (asking || LimitLedger.navigationBlock?.blocked(SystemClock.elapsedRealtime()) == true) return
        this.scope = scope
        directionsConsent.beginRequest()
        request = scope.launch { fetchLeg() }
    }

    /** 동의 시트를 허용 없이 닫았다 — 안내 띠에 동의 안내를 남기고 「다시 시도」 를 누르면 다시 묻는다. iOS `cancelLocationRequest`. */
    fun cancelLocationRequest() {
        if (directionsConsent.isAllowed || phase != Phase.GUIDING) return
        failure = tr(LocationConsentRequired.MESSAGE_KO)
        failureCode = LocationConsentRequired.CODE
    }

    suspend fun fetchLeg() {
        if (asking || phase != Phase.GUIDING || LimitLedger.navigationBlock?.blocked(SystemClock.elapsedRealtime()) == true) return
        if (target?.serverItemId == null || here == null) return
        // 위치기반서비스 동의가 없으면 묻는다 — 한 사용자 요청에서 시트는 한 번만 열린다(위치 갱신마다 다시 묻지 않는다).
        // 허용되면 [resume] 이 최신 상태로 다시 부른다. 취소하면 [cancelLocationRequest] 가 동의 안내를 남긴다.
        val resume: () -> Unit = {
            scope?.let { launchScope -> request = launchScope.launch { fetchLeg() } }
        }
        if (!directionsConsent.proceedOrAsk(resume)) return
        asking = true
        failure = null
        failureCode = null
        dispatchLeg()
    }

    /**
     * 요청은 한 번에 하나만 나간다. 그 사이 목적지가 바뀌어 대기한 요청은 끝난 뒤 **최신 상태로 한 번** 실행된다 — 중간 목적지는 건너뛰고,
     * 안내를 끝냈거나 도착했으면 보내지 않는다(iOS `TripSession.load` 의 요청 중 목적지 변경 처리).
     */
    private suspend fun dispatchLeg() {
        if (!legRequests.begin()) return
        try {
            callLeg()
        } finally {
            if (legRequests.end()) {
                val relaunch = scope
                if (relaunch != null && phase == Phase.GUIDING) {
                    request = relaunch.launch { dispatchLeg() }
                }
            }
        }
    }

    private suspend fun callLeg() {
        val t = target
        val spot = here
        val itemId = t?.serverItemId
        if (phase != Phase.GUIDING || t == null || spot == null || itemId == null) {
            asking = false
            return
        }
        val (lat, lng) = spot
        val token = revision
        val epoch = AuthStore.epoch
        val selectedCourseId = courseId
        val requestId = directionsConsent.requestId
        AppAnalytics.log(AppEvent.GetDirections)
        try {
            apiResult {
                navigationApi
                    .getNextLeg(
                        deviceId,
                        NextLegRequest(courseId = selectedCourseId, itemId = itemId, latitude = lat, longitude = lng),
                        acceptLanguage = AppLanguage.current,
                    ).also {
                        // 위치는 이미 카카오로 나갔다 — 응답이 낡아 버려져도 사용자 요청 하나에 한 번 알린다.
                        directionsConsent.notice.succeeded(requestId)
                    }
            }.onSuccess {
                if (token != revision || epoch != AuthStore.epoch) return@onSuccess
                leg = it
                failure = null
            }.onFailure {
                if (token != revision || epoch != AuthStore.epoch) return@onFailure
                val problem = NetworkFailure.of(it)
                failure = problem.message
                failureCode = problem.code
            }
        } finally {
            if (token == revision) asking = false
        }
    }

    fun foreground(
        active: Boolean,
        scope: CoroutineScope,
    ) {
        if (!active) {
            stopLocationUpdates()
        } else if (isActive) {
            startLocationUpdates(scope)
        }
    }

    private fun startLocationUpdates(scope: CoroutineScope) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        if (!context.hasLocationPermission) return
        stopLocationUpdates()
        val relay =
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    here = location.latitude to location.longitude
                    locationAt = SystemClock.elapsedRealtime()
                    // 기록은 보기 토글과 무관하게 늘 남는다 — 안내 중이면 언제나
                    // (iOS `FootprintStore.record` 주석). 보기는 지도에 그릴지만 가린다.
                    footprints.record(location.latitude, location.longitude)
                    checkArrival()
                    if (phase == Phase.GUIDING && leg == null && failure == null && !asking && request?.isActive != true) {
                        request = scope.launch { fetchLeg() }
                    }
                }

                @Deprecated("API 29 에서 폐기됐지만 minSdk 26 때문에 필요하다")
                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: Bundle?,
                ) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }
        listener = relay
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            .forEach { provider ->
                runCatching { manager.requestLocationUpdates(provider, 2_000L, 5f, relay, Looper.getMainLooper()) }
            }
        dwell =
            scope.launch {
                while (isActive) {
                    delay(5_000)
                    if (SystemClock.elapsedRealtime() - locationAt > 30_000) arrival.reset() else checkArrival()
                }
            }
    }

    private fun stopLocationUpdates() {
        dwell?.cancel()
        dwell = null
        arrival.reset()
        listener?.let {
            (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)?.removeUpdates(it)
        }
        listener = null
    }

    private fun checkArrival() {
        if (phase != Phase.GUIDING) return
        val t = target ?: return
        val (lat, lng) = here ?: return
        val meters = haversineKm(lat, lng, t.place.latitude, t.place.longitude) * 1000
        if (arrival.update(meters, SystemClock.elapsedRealtime())) markArrived()
    }
}
