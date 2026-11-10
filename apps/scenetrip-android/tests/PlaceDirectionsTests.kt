package com.mz2az.scenetrip

import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.routetab.RouteCourse
import com.mz2az.scenetrip.routetab.RouteDay
import com.mz2az.scenetrip.routetab.RouteStop
import com.mz2az.scenetrip.sceneapi.client.model.CourseOrigin
import com.mz2az.scenetrip.sceneapi.client.model.CourseStatus
import com.mz2az.scenetrip.sceneapi.client.model.CourseSummary
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.PlaceSummary
import com.mz2az.scenetrip.searchtab.PlaceDirections
import com.mz2az.scenetrip.searchtab.PlaceDirectionsDeps
import com.mz2az.scenetrip.searchtab.PlaceDirectionsFailure
import com.mz2az.scenetrip.searchtab.PlaceDirectionsOutcome
import com.mz2az.scenetrip.searchtab.PlaceDirectionsRules
import com.mz2az.scenetrip.searchtab.PlaceDirectionsStep
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * 촬영지 상세 「길찾기」(MZ2AZ-532 → MZ2AZ-547 G3) — 한 곳짜리 코스를 만들어 곧바로 여행 모드를 연다.
 * 비회원이면 로그인 뒤 이어서. iOS `PlaceDirectionsTests` 를 하나씩 옮겼다. 서버·로그인 창은 전부 가짜다.
 * iOS `@MainActor` 처럼 한 스레드(runBlocking)에서 돌려, 동시에 누르기는 [DirectionsGate] 로 멈춰 세워 잰다.
 */
class PlaceDirectionsTests {
    private val place = PlaceSummary(id = 42, name = "도깨비 방파제", address = "강릉시 주문진읍", latitude = 37.89, longitude = 128.83)

    // ── 제목 ──

    @Test
    fun titleIsPlaceNamePlusGaneunGil() {
        assertEquals("도깨비 방파제 가는 길", PlaceDirectionsRules.title("도깨비 방파제", Lang.ko))
    }

    @Test
    fun titleInEnglishIsTripTo() {
        assertEquals("Trip to 도깨비 방파제", PlaceDirectionsRules.title("도깨비 방파제", Lang.en))
    }

    /** 번역 표에 「%s 가는 길」 → "Trip to %s" 가 있어야 영어 화면에 한국어가 섞이지 않는다. */
    @Test
    fun titleWordingHasEnglishTranslation() {
        assertEquals("Trip to %s", tr("%s 가는 길", Lang.en))
    }

    @Test
    fun titleMaxIs50() {
        assertEquals(50, PlaceDirectionsRules.TITLE_MAX)
    }

    /** 딱 50자면 자르지 않는다 — 「가」45 + 「 가는 길」5. */
    @Test
    fun titleExactly50IsNotCut() {
        val name = "가".repeat(45)
        val title = PlaceDirectionsRules.title(name, Lang.ko)
        assertEquals("$name 가는 길", title)
        assertEquals(50, title.length)
    }

    /** 넘으면 장소 이름 끝을 말줄임(…)으로 잘라 50자 안에 둔다. */
    @Test
    fun longKoreanTitleIsCutWithEllipsisWithin50() {
        val title = PlaceDirectionsRules.title("가".repeat(60), Lang.ko)
        assertTrue(title.length <= 50, title)
        assertTrue(title.endsWith("… 가는 길"), title)
        assertEquals("가".repeat(44) + "… 가는 길", title)
    }

    /** 잘린 끝의 빈칸은 말줄임 앞에서 지운다. */
    @Test
    fun cutTrimsTrailingSpaceBeforeEllipsis() {
        val name = "가".repeat(43) + " " + "나".repeat(10)
        assertEquals("가".repeat(43) + "… 가는 길", PlaceDirectionsRules.title(name, Lang.ko))
    }

