package com.fahadali.oneos.core.model

/** How much one finding matters. [INFO] is shown but never changes the verdict. */
enum class Level { INFO, OK, WARN, BLOCK }

/**
 * The summary the device report shows. Same three levels as `oneos.compat/1`
 * (OK / WARN / BLOCK, docs/app/compat-safety-logs.md section 1.9), in words.
 */
enum class Verdict(val label: String) {
    COMPATIBLE("Compatible"),
    WARNING("Warning"),
    NOT_SUPPORTED("Not supported"),
}

/**
 * One result of a rule. [id] is stable so packs and logs can refer to it.
 * [title] and [detail] are the technical texts (All checks, the findings);
 * [reason] says which case of [id] this is when one id has several (the image
 * gate: nothing set up, another app version, files changed...), so [Plain]
 * can pick the plain message. Every BLOCK and WARN has one; OK and INFO may not.
 */
data class Finding(
    val id: String,
    val level: Level,
    val title: String,
    val detail: String = "",
    val reason: String? = null,
)

/** The worst finding wins; WARN lets the user continue, BLOCK has no override. */
data class CompatReport(val findings: List<Finding>) {
    val verdict: Verdict = when {
        findings.any { it.level == Level.BLOCK } -> Verdict.NOT_SUPPORTED
        findings.any { it.level == Level.WARN } -> Verdict.WARNING
        else -> Verdict.COMPATIBLE
    }

    /** BLOCK first, then WARN, OK, INFO; rule order kept within a level. */
    val sorted: List<Finding> get() = findings.sortedByDescending { it.level.ordinal }

    fun find(id: String): Finding? = findings.firstOrNull { it.id == id }

    fun count(level: Level): Int = findings.count { it.level == level }
}
