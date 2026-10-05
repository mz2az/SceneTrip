package com.mz2az.scenetrip.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.analytics.AppAnalytics
import com.mz2az.scenetrip.analytics.AppEvent
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.routetab.RouteDay
import com.mz2az.scenetrip.routetab.RouteStop
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** 글의 갈래. 옛 글을 읽기 위해 갈래는 남겨 둔다 — 지금은 화면에서 고르지 않는다(MZ2AZ-351). */
enum class CommunityBoard(
    val label: String,
) {
    COURSE("코스 추천"),
    REVIEW("장소 후기"),
    PHOTO("인증샷"),
    CHAT("자유"),
}

/**
 * 글에 붙인 코스 — **붙인 순간의 사본**이다 (MZ2AZ-351). iOS `CommunityTab/CommunityStore.swift`의
 * `PostCourse`를 옮긴 것이다.
 *
 * 코스 id 만 들고 있으면 글쓴이가 코스를 고치거나 지웠을 때 글이 달라지거나 빈다. 후기는
 * 「내가 다녀온 그 코스」를 말하는 글이므로 일차·장소까지 그대로 박아 둔다. 읽는 사람은
 * 이것을 보고, 「내 코스로 담기」로 자기 코스를 하나 만든다.
 */
data class PostCourse(
    val title: String,
    val days: List<List<Stop>>,
) {
    data class Stop(
        /** 촬영지 id. 지도에 직접 찍은 핀이면 없다. */
        val placeId: Long?,
        val name: String,
        val address: String?,
        val type: String?,
        val latitude: Double,
        val longitude: Double,
        val imageUrl: java.net.URI?,
    )

    val placeCount: Int
        get() = days.sumOf { it.size }

    /** 내 코스로 담을 **새 코스**. 서버 id 가 없으므로 저장하면 내 것이 하나 생긴다. */
    fun asNewCourse(): RouteCourse =
        RouteCourse(
            title = title,
            days =
                days.map { stops ->
                    RouteDay(
                        stops =
                            stops.mapIndexed { index, stop ->
                                RouteStop(
                                    place =
                                        PlaceSummary(
                                            id = stop.placeId ?: -(index + 1).toLong(),
                                            name = stop.name,
                                            type = stop.type,
                                            address = stop.address,
                                            latitude = stop.latitude,
                                            longitude = stop.longitude,
                                            imageUrl = stop.imageUrl,
                                        ),
                                    isPinned = stop.placeId == null,
                                )
                            },
                    )
                },
        )

    companion object {
        fun from(course: RouteCourse): PostCourse =
            PostCourse(
                title = course.title,
                days =
                    course.days.map { day ->
                        day.stops.map { stop ->
                            Stop(
                                placeId = if (stop.isPinned) null else stop.place.id,
                                name = stop.place.name,
                                address = stop.place.address,
                                type = stop.place.type,
                                latitude = stop.place.latitude,
                                longitude = stop.place.longitude,
                                imageUrl = stop.place.imageUrl,
                            )
                        }
                    },
            )
    }
}

/**
 * 커뮤니티 게시글 — **임시판의 자료 모양** (여행후기로 재편 2026-10-05, MZ2AZ-351).
 *
 * 게시판 서버는 아직 없다. 그래서 글과 사진은 **기기에만** 저장한다(글은 SharedPreferences,
 * 사진은 앱 폴더의 파일). 서버가 서면 이 저장소를 API 클라이언트로 갈아 끼우고 모양은 그대로 간다.
 */
data class CommunityPost(
    val id: String = UUID.randomUUID().toString(),
    /** 옛 말머리. 지금은 늘 [CommunityBoard.REVIEW]로 쓴다 — 화면에서 고르지 않는다. */
    val board: CommunityBoard = CommunityBoard.REVIEW,
    val title: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** 옛 글의 첨부 — 코스 이름만 있었다. 새 글은 [course]를 쓴다. */
    val courseTitle: String? = null,
    /** 사진 파일 이름들([CommunityStore.photoFile]). 첫 장이 대표 사진이다. */
    val photos: List<String>? = null,
    /** 붙인 코스의 사본. */
    val course: PostCourse? = null,
    /** 글쓴이 이름. **없으면 내가 쓴 글이다.** 게시판 서버가 없어 남의 글은 시험용으로만 들어온다. */
    val author: String? = null,
) {
    val isMine: Boolean
        get() = author == null
}

/**
 * 커뮤니티 게시글 저장소. iOS `CommunityTab/CommunityStore.swift`(`CommunityStore.shared`)를
 * 옮긴 것이다.
 *
 * 게시판 서버는 아직 없다(백엔드 티켓도 없다). 그래서 글과 사진은 **기기에만** 저장한다 —
 * `LikeStore`와 같은 선택이고 같은 이유로 SharedPreferences다(다시 켜도 남아야 한다).
 * 서버가 서면 이 저장소를 API 클라이언트로 갈아 끼우고 모양은 그대로 간다.
 *
 * **`getInstance`로만 얻는 앱 전역 싱글턴이다** — 커뮤니티에서 쓴 글이 마이페이지의
 * "내가 쓴 글"에 바로 보여야 한다([LikeStore]와 같은 이유).
 */
