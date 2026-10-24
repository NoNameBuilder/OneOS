package com.fahadali.oneos.core.model

import java.util.Locale

/** One `processor : N` block of arm64 `/proc/cpuinfo`. */
data class CpuCore(
    val index: Int,
    /** `CPU implementer`, e.g. `0x51` (Qualcomm), `0x41` (Arm). */
    val implementer: String,
    val architecture: String,
    val variant: String,
    /** `CPU part`, e.g. `0xd81`. */
    val part: String,
    val revision: String,
    /** The `Features` line of this core, lower case. */
    val features: Set<String>,
) {
    /** "Qualcomm 0x001 r0p2" or "Arm Cortex-A720 r0p1". */
    val label: String
        get() {
            val maker = CpuInfoParser.implementerName(implementer)
            val partName = CpuInfoParser.partName(implementer, part)
            val rev = if (variant.isEmpty() && revision.isEmpty()) null else "r${variant.removePrefix("0x")}p$revision"
            return listOfNotNull(maker ?: implementer.ifEmpty { null }, partName ?: part.ifEmpty { null }, rev)
                .joinToString(" ").ifEmpty { "unidentified core" }
        }
}

/**
 * Parsed `/proc/cpuinfo` (readable by apps; the probe reads it again as root).
 * The kernel prints the system-wide hwcaps for every core, so [features] (the
 * features every core lists) is what user space can rely on.
 */
data class CpuInfo(val cores: List<CpuCore>, val hardware: String) {
    /** Features present on every core. */
    val features: Set<String> =
        if (cores.isEmpty()) emptySet() else cores.map { it.features }.reduce { a, b -> a intersect b }

    /** True when the cores do not all list the same features. */
    val heterogeneous: Boolean get() = cores.map { it.features }.distinct().size > 1

    fun has(feature: String): Boolean = feature.lowercase(Locale.ROOT) in features

    /** "8 cores: 2 x Qualcomm 0x002 r0p2, 6 x Qualcomm 0x001 r0p2". */
    val summary: String
        get() {
            if (cores.isEmpty()) return "No cores listed"
            val groups = cores.groupBy { it.label }.entries.joinToString(", ") { (label, list) -> "${list.size} x $label" }
            return "${cores.size} core${if (cores.size == 1) "" else "s"}: $groups"
        }
}

object CpuInfoParser {
    private val KV = Regex("""^([^:]+?)\s*:\s*(.*)$""")

    fun parse(text: String): CpuInfo {
        val cores = mutableListOf<CpuCore>()
        var current: MutableMap<String, String>? = null
        var hardware = ""
        var globalFeatures: Set<String>? = null

        fun flush() {
            val c = current ?: return
            cores += CpuCore(
                index = c["processor"]?.trim()?.toIntOrNull() ?: cores.size,
                implementer = c["cpu implementer"].orEmpty().lowercase(Locale.ROOT),
                architecture = c["cpu architecture"].orEmpty(),
                variant = c["cpu variant"].orEmpty().lowercase(Locale.ROOT),
                part = c["cpu part"].orEmpty().lowercase(Locale.ROOT),
                revision = c["cpu revision"].orEmpty(),
                features = c["features"]?.let(::featureSet) ?: emptySet(),
            )
            current = null
        }

        for (raw in text.lineSequence()) {
            val m = KV.matchEntire(raw.trim()) ?: continue
            val key = m.groupValues[1].trim().lowercase(Locale.ROOT)
            val value = m.groupValues[2].trim()
            when {
                key == "processor" -> {
                    flush()
                    current = mutableMapOf("processor" to value)
                }
                key == "hardware" -> hardware = value
                current != null -> current!![key] = value
                key == "features" -> globalFeatures = featureSet(value)
            }
        }
        flush()
        // Very old layouts list Features once, outside the processor blocks.
        val withGlobal = if (globalFeatures != null) {
            cores.map { if (it.features.isEmpty()) it.copy(features = globalFeatures) else it }
        } else {
            cores
        }
        return CpuInfo(withGlobal, hardware)
    }

