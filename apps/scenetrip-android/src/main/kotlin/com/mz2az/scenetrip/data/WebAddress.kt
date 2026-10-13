package com.mz2az.scenetrip.data

import java.net.URI

/** 서버 주소의 HTTP(S) 경계. 원래 경로·query·서명 문자는 재조립하지 않는다. */
object WebAddress {
    fun url(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (value.any { it.isWhitespace() } || Regex("%(?![0-9A-Fa-f]{2})").containsMatchIn(value)) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return value
    }
}
