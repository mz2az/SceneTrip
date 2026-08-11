package com.mz2az.scenetrip.searchtab

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap

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
    onMapReady: (NaverMap) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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

    AndroidView(
        factory = { mapView },
        modifier = modifier,
        // `update` 가 아니라 여기서 한 번만 지도를 받는다. getMapAsync 를 재구성마다
        // 부르면 콜백이 쌓인다.
        onReset = null,
    ) { view ->
        view.getMapAsync(onMapReady)
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
