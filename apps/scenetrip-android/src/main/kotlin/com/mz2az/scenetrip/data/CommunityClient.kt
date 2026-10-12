package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.api.PostsApi
import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.TripPostCreate
import com.mz2az.scenetrip.sceneapi.client.model.TripPostDetail
import com.mz2az.scenetrip.sceneapi.client.model.TripPostList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** 화면과 실패 시험은 같은 계약 클라이언트를 쓴다. */
interface CommunityClient {
    suspend fun list(
        mine: Boolean,
        limit: Int,
        offset: Int,
    ): TripPostList

    suspend fun detail(id: Long): TripPostDetail

    suspend fun create(input: TripPostCreate): TripPostDetail

    suspend fun delete(id: Long)

    suspend fun saveCourse(id: Long): CourseDetail
}

class CommunityServerClient(
    private val installId: UUID,
) : CommunityClient {
    private val api = PostsApi(API_BASE)

    override suspend fun list(
        mine: Boolean,
        limit: Int,
        offset: Int,
    ): TripPostList =
        withContext(Dispatchers.IO) {
            if (mine) api.listMyPosts(AppLanguage.current, limit, offset) else api.listPosts(AppLanguage.current, limit, offset)
        }

    override suspend fun detail(id: Long): TripPostDetail = withContext(Dispatchers.IO) { api.getPost(id, AppLanguage.current) }

    override suspend fun create(input: TripPostCreate): TripPostDetail =
        withContext(Dispatchers.IO) {
            api.createPost(input, AppLanguage.current)
        }

    override suspend fun delete(id: Long) {
        withContext(Dispatchers.IO) { api.deletePost(id) }
    }

    override suspend fun saveCourse(id: Long): CourseDetail =
        withContext(Dispatchers.IO) {
            api.savePostCourse(installId, id, AppLanguage.current)
        }
}
