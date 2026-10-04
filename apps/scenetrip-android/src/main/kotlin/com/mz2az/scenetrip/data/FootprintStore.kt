package com.mz2az.scenetrip.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** 한국 안인가 — 네모로 판단한다. 국경선을 정확히 그릴 이유가 없다. */
object KoreaBounds {
    fun contains(
        latitude: Double,
        longitude: Double,
    ): Boolean = latitude in 32.5..39.5 && longitude in 124.0..132.5
}

/** 하버사인 — 두 좌표 사이 직선거리(km). 지구 반지름 6371km. */
fun haversineKm(
    lat1: Double,
    lng1: Double,
    lat2: Double,
    lng2: Double,
): Double {
    val radius = 6371.0
    val dLat = (lat2 - lat1) * Math.PI / 180
    val dLng = (lng2 - lng1) * Math.PI / 180
    val a1 = lat1 * Math.PI / 180
    val a2 = lat2 * Math.PI / 180
    val haversine = sin(dLat / 2) * sin(dLat / 2) + sin(dLng / 2) * sin(dLng / 2) * cos(a1) * cos(a2)
    return 2 * radius * asin(min(1.0, sqrt(haversine)))
}

data class FootprintPoint(
    val latitude: Double,
    val longitude: Double,
    val at: Long,
)

/**
 * 발자취 — 여행 모드 동안 내가 지나간 자리. iOS `Models/FootprintStore.swift`를
 * 옮긴 것이다.
 *
 * **기기에만 남는다** — 이동 기록은 가장 민감한 데이터라 서버로 보내지 않는다.
 * **한국 안에서만** 기록한다([KoreaBounds]). 25m 안에서 오락가락한 것은 한 점으로
 * 친다.
 *
 * `record()`를 실제로 부르는 라이브 내비게이션(RouteTab)은 아직 이식되지 않았다 —
 * 지금은 마이페이지가 읽기·지우기만 한다. 데이터가 비어 있어도 화면은 정상 동작한다.
 */
class FootprintStore private constructor(
    context: Context,
) {
    var points by mutableStateOf<List<FootprintPoint>>(emptyList())
        private set

    /** 마이페이지의 「지도에 발자취 보기」 설정 — 꺼 두면 지도에 단추도 발자국도 없다. */
    var enabled by mutableStateOf(false)

    /**
     * 여행 지도의 발자취 단추가 켜져 있는가. 단추는 [enabled]일 때만 있다. 기본은 켜짐 —
     * 설정을 켠 사람은 보려고 켠 것이다. iOS `FootprintStore.trailVisible`.
     */
    var trailVisible by mutableStateOf(true)

    /** 지금 지도에 발자국을 그리는가. iOS `FootprintStore.drawsTrail`. */
    val drawsTrail: Boolean
        get() = enabled && trailVisible

    private val prefs = context.getSharedPreferences("scenetrip", Context.MODE_PRIVATE)

    init {
        enabled = prefs.getBoolean(ENABLED_KEY, false)
        trailVisible = prefs.getBoolean(TRAIL_KEY, true)
        points = load()
    }

    fun updateEnabled(value: Boolean) {
        enabled = value
        prefs.edit().putBoolean(ENABLED_KEY, value).apply()
    }

    fun updateTrailVisible(value: Boolean) {
        trailVisible = value
        prefs.edit().putBoolean(TRAIL_KEY, value).apply()
    }

    fun record(
        latitude: Double,
        longitude: Double,
        atMillis: Long = System.currentTimeMillis(),
    ) {
        if (!KoreaBounds.contains(latitude, longitude)) return
        points.lastOrNull()?.let { last ->
            val meters = haversineKm(last.latitude, last.longitude, latitude, longitude) * 1000
            if (meters < MIN_STEP_METERS) return
        }
        points = points + FootprintPoint(latitude, longitude, atMillis)
        persist()
    }

    fun clear() {
        points = emptyList()
        persist()
    }

    /** 걸은 거리(km) — 점 사이 직선 합. */
    val kilometers: Double
        get() = points.zipWithNext().sumOf { (a, b) -> haversineKm(a.latitude, a.longitude, b.latitude, b.longitude) }

    private fun persist() {
        val array = JSONArray()
        points.forEach { point ->
            array.put(
                JSONObject().apply {
                    put("latitude", point.latitude)
                    put("longitude", point.longitude)
                    put("at", point.at)
                },
            )
        }
        prefs.edit().putString(POINTS_KEY, array.toString()).apply()
    }

    private fun load(): List<FootprintPoint> {
        val raw = prefs.getString(POINTS_KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                FootprintPoint(obj.getDouble("latitude"), obj.getDouble("longitude"), obj.getLong("at"))
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    companion object {
        private const val MIN_STEP_METERS = 25.0
        private const val ENABLED_KEY = "footprint.enabled"
        private const val TRAIL_KEY = "footprint.trailVisible"
        private const val POINTS_KEY = "footprint.points"

        @Volatile
        private var instance: FootprintStore? = null

        fun getInstance(context: Context): FootprintStore =
            instance ?: synchronized(this) {
                instance ?: FootprintStore(context.applicationContext).also { instance = it }
            }
    }
}
