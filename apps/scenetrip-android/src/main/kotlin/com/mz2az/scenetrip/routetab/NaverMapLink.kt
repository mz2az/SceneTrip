package com.mz2az.scenetrip.routetab

import java.net.URI
import java.net.URLEncoder

/**
 * 네이버 지도로 **넘기는** 링크 (MZ2AZ-354). iOS `RouteTab/NaverMapLink.swift` 를 그대로 옮긴 것이다.
 *
 * 편의시설의 사진·평점·리뷰를 우리가 가져와 보여 주지 않는다 — 그 정보는 네이버와 가게,
 * 방문자의 것이다. 이름으로 검색한 화면을 열어 줄 뿐이다.
 */
object NaverMapLink {
    /** 서버가 준 공식 장소 주소만 사용한다. 없거나 유효하지 않으면 단추를 숨긴다. */
    fun place(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val url = runCatching { URI(value) }.getOrNull() ?: return null
        val host = url.host?.lowercase() ?: return null
        if (!url.scheme.equals("https", ignoreCase = true) || url.rawUserInfo != null || url.port != -1) return null
        if (host != "naver.com" && !host.endsWith(".naver.com") && host != "naver.me") return null
        return value
    }

    /**
     * 이름으로 검색한 네이버 지도 주소. 이름이 비면 없다.
     *
     * [near] 는 시군구(「종로구」) — **같은 이름의 다른 지점**이 뜨지 않게 검색어 뒤에 붙인다.
     * 이름만 넘기면 네이버가 제 기준 지역에서 찾는다(종로 가게를 열었는데 중구 중심으로 검색됐다).
     */
    fun search(
        name: String,
        near: String? = null,
    ): String? {
        val place = name.trim()
        if (place.isEmpty()) return null
        val area = near?.trim().orEmpty()
        // 이름에 이미 그 지역이 들어 있으면 두 번 적지 않는다(「명동교자 종로구점 종로구」).
        val term = if (area.isEmpty() || place.contains(area)) place else "$place $area"
        // 경로 한 칸에 들어간다 — URLEncoder 는 영숫자·`.-_*` 만 그대로 두고 나머지(공백·
        // `/?#%&+` 포함)를 모두 퍼센트 인코딩한다. 공백만 `+`로 나오므로 `%20`으로 바꾼다.
        val encoded = URLEncoder.encode(term, "UTF-8").replace("+", "%20")
        return "https://map.naver.com/p/search/$encoded"
    }
}
