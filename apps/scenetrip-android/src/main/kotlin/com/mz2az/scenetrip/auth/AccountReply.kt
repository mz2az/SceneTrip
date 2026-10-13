package com.mz2az.scenetrip.auth

/** 응답이 이전 계정이나 다른 사용자 자료를 덮어쓰지 않도록 확인한다 (MZ2AZ-363). */
object AccountReply {
    fun restoration(
        requestEpoch: Int,
        currentEpoch: Int,
        signedIn: Boolean,
    ): Boolean = requestEpoch == currentEpoch && signedIn

    fun nickname(
        requestEpoch: Int,
        currentEpoch: Int,
        expectedUser: String?,
        currentUser: String?,
        replyUser: String,
    ): Boolean = requestEpoch == currentEpoch && expectedUser != null && expectedUser == currentUser && replyUser == expectedUser
}
