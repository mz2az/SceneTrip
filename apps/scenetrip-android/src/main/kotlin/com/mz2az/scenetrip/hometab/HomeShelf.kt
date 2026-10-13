package com.mz2az.scenetrip.hometab

import com.mz2az.scenetrip.sceneapi.client.model.ContentSummary

/** 서버 인기 목록을 홈 선반에 담는 규칙 (MZ2AZ-422). */
object HomeShelf {
    const val LIMIT = 20

    fun from(items: List<ContentSummary>): List<ContentSummary> = items.take(LIMIT)
}
