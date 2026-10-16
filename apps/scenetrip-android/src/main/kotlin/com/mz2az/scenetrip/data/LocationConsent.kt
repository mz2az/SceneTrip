package com.mz2az.scenetrip.data

import android.content.Context
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 좌표값이 같아도 **기기에서 얻었는지, 사용자가 지도·목적지를 골랐는지는 다르다.** iOS `LocationCoordinateSource`.
 * 지도 범위가 GPS(내 위치·파란 점을 포함한 맞춤)에서 왔으면 [DEVICE], 손으로 옮기거나 검색 결과를 골랐으면 [USER_SELECTED].
 */
enum class CoordinateSource { DEVICE, USER_SELECTED }

/**
 * 호출 한 번의 좌표 출처. iOS 는 요청 헤더(`X-SceneTrip-Coordinate-Source`)로 실어 전송 직전에 떼지만, 생성된 코틀린 클라이언트는
 * 호출마다 헤더를 받지 않는다. 그래서 **같은 스레드에서 도는 동기 호출** 동안만 스레드 값으로 들고 간다 —
 * `RetryInterceptor`·`AuthInterceptor`·동의 인터셉터가 모두 호출한 스레드에서 돌므로 재시도와 인증 재전송도 같은 출처를 본다.
 *
 * 표식이 없으면 **기기 위치로 취급한다**(iOS 와 같다 — 출처를 빠뜨려서 동의를 우회하지 못하게).
 */
object CoordinateSources {
    private val current = ThreadLocal<CoordinateSource?>()

    /** [block] 안의 생성 클라이언트 호출에 [source] 를 붙인다. 중첩하면 바깥 값을 복원한다. 중간에 정지하는 코드를 넣지 않는다. */
    fun <T> with(
        source: CoordinateSource,
        block: () -> T,
    ): T {
        val previous = current.get()
        current.set(source)
        try {
            return block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    fun current(): CoordinateSource = current.get() ?: CoordinateSource.DEVICE
}

/** 시스템 위치 권한과 별개인 위치기반서비스 이용 동의. AI 동의와 저장·철회·전송 잠금을 공유한다. iOS `LocationConsentStore`. */
class LocationConsentStore(
    storage: AIConsentStorage,
    version: Int = CURRENT_VERSION,
) : VersionedConsentStore(storage, KEY, version) {
    companion object {
        const val KEY = "scenetrip.locationConsentVersion"

        /** 안내(시트 본문·약관) 내용이 바뀌면 올린다. 이전 버전의 허용은 새 안내의 동의가 아니다. */
        const val CURRENT_VERSION = 1

        @Volatile private var instance: LocationConsentStore? = null

        /** 앱 전체에서 하나 — 철회가 나가 있는 요청을 끊으려면 인터셉터와 화면이 같은 객체를 써야 한다. */
        fun getInstance(context: Context): LocationConsentStore =
            instance ?: synchronized(this) {
                instance
                    ?: LocationConsentStore(SharedPrefsConsentStorage(context.applicationContext)).also { instance = it }
            }
    }
}

/** 요청이 기기 좌표를 싣는 정도. */
enum class LocationNeed {
    /** 좌표가 없거나 사용자가 고른 좌표(지도·목적지)다 — 위치 동의와 무관하다. */
    NONE,

    /** 기기 좌표가 없으면 의미가 없는 요청 — 동의 없이는 보내지 않는다. */
    REQUIRED,

    /** 좌표는 선택값이다(AI 코스 만들기) — 동의 없으면 좌표만 빼고 보낸다. */
    OPTIONAL,
}

/** 요청이 기기 좌표를 싣는지 가르고, 선택 좌표를 떼어 낸다. iOS `LocationConsentStore.requestForSending` 의 분류 부분. */
object LocationRequests {
    private val COORDINATE_NAMES = setOf("latitude", "longitude")
    private val MAP_QUERY = setOf("bbox", "lat", "lng")

    fun need(
        request: Request,
        source: CoordinateSource,
    ): LocationNeed {
        val path = request.url.encodedPath
        if (path.contains("/navigation/")) return LocationNeed.REQUIRED
        if (path.endsWith("/places") || path.endsWith("/pois")) {
            val carriesMapCoordinates = request.url.queryParameterNames.any { it in MAP_QUERY }
            return if (carriesMapCoordinates && source != CoordinateSource.USER_SELECTED) LocationNeed.REQUIRED else LocationNeed.NONE
        }
        val chat = path.endsWith("/guide/chat")
        val plan = path.endsWith("/guide/plan")
        if (!chat && !plan) return LocationNeed.NONE
        // 본문을 읽을 수 없으면 좌표가 있는 것으로 본다 — 모르면 막는 쪽이 안전하다.
        val names = topLevelNames(request)
        if (names != null && names.none { it in COORDINATE_NAMES }) return LocationNeed.NONE
        return if (chat) {
            if (source == CoordinateSource.USER_SELECTED) LocationNeed.NONE else LocationNeed.REQUIRED
        } else {
            LocationNeed.OPTIONAL
        }
    }

    /** JSON 본문에서 `latitude`·`longitude` 를 뺀 요청. 본문을 못 읽으면 `null`(보내면 안 된다). 나머지 값의 서식은 그대로다. */
    fun withoutCoordinates(request: Request): Request? {
        val body = request.body ?: return request
        if (body.isOneShot()) return null
        return try {
            val source = Buffer().also { body.writeTo(it) }
            val out = Buffer()
            val reader = JsonReader.of(source)
            val writer = JsonWriter.of(out)
            reader.beginObject()
            writer.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name in COORDINATE_NAMES) {
                    reader.skipValue()
                } else {
                    writer.name(name)
                    reader.nextSource().use { value -> writer.valueSink().use { it.writeAll(value) } }
                }
            }
            reader.endObject()
            writer.endObject()
            request.newBuilder().method(request.method, out.readByteString().toRequestBody(body.contentType())).build()
        } catch (_: Exception) {
            null
        }
    }

