package com.mz2az.scenetrip.routetab

import java.net.URLEncoder
import java.util.Locale

/** 지도 앱으로 넘기는 대중교통 길찾기. 목적지가 유효할 때만 링크를 만든다. */
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

    fun kakaoApp(
        start: Spot?,
        end: Spot,
    ): String? {
        if (!end.valid || start?.valid == false) return null
        val origin = start?.let { "sp=${it.lat},${it.lng}&" }.orEmpty()
        return "kakaomap://route?${origin}ep=${end.lat},${end.lng}&by=publictransit"
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

    fun naver(
        start: Spot?,
        end: Spot,
    ): String? {
        if (!end.valid || start?.valid == false) return null
        val origin = start?.let { "slat=${it.lat}&slng=${it.lng}&sname=${it.label}&" }.orEmpty()
        return "nmap://route/public?${origin}dlat=${end.lat}&dlng=${end.lng}&dname=${end.label}&appname=com.mz2az.scenetrip"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