    @Test
    fun longEnglishTitleIsCutWithin50() {
        val title = PlaceDirectionsRules.title("Former Seoul Station Plaza and the Old Train Platform Café Street Market", Lang.en)
        assertTrue(title.length <= 50, title)
        assertTrue(title.startsWith("Trip to Former Seoul Station"), title)
        assertTrue(title.endsWith("…"), title)
    }

    /** 길이는 UTF-16 단위로 잰다 — 두 단위짜리 글자(이모지)도 50 안에 들고, 글자 반쪽(짝 잃은 서로게이트)을 남기지 않는다. */
    @Test
    fun titleLengthIsMeasuredInUtf16AndDoesNotSplitSurrogates() {
        val title = PlaceDirectionsRules.title("😀".repeat(40), Lang.ko)
        assertTrue(title.length <= 50, "length=${title.length}")
        assertTrue(title.endsWith("… 가는 길"), title)
        val name = title.removeSuffix("… 가는 길")
        assertTrue(name.isNotEmpty(), title)
        assertFalse(Character.isHighSurrogate(name.last()), "짝 잃은 서로게이트: $title")
        assertEquals(0, name.length % 2, title)
    }

    // ── 한 곳짜리 코스 ──

    @Test
    fun courseIsOneDayOneStopOfThatPlace() {
        val course = PlaceDirectionsRules.course(place, Lang.ko)
        assertNull(course.serverId)
        assertEquals(1, course.days.size)
        assertEquals(1, course.stops.size)
        val stop = course.stops.first()
        assertEquals(42L, stop.place.id)
        // 촬영지 칸 — 편의시설도 개인 핀도 아니다.
        assertNull(stop.poiId)
        assertNull(stop.customPinId)
        assertFalse(stop.isPinned)
        assertEquals("도깨비 방파제 가는 길", course.title)
        assertFalse(course.madeByAI)
        assertFalse(course.isRunning)
    }

    @Test
    fun courseTitleFollowsLanguage() {
        assertEquals("Trip to 도깨비 방파제", PlaceDirectionsRules.course(place, Lang.en).title)
    }

    // ── 다시 열어도 되는 코스인가 ──

    @Test
    fun isDirectionsCourseOnlyForOneDayOneStopOfThatPlace() {
        assertTrue(PlaceDirectionsRules.isDirectionsCourse(saved(700, listOf(listOf(42))), 42))
        // 다른 장소
        assertFalse(PlaceDirectionsRules.isDirectionsCourse(saved(701, listOf(listOf(7))), 42))
        // 사용자가 다른 곳을 더했다
        assertFalse(PlaceDirectionsRules.isDirectionsCourse(saved(702, listOf(listOf(42, 7))), 42))
        // 일차가 둘
        assertFalse(PlaceDirectionsRules.isDirectionsCourse(saved(703, listOf(listOf(42), emptyList())), 42))
        // 비었다
        assertFalse(PlaceDirectionsRules.isDirectionsCourse(saved(704, listOf(emptyList())), 42))
    }

    /** 한 칸이어도 촬영지가 아니면(편의시설·직접 찍은 핀·개인 핀) 「가는 길」이 아니다. */
    @Test
    fun isDirectionsCourseRequiresAPlaceStop() {
        fun oneStop(stop: RouteStop) =
            RouteCourse(serverId = 710, title = "도깨비 방파제 가는 길", days = listOf(RouteDay(stops = listOf(stop))), detailLoaded = true)
        val p = PlaceSummary(id = 42, name = "장소 42", latitude = 37.0, longitude = 127.0)
        assertTrue(PlaceDirectionsRules.isDirectionsCourse(oneStop(RouteStop(place = p)), 42))
        assertFalse(PlaceDirectionsRules.isDirectionsCourse(oneStop(RouteStop(place = p, poiId = 42)), 42))
        assertFalse(PlaceDirectionsRules.isDirectionsCourse(oneStop(RouteStop(place = p, isPinned = true)), 42))
        assertFalse(PlaceDirectionsRules.isDirectionsCourse(oneStop(RouteStop(place = p, customPinId = 3)), 42))
    }