    private fun topLevelNames(request: Request): Set<String>? {
        val body = request.body ?: return emptySet()
        if (body.isOneShot()) return null
        return try {
            val reader = JsonReader.of(Buffer().also { body.writeTo(it) })
            val names = mutableSetOf<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                names.add(reader.nextName())
                reader.skipValue()
            }
            names
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * 동의 없이 기기 좌표를 보내려는 요청을 **전송 전에** 막았다는 로컬 오류. 네트워크 실패가 아니라 재시도 계층이 되풀이하지 않는다.
 * iOS `LocationConsentRequired`.
 */
class LocationConsentRequired : IOException("Location consent required") {
    companion object {
        /** [NetworkFailure]·[ApiFailure] 가 화면 문구를 가르는 열쇠. 서버 코드가 아니다. */
        const val CODE = "LOCATION_CONSENT_REQUIRED"

        /** iOS `RouteGuideFailure.locationConsentRequired`·`RouteNavFailure.locationConsentRequired` 의 문구. */
        const val MESSAGE_KO = "위치기반서비스 이용에 동의한 뒤 다시 시도해 주세요."

        fun isIn(error: Throwable?): Boolean {
            var current = error
            var depth = 0
            while (current != null && depth++ < 8) {
                if (current is LocationConsentRequired) return true
                current = current.cause
            }
            return false
        }
    }
}

/**
 * 기기 좌표를 싣는 요청의 **매 전송** 직전에 위치 동의를 검사한다 — 인증 갱신 뒤 재전송과 자동 재시도도 이 계층을 지난다
 * (재시도 계층 안쪽에 둔다). iOS `RetryTask.send` 의 `requestForSending` 과 `start` 에 해당한다.
 *
 * - 필수 좌표([LocationNeed.REQUIRED]): 동의가 없으면 [LocationConsentRequired] 로 끝나며 요청은 나가지 않는다.
 * - 선택 좌표([LocationNeed.OPTIONAL], `/guide/plan`): 동의가 없으면 `latitude`·`longitude` 만 빼고 보낸다.
 * - 첫 검사 뒤 철회돼도 [VersionedConsentStore.begin] 이 잠금 안에서 다시 확인하고, 나가 있는 호출은 [VersionedConsentStore.revoke] 가 끊는다.
 *
 * [afterFirstCheck] 는 시험이 「첫 검사와 시작 사이」 에 철회를 끼워 넣는 뒷문이다.
 */
class LocationConsentInterceptor(
    private val store: VersionedConsentStore,
    private val source: () -> CoordinateSource = CoordinateSources::current,
    private val afterFirstCheck: () -> Unit = {},
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val need = LocationRequests.need(request, source())
        if (need == LocationNeed.NONE) return chain.proceed(request)
        if (!store.isAllowed) return withoutConsent(chain, request, need)
        afterFirstCheck()
        val flight = store.begin(chain.call()) ?: return withoutConsent(chain, request, need)
        try {
            return chain.proceed(request)
        } catch (error: IOException) {
            // 철회가 끊은 호출은 「취소」 가 아니라 동의 오류로 올린다.
            if (flight.revoked) throw LocationConsentRequired()
            throw error
        } finally {
            flight.end()
        }
    }

    private fun withoutConsent(
        chain: Interceptor.Chain,
        request: Request,
        need: LocationNeed,
    ): Response {
        if (need == LocationNeed.REQUIRED) throw LocationConsentRequired()
        // 선택 좌표 — 좌표만 빼고 보낸다. 본문을 고칠 수 없으면 좌표가 나갈 수 있으니 보내지 않는다.
        val safe = LocationRequests.withoutCoordinates(request) ?: throw LocationConsentRequired()
        return chain.proceed(safe)
    }
}

/** 기기 좌표일 때만 위치 동의를 묻는다. 사용자가 고른 좌표는 그대로 진행한다. iOS `AIConsentGate.requestLocation(for:)`. */
fun AIConsentGate.requestLocation(
    source: CoordinateSource,
    action: () -> Unit,
) {
    if (source == CoordinateSource.DEVICE) request(action) else action()
}

/**
 * 「길 안내를 위해 카카오에 현재 위치를 보냈어요.」 알림. **사용자 동작(요청) 하나에 한 번**만 띄운다 — GPS 갱신이나 같은 요청의
 * 중복 완료는 알림을 늘리지 않고, 다시 시도·다음 목적지는 새 요청으로 센다. iOS `KakaoDirectionsNotice`.
 *
 * 화면이 구독해 다시 그리도록 [addListener] 를 둔다(Compose 에 기대지 않아 단위 시험이 그대로 돈다).
 */
class KakaoDirectionsNotice {
    @Volatile var visibleRequest: UUID? = null
        private set
    private val notified = mutableSetOf<UUID>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** 새 사용자 요청을 시작한다. 떠 있던 알림은 거둔다. */
    fun beginRequest(): UUID {
        val id = UUID.randomUUID()
        show(null)
        return id
    }

