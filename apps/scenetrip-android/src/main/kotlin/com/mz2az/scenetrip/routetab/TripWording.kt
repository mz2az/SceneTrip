package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.tr

// 여행 안내 문구 조립 (MZ2AZ-478) — 화면 코드에서 떼어 JVM 시험이 그대로 부른다.

/** 정지점 번호를 붙인 제목 — 「2번 낙산공원로 가는 중」 / 「2번 낙산공원 도착」 (iOS `tripTitle`). 번호를 모르면 이름만. */
fun tripTitle(
    name: String,
    number: Int?,
    arrived: Boolean,
): String {
    val prefix = number?.let { tr("%d번 ").format(it) }.orEmpty()
    return if (arrived) tr("%s%s 도착").format(prefix, name) else tr("%s%s로 가는 중").format(prefix, name)
}

/**
 * 도착 스탬프 제목 — 「2번 성지 도착!」, 이 일차 밖의 곳이면 「도착!」 (iOS `stampOverlay`).
 */
fun stampTitle(number: Int?): String = if (number != null) tr("%d번 성지 도착!").format(number) else tr("도착!")
