package com.mz2az.scenetrip.searchtab

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mz2az.scenetrip.data.LocationFix
import com.mz2az.scenetrip.data.LocationReading
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.ui.DisableDialogDim
import com.mz2az.scenetrip.ui.IOS
import androidx.compose.material3.Text as M3Text
import androidx.compose.ui.window.Dialog as ComposeDialog
import androidx.compose.ui.window.DialogProperties as ComposeDialogProperties

/**
 * 「내 위치」 를 눌렀는데 **뜻대로 되지 않은** 경우 (MZ2AZ-252).
 *
 * iOS `LocateAlert.swift` 의 `LocateOutcome` 과 **한 짝이다** — 갈래도 문구도 같아야
 * 한다. 성공에 해당하는 값이 없는 것은 일부러다: 성공하면 지도가 날아가므로 사용자가
 * 눈으로 안다.
 */
enum class LocateOutcome {
    /** 권한이 거부됐다. 앱 안에서는 풀 수 없고 설정으로 보내야 한다. */
    DENIED,

    /** 권한은 있는데 좌표를 못 얻었다. 실내·위치 끔에서 실제로 난다. */
    FAILED,
}

/**
 * 지도 SDK 에 좌표를 먹이는 자리.
 *
 * SDK 에 `FusedLocationSource` 가 딸려 있지만 **구글 플레이 서비스를 요구한다.**
 * 우리에게 필요한 것은 파란 점 하나뿐이라 의존성을 하나 더 들이지 않고 플랫폼
 * `LocationManager` 로 직접 만든다 — iOS 가 `CLLocationManager` 를 쓰는 것과 짝이다.
 *
 * 받은 값을 그대로 넘기지 않고 [LocationFix] 로 거른다(MZ2AZ-509) — 흐린 값으로 점이 튀지 않게,
 * 더 나은 값이 오면 점이 따라가게.
 */
class PlatformLocationSource(
    private val context: Context,
) : com.naver.maps.map.LocationSource {
    private var updates: LocationListener? = null
    private var shown: LocationReading? = null

    override fun activate(listener: com.naver.maps.map.LocationSource.OnLocationChangedListener) {
        val manager = context.locationManager ?: return
        if (!context.hasLocationPermission) return
        shown = null
        val relay =
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    val reading = location.toReading()
                    if (!LocationFix.isUsable(reading) || !LocationFix.shouldReplace(shown, reading)) return
                    shown = reading
                    listener.onLocationChanged(location)
                }

                // API 26 에서는 이 셋이 **추상 메서드**라 안 구현하면 기기에서
                // AbstractMethodError 로 죽는다. API 30 부터 기본 구현이 생겼지만
                // minSdk 가 26 이므로 남겨 둔다.
                @Deprecated("API 29 에서 폐기됐지만 minSdk 26 때문에 필요하다")
                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: Bundle?,
                ) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) = Unit
            }
        updates = relay
        manager.enabledProviders.forEach { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, CONTINUOUS_INTERVAL_MS, CONTINUOUS_MIN_DISTANCE_M, relay, Looper.getMainLooper())
            }
        }
    }

    override fun deactivate() {
        updates?.let { context.locationManager?.removeUpdates(it) }
        updates = null
        shown = null
    }
}

/** 계속 받을 때의 간격(ms)·최소 이동(m). iOS 의 `distanceFilter = 5`. */
private const val CONTINUOUS_INTERVAL_MS = 1_000L
private const val CONTINUOUS_MIN_DISTANCE_M = 5f

/** 안드로이드 위치를 규칙이 읽는 [LocationReading] 으로. 정확도를 모르면 무효(-1) 값이다. */
fun Location.toReading(): LocationReading = LocationReading(latitude, longitude, if (hasAccuracy()) accuracy else -1f, time)

