package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.CoordinateSource
import com.mz2az.scenetrip.sceneapi.client.api.PoisApi
import com.mz2az.scenetrip.sceneapi.client.model.PoiCategoryGroup
import com.mz2az.scenetrip.sceneapi.client.model.PoiSummary
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

// 지도 위 편의시설 칩·골고루 찍기의 규칙 (MZ2AZ-547 P5 M1–M5, iOS `RouteTab/MapPoiRules.swift`, 웹 `proto/protoPoi.tsx`).
// 화면(`MapPoiBar`·`MapPoiMarkers`, 계획 화면 쪽은 `RouteEditorMapPoi.kt`)은 이 답을 그대로 따른다. Compose·Android 에 기대지 않는다.

/** 지도가 지금 보여 주는 범위 — 서랍이 덮은 아래는 뺀 것(`NaverMap.contentBounds`). */
data class MapViewport(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
    val centerLat: Double,
    val centerLng: Double,
    val zoom: Double,
    /** 이 범위가 기기 위치에서 왔나(내 위치로 맞춘 지도) — 서버에 좌표 출처로 알리고, 위치 동의를 먼저 묻는다(MZ2AZ-472). */
    val source: CoordinateSource = CoordinateSource.USER_SELECTED,
) {
    /** 서버의 `bbox` — GeoJSON 순서 `minLng,minLat,maxLng,maxLat`, 소수 여섯 자리(점은 늘 `.`). */
    val bbox: String
        get() = "%.6f,%.6f,%.6f,%.6f".format(Locale.US, west, south, east, north)
}

/** 편의시설 서버 요청 하나(`GET /pois?sort=spread…`, M2). */
data class MapPoiRequest(
    val sort: PoisApi.SortListPois,
    val spreadZoom: Int,
    val limit: Int,
    /** 갈래를 하나만 켰을 때만 서버가 거른다. 여럿이면 다 받아 앱이 거른다(요청은 한 번). */
    val categoryGroup: PoiCategoryGroup?,
) {
    companion object {
        /** 한 번에 받는 수 — 계약 상한. */
        const val LIMIT = 200
    }
}

/**
 * 지도 맨 위 갈래 칩 줄의 상태 (M1·M2). 처음엔 모두 꺼져 있고, 켤 때만 받는다.
 * [ask] 는 받기 차례 번호 — 0 이면 받지 않는다(다 꺼짐). 칩을 켜고 끄거나 「다시 찾기」 를 누르면 오른다.
 */
data class MapPoiChips(
    val groupsOn: Set<PoiCategoryGroup> = emptySet(),
    val ask: Int = 0,
) {
    val anyOn: Boolean get() = groupsOn.isNotEmpty()

    /** 「전체」 칩이 켜진 모양인가 — 넷이 다 켜졌다. */
    val allOn: Boolean get() = groupsOn.size == PoiCategoryGroup.entries.size

    fun isOn(group: PoiCategoryGroup): Boolean = group in groupsOn

    /** 갈래 칩 하나를 누른다. 켜지면 받고, 남은 것이 있으면 다시 받고, 다 꺼지면 받지 않는다(`ask` 0). */
    fun toggle(group: PoiCategoryGroup): MapPoiChips {
        val next = if (group in groupsOn) groupsOn - group else groupsOn + group
        return MapPoiChips(next, if (next.isEmpty()) 0 else ask + 1)
    }

    /** 「전체」 — 다 켜져 있으면 다 끄고(받지 않음), 아니면 다 켜고 받는다. */
    fun toggleAll(): MapPoiChips = if (allOn) reset() else MapPoiChips(PoiCategoryGroup.entries.toSet(), ask + 1)

    /** 「↻ 이 지역에서 다시 찾기」 — 켜진 것이 있을 때만 받는다. */
    fun refresh(): MapPoiChips = if (anyOn) copy(ask = ask + 1) else this

    /** 다 끄고 받지 않는다 — 보기로 돌아가거나 여행을 시작할 때. */
    fun reset(): MapPoiChips = MapPoiChips()

    /** 지금 켜진 칩으로 서버에 물을 것. 다 꺼져 있으면 null. `spreadZoom` 은 줌의 내림(0…22). */
    fun request(zoom: Double): MapPoiRequest? {
        if (!anyOn) return null
        return MapPoiRequest(
            sort = PoisApi.SortListPois.spread,
            spreadZoom = floor(zoom).coerceIn(0.0, 22.0).toInt(),
            limit = MapPoiRequest.LIMIT,
            categoryGroup = groupsOn.singleOrNull(),
        )
    }

    /** 받은 것 중 켜진 갈래만 — 여럿 켰을 때 서버가 다 준 것을 여기서 거른다. */
    fun keep(places: List<PoiSummary>): List<PoiSummary> = places.filter { it.categoryGroup in groupsOn }

    companion object {
        /** 칩 줄의 순서 — 음식점 · 명소 · 숙소 · 교통(웹 `MapPoiBar`). */
        val ORDER: List<PoiCategoryGroup> =
            listOf(PoiCategoryGroup.food, PoiCategoryGroup.sight, PoiCategoryGroup.stay, PoiCategoryGroup.transit)

        /** 칩 줄이 보이나 — 편집 중이고 지도가 펼쳐져 있고 여행 안내 중이 아닐 때만(M1). */
        fun showsBar(
            mode: RouteEditorMode,
            mapOpen: Boolean,
            tripActive: Boolean,
        ): Boolean = mode == RouteEditorMode.EDITING && mapOpen && !tripActive
    }
}

