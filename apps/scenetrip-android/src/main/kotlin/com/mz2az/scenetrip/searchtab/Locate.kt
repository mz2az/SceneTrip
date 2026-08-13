package com.mz2az.scenetrip.searchtab

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
 */
class PlatformLocationSource(
    private val context: Context,
) : com.naver.maps.map.LocationSource {
    private var updates: LocationListener? = null

    override fun activate(listener: com.naver.maps.map.LocationSource.OnLocationChangedListener) {
        val manager = context.locationManager ?: return
        if (!context.hasLocationPermission) return
        val relay =
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
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
                manager.requestLocationUpdates(provider, 2_000L, 5f, relay, Looper.getMainLooper())
            }
        }
    }

    override fun deactivate() {
        updates?.let { context.locationManager?.removeUpdates(it) }
        updates = null
    }
}

/**
 * 좌표를 **한 번만** 받아 온다. iOS 의 `requestLocation()` 에 해당한다.
 *
 * 마지막으로 알려진 위치가 있으면 그것부터 쓴다 — 위성을 새로 잡으면 몇 초가 걸리고,
 * 그동안 화면이 멈춘 것처럼 보인다. 촬영지를 찾아 주는 앱이라 몇십 미터 오차는
 * 문제가 되지 않는다.
 */
fun Context.requestLocationOnce(
    onLocated: (Location) -> Unit,
    onFailure: (LocateOutcome) -> Unit,
) {
    if (!hasLocationPermission) {
        onFailure(LocateOutcome.DENIED)
        return
    }
    val manager = locationManager
    val providers = manager?.enabledProviders.orEmpty()
    if (manager == null || providers.isEmpty()) {
        // 기기의 위치 기능 자체가 꺼져 있다. 권한 문제가 아니므로 설정으로 보내는
        // 안내를 띄우면 엉뚱한 곳으로 데려가게 된다.
        onFailure(LocateOutcome.FAILED)
        return
    }

    // **오래된 것은 버린다.** 처음에는 마지막으로 알려진 위치를 그냥 썼는데,
    // 에뮬레이터에서 LA 를 넣고 눌렀더니 카메라가 **마운틴뷰**(기본 좌표)로 날아갔다.
    // 실기기에서도 어제 있던 도시로 날아가는 것과 같은 일이라, 사용자 눈에는 그냥
    // 고장이다. iOS 의 `requestLocation()` 은 새 좌표를 받아 오므로 이쪽만 낡은 값을
    // 쓰면 두 앱이 갈린다.
    val known =
        providers
            .mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time <= FRESH_ENOUGH_MS }
            .maxByOrNull { it.time }
    if (known != null) {
        onLocated(known)
        return
    }

    // 알려진 위치가 없다 — 한 건만 받고 바로 끊는다. 계속 받으면 배터리를 먹는다.
    var done = false
    val once =
        object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (done) return
                done = true
                manager.removeUpdates(this)
                onLocated(location)
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
    val started =
        providers.any { provider ->
            runCatching {
                manager.requestLocationUpdates(provider, 0L, 0f, once, Looper.getMainLooper())
            }.isSuccess
        }
    if (!started) onFailure(LocateOutcome.FAILED)
}

/**
 * 마지막으로 알려진 위치를 그대로 써도 되는 나이.
 *
 * 2 분이다. 촬영지를 찾는 앱이라 걸어서 움직인 정도는 문제가 되지 않지만, 그보다
 * 오래된 값은 **다른 동네**일 수 있다. 이보다 낡았으면 새로 받아 온다 — 몇 초 기다리는
 * 편이 엉뚱한 곳으로 날아가는 것보다 낫다.
 */
private const val FRESH_ENOUGH_MS = 2 * 60 * 1000L

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
 * 「내 위치」 버튼이 부르는 것. 권한이 없으면 **먼저 물어보고** 그 대답에 이어서 한다.
 *
 * iOS 는 `CLLocationManager` 델리게이트가 이 순서를 맡는다. 안드로이드는 권한 대답이
 * 액티비티 결과로 오므로 컴포저블 쪽에 둔다.
 */
@Composable
fun rememberLocate(
    onLocated: (Location) -> Unit,
    onFailure: (LocateOutcome) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            // 하나라도 허용되면 진행한다 — 「대략적인 위치」만 고른 경우다.
            if (granted.values.any { it }) {
                context.requestLocationOnce(onLocated, onFailure)
            } else {
                onFailure(LocateOutcome.DENIED)
            }
        }
    return {
        if (context.hasLocationPermission) {
            context.requestLocationOnce(onLocated, onFailure)
        } else {
            launcher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                ),
            )
        }
    }
}

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
            LocateOutcome.DENIED -> "위치 권한이 필요합니다"
            LocateOutcome.FAILED -> "현재 위치를 찾지 못했습니다"
        }
    val message =
        when (outcome) {
            LocateOutcome.DENIED -> "설정에서 위치 접근을 허용하면 현재 위치를 보여 드립니다."
            LocateOutcome.FAILED -> "실내이거나 신호가 약할 수 있습니다. 잠시 후 다시 눌러 주세요."
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
                            AlertButton("닫기", bold = true, modifier = Modifier.weight(1f), onClick = onDismiss)
                            Box(Modifier.width(IOS.hairline).fillMaxSize().background(IOS.separator))
                            AlertButton("설정 열기", modifier = Modifier.weight(1f)) {
                                context.openAppSettings()
                                onDismiss()
                            }
                        }
                    }

                    LocateOutcome.FAILED -> {
                        Box(Modifier.height(IOS.alertButton)) {
                            AlertButton("확인", bold = true, modifier = Modifier.fillMaxSize(), onClick = onDismiss)
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
