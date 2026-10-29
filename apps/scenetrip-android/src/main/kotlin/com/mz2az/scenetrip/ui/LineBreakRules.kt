package com.mz2az.scenetrip.ui

/**
 * 마지막 두 낱말을 붙여 둔다(줄바꿈 없는 공백 U+00A0). SwiftUI `Text` 는 마지막 줄에 낱말 하나만 남지 않게 앞 낱말을 함께
 * 밀어 내리는데(「일정으로 / 짜 드립니다」), Compose 는 줄을 꽉 채우고 하나만 내려 보낸다(「일정으로 짜 / 드립니다」, 2026-10-29
 * 감사 #13). 낱말이 둘 이하면 그대로 둔다.
 */
fun String.keepLastWordsTogether(): String {
    val at = lastIndexOf(' ')
    if (at <= 0 || indexOf(' ') == at) return this
    return substring(0, at) + '\u00A0' + substring(at + 1)
}
