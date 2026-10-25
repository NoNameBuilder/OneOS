package com.fahadali.oneos.core.root

/**
 * The stages `begin aside` left behind (0.3.5): what `old-list` reports, one
 * `@@RESULT old <name> <KiB> <image|unknown> <data 0|1> <busy 0|1>` line each,
 * and what `purge <name>` may delete. Named `oneos.old-<epoch>[-<pid>]`, or
 * `lineage_stage.old-...` up to 0.3.6 until the root script renames them
 * ([RootScript.OLD_STAGE_NAME]).
 */
data class OldStage(
    val name: String,
    val kib: Long,
    /** The image id from its marker, or null (a stage OneOS did not prepare). */
    val imageId: String?,
    /** It holds ColorOS's own /data (apps, accounts, settings). */
    val hasData: Boolean,
    /** Mounted, a loop device on it, or a session of this boot: purge would refuse. */
    val busy: Boolean,
) {
    /** `oneos.trash-*` (`lineage_stage.trash-*` up to 0.3.6): a delete that stopped part-way. */
    val isTrash: Boolean get() = name.startsWith("oneos.trash-") || name.startsWith("lineage_stage.trash-")

    /** Seconds since 1970 from the name (when it was set aside), or null. */
    val setAsideEpoch: Long? get() = name.substringAfter('-').substringBefore('-').toLongOrNull()
}

object OldStages {
    private val ID = Regex("^[a-z0-9][a-z0-9-]{0,31}$")

    /** Parses `old-list`'s results, oldest first, the stopped deletes last; lines that are not well formed are skipped. */
    fun parse(results: List<String>): List<OldStage> = results.mapNotNull { line ->
        val p = line.split(' ')
        if (p.size != 6 || p[0] != "old" || !RootScript.OLD_STAGE_NAME.matches(p[1])) return@mapNotNull null
        val kib = p[2].toLongOrNull()?.takeIf { it >= 0 } ?: return@mapNotNull null
        val image = p[3].takeIf { it != "unknown" && ID.matches(it) }
        if (p[4] !in setOf("0", "1") || p[5] !in setOf("0", "1")) return@mapNotNull null
        OldStage(p[1], kib, image, p[4] == "1", p[5] == "1")
    }.sortedWith(compareBy<OldStage>({ it.isTrash }, { it.setAsideEpoch }, { it.name }))

    /** The name the active stage got when `restore` set it aside (`@@RESULT restored <name> <aside>`), or null. */
    fun setAsideBy(results: List<String>, name: String): String? =
        results.firstNotNullOfOrNull { l ->
            val p = l.split(' ')
            if (p.size == 3 && p[0] == "restored" && p[1] == name && RootScript.OLD_STAGE_NAME.matches(p[2])) p[2] else null
        }

    /** The KiB `purge` reports it freed (`@@RESULT purged <name> <KiB>`), or null. */
    fun freedKib(results: List<String>, name: String): Long? =
        results.firstNotNullOfOrNull { l ->
            val p = l.split(' ')
            if (p.size == 3 && p[0] == "purged" && p[1] == name) p[2].toLongOrNull() else null
        }
}
