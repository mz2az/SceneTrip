package com.mz2az.scenetrip.searchtab

import com.mz2az.scenetrip.data.tr
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 지도 핀 아래 이름표. iOS `Models/Wording.swift` 의 `PinCaption` (MZ2AZ-372).
 *
 * 영어 이름은 길다 — 「Korea National University of Arts Seokgwan-dong Campus …」 처럼 80자가 넘는 것이
 * 한 줄로 뻗어 옆 핀과 지도를 덮었다. 이름표는 **어느 핀인지 알아보는** 데 쓰는 것이라 앞머리면 된다 —
 * 전체 이름은 눌러 연 카드에 있다.
 */
object PinCaption {
    /** 이보다 길면 자른다. */
    const val LIMIT = 28

    /** 이름표의 폭(dp). iOS 는 pt 다. 넘으면 SDK 가 줄을 바꾼다. */
    const val WIDTH_DP = 132

    fun text(name: String): String {
        val trimmed = name.trim()
        if (trimmed.codePointCount(0, trimmed.length) <= LIMIT) return trimmed
        val end = trimmed.offsetByCodePoints(0, LIMIT - 1)
        return trimmed.substring(0, end).trim() + "…"
    }
}

/** 위도·경도 한 쌍. 지도 SDK 없이 규칙만 검사하려고 따로 둔다. */
data class MapCoordinate(
    val latitude: Double,
    val longitude: Double,
)

/**
 * 좌표가 완전히 같은 핀들을 **아주 조금씩 벌린다.** iOS `NaverMapView.Coordinator.spread`.
 *
 * 한 건물 안에 서로 다른 장소가 있으면(더현대서울 안의 카페·음식점, IFC 의 호텔과 몰) 좌표가 같게
 * 들어온다. 그대로 그리면 핀이 완전히 포개져 **맨 위 하나만 눌리고 나머지는 있는 줄도 모른다.**
 *
 * 묶어서 개수를 표시하는 방법도 있으나 「목록 3번 = 지도 3번」 규칙이 깨진다. 벌리는 방향은 위쪽부터
 * 시계 방향, 간격은 약 8m 다. 결과는 입력과 같은 순서·같은 길이다.
 */
object PinSpread {
    /** 위도 1도 ≈ 111km. 8m 를 도 단위로 바꾼 값이다. */
    private const val RADIUS_DEGREES = 8.0 / 111_000.0

    fun spread(pins: List<MapCoordinate>): List<MapCoordinate> {
        val groups = LinkedHashMap<MapCoordinate, MutableList<Int>>()
        pins.forEachIndexed { index, pin -> groups.getOrPut(pin) { mutableListOf() }.add(index) }
        val result = pins.toMutableList()
        for (group in groups.values) {
            if (group.size < 2) continue
            group.forEachIndexed { order, index ->
                val angle = 2 * PI * order / group.size
                val pin = pins[index]
                // 경도는 위도가 높을수록 같은 각도의 실제 거리가 짧아진다 — 나누지 않으면 한국 위도에서 가로로 더 벌어져 보인다.
                val lngScale = cos(pin.latitude * PI / 180)
                result[index] =
                    MapCoordinate(
                        pin.latitude + RADIUS_DEGREES * cos(angle),
                        pin.longitude + RADIUS_DEGREES * sin(angle) / lngScale,
                    )
            }
        }
        return result
    }
}

/** 「현 지도 내 성지 검색」이 켜졌을 때 단추 글자. iOS `nearbyCountLabel`. */
object NearbyLabel {
    /**
     * 받은 것이 전부면 「이 지도에서 12곳」, 상한에 걸려 일부면 「486곳 중 200곳」 — 받은 줄 수만 적으면
     * 이 화면에 그것뿐인 것으로 읽힌다(MZ2AZ-372).
     */
    fun text(
        loaded: Int,
        total: Int,
    ): String =
        if (loaded < total) {
            tr("이 지도에서 %2\$d곳 중 %1\$d곳 · 해제").format(loaded, total)
        } else {
            tr("이 지도에서 %d곳 · 해제").format(total)
        }
}
