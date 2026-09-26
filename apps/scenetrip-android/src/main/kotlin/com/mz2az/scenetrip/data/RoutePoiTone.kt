package com.mz2az.scenetrip.data

import androidx.compose.ui.graphics.Color
import com.mz2az.scenetrip.ui.IOS

/**
 * 편의시설 갈래. iOS `RouteTab/RoutePoiTone.swift`의 `RoutePoiGroup`.
 *
 * RouteTab 전체(챗봇 응답의 편의시설 목록 등)는 아직 Android 에 없다 — 이 파일은
 * 온보딩 넷째 장(반경 POI)이 지도·목록과 **같은 색**을 쓰기 위한 최소 이식이다.
 */
enum class RoutePoiGroup { FOOD, STAY, TRANSIT, SIGHT }

/**
 * 갈래의 색. **지도 점과 목록 점이 같은 색이어야** 눈으로 이어진다 — 온보딩도 이걸
 * 그대로 쓴다.
 *
 * 배정(2026-08-27 확정, iOS 와 동일): 음식점·카페=빨강, 숙소=초록, 교통=노랑, 명소=파랑.
 * 보라는 안 쓴다 — 코스 번호 핀과 AI 말풍선이 이미 보라다.
 */
object RoutePoiTone {
    fun of(group: RoutePoiGroup): Color =
        when (group) {
            RoutePoiGroup.FOOD -> Color(0xFFE32933)
            RoutePoiGroup.STAY -> Color(0xFF21A85E)
            RoutePoiGroup.TRANSIT -> Color(0xFFEDBA0D)
            RoutePoiGroup.SIGHT -> IOS.accent
        }
}
