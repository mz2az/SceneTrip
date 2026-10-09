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
