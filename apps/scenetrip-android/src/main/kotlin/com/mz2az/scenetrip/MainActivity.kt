package com.mz2az.scenetrip

import android.app.Activity
import android.os.Bundle
import com.naver.maps.geometry.LatLng
import com.naver.maps.geometry.LatLngBounds
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.MapView
import com.naver.maps.map.NaverMap

/**
 * 첫 화면. 지도를 띄우고 서울을 보여준다 (MZ2AZ-192).
 *
 * 작품검색 탭 전체는 계획서 §3 을 기준으로 iOS 와 **같은 규칙**으로 만든다 — 여기 없는
 * 동작을 어느 쪽에서도 임의로 만들지 않는다. 검색·자동완성·하단 시트·장바구니는
 * MZ2AZ-194 에서 이 위에 얹는다.
 */
class MainActivity : Activity() {
    private lateinit var mapView: MapView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 클라이언트 ID 는 여기서 넣지 않는다. SDK 가 **매니페스트의
        // com.naver.maps.map.NCP_KEY_ID 를 스스로 읽는다** (AndroidManifest.xml).
        //
        // iOS 는 코드에서 넣으므로(SceneTripApp.swift) 두 앱의 모양이 갈리지만,
        // 코드 주입을 시도했더니 SDK 안에서 죽었다 —
        //   java.lang.NullPointerException: String.replace(...) on a null object
        //   at NaverMapSdk$NcpKeyClient.a  ← setClient 안쪽
        // 각 플랫폼 SDK 가 정상으로 삼는 경로가 다르다고 보고 여기서는 문서 경로를
        // 따른다. 값이 소스에 박히지 않는 것은 양쪽 같다.
        mapView = MapView(this)
        setContentView(mapView)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(::configure)
    }

    /**
     * 첫 진입 카메라 (MZ2AZ-162 의 Android 몫).
     *
     * **남한 전체**를 비춘다. iOS 와 같은 범위여야 한다 — 두 앱이 다른 데를 비추면
     * 같은 제품으로 보이지 않는다.
     *
     * MZ2AZ-162 는 처음에 서울 중심으로 적혀 있었고 이 파일도 그렇게 만들어졌다.
     * 그 결정이 iOS 쪽에서 뒤집혔다 — 촬영지가 서울에만 있지 않아 지방 촬영지를 가진
     * 작품이 첫 화면에서 통째로 사라졌기 때문이다. 여기는 그때 함께 고쳐지지 않아
     * 한동안 서울을 비추고 있었다.
     */
    private fun configure(map: NaverMap) {
        map.moveCamera(CameraUpdate.fitBounds(KOREA, FIT_PADDING))
    }

    // MapView 는 액티비티 생명주기를 스스로 따라가지 못한다. 전달하지 않으면 화면을
    // 벗어났다 돌아왔을 때 지도가 검게 남거나 메모리를 붙잡고 있는다.
    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()
    }

    override fun onPause() {
        mapView.onPause()
        super.onPause()
    }

    override fun onStop() {
        mapView.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        mapView.onDestroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
    }

    private companion object {
        /**
         * 남한 전체가 들어오는 범위. 제주까지 담고 울릉도·독도는 뺐다 — 그것까지
         * 넣으면 동해가 화면의 절반을 차지해 정작 촬영지가 몰린 서남부가 작아진다.
         *
         * 네 숫자는 iOS 의 `NaverMapView.swift` 안 `Coordinator.korea` 와 **같아야
         * 한다.** 한쪽만 고치면 두 앱의 첫 화면이 갈린다.
         */
        val KOREA = LatLngBounds(LatLng(33.0, 125.8), LatLng(38.7, 129.8))

        /** iOS 의 `NMFCameraUpdate(fit:padding:)` 에 준 값과 같다. */
        const val FIT_PADDING = 24
    }
}
