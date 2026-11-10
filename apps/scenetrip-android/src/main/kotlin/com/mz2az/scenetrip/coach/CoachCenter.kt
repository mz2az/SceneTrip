package com.mz2az.scenetrip.coach

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 처음 안내를 **한 곳에서** 띄우고 넘긴다 (MZ2AZ-547 P6 C1, 웹 `proto/CoachTour.tsx`, iOS `CoachCenter.swift`).
 *
 * **창이 여럿이다.** 안드로이드의 시트(`IOSSheet`)·확인창(`IOSAlert`)은 앱 창 위에 따로 뜨는 `Dialog` 창이라, 앱 창에 그린 덮개는
 * 시트를 덮지 못한다(「새 여행 코스」 시트의 세 줄이 시트 안에 있다). 그래서 창마다 「층」 번호를 매기고([pushLayer]),
 * 단추는 자기가 든 층과 함께 알리고([coachTarget]), 덮개는 **그 단추가 든 층**에 그린다([CoachOverlayHost]).
 * 앱 창이 0층이고, 대화창이 뜰 때마다 위에 쌓인다. 단추가 든 층 위에 다른 층이 있으면 「가려짐」 — 안내를 숨기고 기다리되
 * 4초 건너뛰기로 세지 않는다(iOS 의 `presentedViewController` 확인과 같다).
 *
 * 안내가 떠 있는 동안은 매 화면 단추 자리를 다시 잰다([CoachFollower]) — 시트가 올라오거나 해태가 비켜 서도 말풍선이 붙어 있게.
 */
object CoachCenter {
    /** 지금 떠 있는 안내와 단계. */
    data class Running(
        val tour: CoachTour,
        val index: Int,
    ) {
        val step: CoachStep get() = tour.steps[minOf(index, tour.steps.size - 1)]
        val key: String get() = "${tour.id}#$index"
    }

    /** 덮개가 지금 그릴 것 — 어느 층에, 단추의 화면 좌표(px). 못 찾았거나 가려졌으면 null. */
    data class Shown(
        val run: Running,
        val layer: Int,
        val screenRect: Rect,
    )

    var running by mutableStateOf<Running?>(null)
        private set
    var shown by mutableStateOf<Shown?>(null)
        private set

    /** 스플래시·언어 고르기·사용법이 걷혔나 — 그 전에는 밑에 깔린 홈 단추를 가리키지 않는다. */
    var appReady by mutableStateOf(false)

    private val queue = CoachQueue()
    private var seen: CoachSeenStore? = null

    /** 확인용 뒷문 — `--ei coachStep 2` 면 안내를 둘째 단계부터 띄운다. */
    private var firstStep = 0

    /** 앱이 처음 뜰 때 한 번. [reset] 은 확인용 뒷문 `--ez coachReset true`(웹 `?coach=reset`, iOS `-coachReset 1`). */
    fun install(
        context: Context,
        reset: Boolean,
        step: Int,
    ) {
        val store = CoachSeenStore(PrefsSeenStorage(context.applicationContext))
        if (reset) store.reset()
        seen = store
        firstStep = maxOf(step - 1, 0)
    }

    /** 아직 [install] 전이면(시험용 화면 등) 본 것으로 친다 — 안내를 띄우지 않는다. */
    fun isSeen(tour: CoachTour): Boolean = seen?.isSeen(tour.id) ?: true

    /** 띄웠으면 true. 이미 봤거나 다른 안내가 떠 있으면 false — 부른 쪽이 잠시 뒤 다시 묻는다. */
    fun start(tour: CoachTour): Boolean {
        if (tour.steps.isEmpty() || !queue.start(tour.id, isSeen(tour))) return false
        running = Running(tour, minOf(firstStep, tour.steps.size - 1))
        return true
    }

    /** 다음·건너뛰기·덮개 누름·단추 없음·화면 떠남. 지금 떠 있는 안내([tourId])가 아니면 아무것도 안 한다. */
    fun perform(
        action: CoachAction,
        tourId: String,
    ) {
        val current = running ?: return
        if (current.tour.id != tourId) return
        when (val outcome = CoachRules.advance(current.index, current.tour.steps.size, action)) {
            // 다음 단계로 — 덮개는 그대로 두고 새 단추 자리로 옮겨 간다(한 화면 꺼졌다 켜지며 깜박이지 않게).
            is CoachOutcome.Step -> {
                running = current.copy(index = outcome.index)
            }

            is CoachOutcome.Finished -> {
                if (outcome.markSeen) seen?.markSeen(tourId)
                queue.end(tourId)
                running = null
                shown = null
            }
        }
    }

    // ── 층(창) ──

    private var nextLayer = 1

    /** 아래(앱 창 0)부터 위로. 대화창이 뜨면 [pushLayer], 닫히면 [popLayer]. 그리는 중에 바뀌므로 화면이 읽는 상태로 두지 않는다 — 재는 쪽([locate])만 읽는다. */
    private val layers = mutableListOf(0)

    fun pushLayer(): Int {
        val id = nextLayer++
        layers.add(id)
        return id
    }

    fun popLayer(id: Int) {
        layers.remove(id)
    }

    // ── 단추 찾기 ──

    /** 표식 하나 — 어느 층의 무엇인지, 그 자리(재는 순간 다시 읽는다). */
    internal class Probe(
        val layer: Int,
        val view: View,
    ) {
        var coordinates: LayoutCoordinates? = null
    }

    private val probes = mutableMapOf<String, MutableList<Probe>>()

