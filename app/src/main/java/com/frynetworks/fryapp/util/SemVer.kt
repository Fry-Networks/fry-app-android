package com.frynetworks.fryapp.util

/**
 * SemVer 2.0 version with §11 precedence: numeric core, then a release outranks its
 * pre-releases, and pre-release identifiers compare numerically when both are numbers, else
 * lexically, numeric below alphanumeric, a shorter list below a longer one with the same prefix.
 * Build metadata (`+…`) is ignored; a leading `v` is accepted.
 */
data class SemVer(val major: Long, val minor: Long, val patch: Long, val preRelease: List<String> = emptyList()) : Comparable<SemVer> {

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (preRelease.isEmpty() || other.preRelease.isEmpty()) return other.preRelease.size.coerceAtMost(1) - preRelease.size.coerceAtMost(1)
        for (i in 0 until minOf(preRelease.size, other.preRelease.size)) {
            val a = preRelease[i]
            val b = other.preRelease[i]
            val an = a.toLongOrNull()
            val bn = b.toLongOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return preRelease.size.compareTo(other.preRelease.size)
    }

    companion object {
        private val PATTERN = Regex("""^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z.-]+)?$""")

        fun parse(text: String?): SemVer? {
            val m = PATTERN.matchEntire(text?.trim().orEmpty()) ?: return null
            val (major, minor, patch, pre) = m.destructured
            return SemVer(
                major.toLongOrNull() ?: return null,
                minor.toLongOrNull() ?: return null,
                patch.toLongOrNull() ?: return null,
                if (pre.isEmpty()) emptyList() else pre.split('.'),
            )
        }
    }
}
