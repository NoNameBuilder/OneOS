package com.fahadali.oneos.core.model

/**
 * One UI version from `ro.build.version.oneui`, which Samsung stores as
 * major * 10000 + minor * 100 + patch: 90000 = 9.0, 80500 = 8.5, 60101 = 6.1.1.
 */
data class OneUiVersion(val major: Int, val minor: Int, val patch: Int) {
    val raw: Int get() = major * 10000 + minor * 100 + patch

    override fun toString(): String =
        if (patch == 0) "$major.$minor" else "$major.$minor.$patch"

    companion object {
        /** Null when the prop is empty or not a One UI number (not a Samsung build). */
        fun parse(prop: String?): OneUiVersion? {
            val s = prop?.trim().orEmpty()
            if (s.isEmpty() || s.length > 7 || !s.all { it in '0'..'9' }) return null
            val v = s.toInt()
            if (v < 10000) return null
            return OneUiVersion(major = v / 10000, minor = (v / 100) % 100, patch = v % 100)
        }
    }
}