/**
 * 위치를 **계속** 받으며 [LocationFix] 규칙을 통과한 값만 넘긴다 (MZ2AZ-509). iOS 가
 * `startUpdatingLocation()` + `LocationFix.shouldReplace` 로 하는 일이다.
 *
 * - 첫 쓸 만한 값은 [onFix] 에 `previous == null` 로 간다. 이후 더 나은(또는 조금 덜 정확해도 더 새)
 *   값만 이전 값과 함께 온다.
 * - 켜진 공급자 전부(네트워크 + GPS)에 건다 — 대략값이 먼저, 정확한 값이 나중에 와 갈아탄다.
 * - 켜진 공급자가 모두 꺼졌고 아직 자리를 모르면 [onUnavailable]. 이미 자리를 알면 조용히 지킨다
 *   (iOS 에서 계속 받는 중의 「아직 모름」을 실패로 띄우지 않는 것과 같다).
 */
class LocationTracker(
    private val context: Context,
) {
    /** 지금 내놓은 값(규칙을 통과한 최신). */
    var shown: LocationReading? = null
        private set

    /**
     * 이 받기 동안 **받을 때 쓸 만했던** 마지막 원본 — 규칙 통과 여부와 무관하다. 두 번째 「내 위치」 누름이 이 값으로 바로 반응한다.
     * 기동 직후 `getLastKnownLocation` 으로 얻은 낡은 값은 여기 들어오지 않는다(어제 있던 도시로 날아가는 것은 고장이다).
     */
    var latest: Location? = null
        private set

    /** 받기 시작한 시각(SystemClock). [resetShown] 이 갱신한다. */
    var startedAt: Long = 0L
        private set

    private var relay: LocationListener? = null
    private var onFix: ((Location, LocationReading?) -> Unit)? = null

    val isRunning: Boolean get() = relay != null

    /** @return 하나라도 걸었으면 true. 켜진 공급자가 없거나 모두 걸지 못하면 false. */
    fun start(
        minTimeMs: Long,
        minDistanceM: Float,
        onFix: (Location, LocationReading?) -> Unit,
        onUnavailable: () -> Unit = {},
    ): Boolean {
        stop()
        val manager = context.locationManager ?: return false
        val providers = manager.enabledProviders
        if (providers.isEmpty()) return false
        this.onFix = onFix
        resetShown()
        val listener =
            object : LocationListener {
                override fun onLocationChanged(location: Location) = deliver(location)

                @Deprecated("API 29 에서 폐기됐지만 minSdk 26 때문에 필요하다")
                override fun onStatusChanged(
                    provider: String?,
                    status: Int,
                    extras: Bundle?,
                ) = Unit

                override fun onProviderEnabled(provider: String) = Unit

                override fun onProviderDisabled(provider: String) {
                    if (shown == null && manager.enabledProviders.isEmpty()) onUnavailable()
                }
            }
        val started =
            providers.count { provider ->
                runCatching {
                    manager.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, Looper.getMainLooper())
                }.isSuccess
            } > 0
        if (!started) {
            this.onFix = null
            return false
        }
        relay = listener
        // 방금 전까지 받아 둔 값이 있으면 위성을 새로 잡기 전에 바로 쓴다 — 단 10 초 안의 값만([LocationFix.isUsable]).
        providers
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .sortedBy { it.time }
            .forEach { deliver(it) }
        return true
    }

    /** 새로 누른 것으로 친다 — 내놓은 값을 비우고 시각을 새로 센다. 받기는 이어 간다. */
    fun resetShown() {
        shown = null
        startedAt = SystemClock.elapsedRealtime()
    }

    /**
     * 가진 최신 값을 다시 내보낸다. 받는 중에는 같은 자리를 또 보내 주지 않아서다.
     *
     * **나이를 따지지 않는다.** iOS 는 두 번째 누름에서 `locationManager.location` 이 10초 안일 때만 쓰는데, iOS 는 받는 동안 값이 계속 와서
     * 그 값이 늘 새것이다. 안드로이드는 5 m 필터라 가만히 있으면 새 값이 안 와 10초가 지난 값은 버려졌고, 지도를 밀어 둔 뒤 다시 눌러도 아무
     * 일이 없었다. 이 받기가 **받을 때 쓸 만했던** 값이고(위 [latest]) 계속 받는 중이라 기기가 크게 움직였다면 새 값이 이미 와 있다.
     */
    fun replayLatest() {
        latest?.let { deliver(it, ignoreAge = true) }
    }

    /** 규칙을 통과한 값만 [onFix] 로 보낸다. */
    internal fun deliver(
        location: Location,
        ignoreAge: Boolean = false,
    ) {
        val reading = location.toReading()
        val usable = if (ignoreAge) reading.accuracyMeters >= 0 else LocationFix.isUsable(reading)
        if (!ignoreAge && usable) latest = location
        if (!usable || !LocationFix.shouldReplace(shown, reading)) return
        val previous = shown
        shown = reading
        onFix?.invoke(location, previous)
    }

    fun stop() {
        relay?.let { context.locationManager?.removeUpdates(it) }
        relay = null
        onFix = null
    }
}