    // ── 목록(요약)에서 후보 찾기 ──

    @Test
    fun candidatesAreActiveOneDayOnePlaceWithSameTitle() {
        val title = "도깨비 방파제 가는 길"
        val courses =
            listOf(
                summary(1, title, days = 1, places = 1, running = true), // 후보
                summary(2, title, days = 1, places = 1, running = false), // 여행 중 아님
                summary(3, title, days = 2, places = 1, running = true), // 2일
                summary(4, title, days = 1, places = 2, running = true), // 2곳
                summary(5, "다른 코스", days = 1, places = 1, running = true), // 제목 다름
                summary(6, title, days = 1, places = 1, running = true), // 후보
            )
        assertEquals(listOf(1L, 6L), PlaceDirectionsRules.candidates(courses, place, Lang.ko))
    }

    @Test
    fun candidatesUseTheTitleInTheGivenLanguage() {
        val courses =
            listOf(
                summary(1, "도깨비 방파제 가는 길", days = 1, places = 1, running = true),
                summary(2, "Trip to 도깨비 방파제", days = 1, places = 1, running = true),
            )
        assertEquals(listOf(2L), PlaceDirectionsRules.candidates(courses, place, Lang.en))
    }

    // ── 로그인한 사람 ──

    @Test
    fun createsStartsAndOpensTheCourse() =
        test {
            val fake = FakeDirectionsDeps()
            val outcome = PlaceDirections(fake).start(place)
            assertEquals(PlaceDirectionsOutcome.Started(901), outcome)
            assertEquals(1, fake.created.size)
            assertEquals(
                listOf(42L),
                fake.created
                    .first()
                    .stops
                    .map { it.place.id },
            )
            assertEquals("도깨비 방파제 가는 길", fake.created.first().title)
            assertEquals(listOf<Long?>(901), fake.started.map { it.serverId })
            assertEquals(listOf(901L), fake.opened)
            assertEquals(0, fake.signInAsked)
        }

    /** 만든 코스를 장소 id 로 기억한다 — 상세를 닫았다 다시 열어도 같은 코스를 쓴다. */
    @Test
    fun remembersMadeCourseAfterCreate() =
        test {
            val fake = FakeDirectionsDeps()
            PlaceDirections(fake).start(place)
            assertEquals(901L, fake.made[42])
        }

    @Test
    fun pressingTwiceWhileRunningCreatesOnceAndSecondIsBusy() =
        test {
            val fake = FakeDirectionsDeps()
            val gate = DirectionsGate<Unit>()
            fake.createGate = gate
            val directions = PlaceDirections(fake)
            val first = async { directions.start(place) }
            settle { gate.entered }
            val second = directions.start(place)
            assertEquals(PlaceDirectionsOutcome.Busy, second)
            gate.open(Unit)
            assertEquals(PlaceDirectionsOutcome.Started(901), first.await())
            assertEquals(1, fake.created.size)
            assertEquals(listOf(901L), fake.opened)
        }

    @Test
    fun createFailureReturnsItsMessageAndDoesNotStartOrOpen() =
        test {
            val fake = FakeDirectionsDeps()
            fake.createFailure = "연결이 원활하지 않아요"
            val outcome = PlaceDirections(fake).start(place)
            assertEquals(PlaceDirectionsOutcome.Failed("연결이 원활하지 않아요"), outcome)
            assertEquals(1, fake.created.size)
            assertTrue(fake.started.isEmpty())
            assertTrue(fake.opened.isEmpty())
            assertNull(fake.made[42])
        }

    /** 만들었다는데 서버 id 가 없으면 열 코스가 없다 — 「코스를 불러오지 못했어요」. */
    @Test
    fun createdCourseWithoutServerIdFails() =
        test {
            val fake = FakeDirectionsDeps()
            fake.createReturnsNoServerId = true
            val outcome = PlaceDirections(fake).start(place)
            assertEquals(PlaceDirectionsOutcome.Failed("코스를 불러오지 못했어요"), outcome)
            assertEquals(1, fake.created.size)
            assertTrue(fake.started.isEmpty())
            assertTrue(fake.opened.isEmpty())
            assertTrue(fake.made.isEmpty())
        }

