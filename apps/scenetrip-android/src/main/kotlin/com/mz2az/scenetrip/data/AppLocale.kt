package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.infrastructure.ApiClient

/**
 * 앱의 언어를 서버에 알린다 — `Accept-Language` (MZ2AZ-305). iOS `Models/AppLocale.swift`를
 * 옮긴 것이다.
 *
 * 계약의 모든 창구가 이 헤더를 받는다(장소 이름·장면 설명·길찾기 안내·가이드 답). 생성 클라이언트가
 * 쓰는 공용 `OkHttpClient.Builder`([ApiClient.builder])에 인터셉터를 **한 번** 둔다 — 창구(각
 * `XxxApi(API_BASE)`)가 늘어도 빠뜨릴 자리가 없다. 서버는 생략하거나 번역이 없으면 `ko` 로 폴백한다.
 *
 * **앱이 뜨자마자, [ApiClient.defaultClient]를 처음 쓰기 전에 불러야 한다** — `defaultClient`는
 * `by lazy`라 한 번 굳으면 인터셉터를 더 못 끼운다. `MainActivity.onCreate`가 가장 먼저 부른다.
 */
object AppLocale {
    private var installed = false

    /** 앱이 뜰 때, 그리고 언어를 바꿀 때. 이후 모든 요청에 `Accept-Language` 가 실린다. */
    fun install() {
        if (!installed) {
            installed = true
            ApiClient.builder.addInterceptor { chain ->
                val request =
                    chain
                        .request()
                        .newBuilder()
                        .header("Accept-Language", AppLanguage.current.value)
                        .build()
                chain.proceed(request)
            }
        }
    }
}
