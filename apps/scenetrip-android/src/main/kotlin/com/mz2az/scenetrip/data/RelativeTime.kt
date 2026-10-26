package com.mz2az.scenetrip.data

import android.icu.text.RelativeDateTimeFormatter
import com.mz2az.scenetrip.sceneapi.client.model.Lang
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * 「3일 전」·「어제」 같은 **이름 붙은 상대 시각** (iOS `.relative(presentation: .named)`, 앱 언어 로케일).
 *
 * 어느 단위로 말할지는 [phrase] 가 값만 보고 정하고(시험이 선다), 글자는 [format] 이 시스템 포매터(ICU)에서 받는다.
 */
object RelativeTime {
    enum class Unit { NOW, MINUTE, HOUR, DAY, WEEK, MONTH, YEAR }

    /** 지금부터 거슬러 올라간 [quantity] 개의 [unit]. 미래는 [NOW] 로 접는다(서버 시계가 조금 앞설 수 있다). */
    data class Phrase(
        val unit: Unit,
        val quantity: Int,
    )

    fun phrase(
        then: OffsetDateTime,
        now: OffsetDateTime,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Phrase {
        val elapsed = Duration.between(then, now)
        if (elapsed.seconds < 60) return Phrase(Unit.NOW, 0)
        if (elapsed.toMinutes() < 60) return Phrase(Unit.MINUTE, elapsed.toMinutes().toInt())
        if (elapsed.toHours() < 24) return Phrase(Unit.HOUR, elapsed.toHours().toInt())
        // 하루 단위는 달력의 날로 센다 — 어젯밤 11시는 지금이 새벽 1시여도 「어제」 다.
        val days = ChronoUnit.DAYS.between(then.atZoneSameInstant(zone).toLocalDate(), now.atZoneSameInstant(zone).toLocalDate()).toInt()
        return when {
            days < 7 -> Phrase(Unit.DAY, maxOf(days, 1))
            days < 30 -> Phrase(Unit.WEEK, days / 7)
            days < 365 -> Phrase(Unit.MONTH, maxOf(days / 30, 1))
            else -> Phrase(Unit.YEAR, days / 365)
        }
    }

    fun format(
        then: OffsetDateTime,
        now: OffsetDateTime = OffsetDateTime.now(),
        lang: Lang = AppLanguage.current,
    ): String {
        val phrase = phrase(then, now)
        val formatter = RelativeDateTimeFormatter.getInstance(if (lang == Lang.ko) Locale.KOREAN else Locale.ENGLISH)
        val last = RelativeDateTimeFormatter.Direction.LAST
        val quantity = phrase.quantity.toDouble()
        return when (phrase.unit) {
            Unit.NOW -> {
                formatter.format(RelativeDateTimeFormatter.Direction.PLAIN, RelativeDateTimeFormatter.AbsoluteUnit.NOW)
            }

            Unit.MINUTE -> {
                formatter.format(quantity, last, RelativeDateTimeFormatter.RelativeUnit.MINUTES)
            }

            Unit.HOUR -> {
                formatter.format(quantity, last, RelativeDateTimeFormatter.RelativeUnit.HOURS)
            }

            Unit.DAY -> {
                if (phrase.quantity == 1) {
                    formatter.format(last, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
                } else {
                    formatter.format(quantity, last, RelativeDateTimeFormatter.RelativeUnit.DAYS)
                }
            }

            Unit.WEEK -> {
                if (phrase.quantity == 1) {
                    formatter.format(last, RelativeDateTimeFormatter.AbsoluteUnit.WEEK)
                } else {
                    formatter.format(quantity, last, RelativeDateTimeFormatter.RelativeUnit.WEEKS)
                }
            }

            Unit.MONTH -> {
                if (phrase.quantity == 1) {
                    formatter.format(last, RelativeDateTimeFormatter.AbsoluteUnit.MONTH)
                } else {
                    formatter.format(quantity, last, RelativeDateTimeFormatter.RelativeUnit.MONTHS)
                }
            }

            Unit.YEAR -> {
                if (phrase.quantity == 1) {
                    formatter.format(last, RelativeDateTimeFormatter.AbsoluteUnit.YEAR)
                } else {
                    formatter.format(quantity, last, RelativeDateTimeFormatter.RelativeUnit.YEARS)
                }
            }
        }
    }
}
