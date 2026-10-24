package com.mz2az.scenetrip.data

import com.mz2az.scenetrip.sceneapi.client.model.Lang
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import android.icu.text.DateFormat as IcuDateFormat

/**
 * 날짜·시각 글자 — 앱 언어의 로케일로 쓴다 (iOS `.formatted(date: .abbreviated, time: .omitted)`·`.dateTime.year().month().day().hour().minute()`
 * 에 `AppLanguage.currentLocale`). 한국어로 박아 두면 영어 화면에서도 「2026.10.24 15:20」 이 나온다 (MZ2AZ-480).
 */
object DateText {
    fun locale(lang: Lang = AppLanguage.current): Locale = if (lang == Lang.ko) Locale.KOREAN else Locale.ENGLISH

    /** 「2026. 9. 5.」 / 「Sep 5, 2026」 — 연도 포함 짧은 날짜. */
    fun date(
        day: LocalDate,
        lang: Lang = AppLanguage.current,
    ): String = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale(lang)))

    fun date(
        millis: Long,
        lang: Lang = AppLanguage.current,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String =
        date(
            java.time.Instant
                .ofEpochMilli(millis)
                .atZone(zone)
                .toLocalDate(),
            lang,
        )

    /** 「2026년 10월 24일 오후 3:20」 / 「Oct 24, 2026, 3:20 PM」 — 날짜와 시·분. */
    fun dateTime(
        millis: Long,
        lang: Lang = AppLanguage.current,
    ): String = IcuDateFormat.getInstanceForSkeleton("yMMMdjmm", locale(lang)).format(Date(millis))
}
