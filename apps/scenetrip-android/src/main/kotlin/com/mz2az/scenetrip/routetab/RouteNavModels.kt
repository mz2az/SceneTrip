package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.tr
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
        /**
         * **종류 칸만으로는 지하철을 못 가린다.** 카카오는 1호선을 `vehicleType: "일반"`,
         * `vehicleName: "1호선"` 으로 보낸다(2026-09-28 실측, 서울역 → DDP) — 버스 종류가
         * 아니면 노선 이름의 「호선」·「…선」·「철도」로 한 번 더 본다.
         */
        fun from(
            mode: RouteLeg.Mode,
            vehicleType: String?,
            vehicleName: String?,
        ): RouteLegMode {
            if (mode == RouteLeg.Mode.walk) return WALK
            val kind = vehicleType.orEmpty()
            if (listOf("지하철", "전철", "경전철", "SUBWAY").any { kind.contains(it) }) return SUBWAY
            if (listOf("버스", "마을", "간선", "지선", "광역", "직행", "순환", "BUS").any { kind.contains(it) }) return BUS
            val name = vehicleName.orEmpty()
            if (name.contains("호선") || name.endsWith("선") || name.contains("철도")) return SUBWAY
            return TRANSIT
        }
    }
}

/**
 * 칩 하나 — iOS `RouteLeg`(화면 타입). 계약은 재료만 주고 문장은 앱이 조립한다.
 * 탈것은 노선이 제목이고 안내문·정거장·시간이 설명, 도보는 안내문이 제목이고
 * 거리·시간이 설명이다.
 */
data class RouteLegChip(
    val mode: RouteLegMode,
    val title: String,
    val detail: String,
    val hasStairs: Boolean,
    val meters: Int?,
    val seconds: Int?,
) {
    /** 칩에 적는 한 줄 — iOS `tripDetail`과 같은 조합. */
    val text: String
        get() =
            if (mode.isVehicle) {
                listOf(title, detail).filter { it.isNotEmpty() }.joinToString(" · ")
            } else {
                detail.ifEmpty { title }
            }
}

private fun pieces(
    seconds: Int?,
    meters: Int?,
    stops: Int?,
): List<String> =
    listOfNotNull(
        seconds?.let { tr("%d분").format(maxOf(1, it / 60)) },
        meters?.let { "$it m" },
        stops?.let { tr("%d 정거장").format(it) },
    )

/**
 * 안내문 앞에 붙은 노선을 뗀다 — 「1호선 (서울역 > 동대문)」→「서울역 > 동대문」,
 * 「마을 종로02외 1대 (…)」→「외 1대 (…)」. 노선이 앞에 없으면(영어 안내 등) 그대로다.
 */
private fun withoutRoute(
    guidance: String,
    route: String,
): String {
    if (!guidance.startsWith(route)) return guidance
    var rest = guidance.removePrefix(route).trim()
    if (rest.startsWith("(") && rest.endsWith(")") && rest.count { it == '(' } == 1) {
        rest = rest.substring(1, rest.length - 1)
    }
    return rest
}

fun RouteLeg.toChip(): RouteLegChip {
    val kind = RouteLegMode.from(mode, vehicleType, vehicleName)
    val found = pieces(seconds, meters, stopCount)
    // 지하철은 이름만 — 카카오의 종류 칸이 「일반」이라 「일반 1호선」이 된다.
    val route =
        if (kind == RouteLegMode.SUBWAY && !vehicleName.isNullOrEmpty()) {
            vehicleName.orEmpty()
        } else {
            listOfNotNull(vehicleType, vehicleName).filter { it.isNotEmpty() }.joinToString(" ")
        }
    val vehicle = kind.isVehicle && route.isNotEmpty()
    return RouteLegChip(
        mode = kind,
        title = if (vehicle) route else guidance,
        detail =
            if (vehicle) {
                (listOf(withoutRoute(guidance, route)) + found).filter { it.isNotEmpty() }.joinToString(" · ")
            } else {
                found.joinToString(" · ")
            },
        hasStairs = hasStairs,
        meters = meters,
        seconds = seconds,
    )
}

/**
 * 안내 띠의 칩 — **이어진 도보는 하나로 합친다**(iOS `RouteNavResult.chips`). 카카오는
 * 내린 뒤 걷는 길을 턴마다 끊어 주어 7~145 m 조각 10개가 칩 10개로 늘어섰다. 지도 선은
 * `legs`를 그대로 쓴다. 합계는 **조각을 전부 알 때만** 낸다 — 아는 것만 더하면 실제보다
 * 짧은 길이 된다.
 */
fun NextLeg.chips(): List<RouteLegChip> {
    val out = mutableListOf<RouteLegChip>()
    val run = mutableListOf<RouteLegChip>()

    fun flush() {
        val first = run.firstOrNull() ?: return
        if (run.size == 1) {
            out += first
        } else {
            val meters = if (run.all { it.meters != null }) run.sumOf { it.meters!! } else null
            val seconds = if (run.all { it.seconds != null }) run.sumOf { it.seconds!! } else null
            out +=
                RouteLegChip(
                    mode = RouteLegMode.WALK,
                    title = first.title,
                    detail = pieces(seconds, meters, null).joinToString(" · "),
                    hasStairs = run.any { it.hasStairs },
                    meters = meters,
                    seconds = seconds,
                )
        }
        run.clear()
    }
    for (chip in legs.map { it.toChip() }) {
        if (chip.mode == RouteLegMode.WALK) {
            run += chip
        } else {
            flush()
            out += chip
        }
    }
    flush()
    return out
}

/**
 * 「환승 1회 · 도보 300 m · 1,450원」. **모르는 도보 거리를 0으로 두지 않는다** —
 * 카카오 후보의 절반 가까이가 도보 구간 없이 오는데, 0 m 로 적으면 「안 걸어도 되는
 * 경로」처럼 보인다(iOS `RouteNavResult.summaryLine` 주석).
 */
fun NextLeg.summaryLine(): String {
    val parts = mutableListOf(tr("환승 %d회").format(transfers))
    parts += walkMeters?.let { tr("도보 %d m").format(it) } ?: tr("도보 정보 없음")
    fareWon?.takeIf { it > 0 }?.let { parts += tr("%s원").format("%,d".format(it)) }
    return parts.joinToString(" · ")
}
