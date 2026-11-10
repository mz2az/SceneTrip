package com.mz2az.scenetrip.coach

import kotlin.math.max
import kotlin.math.min

// 처음 보는 사람을 위한 짧은 안내(코치 마크)의 **규칙** (MZ2AZ-547 P6 C1–C5, 웹 `proto/CoachTour.tsx`, iOS `Coach/CoachRules.swift`).
//
// 단추 하나만 밝게 남기고 나머지를 어둡게 덮은 뒤, 말풍선이 그 단추를 가리키며 무엇을 하는지 말한다.
// 여기에는 화면을 그리지 않는 규칙만 둔다 — 어떤 안내가 있나, 한 번에 하나, 다음·건너뛰기·끝, 본 것 기억,
// 가리킬 단추가 없을 때 건너뛰기, 말풍선 자리. 화면은 `CoachOverlay.kt`, 이어 붙이기는 `CoachCenter.kt`.
// 좌표·길이는 모두 dp(iOS pt 와 같은 값)다.

/** 안내 한 단계 — 무엇을 가리키고([target]) 무슨 말을 하나. 제목·본문은 한국어 원문이 곧 번역 열쇠다(`tr`). */
data class CoachStep(
    /** 가리킬 단추의 표식(`Modifier.coachTarget` 에 준 이름). 예: `"plan-trip"`. */
    val target: String,
    val title: String,
    val text: String,
)

/** 안내 하나 — 이름([id], 본 것 기억의 열쇠), 조건이 맞은 뒤 기다리는 시간(초), 단계들. */
data class CoachTour(
    val id: String,
    /** 조건이 맞은 뒤 이만큼(초) 기다렸다 띄운다 — 시트가 다 올라오고 화면이 자리 잡은 뒤. */
    val delaySeconds: Double,
    val steps: List<CoachStep>,
) {
    companion object {
        /** 홈 — 떠 있는 「여행 코스 짜기」 단추(C2). */
        val homePlanTrip =
            CoachTour(
                "home-plan-trip",
                0.9,
                listOf(
                    CoachStep(
                        "plan-trip",
                        "첫 여행 계획을 짜 보세요",
                        "여기를 누르면 도시·작품·담은 곳 중 하나로 여행 코스를 만들 수 있어요.",
                    ),
                ),
            )

        /** 「새 여행 코스」 시트의 세 줄(C3). */
        val forkOptions =
            CoachTour(
                "fork-options",
                0.55,
                listOf(
                    CoachStep(
                        "fork-city",
                        "도시로 시작",
                        "어디로, 며칠 가는지만 고르면 그 도시 인기 촬영지를 동선대로 이어 코스를 바로 만들어 드려요.",
                    ),
                    CoachStep(
                        "fork-shows",
                        "작품으로",
                        "좋아하는 드라마·영화를 고르면 AI 가 그 작품들의 촬영지로 코스를 짜 줘요.",
                    ),
                    CoachStep(
                        "fork-saved",
                        "담은 곳으로",
                        "보관함에 담아 둔 곳을 날마다 나눠 넣어요. 담은 곳이 없으면 빈 코스에서 하나씩 직접 넣어요.",
                    ),
                ),
            )

        /** 처음 만든 계획 화면 — 지도 · 해태 AI · ＋ · 저장(C4). */
        val editorFirst =
            CoachTour(
                "editor-first",
                1.2,
                listOf(
                    CoachStep("editor-map", "지도", "눌러서 코스를 지도로 펼쳐 동선을 확인해요. 다시 누르면 목록만 봐요."),
                    CoachStep("editor-guide", "해태 AI", "「하루 줄여 줘」 「카페 하나 넣어 줘」처럼 말로 부탁하면 코스를 고쳐 줘요."),
                    CoachStep(
                        "editor-add",
                        "장소 검색해서 담기",
                        "지금은 편집 중이라 장소를 검색해서 더 넣을 수 있어요. 보관함에 담아 둔 곳도 여기서 넣어요.",
                    ),
                    CoachStep("editor-save", "다 고쳤으면 저장", "저장해야 코스가 남아요. 저장하지 않고 나가면 한 번 더 물어봐요."),
                ),
            )

        /** 계획 화면 지도를 처음 펼쳤을 때 — 🔍 · 전체 칩(C5, M8). */
        val mapSearch =
            CoachTour(
                "map-search",
                0.7,
                listOf(
                    CoachStep(
                        "map-search",
                        "지도에서 검색",
                        "촬영지가 아닌 가게·장소를 찾아요 — 맛집·카페·숙소·명소를 이름으로 검색해 「코스에 담기」 로 바로 넣어요.",
                    ),
                    CoachStep("map-chips", "주변 둘러보기", "전체·음식·관광지·숙소 칩을 누르면 지금 보이는 지도 안의 장소가 핀으로 떠요."),
                ),
            )

        val all: List<CoachTour> = listOf(homePlanTrip, forkOptions, editorFirst, mapSearch)
    }
}

// ── 본 것 기억 ──