    /** 이 요청의 길 안내가 성공했다 — 한 요청에 한 번만 보인다. 낡은 응답이어도 위치는 이미 나갔으므로 센다. */
    fun succeeded(id: UUID) {
        val first = synchronized(notified) { notified.add(id) }
        if (first) show(id)
    }

    fun hide(id: UUID) {
        if (visibleRequest == id) show(null)
    }

    private fun show(id: UUID?) {
        visibleRequest = id
        listeners.forEach { it() }
    }
}

/**
 * 한 사용자 요청에서 위치 동의 시트는 **한 번만** 연다(최초 GPS 수신을 포함해 위치 갱신마다 다시 묻지 않는다).
 * iOS `LocationDirectionsConsent`.
 */
class LocationDirectionsConsent(
    private val store: VersionedConsentStore,
) {
    val gate = AIConsentGate(store)
    val notice = KakaoDirectionsNotice()
    private var asked = false

    var requestId: UUID = UUID.randomUUID()
        private set

    val isAllowed: Boolean get() = store.isAllowed

    /** 사용자가 길 안내를 시작·재시도·목적지 변경·종료했다 — 새 요청이다. */
    fun beginRequest() {
        gate.cancel()
        asked = false
        requestId = notice.beginRequest()
    }

    /** 동의돼 있으면 `true`. 아니면 (이 요청에서 처음일 때만) 시트를 띄우고 `false` — 허용되면 [resume] 이 한 번 불린다. */
    fun proceedOrAsk(resume: () -> Unit): Boolean {
        if (store.isAllowed) return true
        if (asked) return false
        asked = true
        val id = requestId
        gate.request { if (requestId == id) resume() }
        return false
    }
}

/**
 * 길 안내 요청은 한 번에 하나만 나간다. 그 사이 목적지가 바뀌면 중간 것은 건너뛰고 **최신 하나**가 끝난 뒤 한 번 더 실행된다.
 * iOS `TripSession.load` 의 「요청 중 start·retry 가 ID 를 바꿨으면 최신 목적지를 이어서 묻는다」.
 */
class LatestRequestQueue {
    private var running = false
    private var queued = false

    /** 지금 보내도 되면 `true`(진행 중으로 표시). 이미 나가 있으면 최신 하나로 대기시키고 `false`. */
    @Synchronized
    fun begin(): Boolean {
        if (running) {
            queued = true
            return false
        }
        running = true
        return true
    }

    /** 요청이 끝났다. 그 사이 대기한 것이 있으면 `true` — 부르는 쪽이 최신 상태로 한 번 더 보낸다. */
    @Synchronized
    fun end(): Boolean {
        running = false
        val again = queued
        queued = false
        return again
    }

    /** 안내 종료·도착 — 대기 중인 요청은 보내지 않는다. */
    @Synchronized
    fun clearQueued() {
        queued = false
    }
}
