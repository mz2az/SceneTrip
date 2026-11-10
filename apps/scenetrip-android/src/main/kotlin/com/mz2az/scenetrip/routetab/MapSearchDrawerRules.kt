package com.mz2az.scenetrip.routetab

// 지도 검색 결과 서랍을 손으로 끌기 (MZ2AZ-547, 계획 docs/project/plans/motion-works-drawer-parity.md §3).
// 서랍은 지도 높이의 42%(낮음) 또는 78%(높음)에 붙는다. 높이는 dp, 속도는 「서랍이 커지는 쪽(위)이 +」 인 dp/초.
//
// TODO(MZ2AZ-547): 아래는 시험을 먼저 쓰기 위한 자리표시(stub)다. 값은 일부러 틀리다.

object MapSearchDrawerRules {
    /** 낮은 서랍 — 지도 높이의 42%. */
    const val LOW_FRACTION = 0.42f

    /** 높은 서랍 — 지도 높이의 78%. */
    const val HIGH_FRACTION = 0.78f

    /** 이만큼(dp/초) 이상 빠르게 튕기면 가까운 쪽이 아니라 튕긴 방향으로 붙는다. */
    const val FLICK_DP_PER_SECOND = 600f

    /** 42% 아래·78% 위로 끌면 넘은 거리의 이만큼만 따라간다(고무줄). */
    const val RUBBER_FACTOR = 0.3f

    /** 붙는 움직임의 길이(ms). */
    const val SNAP_MILLIS = 280

    /** 높은 서랍인지에 따른 서랍 높이(dp). */
    fun height(
        mapHeight: Float,
        tall: Boolean,
    ): Float = 0f

    /**
     * 끄는 동안의 서랍 높이 — 42%~78% 안이면 손가락 그대로([raw]),
     * 밖이면 넘은 거리의 [RUBBER_FACTOR] 만큼만 늘어난다(`한계 + (raw − 한계) × RUBBER_FACTOR`).
     */
    fun dragged(
        mapHeight: Float,
        raw: Float,
    ): Float = raw

    /**
     * 손을 뗐을 때 붙을 쪽 — 높은 서랍이면 true.
     * [velocity] 가 +[FLICK_DP_PER_SECOND] 이상이면 높은 쪽, −[FLICK_DP_PER_SECOND] 이하면 낮은 쪽,
     * 그 사이면 지금 [height] 에서 가까운 쪽(정확히 가운데면 지금 쪽 [wasTall]).
     */
    fun settle(
        mapHeight: Float,
        height: Float,
        velocity: Float,
        wasTall: Boolean,
    ): Boolean = wasTall

    /** 손잡이를 눌렀다 — 42% ↔ 78%. */
    fun toggled(tall: Boolean): Boolean = tall
}
