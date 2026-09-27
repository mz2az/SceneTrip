package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.sceneapi.client.model.NextLeg
import com.mz2az.scenetrip.sceneapi.client.model.RouteLeg

/**
 * 한 구간의 이동 수단 — iOS `RouteNavModels.swift`의 `RouteLegMode`.
 *
 * 도보·대중교통은 **앱이 고르지 않는다.** 서버(`POST /navigation/next-leg`, 카카오)가
 * 한 경로 안에 걷는 구간과 타는 구간을 섞어 주고, 앱은 구간마다 이 갈래로 나눠 칩을
 * 그린다. 앞서 Android는 요약 한 줄만 보여 줘서 버스를 타는 경로도 전부 도보처럼
 * 보였다(2026-09-28 사용자 지적: "왜 전부 도보야? 대중교통은?").
 */
enum class RouteLegMode {
    WALK,

    /** 계약 `vehicleType`이 마을·간선·지선·광역·직행·버스. 지하철과 갈라 그린다. */
    BUS,
    SUBWAY,

    /** 그 밖의 탈것(기차·고속버스·해운) — 종류를 모르는 대중교통도 여기. */
    TRANSIT,
    ;

    val isVehicle: Boolean get() = this != WALK

    companion object {
        fun from(
            mode: RouteLeg.Mode,
            vehicleType: String?,
        ): RouteLegMode {
            if (mode == RouteLeg.Mode.walk) return WALK
            val kind = vehicleType.orEmpty()
            if (listOf("지하철", "전철", "경전철", "SUBWAY").any { kind.contains(it) }) return SUBWAY
            if (listOf("버스", "마을", "간선", "지선", "광역", "직행", "순환", "BUS").any { kind.contains(it) }) return BUS
            return TRANSIT
        }
    }
}

/** 칩 하나에 쓸 문구. 계약은 재료만 주고 문장은 앱이 조립한다(iOS `RouteLeg.init(contract:)`). */
data class RouteLegChip(
    val mode: RouteLegMode,
    val text: String,
    val hasStairs: Boolean,
)

fun RouteLeg.toChip(): RouteLegChip {
    val pieces =
        listOfNotNull(
            seconds?.let { "${maxOf(1, it / 60)}분" },
            meters?.let { "$it m" },
            stopCount?.let { "$it 정거장" },
        )
    val kind = RouteLegMode.from(mode, vehicleType)
    // 탈것은 **노선이 제목**이다(「간선 150」「지하철 3호선」) — 안내문·정거장·시간은 설명으로.
    // 도보는 거리·시간이 칩에 들어가고, 없을 때만 안내문을 쓴다.
    val route = listOfNotNull(vehicleType, vehicleName).filter { it.isNotEmpty() }.joinToString(" ")
    val text =
        if (kind.isVehicle && route.isNotEmpty()) {
            (listOf(route, guidance) + pieces).filter { it.isNotEmpty() }.joinToString(" · ")
        } else {
            pieces.joinToString(" · ").ifEmpty { guidance }
        }
    return RouteLegChip(kind, text, hasStairs)
}

/**
 * 「환승 1회 · 도보 300 m · 1,450원」. **모르는 도보 거리를 0으로 두지 않는다** —
 * 카카오 후보의 절반 가까이가 도보 구간 없이 오는데, 0 m 로 적으면 「안 걸어도 되는
 * 경로」처럼 보인다(iOS `RouteNavResult.summaryLine` 주석).
 */
fun NextLeg.summaryLine(): String {
    val parts = mutableListOf("환승 ${transfers}회")
    parts += walkMeters?.let { "도보 $it m" } ?: "도보 정보 없음"
    fareWon?.takeIf { it > 0 }?.let { parts += "%,d원".format(it) }
    return parts.joinToString(" · ")
}
