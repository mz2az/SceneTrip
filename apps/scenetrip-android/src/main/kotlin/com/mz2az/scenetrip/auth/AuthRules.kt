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

    /**
     * 로그인 응답이 실패했을 때 화면에 띄울 한 줄 — 서버 문장이 아니라 code 로 가른다(iOS `signInWithGoogle` 의 catch).
     * 응답이 없으면([status] null·0 이하) 연결 문제다.
     */
    fun signInFailureText(
        status: Int?,
        code: String?,
    ): String =
        when {
            status == null || status <= 0 -> "연결이 원활하지 않아요. 잠시 뒤 다시 해 주세요"
            code == "AUTH_PROVIDER_UNAVAILABLE" -> "구글에 잠시 닿지 않아요. 잠시 뒤 다시 해 주세요"
            else -> "로그인하지 못했어요. 다시 해 주세요"
        }

    private val codePattern = Regex("\"code\"\\s*:\\s*\"([A-Z_]+)\"")

    /** 오류 본문의 `code`. 본문이 없거나 우리 모양이 아니면 `null`. */
    fun apiCode(body: String?): String? = body?.let { codePattern.find(it)?.groupValues?.get(1) }

    private val anonymousReadPath =
        Regex("""(^|/)((places|pois)/\d+(/photos)?|posts(/\d+)?|me/posts)/?$""")

    /**
     * 부르기 **전에** 액세스 토큰을 살필 읽기 창구인가 (MZ2AZ-461, 권호 결정 2026-10-15). iOS `AuthRefresher.refreshIfStale()` 를
     * 거치는 곳과 같다 — 리뷰 목록·내 리뷰, 촬영지·편의시설 상세와 사진첩, 여행후기 목록·상세.
     *
     * 이런 **누구나 읽는 창구**는 만료된 토큰에 401 을 주지 않고 비회원으로 답한다(서버 `CurrentAccount.signedInOrNull`). 그러면 갱신할
     * 계기가 없어 차단한 사람의 리뷰·사진·후기가 다시 섞이고 「내 리뷰」 표시가 빠진다. 그래서 만료됐거나 곧 만료되면 먼저 갱신한다.
     */
    fun refreshesBeforeRead(
        method: String,
        encodedPath: String,
    ): Boolean = method == "GET" && (encodedPath.contains("/reviews") || anonymousReadPath.containsMatchIn(encodedPath))

    /** 로그인·갱신·로그아웃 창구 자신의 401 은 가로채지 않는다 — 갱신이 갱신을 부르면 끝나지 않는다. */
    fun intercepts(url: String): Boolean = !url.contains("/auth/")
}