/** 본 안내 이름을 담는 곳. 앱은 SharedPreferences(`CoachCenter`), 시험은 메모리 가짜를 쓴다. */
interface CoachSeenStorage {
    fun getIds(key: String): List<String>?

    fun setIds(
        key: String,
        ids: List<String>,
    )
}

/** 끝까지 보거나 「건너뛰기」 한 안내는 이 기기에서 다시 띄우지 않는다. */
class CoachSeenStore(
    private val storage: CoachSeenStorage,
) {
    private val ids: List<String> get() = storage.getIds(KEY) ?: emptyList()

    fun isSeen(id: String): Boolean = id in ids

    fun markSeen(id: String) {
        val now = ids
        if (id in now) return
        storage.setIds(KEY, now + id)
    }

    /** 본 기록을 다 지운다 — 확인용 뒷문 `--ez coachReset true`. */
    fun reset() {
        storage.setIds(KEY, emptyList())
    }

    companion object {
        /** iOS `UserDefaults` 와 같은 열쇠. */
        const val KEY = "scenetrip.coach.seen"
    }
}

// ── 한 번에 하나 ──

/** 떠 있는 안내는 언제나 하나다 — 다른 안내가 떠 있으면 그것이 끝날 때까지 기다린다. */
class CoachQueue {
    var active: String? = null
        private set

    /** 아직 안 봤고 다른 안내가 떠 있지 않으면 [id] 를 띄우고 true. 같은 안내가 이미 떠 있어도 false. */
    fun start(
        id: String,
        seen: Boolean,
    ): Boolean {
        if (seen || active != null) return false
        active = id
        return true
    }

    /** [id] 가 떠 있는 안내일 때만 내린다 — 다른 안내의 끝내기가 지금 안내를 지우지 않게. */
    fun end(id: String) {
        if (active == id) active = null
    }
}

// ── 단계 넘기기 ──

enum class CoachAction {
    /** 「다음」/「알겠어요」. */
    NEXT,

    /** 「건너뛰기」 — 남은 단계를 모두 넘기고 본 것으로 친다. */
    SKIP,

    /** 어두운 덮개를 눌렀다 — 「다음」 과 같다. */
    TAP_SCRIM,

    /** 가리킬 단추가 [CoachRules.MISSING_TARGET_WAIT] 넘게 안 보였다 — 그 단계를 건너뛴다. */
    TARGET_MISSING,

    /** 화면을 떠났다(시트를 닫는 등) — 접되 본 것으로 치지 않는다. 다음에 다시 띄운다. */
    LEAVE,
}

sealed class CoachOutcome {
    /** 이 단계(0부터)를 보인다. */
    data class Step(
        val index: Int,
    ) : CoachOutcome()

    /** 안내를 접는다. [markSeen] 이면 다시 띄우지 않는다. */
    data class Finished(
        val markSeen: Boolean,
    ) : CoachOutcome()
}

// ── 자리 ──

/** 화면(덮개) 기준 틀(dp). */
data class CoachRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
) {
    val minX: Float get() = x
    val minY: Float get() = y
    val maxX: Float get() = x + width
    val maxY: Float get() = y + height
    val midX: Float get() = x + width / 2
    val midY: Float get() = y + height / 2
}

data class CoachSize(
    val width: Float,
    val height: Float,
)

/** 구멍과 말풍선이 놓일 자리. */
data class CoachLayout(
    /** 밝게 남길 구멍(단추 + 여백, 화면 안으로 가둔 것). */
    val hole: CoachRect,
    /** 구멍 모서리 — 높이의 반, 최대 22. */
    val holeRadius: Float,
    /** 말풍선 틀(높이는 잰 값 그대로). */
    val bubble: CoachRect,
    /** 말풍선이 구멍 아래에 있나. */
    val below: Boolean,
    /** 화살표의 왼쪽 끝 — 말풍선 왼쪽에서부터. */
    val arrowX: Float,
    /** 화살표를 그리나 — 화면 안으로 밀어 넣느라 말풍선이 구멍과 겹치면 숨긴다. */
    val showsArrow: Boolean,
)

object CoachRules {
    /** 구멍이 단추보다 사방으로 넓은 만큼. */
    const val PAD: Float = 6f
    const val BUBBLE_WIDTH: Float = 280f

    /** 구멍과 말풍선 사이. */
    const val GAP: Float = 12f

    /** 화면 가장자리에서 말풍선·구멍이 떨어지는 거리. 상태줄·제스처 막대(안전 영역)는 따로 더한다. */
    const val EDGE: Float = 12f

    /** 가리킬 단추를 기다리는 시간(초) — 넘으면 그 단계를 건너뛴다. */
    const val MISSING_TARGET_WAIT: Double = 4.0

    /** 다른 안내가 떠 있을 때 다시 볼 간격(초). */
    const val RETRY_INTERVAL: Double = 0.4

