package com.mz2az.scenetrip.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.mz2az.scenetrip.data.WebAddress

/**
 * 외부 브라우저로 연다 — 앱의 다른 외부 페이지(제작사·네이버 지도)와 같은 방식이다. iOS 는 Safari 로 연다.
 * 열 수 있는 앱이 없거나 주소가 HTTP(S) 가 아니면 `false`.
 */
fun openExternalUrl(
    context: Context,
    url: String,
): Boolean {
    val safe = WebAddress.url(url) ?: return false
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
