package com.mz2az.scenetrip.routetab

/** 원본 이름은 보존하고 표시용 번역·로마자·주소를 골라 쓴다. */
data class PoiLabel(
    val title: String,
    val reading: String?,
    val category: String?,
    val address: String?,
) {
    val heading: String get() = listOfNotNull(title, reading).joinToString(" · ")

    companion object {
        private const val SEPARATOR = " · "

        /**
         * 한 줄로 이은 것을 **다시 두 줄로** — 리뷰 쓰기 머리줄처럼 좁은 자리는 카드와 같이 제목 / 작은 아랫줄로 적는다. 그냥 줄을
         * 바꾸게 두면 한국어 이름 가운데서 끊긴다 (iOS `PoiLabel.lines(of:)`).
         */
        fun lines(heading: String): Pair<String, String?> {
            val cut = heading.indexOf(SEPARATOR)
            return if (cut < 0) heading to null else heading.substring(0, cut) to heading.substring(cut + SEPARATOR.length)
        }

        fun make(
            name: String,
            displayName: String?,
            nameRoman: String?,
            category: String?,
            categoryLabel: String?,
            address: String?,
            displayAddress: String?,
            korean: Boolean,
        ): PoiLabel {
            if (korean) return PoiLabel(name, null, category, address)
            val shown = displayName.filled()
            val reading =
                if (shown !=
                    null
                ) {
                    name.takeUnless { it.equals(shown, true) }
                } else {
                    nameRoman.filled()?.takeUnless { it.equals(name.trim(), true) }
                }
            return PoiLabel(shown ?: name, reading, categoryLabel.filled() ?: category, displayAddress.filled() ?: address)
        }

        private fun String?.filled(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}
