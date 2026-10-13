package com.mz2az.scenetrip.data

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** 서명 URL의 Host·경로·query를 바꾸지 않고 개발용 MinIO 소켓만 호스트로 연결한다. */
class ImageDns(
    apiBase: String,
    private val fallback: Dns = Dns.SYSTEM,
) : Dns {
    private val emulator = apiBase.toHttpUrlOrNull()?.host == "10.0.2.2"

    override fun lookup(hostname: String): List<InetAddress> =
        if (emulator && hostname in setOf("localhost", "127.0.0.1")) {
            listOf(InetAddress.getByAddress(byteArrayOf(10, 0, 2, 2)))
        } else {
            fallback.lookup(hostname)
        }
}

/** API 인증 헤더·재시도 인터셉터를 이미지 서버로 보내지 않는다. */
object ImageRequests {
    fun client(apiBase: String): OkHttpClient =
        OkHttpClient
            .Builder()
            .dns(ImageDns(apiBase))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

    fun request(rawUrl: String): Request {
        require(WebAddress.url(rawUrl) != null)
        return Request
            .Builder()
            .url(rawUrl)
            .get()
            .build()
    }
}
