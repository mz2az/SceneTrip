package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.ApiFailure
import com.mz2az.scenetrip.data.CourseServer
import com.mz2az.scenetrip.data.RouteCourseSession
import com.mz2az.scenetrip.data.englishOf
import com.mz2az.scenetrip.routetab.RouteBridge
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.routetab.RouteCourseOpening
import com.mz2az.scenetrip.routetab.RouteDay
import com.mz2az.scenetrip.sceneapi.client.model.CourseCreate
import com.mz2az.scenetrip.sceneapi.client.model.CourseDay
import com.mz2az.scenetrip.sceneapi.client.model.CourseDetail
import com.mz2az.scenetrip.sceneapi.client.model.CourseItem
import com.mz2az.scenetrip.sceneapi.client.model.CourseItemSource
import com.mz2az.scenetrip.sceneapi.client.model.CourseOrigin
import com.mz2az.scenetrip.sceneapi.client.model.CourseReplace
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.sceneapi.client.model.TravelBasis
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.OffsetDateTime

/**
 * MZ2AZ-474 B1 — 코스 상세를 못 받았을 때 요약만 있는 빈 코스로 편집 화면이 열리고, 「저장」하면 서버 코스를
 * 빈 일차로 덮어쓰던 문제. iOS `RouteCreationTests`의 상세 조회 실패 시험 네 개와 같은 모양이다.
 */
class CourseDetailSafetyTests {
    private class FakeServer(
        var getFails: Boolean = false,
        var replaceFails: Boolean = false,
    ) : CourseServer {
        val gets = mutableListOf<Long>()
        val replaces = mutableListOf<Pair<Long, CourseReplace>>()
        val creates = mutableListOf<CourseCreate>()
        val deletes = mutableListOf<Long>()

        override fun get(courseId: Long): CourseDetail {
            gets += courseId
            if (getFails) throw IOException("offline")
            return detail(courseId, stops = 2)
        }

        override fun create(body: CourseCreate): CourseDetail {
            creates += body
            return detail(99, stops = 0)
        }

        override fun replace(
            courseId: Long,
            body: CourseReplace,
        ): CourseDetail {
            replaces += courseId to body
            if (replaceFails) throw IOException("offline")
            return detail(courseId, stops = body.days.sumOf { it.items.size })
        }

        override fun delete(courseId: Long) {
            deletes += courseId
        }
    }

