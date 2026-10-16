package com.mz2az.scenetrip.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mz2az.scenetrip.auth.AuthStore
import com.mz2az.scenetrip.sceneapi.client.model.TripPostDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 서버의 후기와 기기에 남은 옛 글. 옛 글은 비공개 읽기 목록으로 보존한다(MZ2AZ-396). */
class CommunityStore private constructor(
    private val appContext: Context,
) {
    var state by mutableStateOf(CommunityState())
        private set
    private val client = CommunityServerClient(InstallIdentity.of(appContext))
    private val session = CommunitySession(client, { AuthStore.epoch }) { state = it }
    private val prefs = appContext.getSharedPreferences("scenetrip", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val legacyPosts: List<CommunityPost> = load()
    val posts get() = state.posts
    val mine get() = state.mine
    val mineTotal get() = state.mineTotal

    fun syncAccount() = session.syncAccount()

    fun submission(onChanged: (SubmissionState) -> Unit) = CommunitySubmission(client, { AuthStore.epoch }, onChanged = onChanged)

    fun accept(detail: TripPostDetail) = session.accept(detail)

    suspend fun detail(post: CommunityPost) = session.detail(post)

    suspend fun saveCourse(post: CommunityPost) = session.saveCourse(post)

    suspend fun remove(post: CommunityPost) = session.remove(post)

    /**
     * 그 글의 작성자를 차단했다(MZ2AZ-461) — 바로 빼고 첫 쪽부터 새로 받는다. 화면이 닫혀도 끝까지 간다 — 차단한 글의 상세를
     * 닫으면 그 화면의 코루틴이 같이 취소돼 목록을 다시 받지 못한다(iOS 는 전역 `Task` 로 부른다).
     */
    fun blockedInBackground(postId: Long) {
        session.drop(postId)
        scope.launch { session.invalidateAndRefresh() }
    }

    /** 차단·풀기 뒤 — 진행 중인 피드 요청을 버리고 첫 쪽을 반드시 다시 받는다. */
    fun invalidateAndRefreshInBackground() {
        scope.launch { session.invalidateAndRefresh() }
    }

    suspend fun refresh(
        mine: Boolean = false,
        more: Boolean = false,
    ) {
        session.syncAccount()
        if (mine && !AuthStore.signedIn) session.clearMine() else session.refresh(mine, more)
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

    private val photoDir: File get() = File(appContext.filesDir, "community")

    fun photoFile(name: String): File {
        require(name == File(name).name && name.endsWith(".jpg") && name != ".jpg")
        return File(photoDir, name)
    }

    fun photo(name: String): Bitmap? = runCatching { BitmapFactory.decodeFile(photoFile(name).path) }.getOrNull()

    companion object {
        private const val KEY = "scenetrip.communityPosts"

        @Volatile private var instance: CommunityStore? = null

        fun accountChanged() {
            instance?.syncAccount()
        }

        fun getInstance(context: Context): CommunityStore =
            instance ?: synchronized(this) {
                instance ?: CommunityStore(context.applicationContext).also { instance = it }
            }
    }
}