    fun advance(
        index: Int,
        count: Int,
        action: CoachAction,
    ): CoachOutcome =
        when (action) {
            CoachAction.SKIP -> {
                CoachOutcome.Finished(markSeen = true)
            }

            CoachAction.LEAVE -> {
                CoachOutcome.Finished(markSeen = false)
            }

            CoachAction.NEXT, CoachAction.TAP_SCRIM, CoachAction.TARGET_MISSING -> {
                if (index + 1 < count) CoachOutcome.Step(index + 1) else CoachOutcome.Finished(markSeen = true)
            }
        }

    /** 단추가 안 보인 지 [waitedSeconds] 초 — 건너뛸 때인가. */
    fun shouldSkipMissing(waitedSeconds: Double): Boolean = waitedSeconds > MISSING_TARGET_WAIT

    fun isLast(
        index: Int,
        count: Int,
    ): Boolean = index >= count - 1

    /** 「1 / 3」. */
    fun counter(
        index: Int,
        count: Int,
    ): String = "${index + 1} / $count"

    /** 「건너뛰기」 는 마지막 단계에 없다. */
    fun showsSkip(
        index: Int,
        count: Int,
    ): Boolean = !isLast(index, count)

    /** 오른쪽 단추 글자(번역 열쇠) — 마지막이면 「알겠어요」, 아니면 「다음」. */
    fun primaryTitle(
        index: Int,
        count: Int,
    ): String = if (isLast(index, count)) "알겠어요" else "다음"

    /**
     * 구멍과 말풍선 자리. 좌표는 모두 화면(덮개) 기준이다.
     *
     * @param target 가리킬 단추의 틀.
     * @param container 덮개 크기(화면 전체).
     * @param safeTop 위 안전영역(상태줄).
     * @param safeBottom 아래 안전영역(제스처 막대).
     * @param bubbleHeight 잰 말풍선 높이.
     */
    fun layout(
        target: CoachRect,
        container: CoachSize,
        safeTop: Float,
        safeBottom: Float,
        bubbleHeight: Float,
    ): CoachLayout {
        // 화면 가장자리·안전영역 안쪽.
        val innerTop = EDGE + safeTop
        val innerLeft = EDGE
        val innerRight = container.width - EDGE
        val innerBottom = container.height - EDGE - safeBottom
        // 구멍 — 단추보다 사방 6 넓게, 화면 안으로 가둔다.
        val holeTop = clamp(target.minY - PAD, innerTop - EDGE / 2, innerBottom)
        val holeLeft = clamp(target.minX - PAD, 4f, innerRight)
        val holeRight = clamp(target.maxX + PAD, holeLeft, container.width - 4f)
        val holeBottom = clamp(target.maxY + PAD, holeTop, innerBottom + EDGE / 2)
        val hole = CoachRect(holeLeft, holeTop, holeRight - holeLeft, holeBottom - holeTop)
        val width = min(BUBBLE_WIDTH, innerRight - innerLeft)
        // 아래에 자리가 있으면 아래, 아니면 위. 위에도 자리가 없으면 아래로 두고 화면 안으로 밀어 넣는다.
        val below = hole.maxY + GAP + bubbleHeight <= innerBottom || hole.minY - GAP - bubbleHeight < innerTop
        val rawTop = if (below) hole.maxY + GAP else hole.minY - GAP - bubbleHeight
        val top = clamp(rawTop, innerTop, max(innerTop, innerBottom - bubbleHeight))
        val left = clamp(target.midX - width / 2, innerLeft, innerRight - width)
        val arrowX = clamp(target.midX - left - 8f, 18f, width - 34f)
        // 밀어 넣느라 말풍선이 구멍과 겹치면 화살표는 숨긴다(가리킬 곳이 말풍선 밑이다).
        val showsArrow = if (below) top >= hole.maxY else top + bubbleHeight <= hole.minY
        return CoachLayout(
            hole = hole,
            holeRadius = min(22f, hole.height / 2),
            bubble = CoachRect(left, top, width, bubbleHeight),
            below = below,
            arrowX = arrowX,
            showsArrow = showsArrow,
        )
    }

    private fun clamp(
        value: Float,
        low: Float,
        high: Float,
    ): Float = max(low, min(high, value))

    // ── 언제 띄우나 ──

    /** 홈(C2) — 여행 중 코스가 없고, 떠 있는 줄이 접혀 있고, 홈이 가려져 있지 않을 때(다른 탭·덮개·시트). */
    fun homeActive(
        running: Boolean,
        floatOpen: Boolean,
        covered: Boolean,
    ): Boolean = !running && !floatOpen && !covered

    /** 계획 화면(C4) — 편집 중이고 새 코스·방금 만든 코스이며 묻는 창이 떠 있지 않을 때. */
    fun editorFirstActive(
        editing: Boolean,
        isNew: Boolean,
        fresh: Boolean,
        confirming: Boolean,
    ): Boolean = editing && (isNew || fresh) && !confirming

    /** 지도 검색(C5) — 칩 줄이 보이고(편집·지도 펼침·여행 아님) 검색칸·결과 지도가 열려 있지 않을 때. */
    fun mapSearchActive(
        barShown: Boolean,
        searching: Boolean,
        resultsOpen: Boolean,
    ): Boolean = barShown && !searching && !resultsOpen
}