    internal fun register(
        id: String,
        probe: Probe,
    ) {
        val list = probes.getOrPut(id) { mutableListOf() }
        if (probe !in list) list.add(probe)
    }

    internal fun unregister(
        id: String,
        probe: Probe,
    ) {
        probes[id]?.remove(probe)
    }

    sealed class Lookup {
        /** 그 층 창의 화면 좌표(px). */
        data class Found(
            val layer: Int,
            val screenRect: Rect,
        ) : Lookup()

        /** 단추는 있지만 그 위에 다른 창(시트·확인창)이 떠 있다 — 숨기고 기다린다. 「없음」으로 세지 않는다. */
        data object Covered : Lookup()

        data object Missing : Lookup()
    }

    fun locate(id: String): Lookup {
        var sawCovered = false
        for (probe in probes[id].orEmpty()) {
            val coords = probe.coordinates?.takeIf { it.isAttached } ?: continue
            val bounds = coords.boundsInWindow()
            // 스크롤로 밀려 나갔거나 아직 안 그려진 것 — 크기가 없다.
            if (bounds.width <= 0f && bounds.height <= 0f) continue
            if (!appReady || layers.lastOrNull() != probe.layer) {
                sawCovered = true
                continue
            }
            val origin = IntArray(2).also { probe.view.getLocationOnScreen(it) }
            return Lookup.Found(probe.layer, bounds.translate(origin[0].toFloat(), origin[1].toFloat()))
        }
        return if (sawCovered) Lookup.Covered else Lookup.Missing
    }

    /** [CoachFollower] 가 매 화면 부른다. 안 보인 지 4초가 넘으면 그 단계를 건너뛴다(가려진 동안은 세지 않는다). */
    internal suspend fun follow(run: Running) {
        var lastSeen = System.currentTimeMillis()
        while (running == run) {
            when (val found = locate(run.step.target)) {
                is Lookup.Found -> {
                    lastSeen = System.currentTimeMillis()
                    val old = shown
                    if (old == null || old.run != run || old.layer != found.layer || moved(old.screenRect, found.screenRect)) {
                        shown = Shown(run, found.layer, found.screenRect)
                    }
                }

                Lookup.Covered -> {
                    lastSeen = System.currentTimeMillis()
                    shown = null
                }

                Lookup.Missing -> {
                    shown = null
                    val waited = (System.currentTimeMillis() - lastSeen) / 1000.0
                    if (CoachRules.shouldSkipMissing(waited)) {
                        perform(CoachAction.TARGET_MISSING, run.tour.id)
                        return
                    }
                }
            }
            withFrameMillis { }
        }
    }

    private fun moved(
        old: Rect,
        new: Rect,
    ): Boolean = abs(old.left - new.left) + abs(old.top - new.top) + abs(old.width - new.width) + abs(old.height - new.height) >= 0.5f
}

/** 본 안내 이름 — 앱 설정 파일 하나에 쉼표로 이어 둔다(이름에는 쉼표가 없다). */
private class PrefsSeenStorage(
    context: Context,
) : CoachSeenStorage {
    private val prefs = context.getSharedPreferences("scenetrip", Context.MODE_PRIVATE)

    override fun getIds(key: String): List<String>? = prefs.getString(key, null)?.split(',')?.filter { it.isNotEmpty() }

    override fun setIds(
        key: String,
        ids: List<String>,
    ) {
        prefs.edit().putString(key, ids.joinToString(",")).apply()
    }
}

/** 앱 창(0층)에 한 번 — 안내가 떠 있는 동안 단추 자리를 매 화면 다시 잰다. */
@Composable
fun CoachFollower() {
    val run = CoachCenter.running
    LaunchedEffect(run?.key) {
        if (run != null) CoachCenter.follow(run)
    }
}

/** 안내가 가리킬 단추라고 표시한다. 이름은 [CoachStep.target]. 단추가 든 층은 [LocalCoachLayer] 가 안다. */
fun Modifier.coachTarget(id: String): Modifier =
    composed {
        val layer = LocalCoachLayer.current
        val view = LocalView.current
        val probe = remember(layer, view) { CoachCenter.Probe(layer, view) }
        DisposableEffect(id, probe) {
            CoachCenter.register(id, probe)
            onDispose {
                CoachCenter.unregister(id, probe)
                probe.coordinates = null
            }
        }
        onGloballyPositioned { probe.coordinates = it }
    }

/** [active] 가 되면(그리고 아직 안 봤으면) [CoachTour.delaySeconds] 뒤에 띄운다 — 화면이 떠나거나 조건이 풀리면 접는다(본 것으로 치지 않는다). */
@Composable
fun CoachTourEffect(
    tour: CoachTour,
    active: Boolean,
) {
    LaunchedEffect(tour.id, active) {
        if (!active) {
            CoachCenter.perform(CoachAction.LEAVE, tour.id)
            return@LaunchedEffect
        }
        delay((tour.delaySeconds * 1000).roundToLong())
        // 한 번에 하나 — 다른 안내가 떠 있으면 그것이 끝날 때까지 기다린다.
        while (isActive && !CoachCenter.isSeen(tour)) {
            if (CoachCenter.start(tour) || CoachCenter.running?.tour?.id == tour.id) return@LaunchedEffect
            delay((CoachRules.RETRY_INTERVAL * 1000).roundToLong())
        }
    }
    DisposableEffect(tour.id) {
        onDispose { CoachCenter.perform(CoachAction.LEAVE, tour.id) }
    }
}
