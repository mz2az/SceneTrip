package com.mz2az.scenetrip.routetab

import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.ApiFailure
import com.mz2az.scenetrip.data.FootprintStore
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.data.haversineKm
import com.mz2az.scenetrip.sceneapi.client.api.NavigationApi
import com.mz2az.scenetrip.sceneapi.client.model.NextLeg
import com.mz2az.scenetrip.sceneapi.client.model.NextLegRequest
import com.mz2az.scenetrip.searchtab.hasLocationPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    val isActive: Boolean get() = target != null

    /** 도착했다 — 편집 화면이 코스에 방문 표시를 남긴다. */
    var onArrived: ((RouteStop) -> Unit)? = null

    private val navigationApi = NavigationApi(API_BASE)
    private val deviceId: UUID = InstallIdentity.of(context)
    private val footprints = FootprintStore.getInstance(context)
    private var listener: LocationListener? = null
    private var courseId: Long = 0

    /** 도착 판정 반경(m). iOS `DemoDrive.stopWithinMeters`보다 넓다 — 그건 걷기를
     * 멈추는 자리, 이건 실제 「도착」 판정 자리라 서로 다른 값이다(iOS 주석 참고). */
    private val arrivalMeters = 100.0

    fun start(
        courseId: Long,
        target: RouteStop,
        scope: CoroutineScope,
    ) {
        this.courseId = courseId
        this.target = target
        phase = Phase.GUIDING
        leg = null
        failure = null
        startLocationUpdates(scope)
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
        target = next
        phase = Phase.GUIDING
        leg = null
        scope.launch { fetchLeg() }
    }

    /** "여기 도착함" — GPS 판정을 기다리지 않고 사람이 직접 확인한다. */
    fun markArrived() {
        val t = target ?: return
        phase = Phase.ARRIVED
        onArrived?.invoke(t)
    }

    fun end() {
        target = null
        leg = null
        phase = Phase.PLAN
        stopLocationUpdates()
    }

    /** 실패했을 때 "다시 시도" — iOS `RouteEditorTrip.tripDetail`의 재시도 단추. */
    fun retry(scope: CoroutineScope) {
        scope.launch { fetchLeg() }
    }

    suspend fun fetchLeg() {
        val t = target ?: return
        val (lat, lng) = here ?: return
        val itemId = t.serverItemId ?: return
        asking = true
        runCatching {
            withContext(Dispatchers.IO) {
                navigationApi.getNextLeg(deviceId, NextLegRequest(courseId = courseId, itemId = itemId, latitude = lat, longitude = lng))
            }
        }.onSuccess {
            leg = it
            failure = null
        }.onFailure {
            failure = ApiFailure.of(it).message
        }
        asking = false
    }

    private fun startLocationUpdates(scope: CoroutineScope) {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        if (!context.hasLocationPermission) return
        stopLocationUpdates()
        val relay =
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    here = location.latitude to location.longitude
                    // 기록은 보기 토글과 무관하게 늘 남는다 — 안내 중이면 언제나
                    // (iOS `FootprintStore.record` 주석). 보기는 지도에 그릴지만 가린다.
                    footprints.record(location.latitude, location.longitude)
                    checkArrival()
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
    }

    private fun stopLocationUpdates() {
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
        if (meters <= arrivalMeters) {
            phase = Phase.ARRIVED
            onArrived?.invoke(t)
        }
    }
}
