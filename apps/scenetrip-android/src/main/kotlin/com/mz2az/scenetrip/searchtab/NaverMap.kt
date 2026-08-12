package com.mz2az.scenetrip.searchtab

import android.graphics.PointF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraAnimation
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
    searchBarInset: Dp = 108.dp,
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

    // **첫 진입 카메라.** `getMapAsync` 콜백은 뷰가 배치되기 전에 오고, 그때
    // `fitBounds` 를 부르면 크기가 0 인 화면을 기준으로 계산돼 한 단계 축소된
    // 값이 나온다(대조 검사 — iOS 1.0 km/dp 인데 2.18 km/dp 였다).
    //
    // 그래서 **한 프레임 기다렸다가** 맞춘다. 여백도 그 전에는 주지 않는다 —
    // `fitBounds` 는 여백을 뺀 영역에 맞추므로, 시트가 덮는 만큼 좁은 띠에
    // 한반도를 우겨넣게 된다. iOS 는 화면 전체를 기준으로 맞춘다.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    var fitted by remember { mutableStateOf(false) }
    LaunchedEffect(map, sheetHeight) {
        val target = map ?: return@LaunchedEffect
        if (fitted || sheetHeight <= 0.dp) return@LaunchedEffect
        withFrameNanos {}
        // **여백을 먼저 주고 그 다음에 맞춘다.**
        //
        // 2 차에서 여백 뒤에 맞췄더니 2.2 배 축소돼 보였는데, 진짜 원인은 여백이
        // 아니라 **뷰가 배치되기 전에 맞춘 것**이었다(크기 0 기준으로 계산됐다).
        // 한 프레임 기다리는 것으로 그것이 풀렸고, 이제는 여백을 함께 넣어야
        // iOS 와 **같은 자리**가 나온다 — iOS 도 `contentInset` 을 카메라 계산에
        // 넣기 때문이다. 3 차에서 축척은 맞았는데 세로 중심이 122 단위 어긋난
        // 것이 이 차이였다.
        // **여백 없이 맞춘 뒤 여백을 준다.**
        //
        // 여백을 먼저 주면 시트가 덮는 좁은 띠에 한반도를 우겨넣어 2 배 축소된다.
        // 여백 없이 맞추면 축척은 iOS 와 같아지지만(3 차 검사 0.7% 차) 세로 중심이
        // 122 단위 어긋난다 — iOS 는 `contentInset` 을 걸 때 화면이 함께 밀리기
        // 때문이다. 그래서 맞춘 뒤 **화면을 미는 쪽으로** 여백을 준다.
        target.setContentPadding(0, 0, 0, 0, true)
        target.showWholeKorea(density)
        applyInset(target, density, screenHeight, sheetHeight, searchBarInset, keepCamera = false)
        fitted = true
    }
    DisposableEffect(map, sheetHeight, fitted) {
        val target = map
        if (target != null && fitted) {
            applyInset(target, density, screenHeight, sheetHeight, searchBarInset)
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
            // **지도 글자를 한국어로 못 박는다.** 기본값은 기기 로케일을 따라가는데,
            // 영어 기기에서는 `Daegu 대구` 처럼 두 언어가 겹쳐 나와 지저분하고
            // iOS(한국어 기기)와도 갈린다. 두 앱이 같아야 하므로 값을 박는다.
            //
            // 외국인 대상 앱이므로 나중에 사용자 언어 설정을 따라가야 한다 —
            // 그때는 iOS 도 함께 바꾼다 (MZ2AZ-117).
            ready.setLocale(java.util.Locale.KOREAN)
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

/**
 * iOS 의 `NMFCameraUpdate(fit:padding:)` 에 준 값과 **같은 뜻**이어야 하는 여백.
 *
 * **단위가 다르다.** iOS 는 pt 를 받고 안드로이드는 **px** 를 받는다. 24 를 그대로
 * 넘기면 3배 밀도 화면에서 8dp 가 되어 지도가 iOS 보다 확대돼 보인다(실측 —
 * 서울 핀이 화면 위로 잘렸다). 그래서 dp 로 두고 쓸 때 곱한다.
 */
val KOREA_FIT_PADDING = 24.dp

/** 지금 보이는 범위가 남한을 **완전히 벗어났는가.** iOS `MapCamera.isOutsideKorea`. */
fun NaverMap.isOutsideKorea(): Boolean {
    val bounds = contentBounds
    val lngApart =
        bounds.eastLongitude < KOREA.westLongitude ||
            bounds.westLongitude > KOREA.eastLongitude
    val latApart =
        bounds.northLatitude < KOREA.southLatitude ||
            bounds.southLatitude > KOREA.northLatitude
    return lngApart || latApart
}

/**
 * 주어진 핀들이 **한 화면에 다 들어오게** 맞춘다.
 *
 * iOS `fitToken` 이 오를 때 하는 일이다 — 첫 화면에서 장소 탭으로 옮기면 인기
 * 10곳이 다 보여야 한다. 서울 중심 그대로면 강릉·포항이 화면 밖이라 목록의 절반이
 * 어디 있는지 보이지 않는다.
 */
fun NaverMap.fit(
    places: List<PlaceSummary>,
    density: Density,
    screenHeight: Dp,
    sheetHeight: Dp,
    searchBarInset: Dp,
) {
    if (places.isEmpty()) return
    // **한 곳이면 그 장소로 확대한다** — iOS `fit(_ pins:)` 가 `zoom(to:)` 로
    // 넘긴다(줌 16). 줌 14 로 해 뒀더니 같은 검색어에서 iOS 는 골목이 보이는데
    // 안드로이드는 경복궁·창덕궁까지 보였다(4 차 검사 — 3.2 배 차).
    if (places.size == 1) {
        zoomTo(places.first(), density, screenHeight, sheetHeight, searchBarInset)
        return
    }
    // iOS 는 **맞추기 전에 여백을 다시 건다.** 빠뜨리면 시트가 덮는 만큼을 셈에
    // 넣지 않아 결과가 조금씩 더 확대된다(4 차 검사 — 7.5% 차).
    applyInset(this, density, screenHeight, sheetHeight, searchBarInset)
    val bounds =
        LatLngBounds
            .Builder()
            .apply { places.forEach { include(it.position) } }
            .build()
    val padding = with(density) { KOREA_FIT_PADDING.roundToPx() }
    moveCamera(
        CameraUpdate
            .fitBounds(bounds, padding)
            .animate(CameraAnimation.Easing, 400L),
    )
}

/**
 * 선택한 장소 하나로 **확대**한다. iOS `zoom(to:)` — 줌 16, easeIn 0.5 초.
 */
fun NaverMap.zoomTo(
    place: PlaceSummary,
    density: Density,
    screenHeight: Dp,
    sheetHeight: Dp,
    searchBarInset: Dp,
) {
    applyInset(this, density, screenHeight, sheetHeight, searchBarInset)
    moveCamera(
        CameraUpdate
            .scrollAndZoomTo(place.position, 16.0)
            .animate(CameraAnimation.Easing, 500L),
    )
}

/**
 * 담은 장소가 가운데 오도록 **이동만** 한다 — 확대는 장소를 열 때만 한다.
 * iOS `center(on:)` — easeIn 0.4 초.
 */
fun NaverMap.centerOn(place: PlaceSummary) {
    moveCamera(
        CameraUpdate
            .scrollTo(place.position)
            .animate(CameraAnimation.Easing, 400L),
    )
}

/** 첫 진입에서 [KOREA] 가 다 보이게 맞춘다. */
fun NaverMap.showWholeKorea(density: Density) {
    val padding = with(density) { KOREA_FIT_PADDING.roundToPx() }
    moveCamera(CameraUpdate.fitBounds(KOREA, padding))
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
    onTap: (PlaceSummary) -> Unit = {},
) {
    if (map == null) return
    val metrics = LocalContext.current.resources.displayMetrics
    DisposableEffect(map, places, numbered, onTap) {
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
                    // **줌 13 이상에서만 이름표를 띄운다.** iOS 와 같은 값이다.
                    // 전국 뷰에서 이름을 다 그리면 "인천스마트밸리지식산업센터" 같은
                    // 긴 이름들이 서로 겹쳐 검은 뭉텅이가 된다(실측).
                    captionMinZoom = 13.0
                    setOnClickListener {
                        onTap(place)
                        true
                    }
                    this.map = map
                }
            }
        onDispose { markers.forEach { it.map = null } }
    }
}

