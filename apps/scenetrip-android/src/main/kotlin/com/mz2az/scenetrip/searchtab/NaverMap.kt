package com.mz2az.scenetrip.searchtab

import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.Marker

/**
 * 네이버 지도를 Compose 안에 넣는다.
 *
 * iOS 의 `NaverMapView.swift` 와 **같은 자리**다. 그쪽은 `UIViewRepresentable`,
 * 이쪽은 `AndroidView` — 둘 다 "선언형 UI 안에 명령형 뷰를 끼워 넣는" 같은 문제를
 * 각 플랫폼이 푼 방식이다. 두 파일이 같은 이름의 상수를 들고 있어야 하는 이유가
 * 여기 있다.
 *
 * **지도만 Compose 밖에 있다.** 네이버가 Compose 용 지도를 내놓지 않아서다. 나머지
 * 화면(검색창·시트·목록)은 전부 Compose 로 짓는다.
 */
@Composable
fun NaverMapCanvas(
    modifier: Modifier = Modifier,
    sheetHeight: Dp = 0.dp,
    onMapReady: (NaverMap) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var map by remember { mutableStateOf<NaverMap?>(null) }

    // `remember` 없이 만들면 **재구성마다 지도가 새로 생긴다.** 화면이 깜빡이는
    // 정도가 아니라 타일을 매번 다시 받아 오고 카메라 위치도 초기화된다.
    val mapView =
        remember {
            MapView(context).apply {
                // 액티비티의 onCreate 를 그대로 받아 넘길 수 없다 — 이 컴포저블이
                // 액티비티보다 늦게 붙기 때문이다. 이미 지나간 콜백이라 여기서 직접
                // 부른다. 인자가 null 인 것은 복원할 상태가 없다는 뜻이다.
                onCreate(null)
            }
        }

    // MapView 는 액티비티 생명주기를 스스로 따라가지 못한다. 전달하지 않으면 화면을
    // 벗어났다 돌아왔을 때 지도가 검게 남거나 메모리를 붙잡고 있는다.
    //
    // 뷰 방식에서는 액티비티가 자기 콜백에서 하나씩 넘겨 줬다. Compose 에는 그
    // 콜백이 없으므로 생명주기를 직접 관찰한다.
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    // 시트가 덮는 만큼 지도의 여백을 준다. iOS `applyInset` 과 같은 계산이다.
    //
    // **카메라 여백과 로고 여백을 따로 둔다.** 시트를 끝까지 올려도 카메라가 화면
    // 절반 아래로는 밀리지 않아야 하고(그러면 핀이 위쪽 띠에 몰린다), 반대로 로고는
    // 시트에 가리면 안 되므로 시트 높이를 그대로 따라가야 한다.
    val density = LocalDensity.current
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    DisposableEffect(map, sheetHeight) {
        val target = map
        if (target != null) {
            // 화면 높이는 뷰가 아니라 설정에서 읽는다 — 이 효과가 도는 시점에
            // MapView 가 아직 배치되지 않아 height 가 0 인 경우가 있다.
            val heightPx = with(density) { screenHeight.toPx() }
            val sheetPx = with(density) { sheetHeight.toPx() }.toInt()
            val cameraBottom = minOf(sheetPx, (heightPx * 0.48f).toInt())
            // **다섯째 인자가 핵심이다.** 네 인자짜리는 여백을 주면서 카메라를 함께
            // 옮겨, 남한 전체로 맞춰 둔 화면이 위로 밀린다(실측 — 평양이 보였다).
            // `keepCamera = true` 면 보이는 화면은 그대로 두고 여백만 바뀐다 —
            // iOS 의 `contentInset` 과 같은 뜻이다.
            target.setContentPadding(
                0,
                with(density) { 108.dp.toPx() }.toInt(),
                0,
                cameraBottom,
                true,
            )
            val logoBottom = maxOf(0, sheetPx - cameraBottom) + with(density) { 6.dp.toPx() }.toInt()
            val side = with(density) { 4.dp.toPx() }.toInt()
            target.uiSettings.setLogoMargin(side, 0, side, logoBottom)
        }
        onDispose {}
    }

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        // `update` 가 아니라 여기서 한 번만 지도를 받는다. getMapAsync 를 재구성마다
        // 부르면 콜백이 쌓인다.
        onReset = null,
    ) { view ->
        view.getMapAsync { ready ->
            // iOS `makeUIView` 와 같은 설정이다 (NaverMapView.swift:93~96).
            //
            // 줌 버튼(＋/−)을 끈다 — iOS 에 없다. 대신 지도 위 원형 버튼이 그 몫을
            // 한다. 축척은 **켜 둔다**: "축척은 반드시 필요해, 지도잖아" 가 iOS 쪽
            // 결정이었다.
            ready.uiSettings.isZoomControlEnabled = false
            ready.uiSettings.isLocationButtonEnabled = false
            ready.uiSettings.isScaleBarEnabled = true
            ready.uiSettings.logoGravity =
                android.view.Gravity.BOTTOM or android.view.Gravity.START
            map = ready
            onMapReady(ready)
        }
    }
}

