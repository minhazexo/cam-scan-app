package com.camscan.app.domain.model

enum class FilterMode(val displayName: String) {
    AUTO("Auto / Magic Color"),
    ORIGINAL("Original"),
    DOCUMENT("Document (GCMODE)"),
    LIGHTEN("Lighten (RMODE)"),
    GRAYSCALE("Grayscale"),
    BLACK_AND_WHITE("B&W (SMODE)"),
    HIGH_CONTRAST("High Contrast");

    companion object {
        fun fromString(name: String?): FilterMode {
            return entries.firstOrNull { it.name.equalsIgnoreCase(name ?: "") } ?: AUTO
        }

        private fun String.equalsIgnoreCase(other: String): Boolean {
            return this.equals(other, ignoreCase = true)
        }
    }
}
