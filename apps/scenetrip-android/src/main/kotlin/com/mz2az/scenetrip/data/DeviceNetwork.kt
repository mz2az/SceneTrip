package com.mz2az.scenetrip.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 기기가 오프라인인가 — 화면이 「서버에 연결하지 못했습니다」 대신 「인터넷 연결을 확인해 주세요」 를 말하는 근거다
 * (MZ2AZ-366, iOS `RetryRules.isOffline`). 연결 실패 자체(호스트를 못 찾음·연결 거부)만으로는 기기 탓인지 서버 탓인지
 * 알 수 없어서 시스템의 연결 상태를 따로 본다.
 *
 * 앱이 시작할 때 [install] 로 연결하고, 시험은 [probe] 를 바꿔 끼운다.
 */
object DeviceNetwork {
    @Volatile
    var probe: () -> Boolean = { false }

    /** 지금 기기가 오프라인인가. */
    fun isOffline(): Boolean = probe()

    fun install(context: Context) {
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        probe = {
            val capabilities = manager.activeNetwork?.let(manager::getNetworkCapabilities)
            capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }
}