    private fun featureSet(v: String): Set<String> =
        v.split(Regex("\\s+")).map { it.lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.toSet()

    fun implementerName(implementer: String): String? = when (implementer.lowercase(Locale.ROOT)) {
        "0x41" -> "Arm"
        "0x51" -> "Qualcomm"
        "0x53" -> "Samsung"
        else -> null
    }

    /** Arm's own core names (MIDR part numbers from the Arm TRMs); others stay as hex. */
    fun partName(implementer: String, part: String): String? {
        if (implementer.lowercase(Locale.ROOT) != "0x41") return null
        return ARM_PARTS[part.lowercase(Locale.ROOT)]
    }

    private val ARM_PARTS = mapOf(
        "0xd05" to "Cortex-A55",
        "0xd0d" to "Cortex-A77",
        "0xd41" to "Cortex-A78",
        "0xd44" to "Cortex-X1",
        "0xd46" to "Cortex-A510",
        "0xd47" to "Cortex-A710",
        "0xd48" to "Cortex-X2",
        "0xd4d" to "Cortex-A715",
        "0xd4e" to "Cortex-X3",
        "0xd80" to "Cortex-A520",
        "0xd81" to "Cortex-A720",
        "0xd82" to "Cortex-X4",
        "0xd85" to "Cortex-X925",
        "0xd87" to "Cortex-A725",
    )
}

/**
 * CPU features the OneOS images need. The ColorOS images'
 * `/system/bin/bootstrap/linker64` contains SME, MOPS and SVE/SVE2
 * instructions (MysticGSI's build warning, `mysticgsi/tools/isa.py`). The tested
 * Z Fold (SM8850) runs ColorOS with SVE, SVE2 and SME but without SME2 or MOPS
 * (its /proc/cpuinfo in the findings of 2026-10-22), so the MOPS code has a
 * runtime fallback. A CPU without SVE2 or SME is a WARN: the image may not start
 * there (it may also have runtime fallbacks; untested).
 *
 * Build 2 makes the rule per image ([forImage], DA14 / AA21): the scan of the
 * Find X9 Ultra image found SVE/SME only in bionic's runtime-selected routines
 * and some OPPO app and media libraries, so that image needs nothing beyond
 * arm64 and a CPU without them (the SM8750 of the S25 Ultra and S25) gets a WARN that some
 * OPPO app and media features may crash. The Find N6 image keeps the rule
 * above ([finding]).
 */
object CpuRules {
    /** Shown in the report and the findings, in this order. */
    val REPORTED = listOf("sve", "sve2", "sme", "sme2", "mops", "bf16", "i8mm")

    /** Missing any of these is the WARN. */
    val COLOROS_REQUIRED = listOf("sve2", "sme")

    const val ID_OK = "cpu-features"
    const val ID_MISSING = "cpu-features-missing"
    const val ID_UNKNOWN = "cpu-unknown"

    /** [forImage]: the image runs without these, some OPPO app and media libraries use them. */
    const val ID_APP_FEATURES = "cpu-app-features"

    fun missingForColorOs(cpu: CpuInfo): List<String> = COLOROS_REQUIRED.filterNot(cpu::has)

    /** "sve yes, sve2 yes, sme no, ...". */
    fun presence(cpu: CpuInfo): String =
        REPORTED.joinToString(", ") { "${it.uppercase(Locale.ROOT)} ${if (cpu.has(it)) "yes" else "no"}" }

    /** [Finding.reason]: a feature the image may not start without is missing; a feature only some of its apps use. */
    const val R_MISSING = "missing"
    const val R_APP_FEATURES = "app-features"

    /** What the texts call the images [finding] is for when no image is named. */
    const val SYSTEM_IMAGES = "the system images"

    /** [what]: the image ([ImageRef.name]) or, without one, [SYSTEM_IMAGES]. */
    fun finding(cpu: CpuInfo?, required: List<String> = COLOROS_REQUIRED, what: String = SYSTEM_IMAGES): Finding {
        if (cpu == null || cpu.cores.isEmpty()) {
            return Finding(
                ID_UNKNOWN, Level.INFO, "CPU features not read",
                "Could not read /proc/cpuinfo. ${Sentence.start(what)} may use ${required.joinToString(" and ") { it.uppercase(Locale.ROOT) }} instructions.",
            )
        }
        val missing = required.filterNot(cpu::has)
        return if (missing.isEmpty()) {
            Finding(
                ID_OK, Level.OK, "CPU has the features needed by $what",
                "${presence(cpu)}.",
            )
        } else {
            val names = missing.joinToString(", ") { it.uppercase(Locale.ROOT) }
            Finding(
                ID_MISSING, Level.WARN, "CPU lacks $names used by $what",
                "The linker of $what (/system/bin/bootstrap/linker64) uses SME and SVE/SVE2 " +
                    "instructions; the tested Z Fold (SM8850) has them (it has no SME2 or MOPS, which it " +
                    "does without). It may not start on this CPU. " +
                    "${presence(cpu)}.",
                R_MISSING,
            )
        }
    }

    /**
     * The CPU rule for [image]: its [ImageRef.cpuRequired] works like
     * [finding]; missing [ImageRef.cpuUsedByApps] is [ID_APP_FEATURES] WARN.
     * Never BLOCK: the scan is not a run.
     */
    fun forImage(image: ImageRef, cpu: CpuInfo?): Finding {
        if (image.cpuRequired.isNotEmpty()) return finding(cpu, image.cpuRequired, image.name)
        if (cpu == null || cpu.cores.isEmpty()) {
            return Finding(
                ID_UNKNOWN, Level.INFO, "CPU features not read",
                "Could not read /proc/cpuinfo. The ${image.shortName} image needs nothing beyond 64-bit ARM.",
            )
        }
        val missing = image.cpuUsedByApps.filterNot(cpu::has)
        return if (missing.isEmpty()) {
            Finding(ID_OK, Level.OK, "CPU has the features the ${image.shortName} image uses", "${presence(cpu)}.")
        } else {
            Finding(
                ID_APP_FEATURES, Level.WARN, "Some app and media features may crash",
                "The ${image.shortName} image's system does not need ${missing.joinToString(", ") { it.uppercase(Locale.ROOT) }} " +
                    "(scan of the image), but some of its app and media libraries use them and this CPU lacks them. " +
                    "${presence(cpu)}.",
                R_APP_FEATURES,
            )
        }
    }
}