class CommunityStore private constructor(
    private val appContext: Context,
) {
    var posts by mutableStateOf<List<CommunityPost>>(emptyList())
        private set

    private val prefs = appContext.getSharedPreferences("scenetrip", Context.MODE_PRIVATE)

    init {
        posts = load()
    }

    /** 내가 쓴 글만 — 마이페이지의 「내가 쓴 글」. */
    val mine: List<CommunityPost>
        get() = posts.filter { it.isMine }

    fun add(
        title: String,
        body: String,
        photos: List<Bitmap>,
        course: PostCourse?,
    ) {
        val names = photos.mapNotNull { store(it) }
        posts =
            listOf(
                CommunityPost(
                    title = title,
                    body = body,
                    courseTitle = course?.title,
                    photos = names.ifEmpty { null },
                    course = course,
                ),
            ) + posts
        persist()
        AppAnalytics.log(AppEvent.PostReview(photoCount = names.size, hasCourse = course != null))
    }

    fun remove(post: CommunityPost) {
        post.photos.orEmpty().forEach { name -> photoFile(name).delete() }
        posts = posts.filterNot { it.id == post.id }
        persist()
    }

    private fun persist() {
        val array = JSONArray()
        posts.forEach { post ->
            array.put(
                JSONObject().apply {
                    put("id", post.id)
                    put("board", post.board.name)
                    put("title", post.title)
                    put("body", post.body)
                    put("createdAt", post.createdAt)
                    put("courseTitle", post.courseTitle ?: JSONObject.NULL)
                    put("photos", post.photos?.let { JSONArray(it) } ?: JSONObject.NULL)
                    put("course", post.course?.let { encodeCourse(it) } ?: JSONObject.NULL)
                    put("author", post.author ?: JSONObject.NULL)
                },
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun load(): List<CommunityPost> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val board = runCatching { CommunityBoard.valueOf(obj.getString("board")) }.getOrNull() ?: CommunityBoard.REVIEW
                CommunityPost(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    board = board,
                    title = obj.optString("title"),
                    body = obj.optString("body"),
                    createdAt = obj.optLong("createdAt"),
                    courseTitle = if (obj.isNull("courseTitle")) null else obj.optString("courseTitle"),
                    photos = obj.optJSONArray("photos")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } },
                    course = obj.optJSONObject("course")?.let { decodeCourse(it) },
                    author = if (obj.isNull("author")) null else obj.optString("author"),
                )
            }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    private fun encodeCourse(course: PostCourse): JSONObject =
        JSONObject().apply {
            put("title", course.title)
            put(
                "days",
                JSONArray(
                    course.days.map { stops ->
                        JSONArray(
                            stops.map { stop ->
                                JSONObject().apply {
                                    put("placeId", stop.placeId ?: JSONObject.NULL)
                                    put("name", stop.name)
                                    put("address", stop.address ?: JSONObject.NULL)
                                    put("type", stop.type ?: JSONObject.NULL)
                                    put("latitude", stop.latitude)
                                    put("longitude", stop.longitude)
                                    put("imageUrl", stop.imageUrl?.toString() ?: JSONObject.NULL)
                                }
                            },
                        )
                    },
                ),
            )
        }

    private fun decodeCourse(obj: JSONObject): PostCourse? =
        try {
            val daysArray = obj.getJSONArray("days")
            val days =
                (0 until daysArray.length()).map { d ->
                    val stopsArray = daysArray.getJSONArray(d)
                    (0 until stopsArray.length()).map { s ->
                        val stopObj = stopsArray.getJSONObject(s)
                        PostCourse.Stop(
                            placeId = if (stopObj.isNull("placeId")) null else stopObj.getLong("placeId"),
                            name = stopObj.optString("name"),
                            address = if (stopObj.isNull("address")) null else stopObj.optString("address"),
                            type = if (stopObj.isNull("type")) null else stopObj.optString("type"),
                            latitude = stopObj.optDouble("latitude"),
                            longitude = stopObj.optDouble("longitude"),
                            imageUrl =
                                if (stopObj.isNull("imageUrl")) {
                                    null
                                } else {
                                    runCatching { java.net.URI(stopObj.optString("imageUrl")) }.getOrNull()
                                },
                        )
                    }
                }
            PostCourse(title = obj.optString("title"), days = days)
        } catch (e: JSONException) {
            null
        }

    // MARK: 사진 파일

    private val photoDir: File by lazy {
        File(appContext.filesDir, "community").apply { mkdirs() }
    }

    fun photoFile(name: String): File = File(photoDir, name)

    fun photo(name: String): Bitmap? = runCatching { BitmapFactory.decodeFile(photoFile(name).path) }.getOrNull()

    /** 긴 변 1600 으로 줄여 JPEG 로 둔다 — 폰 사진 원본은 장당 수 MB 다. */
    private fun store(photo: Bitmap): String? {
        val longest = max(photo.width, photo.height).toFloat()
        val scale = min(1f, 1600f / max(longest, 1f))
        val resized =
            if (scale < 1f) {
                Bitmap.createScaledBitmap(photo, (photo.width * scale).toInt(), (photo.height * scale).toInt(), true)
            } else {
                photo
            }
        val name = "${UUID.randomUUID()}.jpg"
        return try {
            FileOutputStream(photoFile(name)).use { out -> resized.compress(Bitmap.CompressFormat.JPEG, 82, out) }
            name
        } catch (e: java.io.IOException) {
            null
        }
    }

    companion object {
        private const val KEY = "scenetrip.communityPosts"

        @Volatile
        private var instance: CommunityStore? = null

        fun getInstance(context: Context): CommunityStore =
            instance ?: synchronized(this) {
                instance ?: CommunityStore(context.applicationContext).also { instance = it }
            }
    }
}