/**
 * 좌표를 **잠깐 받아 가장 나은 값을 고른다.** iOS `RouteLocator.start()` 와 같다 (MZ2AZ-509).
 *
 * 처음 쓸 만한 값을 바로 내놓고, 더 정확한 값이 오면 [onLocated] 를 다시 불러 갈아 끼운다.
 * 20 m 안쪽이 오거나 [LocationFix.ONE_SHOT_WAIT_MS] 가 지나면 끊는다. 3 초가 지나도 값이 없으면 첫 값까지
 * 기다렸다가 그 값을 내놓고 끊는다.
 *
 * 마지막으로 알려진 위치는 **10 초 안의 것만** 쓴다 — 어제 있던 도시로 날아가는 것은 사용자 눈에 고장이다
 * (에뮬레이터에서 LA 를 넣었는데 마운틴뷰로 날아갔던 일).
 */
fun Context.requestLocationOnce(
    onLocated: (Location) -> Unit,
    onFailure: (LocateOutcome) -> Unit,
) {
    if (!hasLocationPermission) {
        onFailure(LocateOutcome.DENIED)
        return
    }
    val tracker = LocationTracker(this)
    val handler = Handler(Looper.getMainLooper())
    var finished = false
    var waitedOut = false

    fun finish() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        tracker.stop()
    }

    val started =
        tracker.start(
            minTimeMs = 0L,
            minDistanceM = 0f,
            onFix = { location, _ ->
                if (!finished) {
                    onLocated(location)
                    if ((location.hasAccuracy() && location.accuracy <= LocationFix.GOOD_ENOUGH_METERS) || waitedOut) finish()
                }
            },
            onUnavailable = {
                if (!finished) {
                    finish()
                    onFailure(LocateOutcome.FAILED)
                }
            },
        )
    if (!started) {
        // 기기의 위치 기능 자체가 꺼져 있다. 권한 문제가 아니므로 설정으로 보내는
        // 안내를 띄우면 엉뚱한 곳으로 데려가게 된다.
        onFailure(LocateOutcome.FAILED)
        return
    }
    if (!finished) {
        handler.postDelayed({
            waitedOut = true
            if (tracker.shown != null) finish()
        }, LocationFix.ONE_SHOT_WAIT_MS)
    }
}

private val Context.locationManager: LocationManager?
    get() = getSystemService(Context.LOCATION_SERVICE) as? LocationManager

/**
 * 켜져 있는 공급자만. **`GPS_PROVIDER` 하나만 쓰면 실내에서 영영 안 온다** — 네트워크
 * 공급자가 훨씬 빨리 답한다.
 */
private val LocationManager.enabledProviders: List<String>
    get() =
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { isProviderEnabled(it) }.getOrDefault(false) }

