package com.mz2az.scenetrip.routetab

import java.net.URLEncoder
import java.util.Locale

/**
 * 지도 앱으로 넘기는 대중교통 길찾기. 목적지가 유효할 때만 링크를 만든다.
 *
 * **앱은 현재 기기 좌표를 주소에 싣지 않는다**(MZ2AZ-472) — 출발지를 모르는 `start == null` 로 부르면 카카오맵 웹의 목적지만 있는
 * 링크(`/link/to/`)를 쓰고, 출발지를 정하는 일은 외부 지도 앱이 한다. 출발지가 있는 꼴은 다른 출처의 좌표(예: 저장한 장소)를 위해 남겨 둔다.
 */
object ExternalDirections {
    data class Spot(
        val name: String,
        val latitude: Double,
        val longitude: Double,
    ) {
        val valid: Boolean get() = latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0
        val lat: String get() = String.format(Locale.US, "%.6f", latitude)
        val lng: String get() = String.format(Locale.US, "%.6f", longitude)
        val label: String get() =
            encode(
                name
                    .replace(',', ' ')
                    .replace('/', ' ')
                    .trim()
                    .ifEmpty { "-" },
            )
    }

    /** 앱 스킴은 출발지가 있어야 쓴다(문서에 출발지를 뺀 꼴이 없다). 출발지를 모르면 `null` — 부르는 쪽이 웹 주소로 간다. */
    fun kakaoApp(
        start: Spot?,
        end: Spot,
    ): String? {
        if (start == null || !end.valid || !start.valid) return null
        return "kakaomap://route?sp=${start.lat},${start.lng}&ep=${end.lat},${end.lng}&by=publictransit"
    }

    fun kakaoWeb(
        start: Spot?,
        end: Spot,
    ): String? {
        if (!end.valid || start?.valid == false) return null
        val destination = "${end.label},${end.lat},${end.lng}"
        return if (start ==
            null
        ) {
            "https://map.kakao.com/link/to/$destination"
        } else {
            "https://map.kakao.com/link/by/traffic/${start.label},${start.lat},${start.lng}/$destination"
        }
    }

    /** 네이버 지도도 출발지가 있을 때만 단추를 세운다(iOS `ExternalDirections.offers`). 출발지를 모르면 `null`. */
    fun naver(
        start: Spot?,
        end: Spot,
    ): String? {
        if (start == null || !end.valid || !start.valid) return null
        return "nmap://route/public?slat=${start.lat}&slng=${start.lng}&sname=${start.label}" +
            "&dlat=${end.lat}&dlng=${end.lng}&dname=${end.label}&appname=com.mz2az.scenetrip"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