    /** 시작이 실패하면 열지 않고, 다시 누르면 새로 만들지 않고 시작만 다시 한다. */
    @Test
    fun startFailureThenRetryStartsOnlyWithoutCreatingAgain() =
        test {
            val fake = FakeDirectionsDeps()
            fake.startFailures += "시작하지 못했어요"
            val directions = PlaceDirections(fake)
            assertEquals(PlaceDirectionsOutcome.Failed("시작하지 못했어요"), directions.start(place))
            assertTrue(fake.opened.isEmpty())
            assertEquals(PlaceDirectionsOutcome.Started(901), directions.start(place))
            assertEquals(1, fake.created.size)
            assertEquals(listOf<Long?>(901, 901), fake.started.map { it.serverId })
            assertEquals(listOf(901L), fake.opened)
        }

    /** 시작이 실패한 코스를 다른 컨트롤러(상세를 다시 연 것)가 눌러도 만들지 않고 시작만 한다. */
    @Test
    fun startFailureThenNewControllerStartsOnly() =
        test {
            val fake = FakeDirectionsDeps()
            fake.startFailures += "x"
            PlaceDirections(fake).start(place)
            val outcome = PlaceDirections(fake).start(place)
            assertEquals(PlaceDirectionsOutcome.Started(901), outcome)
            assertEquals(1, fake.created.size)
        }

    // ── 이미 있는 「가는 길」 다시 쓰기 ──

    /** 받아 둔 목록에 이 장소의 여행 중 1곳짜리 코스가 있으면 만들지도, 시작을 다시 하지도 않고 연다. */
    @Test
    fun existingRunningCourseIsOpenedWithoutCreateOrStart() =
        test {
            val fake = FakeDirectionsDeps()
            fake.server[700] = saved(700, listOf(listOf(42)), running = true)
            fake.candidates = listOf(700)
            val outcome = PlaceDirections(fake).start(place)
            assertEquals(PlaceDirectionsOutcome.Started(700), outcome)
            assertTrue(fake.created.isEmpty())
            assertTrue(fake.started.isEmpty())
            assertEquals(listOf(700L), fake.opened)
        }

    /** 여행 중이 아닌 기존 「가는 길」은 시작만 하고 연다. */
    @Test
    fun existingNotRunningCourseIsStartedThenOpened() =
        test {
            val fake = FakeDirectionsDeps()
            fake.server[705] = saved(705, listOf(listOf(42)), running = false)
            fake.candidates = listOf(705)
            val outcome = PlaceDirections(fake).start(place)
            assertEquals(PlaceDirectionsOutcome.Started(705), outcome)
            assertTrue(fake.created.isEmpty())
            assertEquals(listOf<Long?>(705), fake.started.map { it.serverId })
            assertEquals(listOf(705L), fake.opened)
        }

    /** 후보 속을 읽어 보니 다른 장소의 코스면 새로 만든다. */
    @Test
    fun candidateOfAnotherPlaceIsNotReused() =
        test {
            val fake = FakeDirectionsDeps()
            fake.server[701] = saved(701, listOf(listOf(7)), running = true)
            fake.candidates = listOf(701)
            val outcome = PlaceDirections(fake).start(place)
            assertEquals(listOf(701L), fake.loaded)
            assertEquals(1, fake.created.size)
            assertEquals(listOf(901L), fake.opened)
            assertEquals(PlaceDirectionsOutcome.Started(901), outcome)
        }

    /** 새 컨트롤러(상세를 닫았다 다시 연 것)라도 같은 장소면 새로 만들지 않는다 — 기억은 deps 의 `made`. */
    @Test
    fun newControllerReusesRememberedCourse() =
        test {
            val fake = FakeDirectionsDeps()
            PlaceDirections(fake).start(place)
            val outcome = PlaceDirections(fake).start(place)
            assertEquals(PlaceDirectionsOutcome.Started(901), outcome)
            assertEquals(1, fake.created.size)
            assertEquals(listOf(901L, 901L), fake.opened)
        }

