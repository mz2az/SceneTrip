package com.mz2az.scenetrip.data

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 로그아웃·탈퇴·세션 소실 때 **화면이 든 사본을 먼저 비운다** (iOS `AccountDataReset`, MZ2AZ-480).
 *
 * 다시 읽기가 실패해도 앞 계정의 찜·코스·스탬프가 남아 보이지 않게 한다. 세대 번호([generation])는 비동기 응답이 돌아왔을 때
 * 「떠난 사이에 계정이 바뀌었는가」 를 가리는 열쇠다 — 다르면 그 응답은 버린다. 로그인은 합치기라 비우지 않는다.
 *
 * Compose 에 의존하지 않는 순수한 부분이라 시험이 선다. 화면은 [rememberAccountResetGeneration] 으로 읽는다.
 */
object AccountDataReset {
    @Volatile
    var generation = 0
        private set

    private val watchers = CopyOnWriteArrayList<() -> Unit>()

    /** 앞 계정의 사본을 비운다. 세대가 오르고 구독한 곳이 비운다. */
    fun clear() {
        generation += 1
        watchers.forEach { it() }
    }

    /** 비우라는 신호를 받는다. 돌려주는 함수로 구독을 끊는다. */
    fun watch(listener: () -> Unit): () -> Unit {
        watchers.add(listener)
        return { watchers.remove(listener) }
    }
}

/** 화면이 `LaunchedEffect` 의 열쇠로 쓰는 세대 번호 — 계정이 떠나면 값이 바뀌어 다시 읽는다. */
@Composable
fun rememberAccountResetGeneration(): Int {
    val state = remember { mutableIntStateOf(AccountDataReset.generation) }
    DisposableEffect(Unit) {
        val stop = AccountDataReset.watch { state.intValue = AccountDataReset.generation }
        onDispose { stop() }
    }
    return state.intValue
}