/**
 * 대략적인 위치라도 허용돼 있는가.
 *
 * **FINE 만 보면 안 된다.** Android 12 부터 사용자가 「대략적인 위치」를 고를 수 있고,
 * 그때는 COARSE 만 켜진다. 촬영지를 찾는 데는 그것으로 충분하다.
 */
val Context.hasLocationPermission: Boolean
    get() =
        listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

/** 앱 설정 화면으로. 거부된 위치 권한은 앱 안에서 다시 물어볼 수 없다. */
fun Context.openAppSettings() {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/**
 * Android 12+ 에서 사용자가 「대략적인 위치」만 허용했는가. 이때는 COARSE 만 켜져 있어 네트워크 값(수백 m 어긋남)밖에
 * 못 받는다. iOS 의 `accuracyAuthorization == .reducedAccuracy` 와 같은 자리다.
 */
val Context.hasOnlyApproximateLocation: Boolean
    get() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED

/** 정확한 위치를 다시 물은 적이 있는가(앱 실행 동안). 거절한 사람에게 누를 때마다 되묻지 않는다. */
private var askedPreciseThisRun = false

/**
 * 「내 위치」 버튼이 부르는 것. 권한이 없으면 **먼저 물어보고** 그 대답에 이어서 한다.
 *
 * iOS 는 `CLLocationManager` 델리게이트가 이 순서를 맡는다. 안드로이드는 권한 대답이
 * 액티비티 결과로 오므로 컴포저블 쪽에 둔다.
 *
 * 「대략적인 위치」만 허용돼 있으면 정확한 위치를 **다시 요청**한다 (MZ2AZ-509, iOS
 * `LocationFix.requestFullAccuracyIfReduced`). 거절해도 받은 정확도로 이어 간다.
 *
 * [onRefined] 를 주면 **계속 받는다.** 처음 쓸 만한 값은 [onLocated] 로 가고(지도가 날아간다), 그 뒤 더 나은
 * 값이 오면 이전 값과 누른 뒤 지난 시간과 함께 [onRefined] 로 온다 — 검색 지도 「내 위치」. 다시 누르면
 * 가진 최신 값으로 바로 [onLocated] 가 불린다. 주지 않으면 잠깐 받아 가장 나은 값을 고르고 끊는다
 * ([requestLocationOnce]).
 */
@Composable
fun rememberLocate(
    onLocated: (Location) -> Unit,
    onFailure: (LocateOutcome) -> Unit,
    onRefined: ((Location, LocationReading, Long) -> Unit)? = null,
): () -> Unit {
    val context = LocalContext.current
    val located by rememberUpdatedState(onLocated)
    val failed by rememberUpdatedState(onFailure)
    val refined by rememberUpdatedState(onRefined)
    val continuous = onRefined != null
    val tracker = remember { LocationTracker(context) }
    DisposableEffect(tracker) { onDispose { tracker.stop() } }

    fun begin() {
        if (!continuous) {
            context.requestLocationOnce({ located(it) }, { failed(it) })
            return
        }
        // 새로 누른 것으로 친다. 이미 받는 중이면 같은 자리를 다시 보내 주지 않으므로(두 번째 누름이 아무 일도 안 하던 것),
        // 가진 최신 값으로 바로 반응하고 더 나은 값은 이어서 받는다.
        if (tracker.isRunning) {
            tracker.resetShown()
            tracker.replayLatest()
            return
        }
        val started =
            tracker.start(
                minTimeMs = CONTINUOUS_INTERVAL_MS,
                minDistanceM = CONTINUOUS_MIN_DISTANCE_M,
                onFix = { location, previous ->
                    if (previous == null) {
                        located(location)
                    } else {
                        refined?.invoke(location, previous, SystemClock.elapsedRealtime() - tracker.startedAt)
                    }
                },
                onUnavailable = { failed(LocateOutcome.FAILED) },
            )
        if (!started) failed(LocateOutcome.FAILED)
    }

    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            // 하나라도 허용돼 있으면 진행한다 — 「대략적인 위치」만 고른 경우다. 정확한 위치를 거절해도 이어 간다.
            if (context.hasLocationPermission) begin() else failed(LocateOutcome.DENIED)
        }
    return {
        if (!context.hasLocationPermission) {
            launcher.launch(LOCATION_PERMISSIONS)
        } else if (context.hasOnlyApproximateLocation && !askedPreciseThisRun) {
            askedPreciseThisRun = true
            launcher.launch(LOCATION_PERMISSIONS)
        } else {
            begin()
        }
    }
}

