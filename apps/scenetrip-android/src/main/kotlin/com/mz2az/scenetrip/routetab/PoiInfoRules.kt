package com.mz2az.scenetrip.routetab

import com.mz2az.scenetrip.data.AppLanguage
import com.mz2az.scenetrip.data.WebAddress
import com.mz2az.scenetrip.data.tr
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import com.mz2az.scenetrip.sceneapi.client.model.PoiInfo
import com.mz2az.scenetrip.sceneapi.client.model.PoiInfoText

/** 관광공사 안내의 표시 규칙 (MZ2AZ-470). 내용·언어 폴백은 서버가 결정한다. iOS `PoiInfoRules`. */
object PoiInfoRules {
    data class Row(
        val id: String,
        val label: String,
        val text: String,
        val languageTag: String?,
    )

    /**
     * 값이 있는 줄만, 개요 → 이용 시간 → 쉬는 날 → 주차 → 대표 메뉴 → 입장료 순으로. `info` 도 필드도 없거나 null 이거나
     * 공백뿐이면 그 줄만 뺀다. 문장은 서버가 준 그대로(줄바꿈 보존)이고 영업 중 여부는 계산하지 않는다.
     */
    fun rows(
        info: PoiInfo?,
        app: Lang = AppLanguage.current,
    ): List<Row> {
        if (info == null) return emptyList()
        val fields: List<Pair<String, PoiInfoText?>> =
            listOf(
                "개요" to info.overview,
                "이용 시간" to info.useTime,
                "쉬는 날" to info.restDate,
                "주차" to info.parking,
                "대표 메뉴" to info.firstMenu,
                "입장료" to info.useFee,
            )
        return fields.mapNotNull { (label, value) ->
            if (value == null || value.text.isBlank()) return@mapNotNull null
            Row(label, tr(label, app), value.text, languageTag(value.lang, app))
        }
    }

    /** HTTP(S) 주소만 브라우저로 연다. */
    fun homepage(info: PoiInfo?): String? = WebAddress.url(info?.homepage?.toString())

    fun isVisible(
        info: PoiInfo?,
        app: Lang = AppLanguage.current,
    ): Boolean = rows(info, app).isNotEmpty() || homepage(info) != null

    /** 그 필드의 실제 언어가 앱 언어와 다를 때만 작은 언어 태그. */
    private fun languageTag(
        lang: Lang,
        app: Lang,
    ): String? {
        if (lang == app) return null
        return when (lang) {
            Lang.ko -> tr("한국어", app)
            Lang.en -> tr("영어", app)
            Lang.ja -> tr("일본어", app)
            Lang.zhMinusHant -> tr("중국어 번체", app)
        }
    }
}
