package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.routetab.PoiText
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.routetab.RouteDay
import com.mz2az.scenetrip.routetab.RouteStop
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.sceneapi.client.model.TripPostCourse
import com.mz2az.scenetrip.sceneapi.client.model.TripPostDetail
import com.mz2az.scenetrip.sceneapi.client.model.TripPostSummary
import java.net.URI
import java.util.UUID

/** 옛 기기 글의 말머리. 새 글은 여행후기 하나다. */
enum class CommunityBoard(
    val label: String,
) {
    COURSE("코스 추천"),
    REVIEW("장소 후기"),
    PHOTO("인증샷"),
    CHAT("자유"),
}

/** 후기의 코스는 작성 당시 사본이며, 원본 코스가 바뀌어도 함께 바뀌지 않는다. */
data class PostCourse(
    val title: String,
    val days: List<List<Stop>>,
    val serverId: Long? = null,
    val excludedPins: Int = 0,
) {
    data class Stop(
        val placeId: Long?,
        val name: String,
        val address: String?,
        val type: String?,
        val latitude: Double,
        val longitude: Double,
        val imageUrl: URI?,
        val poiId: Long? = null,
        val displayName: String? = null,
        val nameRoman: String? = null,
        val displayAddress: String? = null,
        val categoryLabel: String? = null,
        val dwellMinutes: Int = RouteStop.DEFAULT_STAY_MINUTES,
    ) {
        val shownName: String get() = if (AppLanguage.current == Lang.ko) name else displayName ?: name
        val shownAddress: String? get() = if (AppLanguage.current == Lang.ko) address else displayAddress ?: address
    }

    val placeCount: Int get() = days.sumOf { it.size }

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
                                            id = stop.placeId ?: if (stop.poiId != null) 0 else -(index + 1).toLong(),
                                            name = stop.name,
                                            type = stop.type,
                                            address = stop.address,
                                            latitude = stop.latitude,
                                            longitude = stop.longitude,
                                            imageUrl = stop.imageUrl,
                                        ),
                                    isPinned = stop.placeId == null && stop.poiId == null,
                                    poiId = stop.poiId,
                                    poiText =
                                        stop.poiId?.let {
                                            PoiText(
                                                stop.displayName,
                                                stop.nameRoman,
                                                stop.categoryLabel,
                                                stop.displayAddress,
                                            )
                                        },
                                    stayMinutes = stop.dwellMinutes,
                                )
                            },
                    )
                },
        )

    companion object {
        fun from(course: RouteCourse): PostCourse =
            PostCourse(
                title = course.title,
                serverId = course.serverId,
                excludedPins = course.stops.count { it.isPinned },
                days =
                    course.days.map { day ->
                        day.stops.map { stop ->
                            Stop(
                                stop.savablePlaceId,
                                stop.place.name,
                                stop.place.address,
                                stop.place.type,
                                stop.place.latitude,
                                stop.place.longitude,
                                stop.place.imageUrl,
                                stop.poiId,
                                stop.poiText?.displayName,
                                stop.poiText?.nameRoman,
                                stop.poiText?.displayAddress,
                                stop.poiText?.categoryLabel,
                                stop.stayMinutes,
                            )
                        }
                    },
            )

        fun from(course: TripPostCourse): PostCourse =
            PostCourse(
                course.title,
                course.days.sortedBy { it.dayNumber }.map { day ->
                    day.stops.map { stop ->
                        Stop(
                            stop.placeId,
                            stop.name,
                            stop.address,
                            stop.category,
                            stop.latitude,
                            stop.longitude,
                            stop.imageUrl,
                            stop.poiId,
                            stop.displayName,
                            stop.nameRoman,
                            stop.displayAddress,
                            stop.categoryLabel,
                            stop.dwellMinutes,
                        )
                    }
                },
            )
    }
}

/** 서버 주소와 상세는 메모리에서만 사용하고, 옛 사진 파일·글은 그대로 보존한다. */
data class CommunityPost(
    val id: String = UUID.randomUUID().toString(),
    val board: CommunityBoard = CommunityBoard.REVIEW,
    val title: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    val courseTitle: String? = null,
    val photos: List<String>? = null,
    val course: PostCourse? = null,
    val author: String? = null,
    val serverId: Long? = null,
    val serverIsMine: Boolean? = null,
    val remotePhotos: List<String> = emptyList(),
    val detailLoaded: Boolean = false,
) {
    val isMine: Boolean get() = serverIsMine ?: (author == null)

    fun authorName(myNickname: String?): String =
        author ?: if (serverId != null) {
            tr("탈퇴한 사용자")
        } else {
            myNickname?.trim()?.takeIf { it.isNotEmpty() } ?: tr("나")
        }

    companion object {
        fun from(summary: TripPostSummary): CommunityPost =
            CommunityPost(
                id = "server:${summary.id}",
                serverId = summary.id,
                title = summary.title,
                body = summary.excerpt,
                createdAt = summary.createdAt.toInstant().toEpochMilli(),
                courseTitle = summary.course?.title,
                author = summary.author?.nickname,
                serverIsMine = summary.isMine,
                remotePhotos = listOfNotNull(summary.coverPhotoUrl?.toString()),
            )

        fun from(detail: TripPostDetail): CommunityPost =
            CommunityPost(
                id = "server:${detail.id}",
                serverId = detail.id,
                title = detail.title,
                body = detail.body,
                createdAt = detail.createdAt.toInstant().toEpochMilli(),
                course = detail.course?.let { PostCourse.from(it) },
                author = detail.author?.nickname,
                serverIsMine = detail.isMine,
                remotePhotos = detail.photoUrls.map { it.toString() },
                detailLoaded = true,
            )
    }
}
