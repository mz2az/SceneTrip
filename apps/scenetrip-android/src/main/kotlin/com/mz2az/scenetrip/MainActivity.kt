package com.mz2az.scenetrip

import android.app.Activity
import android.os.Bundle
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraPosition
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
     * 좌표와 줌은 iOS 와 **같은 값**이다 (`NaverMapView.swift:48`). 두 앱이 다른 데를
     * 비추면 같은 제품으로 보이지 않는다.
     */
    private fun configure(map: NaverMap) {
        map.cameraPosition = CameraPosition(SEOUL, SEOUL_ZOOM)
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
        val SEOUL = LatLng(37.5666, 126.9784)
        const val SEOUL_ZOOM = 11.0
    }
}
