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
 * 기록은 기본 꺼짐이며 마이페이지에서 안내에 동의해야 켜진다.
 * 로그아웃·탈퇴·세션 소실 때 기록과 동의를 지운다 (MZ2AZ-348).
 */
class FootprintStore private constructor(
    context: Context,
) {
    var points by mutableStateOf<List<FootprintPoint>>(emptyList())
        private set

    var recording by mutableStateOf(false)
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

    private val session =
        FootprintRecording(load(), prefs.getBoolean(RECORDING_KEY, false)) {
            points = it.points
            recording = it.recording
            persist()
            prefs.edit().putBoolean(RECORDING_KEY, recording).apply()
        }

    init {
        recording = session.state.recording
        enabled = prefs.getBoolean(ENABLED_KEY, false)
        trailVisible = prefs.getBoolean(TRAIL_KEY, true)
        points = session.state.points
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
        session.record(latitude, longitude, atMillis)
    }

    fun updateRecording(value: Boolean) = session.consent(value)

    fun forgetOwner() = session.forgetOwner()

    fun clear() = session.clear()

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
        private const val RECORDING_KEY = "footprint.recording"
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
