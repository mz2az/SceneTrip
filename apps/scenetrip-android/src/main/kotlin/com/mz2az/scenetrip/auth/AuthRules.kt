package com.mz2az.scenetrip.auth

/** 401 을 받았을 때 할 일 (MZ2AZ-336). iOS `Auth/AuthRules.swift` 와 같다 — 계약 `docs/api/errors.md` 인증 표. */
enum class AuthAction {
    /** 액세스 토큰이 만료됐다 — 갱신하고 원래 요청을 **한 번** 다시 보낸다. */
    REFRESH_AND_RETRY,

    /** 세션이 깨졌다(위조·폐기·리프레시 만료) — 토큰을 지우고 로그인 화면. */
    SIGN_OUT,

    /** 비회원이 가입해야 쓰는 기능을 불렀다 — 로그인 화면으로 이끈다. */
    PROMPT_SIGN_IN,
    NONE,
}

object AuthRules {
    fun action(
        status: Int,
        code: String?,
    ): AuthAction {
        if (status != 401) return AuthAction.NONE
        return when (code) {
            "ACCESS_TOKEN_EXPIRED" -> AuthAction.REFRESH_AND_RETRY
            "ACCESS_TOKEN_INVALID", "SESSION_REQUIRED", "REFRESH_TOKEN_INVALID" -> AuthAction.SIGN_OUT
            "SIGN_IN_REQUIRED" -> AuthAction.PROMPT_SIGN_IN
            else -> AuthAction.NONE
        }
    }

    private val codePattern = Regex("\"code\"\\s*:\\s*\"([A-Z_]+)\"")

    /** 오류 본문의 `code`. 본문이 없거나 우리 모양이 아니면 `null`. */
    fun apiCode(body: String?): String? = body?.let { codePattern.find(it)?.groupValues?.get(1) }

    /** 로그인·갱신·로그아웃 창구 자신의 401 은 가로채지 않는다 — 갱신이 갱신을 부르면 끝나지 않는다. */
    fun intercepts(url: String): Boolean = !url.contains("/auth/")
}