    private companion object {
        fun detail(
            id: Long,
            stops: Int,
        ): CourseDetail {
            val now = OffsetDateTime.parse("2026-10-16T00:00:00+09:00")
            val items =
                List(stops) { index ->
                    CourseItem(
                        id = 1000L + index,
                        source = CourseItemSource.place,
                        name = "촬영지 $index",
                        latitude = 37.5 + index / 100.0,
                        longitude = 127.0,
                        dwellMinutes = 30,
                        placeId = 10L + index,
                    )
                }
            return CourseDetail(
                id = id,
                title = "서울 하루",
                dayCount = 1,
                status = CourseStatus.upcoming,
                origin = CourseOrigin.self,
                placeCount = stops,
                createdAt = now,
                updatedAt = now,
                days = listOf(CourseDay(1, items, 30 * stops, 0, 30 * stops, TravelBasis.route)),
            )
        }

        /** 목록(`CourseSummary`)에서 만든 사본 — 일차 속이 비어 있다. */
        fun summaryCourse(id: Long = 7): RouteCourse {
            val now = OffsetDateTime.parse("2026-10-16T00:00:00+09:00")
            return RouteBridge.course(
                CourseSummary(
                    id = id,
                    title = "서울 하루",
                    dayCount = 1,
                    status = CourseStatus.upcoming,
                    origin = CourseOrigin.self,
                    placeCount = 2,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    @Test
    fun summaryCopyCannotBeSavedButDetailCopyAndNewCourseCan() {
        val summary = summaryCourse()
        assertFalse(summary.detailLoaded)
        assertFalse(summary.canSave)
        assertTrue(RouteBridge.course(detail(7, 2)).canSave)
        // 아직 서버에 없는 새 코스(직접 짜기 초안)는 상세가 없어도 저장할 수 있다.
        assertTrue(RouteCourse(title = "내 코스", days = listOf(RouteDay())).canSave)
    }

    @Test
    fun detailFailureDoesNotOpenEditorAndSummarySaveIsRefusedWithoutAnyWrite() =
        runBlocking {
            val server = FakeServer(getFails = true)
            val session = RouteCourseSession(server)
            val opening = RouteCourseOpening()
            val summary = summaryCourse()

            opening.open(summary, session::detail)

            assertNull(opening.editing)
            assertEquals(summary, opening.failedCourse)
            assertFalse(opening.isLoading)
            assertNotNull(session.failure)
            // 편집 화면이 안 열렸더라도 요약 사본이 저장 경로에 닿으면 막힌다 — 서버에는 아무 요청도 가지 않는다.
            assertNull(session.save(summary))
            assertTrue(server.replaces.isEmpty())
            assertTrue(server.creates.isEmpty())
            assertEquals(ApiFailure.courseDetailNotLoaded, session.saveFailure)
            assertEquals("코스를 불러오지 못했어요", session.saveFailure?.courseSaveMessage)
        }

    @Test
    fun retryAfterDetailFailureOpensFullCourseAndSavePreservesItsStops() =
        runBlocking {
            val server = FakeServer(getFails = true)
            val session = RouteCourseSession(server)
            val opening = RouteCourseOpening()

            opening.open(summaryCourse(), session::detail)
            assertNull(opening.editing)

            server.getFails = false
            opening.retry(session::detail)

            val editing = opening.editing
            assertNotNull(editing)
            assertNull(opening.failedCourse)
            assertEquals(2, editing!!.stops.size)
            assertTrue(editing.canSave)
            assertNotNull(session.save(editing))
            val (id, body) = server.replaces.single()
            assertEquals(7L, id)
            assertEquals(
                listOf(1000L, 1001L),
                body.days
                    .single()
                    .items
                    .map { it.id },
            )
            assertNull(session.saveFailure)
        }

    @Test
    fun summarySaveIsRefusedEvenWithoutAnEarlierDetailFailure() =
        runBlocking {
            val server = FakeServer()
            val session = RouteCourseSession(server)
            assertNull(session.save(summaryCourse()))
            assertTrue(server.replaces.isEmpty())
            assertEquals(ApiFailure.courseDetailNotLoaded, session.saveFailure)
        }

    @Test
    fun loadedCourseCanIntentionallySaveWithoutStops() =
        runBlocking {
            val server = FakeServer()
            val session = RouteCourseSession(server)
            val loaded = session.detail(summaryCourse())!!
            val emptied = loaded.copy(days = loaded.days.map { it.copy(stops = emptyList()) })
            // 사용자가 일부러 모든 장소를 뺀 코스는 상세를 받은 사본이므로 그대로 저장된다.
            assertNotNull(session.save(emptied))
            assertTrue(
                server.replaces
                    .single()
                    .second.days
                    .all { it.items.isEmpty() },
            )
            assertNull(session.saveFailure)
        }

    @Test
    fun anotherDetailLoadDoesNotUnlockTheOldSummaryCopy() =
        runBlocking {
            val server = FakeServer()
            val session = RouteCourseSession(server)
            val summary = summaryCourse()
            assertNotNull(session.detail(summary))
            assertNull(session.save(summary))
            assertTrue(server.replaces.isEmpty())
        }

    @Test
    fun openingTreatsASummaryReturnedAsDetailAsAFailure() =
        runBlocking {
            val opening = RouteCourseOpening()
            val summary = summaryCourse()
            opening.open(summary) { it }
            assertNull(opening.editing)
            assertEquals(summary, opening.failedCourse)
        }

    @Test
    fun aSecondOpenWhileLoadingIsIgnored() =
        runBlocking {
            val opening = RouteCourseOpening()
            val started = CompletableDeferred<Unit>()
            val reply = CompletableDeferred<RouteCourse?>()
            var requests = 0
            val first =
                async {
                    opening.open(summaryCourse(1)) {
                        requests++
                        started.complete(Unit)
                        reply.await()
                    }
                }
            started.await()
            assertTrue(opening.isLoading)
            opening.open(summaryCourse(2)) {
                requests++
                null
            }
            reply.complete(RouteBridge.course(detail(1, 1)))
            first.await()
            assertEquals(1, requests)
            assertEquals(1L, opening.editing?.serverId)
            assertFalse(opening.isLoading)
        }

    @Test
    fun loadFailureNeverBecomesASaveFailureAndSaveFailureSurvivesAGoodRead() =
        runBlocking {
            val server = FakeServer(getFails = true)
            val session = RouteCourseSession(server)
            assertNull(session.detail(summaryCourse()))
            assertNotNull(session.failure)
            assertNull(session.saveFailure)

            // 저장 요청이 실제로 실패하면 저장 실패가 남고, 그 뒤 읽기가 성공해도 저장 실패는 그대로다.
            val saved = detail(7, 2).let { RouteBridge.course(it) }
            server.replaceFails = true
            assertNull(session.save(saved))
            assertNotNull(session.saveFailure)
            server.getFails = false
            assertNotNull(session.detail(summaryCourse()))
            assertNull(session.failure)
            assertNotNull(session.saveFailure)

            session.clearFailure()
            assertNull(session.saveFailure)
        }

    @Test
    fun newCourseStillSavesAndRollsBackTheShellWhenFillingFails() =
        runBlocking {
            val server = FakeServer()
            val session = RouteCourseSession(server)
            val draft = RouteCourse(title = "내 코스", days = listOf(RouteDay()))
            assertNotNull(session.save(draft))
            assertEquals(1, server.creates.size)
            assertEquals(99L, server.replaces.single().first)

            val failing = FakeServer(replaceFails = true)
            assertNull(RouteCourseSession(failing).save(draft))
            // 만든 빈 코스는 되돌린다 — 저장을 다시 누를 때마다 빈 코스가 쌓이지 않는다.
            assertEquals(listOf(99L), failing.deletes)
        }

    @Test
    fun detailNotLoadedMessageHasEnglishAndKeepsRealFailureMessages() {
        assertEquals("Couldn't load the course", englishOf("코스를 불러오지 못했어요"))
        val offline = ApiFailure(statusCode = null)
        assertEquals(offline.message, offline.courseSaveMessage)
    }
}