private val LOCATION_PERMISSIONS =
    arrayOf(
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION,
    )

/**
 * 실패했을 때만 뜨는 안내. **iOS `UIAlertController` 모양을 따라 그린다.**
 *
 * Material 의 `AlertDialog` 를 쓰면 이 화면만 다른 앱처럼 보인다 — Material 은 글을
 * 왼쪽으로 붙이고 버튼을 오른쪽 아래로 몰지만, iOS 는 **가운데 정렬에 버튼이 가로로
 * 반씩** 나뉜다. 문구는 iOS `LocateAlert.swift` 와 한 글자도 다르면 안 된다.
 */
@Composable
fun LocateFailureDialog(
    outcome: LocateOutcome,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val title =
        when (outcome) {
            LocateOutcome.DENIED -> tr("위치 권한이 필요합니다")
            LocateOutcome.FAILED -> tr("현재 위치를 찾지 못했습니다")
        }
    val message =
        when (outcome) {
            LocateOutcome.DENIED -> tr("설정에서 위치 접근을 허용하면 현재 위치를 보여 드립니다.")
            LocateOutcome.FAILED -> tr("실내이거나 신호가 약할 수 있습니다. 잠시 후 다시 눌러 주세요.")
        }

    ComposeDialog(
        onDismissRequest = onDismiss,
        properties = ComposeDialogProperties(usePlatformDefaultWidth = false),
    ) {
        // 딤은 우리가 칠한다 — 안 끄면 기본 60% 와 겹쳐 두 배로 어두워진다
        // (장면 팝업에서 겪은 것과 같은 함정이다).
        DisableDialogDim()
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = IOS.DIM)),
        ) {
            Column(
                modifier =
                    Modifier
                        .width(IOS.alertWidth)
                        .clip(RoundedCornerShape(IOS.alertCorner))
                        .background(IOS.popupSurface),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                ) {
                    M3Text(
                        title,
                        style = IOS.headline,
                        color = IOS.label,
                        textAlign = TextAlign.Center,
                    )
                    M3Text(
                        message,
                        style = IOS.footnote,
                        color = IOS.label,
                        textAlign = TextAlign.Center,
                    )
                }
                Box(Modifier.fillMaxWidth().height(IOS.hairline).background(IOS.separator))
                when (outcome) {
                    LocateOutcome.DENIED -> {
                        Row(Modifier.height(IOS.alertButton)) {
                            // iOS 는 `.cancel` 이 **굵게** 온다. 왼쪽이 취소다.
                            AlertButton(tr("닫기"), bold = true, modifier = Modifier.weight(1f), onClick = onDismiss)
                            Box(Modifier.width(IOS.hairline).fillMaxSize().background(IOS.separator))
                            AlertButton(tr("설정 열기"), modifier = Modifier.weight(1f)) {
                                context.openAppSettings()
                                onDismiss()
                            }
                        }
                    }

                    LocateOutcome.FAILED -> {
                        Box(Modifier.height(IOS.alertButton)) {
                            AlertButton(tr("확인"), bold = true, modifier = Modifier.fillMaxSize(), onClick = onDismiss)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlertButton(
    label: String,
    modifier: Modifier = Modifier,
    bold: Boolean = false,
    onClick: () -> Unit,
) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize().clickable(onClick = onClick)) {
        M3Text(
            label,
            style = IOS.body.copy(fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
            color = IOS.accent,
        )
    }
}
