package com.mz2az.scenetrip.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

/** 글의 갈래. 갈래가 없으면 코스 추천과 맛집 후기가 한 줄에 섞여 둘 다 못 찾는다. */
enum class CommunityBoard(
    val label: String,
) {
    COURSE("코스 추천"),
    REVIEW("장소 후기"),
    PHOTO("인증샷"),
    CHAT("자유"),
}

/**
 * 커뮤니티 게시글 — **임시판의 자료 모양**. 게시판 서버는 아직 없어 기기에만 둔다.
 */
data class CommunityPost(
    val id: String = UUID.randomUUID().toString(),
    val board: CommunityBoard,
    val title: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    /** 첨부한 내 코스 이름. 있으면 배지로 보여 준다. */
    val courseTitle: String? = null,
)

/**
 * 커뮤니티 게시글 저장소. iOS `CommunityTab/CommunityStore.swift`(`CommunityStore.shared`)를
 * 옮긴 것이다.
 *
 * 게시판 서버는 아직 없다(백엔드 티켓도 없다). 그래서 글은 **기기에만** 저장한다 —
 * `LikeStore`와 같은 선택이고 같은 이유로 SharedPreferences다(다시 켜도 남아야
 * 한다). 서버가 서면 이 저장소를 API 클라이언트로 갈아 끼우고 모양은 그대로 간다.
 *
 * JSON 은 `org.json`(Android 플랫폼 내장)으로 직접 인코딩한다 — Moshi/Gson 은 이미
 * 계약 클라이언트가 쓰고 있지만, 이 파일 하나 때문에 우리 모듈의 `deps`에 새로
 * 끌어올 것은 아니다.
 *
 * **`getInstance`로만 얻는 앱 전역 싱글턴이다** — 커뮤니티에서 쓴 글이 마이페이지의
 * "내가 쓴 글"에 바로 보여야 한다([LikeStore]와 같은 이유).
 */
class CommunityStore private constructor(
    context: Context,
) {
    var posts by mutableStateOf<List<CommunityPost>>(emptyList())
        private set

    private val prefs = context.getSharedPreferences("scenetrip", Context.MODE_PRIVATE)

    init {
        posts = load()
    }

    fun add(
        board: CommunityBoard,
        title: String,
        body: String,
        courseTitle: String?,
    ) {
        posts = listOf(CommunityPost(board = board, title = title, body = body, courseTitle = courseTitle)) + posts
        persist()
    }

    fun remove(post: CommunityPost) {
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
                val board = runCatching { CommunityBoard.valueOf(obj.getString("board")) }.getOrNull()
                board ?: return@mapNotNull null
                CommunityPost(
                    id = obj.optString("id", UUID.randomUUID().toString()),
                    board = board,
                    title = obj.optString("title"),
                    body = obj.optString("body"),
                    createdAt = obj.optLong("createdAt"),
                    courseTitle = if (obj.isNull("courseTitle")) null else obj.optString("courseTitle"),
                )
            }
        } catch (e: JSONException) {
            emptyList()
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