    /** 기억한 코스를 먼저 본다 — 목록 후보보다 앞. */
    @Test
    fun rememberedCourseIsCheckedBeforeListCandidates() =
        test {
            val fake = FakeDirectionsDeps()
            fake.server[800] = saved(800, listOf(listOf(42)), running = true)
            fake.server[700] = saved(700, listOf(listOf(42)), running = true)
            fake.made[42] = 800
            fake.candidates = listOf(700, 800)
            val outcome = PlaceDirections(fake).start(place)
            assertEquals(PlaceDirectionsOutcome.Started(800), outcome)
            assertEquals(800L, fake.loaded.firstOrNull())
            assertTrue(fake.created.isEmpty())
            assertEquals(listOf(800L), fake.opened)
        }

    /** 기억한 코스가 지워졌으면 기억을 버리고 새로 만든다(새 코스를 기억한다). */
    @Test
    fun deletedRememberedCourseIsForgottenAndReplaced() =
        test {
            val fake = FakeDirectionsDeps()
            fake.made[42] = 555 // 서버에 없다
            val outcome = PlaceDirections(fake).start(place)
            assertEquals(listOf(555L), fake.loaded)
            assertEquals(1, fake.created.size)
            assertEquals(PlaceDirectionsOutcome.Started(901), outcome)
            assertEquals(901L, fake.made[42])
        }

    /** 기억한 코스를 사용자가 고쳐 다른 곳을 더했으면 기억을 버린다 — 만들기가 실패해도 다시 기억하지 않는다. */
    @Test
    fun editedRememberedCourseIsForgotten() =
        test {
            val fake = FakeDirectionsDeps()
            fake.server[556] = saved(556, listOf(listOf(42, 7)), running = true)
            fake.made[42] = 556
            fake.createFailure = "x"
            val outcome = PlaceDirections(fake).start(place)
            assertEquals(PlaceDirectionsOutcome.Failed("x"), outcome)
            assertEquals(listOf(556L), fake.loaded)
            assertNull(fake.made[42])
            assertTrue(fake.opened.isEmpty())
        }

    // ── 비회원 ──

    @Test
    fun signInClosedCancelsWithoutCreating() =
        test {
            val fake = FakeDirectionsDeps(signedIn = false)
            fake.signInResult = false
            val outcome = PlaceDirections(fake).start(place)
            assertEquals(1, fake.signInAsked)
            assertEquals(PlaceDirectionsOutcome.Cancelled, outcome)
            assertTrue(fake.created.isEmpty())
            assertTrue(fake.started.isEmpty())
            assertTrue(fake.opened.isEmpty())
        }

    @Test
    fun signedInContinuesWithTheSamePlace() =
        test {
            val fake = FakeDirectionsDeps(signedIn = false)
            fake.signInResult = true
            val outcome = PlaceDirections(fake).start(place)
            assertEquals(1, fake.signInAsked)
            assertEquals(1, fake.created.size)
            assertEquals(
                42L,
                fake.created
                    .first()
                    .stops
                    .first()
                    .place.id,
            )
            assertEquals(listOf(901L), fake.opened)
            assertEquals(PlaceDirectionsOutcome.Started(901), outcome)
        }

    /** 로그인 시트가 떠 있는 동안 또 눌러도 시트를 또 띄우지 않는다. */
    @Test
    fun pressWhileSignInSheetIsUpIsBusy() =
        test {
            val fake = FakeDirectionsDeps(signedIn = false)
            val gate = DirectionsGate<Boolean>()
            fake.signInGate = gate
            val directions = PlaceDirections(fake)
            val first = async { directions.start(place) }
            settle { gate.entered }
            val second = directions.start(place)
            assertEquals(PlaceDirectionsOutcome.Busy, second)
            gate.open(true)
            first.await()
            assertEquals(1, fake.signInAsked)
            assertEquals(1, fake.created.size)
        }