/** 칩 줄 아래 상태 알약 (M3). */
enum class MapPoiPill {
    NONE,

    /** 「찾는 중」 */
    SEARCHING,

    /** 「↻ 이 지역에서 다시 찾기」 — 받은 뒤 지도가 움직였다. */
    REFRESH,

    /** 「조금 더 확대하면 보여요」 */
    ZOOM_IN,
}

/** 받기와 지도 움직임 — 알약을 정한다 (M3). 지도를 옮겨도 점은 그대로 두고 「다시 찾기」 만 띄운다. */
data class MapPoiFetch(
    val loading: Boolean = false,
    val stale: Boolean = false,
) {
    /** 받기 시작 — 「찾는 중」. 지난 「다시 찾기」 는 거둔다. */
    fun begin(): MapPoiFetch = MapPoiFetch(loading = true, stale = false)

    /** 다 받았다. 받는 사이 지도가 움직였으면 「다시 찾기」 는 남긴다(받은 것은 옛 범위의 것이다). */
    fun finish(): MapPoiFetch = copy(loading = false)

    /** 지도가 멈췄다. */
    fun cameraIdle(): MapPoiFetch = copy(stale = true)

    /** 다 끄면 알약도 없다. */
    fun clear(): MapPoiFetch = MapPoiFetch()

    fun pill(
        anyOn: Boolean,
        zoom: Double,
    ): MapPoiPill =
        when {
            !anyOn -> MapPoiPill.NONE
            loading -> MapPoiPill.SEARCHING
            zoom < MIN_ZOOM -> MapPoiPill.ZOOM_IN
            stale -> MapPoiPill.REFRESH
            else -> MapPoiPill.NONE
        }

    companion object {
        /** 이보다 넓으면 받지 않는다. 골고루 찍기는 제한이 없다(M2 — 예전 줌 13 제한을 뺐다). */
        const val MIN_ZOOM: Double = 0.0
    }
}

/**
 * 골고루 찍기 (M4) — 지도에 고정된 72dp 칸마다 받은 순서의 1등(`major`)만 아이콘으로 그리고, 나머지(`minor`)는 그리지 않는다
 * (§7 구글처럼, 2026-11-10 — 예전의 작은 점을 뺐다. 확대하면 칸이 작아져 나타난다).
 * 칸은 화면이 아니라 지도(웹 메르카토르)에 고정이라 지도를 옮겨도 같은 곳이 같은 칸에 든다. 확대하면 다시 받지 않고 다시 솎는다.
 */
object MapPoiSpread {
    /** 칸 한 변(dp, 그 줌에서). */
    const val CELL: Double = 72.0

    /** 예전 작은 점의 보이는 지름과 누르는 자리(dp) — §7 뒤로 그리지 않는다. 기존 시험이 이 값을 본다(iOS 와 같다). */
    const val MINOR_VISIBLE: Float = 9f
    const val MINOR_HIT: Float = 16f

    /** [tileSize] — 줌 0 에서 세계 한 변이 몇 dp 인가(네이버 지도는 256). 받은 순서가 곧 순위다(서버 `sort=spread`). */
    fun split(
        places: List<PoiSummary>,
        zoom: Double,
        tileSize: Double = 256.0,
    ): Pair<List<PoiSummary>, List<PoiSummary>> {
        // 칸마다 먼저 온 것이 큰 점 — 서버의 칸 안 순서와 같은 줄이라 확대해 칸을 쪼개도 큰 점은 늘기만 한다.
        val world = tileSize * 2.0.pow(floor(zoom)) / CELL
        val seen = HashSet<Pair<Double, Double>>()
        val major = ArrayList<PoiSummary>()
        val minor = ArrayList<PoiSummary>()
        for (place in places) {
            val column = floor((place.longitude + 180) / 360 * world)
            val lat = place.latitude * PI / 180
            val row = floor((1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2 * world)
            if (seen.add(column to row)) major += place else minor += place
        }
        return major to minor
    }

    /** 큰 점(칸마다 1등)의 id — 지도 점은 다시 만들지 않고 이 답으로 그림만 바꾼다. */
    fun majorIds(
        places: List<PoiSummary>,
        zoom: Double,
        tileSize: Double = 256.0,
    ): Set<Long> = split(places, zoom, tileSize).first.mapTo(HashSet()) { it.id }
}
