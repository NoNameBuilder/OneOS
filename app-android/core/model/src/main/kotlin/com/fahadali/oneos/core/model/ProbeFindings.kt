package com.fahadali.oneos.core.model

/**
 * Host SELinux type names from `selinux/types.txt` (lines `(type name` or
 * `(typealias name`, read from the CIL files the way guest_prepare.sh does,
 * debug files skipped).
 */
data class SelinuxTypes(val declarations: Int, val aliases: Int, val names: Set<String>) {
    val missingGeneric: List<String> get() = ProbeFindings.GENERIC_APP_TYPES.filterNot { it in names }

    companion object {
        private val LINE = Regex("""^\s*\((type|typealias)\s+([^\s)]+)""")

        fun parse(text: String): SelinuxTypes {
            var types = 0
            var aliases = 0
            val names = sortedSetOf<String>()
            for (l in text.lineSequence()) {
                val m = LINE.find(l) ?: continue
                if (m.groupValues[1] == "type") types++ else aliases++
                names += m.groupValues[2]
            }
            return SelinuxTypes(types, aliases, names)
        }
    }
}

/** What the findings need from a probe run (read from the capture before redaction). */
data class ProbeData(
    val results: List<ProbeResult>,
    val selinux: SelinuxTypes?,
    /** A OneOS stage exists (`/data/local/tmp/oneos`, or `/data/local/tmp/lineage_stage` up to 0.3.6); null = not checked. */
    val stagePresent: Boolean?,
    /** First line of root/manager.txt, e.g. `kernelsu ksud 1.0.5`. */
    val rootManager: String?,
    val installd: InstalldDerivation?,
)

/**
 * Findings that need root reads (the probe), added to [CompatEvaluator]'s.
 * Ids are stable for logs and fix packs.
 */
object ProbeFindings {
    /** The generic types the guest seapp rewrite relies on (compat-safety-logs.md 1.8). */
    val GENERIC_APP_TYPES = listOf(
        "untrusted_app", "priv_app", "platform_app", "system_app", "app_data_file", "privapp_data_file",
        "system_app_data_file",
    )

    /** installd calls the guest must be able to reach (the table is not the interface we think without them). */
    val INSTALLD_REQUIRED = listOf("destroyUserData", "createAppData")

    fun evaluate(d: ProbeData): List<Finding> {
        val out = mutableListOf<Finding>()

        val ins = d.installd
        when {
            ins == null -> out += Finding(
                "installd-unknown", Level.WARN, "installd interface not read",
                "The captured files did not include One UI's installd interface. Switching stays " +
                    "blocked until the OneOS team has derived it.",
            )
            !ins.ok -> out += Finding(
                "installd-unknown", Level.WARN, "installd interface not derived on the phone",
                "${ins.error}. The raw files are in the findings; switching stays blocked until the " +
                    "OneOS team has derived the table from them.",
            )
            INSTALLD_REQUIRED.any { !ins.has(it) } -> out += Finding(
                "installd-unexpected", Level.WARN, "installd interface looks different",
                "${ins.methods.size} methods, but ${INSTALLD_REQUIRED.filterNot(ins::has).joinToString()} " +
                    "missing. Switching stays blocked until the OneOS team has checked it.",
            )
            else -> out += Finding(
                "installd-table", Level.OK, "installd interface read: ${ins.methods.size} methods",
                "From ${ins.sourceJar}${ins.sourceEntry?.let { "!$it" } ?: ""}; " +
                    "${INSTALLD_REQUIRED.joinToString(" and ")} present. The OneOS team derives it again " +
                    "from the captured files before switching is allowed.",
            )
        }

        val se = d.selinux
        when {
            se == null || se.names.isEmpty() -> out += Finding(
                "selinux-types-unread", Level.INFO, "SELinux types not read",
                "The host policy files could not be read.",
            )
            se.missingGeneric.isNotEmpty() -> out += Finding(
                "selinux-types-missing", Level.WARN, "SELinux app types missing",
                "The host policy lacks ${se.missingGeneric.joinToString()}. The guest's app labels are " +
                    "rewritten to these types, so that rewrite would stay off.",
            )
            else -> out += Finding(
                "selinux-types", Level.OK, "SELinux policy has the generic app types",
                "${se.declarations} type declarations (${se.names.size} names with aliases).",
            )
        }

        if (d.stagePresent == true) {
            out += Finding(
                "stage-present", Level.INFO, "A OneOS stage exists",
                "/data/local/tmp/oneos (or /data/local/tmp/lineage_stage, its path up to OneOS 0.3.6) is there. OneOS Probe only listed it.",
            )
        }

        d.rootManager?.takeIf { it.isNotBlank() }?.let {
            out += Finding("root-manager-version", Level.INFO, "Root manager: ${it.take(80)}")
        }

        val bad = d.results.filter { it.status == ProbeStatus.FAILED || it.status == ProbeStatus.TIMEOUT }
        if (bad.isNotEmpty()) {
            out += Finding(
                "probes-incomplete", Level.INFO, "${bad.size} of ${d.results.size} tests failed or timed out",
                bad.joinToString(", ") { "${it.id} (${it.status.label}${it.exitCode?.let { c -> ", exit $c" } ?: ""})" },
            )
        }
        return out
    }
}