    /** 로그인을 기다리는 사이 화면을 떠났으면(dispose) 로그인돼도 만들지 않는다. */
    @Test
    fun disposeWhileSignInPendingCancelsAfterSignIn() =
        test {
            val fake = FakeDirectionsDeps(signedIn = false)
            val gate = DirectionsGate<Boolean>()
            fake.signInGate = gate
            val directions = PlaceDirections(fake)
            val pending = async { directions.start(place) }
            settle { gate.entered }
            directions.dispose()
            gate.open(true)
            val outcome = pending.await()
            assertEquals(1, fake.signInAsked)
            assertEquals(PlaceDirectionsOutcome.Cancelled, outcome)
            assertTrue(fake.created.isEmpty())
            assertTrue(fake.opened.isEmpty())
        }

    // ── 요청 중에 화면을 떠나면 ──

    /** 만들기 중에 떠났으면 여행 중으로 바꾸지 않고 열지 않는다(만든 코스는 기억해 둔다). */
    @Test
    fun disposeDuringCreateDoesNotStartOrOpen() =
        test {
            val fake = FakeDirectionsDeps()
            val gate = DirectionsGate<Unit>()
            fake.createGate = gate
            val directions = PlaceDirections(fake)
            val pending = async { directions.start(place) }
            settle { gate.entered }
            directions.dispose()
            gate.open(Unit)
            val outcome = pending.await()
            assertEquals(PlaceDirectionsOutcome.Cancelled, outcome)
            assertEquals(1, fake.created.size)
            assertEquals(901L, fake.made[42])
            assertTrue(fake.started.isEmpty())
            assertTrue(fake.opened.isEmpty())
        }

    /** 기존 코스를 확인하는 중에 떠났어도 시작·열기를 하지 않는다. */
    @Test
    fun disposeDuringExistingCheckDoesNotStartOrOpen() =
        test {
            val fake = FakeDirectionsDeps()
            fake.server[702] = saved(702, listOf(listOf(42)), running = false)
            fake.candidates = listOf(702)
            val gate = DirectionsGate<Unit>()
            fake.loadGate = gate
            val directions = PlaceDirections(fake)
            val pending = async { directions.start(place) }
            settle { gate.entered }
            directions.dispose()
            gate.open(Unit)
            val outcome = pending.await()
            assertEquals(listOf(702L), fake.loaded)
            assertEquals(PlaceDirectionsOutcome.Cancelled, outcome)
            assertTrue(fake.created.isEmpty())
            assertTrue(fake.started.isEmpty())
            assertTrue(fake.opened.isEmpty())
        }

    /** 떠난 뒤의 누름은 아무것도 하지 않는다 — 버린 누름(Busy). */
    @Test
    fun startAfterDisposeDoesNothing() =
        test {
            val fake = FakeDirectionsDeps()
            val directions = PlaceDirections(fake)
            directions.dispose()
            val outcome = directions.start(place)
            assertEquals(PlaceDirectionsOutcome.Busy, outcome)
            assertTrue(fake.created.isEmpty())
            assertTrue(fake.opened.isEmpty())
            assertEquals(0, fake.signInAsked)
        }

    // ── 도우미 ──

    /** 한 스레드에서 돌린다(iOS @MainActor). 구현이 문을 안 열고 멈추면 5초 뒤 실패로 끝낸다. */
    private fun test(block: suspend CoroutineScope.() -> Unit) {
        runBlocking { withTimeout(5_000) { block() } }
    }

    /** 가짜가 문 앞에 닿을 때까지 기다린다. 닿지 않으면(구현이 그 길로 가지 않으면) 잠시 뒤 그냥 넘어간다. */
    private suspend fun settle(reached: () -> Boolean) {
        repeat(1000) {
            if (reached()) return
            yield()
        }
    }