/**
 * 첫 진입 카메라 — **남한 전체**.
 *
 * 제주까지 담고 울릉도·독도는 뺐다. 그것까지 넣으면 동해가 화면의 절반을 차지해
 * 정작 촬영지가 몰린 서남부가 작아진다.
 *
 * 네 숫자는 iOS 의 `NaverMapView.swift` 안 `Coordinator.korea` 와 **같아야 한다.**
 * 한쪽만 고치면 두 앱의 첫 화면이 갈린다.
 */
val KOREA: LatLngBounds = LatLngBounds(LatLng(33.0, 125.8), LatLng(38.7, 129.8))

/** iOS 의 `NMFCameraUpdate(fit:padding:)` 에 준 값과 같다. */
const val KOREA_FIT_PADDING = 24

/** 첫 진입에서 [KOREA] 가 다 보이게 맞춘다. */
fun NaverMap.showWholeKorea() {
    moveCamera(CameraUpdate.fitBounds(KOREA, KOREA_FIT_PADDING))
}

/**
 * 지도에 핀을 꽂는다.
 *
 * **Compose 밖의 상태를 다룬다.** 마커는 Compose 트리에 들어가지 않고 지도 객체에
 * 직접 붙으므로, 재구성마다 다시 만들면 이전 것이 남아 겹친다. 그래서 `DisposableEffect`
 * 로 붙이고 떼는 짝을 명시한다 — 목록이 바뀌면 통째로 갈아 끼운다.
 *
 * 번호를 찍을지는 밖에서 정한다. "목록의 N 번 = 지도의 N 번" 이 성립하는 화면에서만
 * 번호가 뜻을 갖기 때문이다 — iOS 의 `numbersOnPins` 와 같은 판단이다.
 */
@Composable
fun MapPins(
    map: NaverMap?,
    places: List<PlaceSummary>,
    numbered: Boolean,
) {
    if (map == null) return
    val metrics = LocalContext.current.resources.displayMetrics
    DisposableEffect(map, places, numbered) {
        val markers =
            places.mapIndexed { index, place ->
                Marker().apply {
                    position = place.position
                    // **직접 그린 핀을 쓴다.** 네이버 기본 마커(초록 물방울)를 그대로
                    // 두면 iOS 의 하늘→보라 그러데이션 핀과 한눈에 달라 보인다.
                    icon = PinImage.numbered(if (numbered) index + 1 else null, metrics)
                    // 꼬리 끝이 좌표를 가리켜야 한다. 기본 앵커는 그림 가운데라 핀이
                    // 실제 위치보다 위에 뜬다. iOS 는 꼬리를 45/50 지점에 둔다.
                    anchor = PointF(0.5f, 45f / 50f)
                    captionText = place.name
                    this.map = map
                }
            }
        onDispose { markers.forEach { it.map = null } }
    }
}
