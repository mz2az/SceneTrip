package com.mz2az.scenetrip.newcourse

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.data.API_BASE
import com.mz2az.scenetrip.data.AccountDataReset
import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.InstallIdentity
import com.mz2az.scenetrip.hometab.HomeCityRules
import com.mz2az.scenetrip.hometab.HomeTabModel
import com.mz2az.scenetrip.sceneapi.client.api.PlacesApi
import com.mz2az.scenetrip.sceneapi.client.api.TripApi
import com.mz2az.scenetrip.sceneapi.client.infrastructure.ClientException
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.PlaceCity
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.TripPace
import com.mz2az.scenetrip.sceneapi.client.model.TripPlanInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 도시로 여행 짜기가 받는 것 — 도시 목록(`GET /places/cities`)과 고른 도시의 촬영지(`GET /places?city=`) (MZ2AZ-547 Q1·R1·S1).
 * iOS `CityPlacesStore` 를 옮긴 것이다.
 *
 * 앱에 하나다 — 새 코스 시트의 도시 사진, 질문의 도시 칸, 추천 코스·직접 고르기가 같은 것을 본다. 도시 촬영지는 한 번 받으면
 * 들고 있다(앱 언어가 바뀌면 다시 받는다).
 */
object CityPlacesStore {
    enum class Status { IDLE, LOADING, LOADED, FAILED }

    var cities by mutableStateOf<List<PlaceCity>>(emptyList())
        private set
    var status by mutableStateOf(Status.IDLE)
        private set
    var places by mutableStateOf<Map<String, List<PlaceSummary>>>(emptyMap())
        private set
    var placeFailures by mutableStateOf<Set<String>>(emptySet())
        private set

    private val api by lazy { PlacesApi(API_BASE) }
    private var lang: Lang? = null
    private var retried = false

    val shownCities: List<PlaceCity> get() = CityListRules.shown(cities)

    fun city(key: String?): PlaceCity? = cities.firstOrNull { it.key == key }

    /** 도시 목록을 (아직 없으면) 받는다. 못 받으면 15초 뒤 한 번 더 — 요청 한도(429)에 걸리기 쉽다(웹과 같다). */
    suspend fun loadCities() {
        if (lang != AppLanguage.current) {
            lang = AppLanguage.current
            cities = emptyList()
            places = emptyMap()
            status = Status.IDLE
        }
        if (status == Status.LOADED || status == Status.LOADING) return
        status = Status.LOADING
        val list = withContext(Dispatchers.IO) { runCatching { api.listPlaceCities() }.getOrNull() }
        if (list != null && list.items.isNotEmpty()) {
            cities = list.items
            status = Status.LOADED
            return
        }
        status = Status.FAILED
        if (retried) return
        retried = true
        delay(RETRY_MILLIS)
        if (status == Status.FAILED) loadCities()
    }

    /** 「다시 시도」. */
    suspend fun retry() {
        status = Status.IDLE
        loadCities()
    }

    /** 이 도시의 촬영지(인기순)를 (아직 없으면) 받는다. */
    suspend fun loadPlaces(key: String) {
        if (places[key] != null) return
        placeFailures = placeFailures - key
        val list =
            withContext(Dispatchers.IO) {
                runCatching { api.listPlaces(city = key, limit = CityListRules.PLACE_PAGE) }.getOrNull()
            }
        if (list != null) places = places + (key to list.items.orEmpty()) else placeFailures = placeFailures + key
    }

    /** 도시 칸·시트 줄의 사진 — 고정 사진이 먼저, 없으면 받아 둔 인기 1위 촬영지 사진. */
    fun photo(key: String): HomeCityRules.Photo? {
        val top = places[key]?.firstOrNull { HomeTabModel.hasPhoto(it) }?.imageUrl?.toString()
        return HomeCityRules.photo(key, top)
    }

    private const val RETRY_MILLIS = 15_000L
}

/**
 * 정한 여행(Q4) — 도시·며칠·속도·만든 코스. 기기에 기억하고 서버(`/me/trip`, 비회원도 설치 id 로)와 맞춘다. iOS `TripMemoryStore`.
 *
 * 켤 때 한 번 서버에 묻는다 — 서버 값이 이긴다. 서버에 없고 기기에만 있으면 올린다([TripMemoryRules.reconcile]).
 * 바꿀 때마다 `PUT`. 실패해도 기기 것은 남고 다음에 켤 때 다시 올린다.
 */
class TripMemoryStore private constructor(
    context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val installId = InstallIdentity.of(context)
    private val api by lazy { TripApi(API_BASE) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    var trip by mutableStateOf(readLocal())
        private set

    init {
        // 계정을 지우거나 바꾸면 여행 기억도 그 사람 것이 아니다.
        AccountDataReset.watch {
            trip = null
            prefs.edit().clear().apply()
        }
    }

    suspend fun sync() {
        val server =
            withContext(Dispatchers.IO) {
                try {
                    val found = api.getTrip(installId)
                    ServerTrip.Found(TripMemo(found.city, found.days, found.pace, found.courseId))
                } catch (error: ClientException) {
                    if (error.statusCode == 404) ServerTrip.Missing else ServerTrip.Failed
                } catch (error: Exception) {
                    ServerTrip.Failed
                }
            }
        val result = TripMemoryRules.reconcile(trip, server)
        store(result.use)
        val use = result.use
        if (result.push && use != null) push(use)
    }

    /** 바꿨다 — 기기에 바로 적고, 서버에는 화면과 무관하게 올린다(화면이 닫혀도 끊기지 않게 자기 범위에서). */
    fun set(next: TripMemo) {
        store(next)
        scope.launch { push(next) }
    }

    private suspend fun push(memo: TripMemo) {
        withContext(Dispatchers.IO) {
            runCatching {
                api.putTrip(
                    installId,
                    TripPlanInput(city = memo.city, days = memo.days, pace = memo.pace, courseId = memo.courseId),
                )
            }
        }
    }

    private fun store(memo: TripMemo?) {
        trip = memo
        prefs
            .edit()
            .apply {
                if (memo == null) {
                    clear()
                } else {
                    putString(KEY_CITY, memo.city)
                    putInt(KEY_DAYS, memo.days)
                    putString(KEY_PACE, memo.pace.value)
                    if (memo.courseId != null) putLong(KEY_COURSE, memo.courseId) else remove(KEY_COURSE)
                }
            }.apply()
    }

    private fun readLocal(): TripMemo? {
        val city = prefs.getString(KEY_CITY, null) ?: return null
        val days = prefs.getInt(KEY_DAYS, 0).takeIf { it > 0 } ?: return null
        val pace = TripPace.decode(prefs.getString(KEY_PACE, null)) ?: TripPace.normal
        val course = if (prefs.contains(KEY_COURSE)) prefs.getLong(KEY_COURSE, 0) else null
        return TripMemo(city, days, pace, course)
    }

    companion object {
        private const val PREFS = "scenetrip.trip"
        private const val KEY_CITY = "city"
        private const val KEY_DAYS = "days"
        private const val KEY_PACE = "pace"
        private const val KEY_COURSE = "courseId"

        @Volatile
        private var instance: TripMemoryStore? = null

        fun getInstance(context: Context): TripMemoryStore =
            instance ?: synchronized(this) {
                instance ?: TripMemoryStore(context.applicationContext).also { instance = it }
            }
    }
}