    /** 서버에 저장된(상세를 받은) 코스 — `stops` 는 일차마다 장소 id 목록. */
    private fun saved(
        id: Long,
        stops: List<List<Long>>,
        running: Boolean = false,
    ) = RouteCourse(
        serverId = id,
        title = "도깨비 방파제 가는 길",
        days =
            stops.map { ids ->
                RouteDay(stops = ids.map { RouteStop(place = PlaceSummary(id = it, name = "장소 $it", latitude = 37.0, longitude = 127.0)) })
            },
        isRunning = running,
        detailLoaded = true,
    )

    /** 코스 목록의 요약(계약 `CourseSummary`). 여행 중이면 status active. */
    private fun summary(
        id: Long,
        title: String,
        days: Int,
        places: Int,
        running: Boolean,
    ) = CourseSummary(
        id = id,
        title = title,
        dayCount = days,
        status = if (running) CourseStatus.active else CourseStatus.upcoming,
        origin = CourseOrigin.self,
        placeCount = places,
        createdAt = OffsetDateTime.parse("2026-11-01T00:00:00Z"),
        updatedAt = OffsetDateTime.parse("2026-11-01T00:00:00Z"),
    )
}

/** 늦게 끝나는 문 — 「진행 중에 또 누른다」·「도중에 떠난다」를 재기 위해. */
private class DirectionsGate<T> {
    private val value = CompletableDeferred<T>()
    var entered = false
        private set

    suspend fun wait(): T {
        entered = true
        return value.await()
    }

    fun open(result: T) {
        value.complete(result)
    }
}

/** [PlaceDirectionsDeps] 의 가짜. 만든 코스는 900+n 번이고, 시작하면 가짜 서버의 그 코스가 여행 중이 된다. */
private class FakeDirectionsDeps(
    var signedIn: Boolean = true,
) : PlaceDirectionsDeps {
    var signInResult = false
    var signInGate: DirectionsGate<Boolean>? = null
    var signInAsked = 0

    val created = mutableListOf<RouteCourse>()
    var createFailure: String? = null
    var createReturnsNoServerId = false
    var createGate: DirectionsGate<Unit>? = null

    val started = mutableListOf<RouteCourse>()

    /** 앞에서부터 하나씩 꺼내 실패로 돌려준다. 비면 성공. */
    val startFailures = mutableListOf<String>()

    val opened = mutableListOf<Long>()
    override val made: MutableMap<Long, Long> = mutableMapOf()
    var candidates: List<Long> = emptyList()
    val server = mutableMapOf<Long, RouteCourse>()
    val loaded = mutableListOf<Long>()
    var loadGate: DirectionsGate<Unit>? = null

    override fun isSignedIn(): Boolean = signedIn

    override suspend fun askSignIn(): Boolean {
        signInAsked += 1
        val ok = signInGate?.wait() ?: signInResult
        if (ok) signedIn = true
        return ok
    }

    override suspend fun createCourse(course: RouteCourse): Result<RouteCourse> {
        created.add(course)
        createGate?.wait()
        createFailure?.let { return Result.failure(PlaceDirectionsFailure(it)) }
        if (createReturnsNoServerId) return Result.success(course)
        val id = 900L + created.size
        val saved = course.copy(serverId = id, detailLoaded = true)
        server[id] = saved
        return Result.success(saved)
    }

    override suspend fun startCourse(course: RouteCourse): PlaceDirectionsStep {
        started.add(course)
        if (startFailures.isNotEmpty()) return PlaceDirectionsStep.Failed(startFailures.removeAt(0))
        val id = course.serverId
        if (id != null) server[id]?.let { server[id] = it.copy(isRunning = true) }
        return PlaceDirectionsStep.Ok
    }

    override fun openTrip(courseId: Long) {
        opened.add(courseId)
    }

    override fun existingCandidates(place: PlaceSummary): List<Long> = candidates

    override suspend fun loadCourse(courseId: Long): RouteCourse? {
        loaded.add(courseId)
        loadGate?.wait()
        return server[courseId]
    }
}
