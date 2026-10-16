package com.mz2az.scenetrip.routetab

/**
 * 가이드 창의 「AI 로 짜기」로 편집 화면을 나가는 흐름 — 「버릴까요?」 창을 거칠 수 있다.
 *
 * 「마법사를 열어 달라」는 표시([setPending], 곧 `TabRouter.pendingPlanner`)는 **편집 화면이 정말 닫힐 때만** 올린다.
 * 알림 창은 어느 단추를 누르든 먼저 `onDismiss`를 부르고 그다음 단추 동작을 부르므로(`AlertCell`), `onDismiss`에서
 * 표시를 만지면 「버리기」가 읽기도 전에 지워진다. 그래서 의도는 여기서 들고 있다가 [discard]에서만 표시로 바꾼다.
 * 「계속 편집」이나 바깥을 눌러 닫은 경우에는 표시가 올라간 적이 없어 남는 것이 없다(iOS 는 「계속 편집」에서만 거둔다).
 */
class PlannerExit(
    private val setPending: (Boolean) -> Unit,
) {
    private var afterDiscard = false

    /**
     * 닫기를 요청한다. 바꾼 것이 없으면 바로 닫고(마법사 표시 포함) false, 바꾼 것이 있으면 버릴지 묻도록 true 를 돌려준다.
     * [planner]가 거짓인 일반 닫기는 이전 요청의 의도를 지운다.
     */
    fun request(
        planner: Boolean,
        changed: Boolean,
        close: () -> Unit,
    ): Boolean {
        afterDiscard = planner
        if (changed) return true
        setPending(planner)
        close()
        return false
    }

    /** 「버리기」. */
    fun discard(close: () -> Unit) {
        setPending(afterDiscard)
        afterDiscard = false
        close()
    }

    /** 「계속 편집」. */
    fun keepEditing() {
        afterDiscard = false
    }
}