/**
 * 시트가 덮는 만큼 지도에 여백을 준다. iOS `applyInset` 과 같은 계산이다.
 *
 * **카메라 여백과 로고 여백을 따로 둔다.** 시트를 끝까지 올려도 카메라가 화면
 * 절반 아래로는 밀리지 않아야 하고(그러면 핀이 위쪽 띠에 몰린다), 반대로 로고는
 * 시트에 가리면 안 되므로 시트 높이를 그대로 따라가야 한다.
 *
 * 다섯째 인자가 핵심이다. 네 인자짜리는 여백을 주면서 카메라를 함께 옮겨,
 * 맞춰 둔 화면이 밀린다(실측 — 평양이 보였다).
 */
internal fun applyInset(
    map: NaverMap,
    density: Density,
    screenHeight: Dp,
    sheetHeight: Dp,
    searchBarInset: Dp,
    keepCamera: Boolean = true,
) {
    with(density) {
        val heightPx = screenHeight.toPx()
        val sheetPx = sheetHeight.toPx().toInt()
        val cameraBottom = minOf(sheetPx, (heightPx * 0.48f).toInt())
        // 위 여백은 검색바가 덮는 만큼이다. iOS 는 108pt 를 쓰는데, 안드로이드는
        // 검색바가 상태바 아래에 놓여 그만큼 덜 덮는다 — 3 차에서 -122 였던 세로
        // 어긋남이 4 차에 +25.4 로 뒤집혔던 것이 이 차이다.
        // 위 여백은 **검색바가 덮는 높이**다. iOS 는 108 을 쓰는데 그것은 iOS
        // 검색바의 실제 높이에서 나온 값이라, 안드로이드에 그대로 옮기면 맞지
        // 않는다 — 108 로 뒀더니 초기 배율이 14.8% 어긋났다(6 차 검사).
        //
        // 그래서 **숫자를 옮기지 않고 뜻을 옮긴다.** 상태바 + 바깥 여백 + 바 높이를
        // 화면이 재서 넘겨 준다.
        val topInset = searchBarInset.toPx().toInt()
        map.setContentPadding(0, topInset, 0, cameraBottom, keepCamera)
        val logoBottom = maxOf(0, sheetPx - cameraBottom) + 6.dp.toPx().toInt()
        val side = 4.dp.toPx().toInt()
        map.uiSettings.setLogoMargin(side, 0, side, logoBottom)
    }
}
