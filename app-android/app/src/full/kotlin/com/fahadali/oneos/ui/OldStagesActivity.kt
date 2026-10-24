package com.fahadali.oneos.ui

import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.fahadali.oneos.R
import com.fahadali.oneos.core.model.Images
import com.fahadali.oneos.core.model.Level
import com.fahadali.oneos.core.model.Units
import com.fahadali.oneos.core.root.OldStage
import com.fahadali.oneos.session.OldStagesJob
import com.fahadali.oneos.session.StageCheck
import dev.oneuiproject.oneui.R as OuiIcons

/**
 * Free up space (Advanced, and the fix "Free up space"): how full the phone
 * is, the size of the system set up now, and the old copies setup kept
 * (oneos.old-<time>; lineage_stage.old-<time> up to 0.3.6) with what deleting
 * them frees. Delete is one copy at a time, after a confirmation; root
 * deletes only such a folder, and only when nothing is mounted, bridged or
 * running in it. The folder names and root's reasons are under Details.
 */
class OldStagesActivity : ToolbarActivity() {
    override val screenTitle: CharSequence get() = getString(R.string.space_title)

    override fun createFragment(): Fragment = OldStagesFragment()
}

class OldStagesFragment : CardsFragment() {
    private val subs = mutableListOf<() -> Unit>()

    override fun onStart() {
        super.onStart()
        subs += OldStagesJob.observe { redraw() }
        subs += StageCheck.observe { redraw() }
        if (!OldStagesJob.state.busy) OldStagesJob.refresh(requireContext())
    }

    override fun onStop() {
        subs.forEach { it() }
        subs.clear()
        super.onStop()
    }

    override fun build(column: LinearLayout) {
        val ctx = column.context
        val st = OldStagesJob.state
        val stages = st.stages
        val oldBytes = stages?.sumOf { it.kib }?.times(1024)

        // Storage: the bar, the system set up now, the old copies.
        val storage = Cards.section(column, getString(R.string.space_card_storage))
        CardRows.storage()?.let { (free, total) ->
            val used = ((total - free) * 100 / total).toInt()
            CardRows.storageBar(
                storage, used,
                getString(R.string.space_free_line, Units.bytes(free), Units.bytes(total)),
                getString(R.string.space_bar_desc, used),
            )
        }
        val tiles = mutableListOf<Pair<CharSequence, CharSequence>>()
        val now = StageCheck.state.result?.status?.takeIf { it.ours }
        val nowRaw = now?.rawSize
        if (now != null && nowRaw != null) {
            val system = Images.systemName(now.marker?.imageId?.let { Images.byId(it) })
            tiles += Units.bytes(nowRaw) to getString(R.string.space_system_label, system)
        }
        tiles += when {
            stages == null -> getString(R.string.space_value_unknown) to getString(R.string.space_old_label)
            stages.isEmpty() -> getString(R.string.space_value_none) to getString(R.string.space_old_label)
            else -> Units.bytes(oldBytes ?: 0) to resources.getQuantityString(R.plurals.advanced_space_copies, stages.size, stages.size)
        }
        CardRows.stats(storage, tiles)
        if (!stages.isNullOrEmpty() && oldBytes != null) {
            CardRows.text(storage, getString(R.string.space_frees, Units.bytes(oldBytes)), R.style.OneOS_Text_Caption)
        }

        // Old copies: one row each; a tap asks before it deletes.
        val list = Cards.section(column, getString(R.string.space_card_old), rows = true)
        CardRows.text(list, getString(R.string.space_old_line), R.style.OneOS_Text_Caption, inList = true)
        when {
            st.phase == OldStagesJob.Phase.LISTING ->
                CardRows.tint(Cards.row(list, OuiIcons.drawable.ic_oui_refresh, getString(R.string.space_listing), null), Level.INFO)
            stages == null -> CardRows.status(list, Level.WARN, getString(R.string.space_not_read), getString(R.string.space_not_read_line))
            stages.isEmpty() -> CardRows.status(list, Level.OK, getString(R.string.space_none), getString(R.string.space_none_summary))
            else -> for (s in stages) {
                val deleting = st.deleting == s.name
                val row = CardRows.row(
                    list,
                    if (s.busy) OuiIcons.drawable.ic_oui_lock else OuiIcons.drawable.ic_oui_delete_outline,
                    if (deleting) getString(R.string.space_deleting) else copyTitle(s),
                    describe(s),
                    enabled = !s.busy && (!st.busy || deleting),
                ) { if (!st.busy && !s.busy) confirmDelete(s) }
                if (s.busy) CardRows.tint(row, Level.WARN)
                if (deleting) CardRows.tint(row, Level.INFO)
            }
        }
        // The last delete: what it freed, or that it did not happen; or that reading the copies
        // again failed (the list above is the one read before). Root's reason is under Details.
        if (!st.busy && st.stages != null && st.message != null) {
            val freed = st.freedKib
            when {
                st.phase == OldStagesJob.Phase.DONE && freed != null ->
                    CardRows.status(list, Level.OK, getString(R.string.space_deleted, Units.bytes(freed * 1024)), null)
                st.phase == OldStagesJob.Phase.FAILED && st.deleteFailed ->
                    CardRows.status(list, Level.WARN, getString(R.string.space_not_deleted), getString(R.string.space_not_deleted_line))
                st.phase == OldStagesJob.Phase.FAILED ->
                    CardRows.status(list, Level.WARN, getString(R.string.space_not_read), getString(R.string.space_not_read_line))
            }
        }
        if (!st.busy) {
            Cards.row(list, OuiIcons.drawable.ic_oui_refresh, getString(R.string.space_check_again), null) { OldStagesJob.refresh(ctx) }
        }

        // Details: what root checks, the folder names, what root said.
        val more = Cards.section(column, title = null, rows = true)
        details(more, getString(R.string.space_details_summary)) { card ->
            CardRows.text(card, getString(R.string.space_details_rules), R.style.OneOS_Text_Caption, inList = true)
            stages?.forEach { s -> Cards.row(card, 0, s.name, getString(R.string.space_details_folder, OldStagesJob.gib(s.kib), s.imageId ?: "-")) }
            st.message?.let { Cards.row(card, 0, getString(R.string.setup_details_result), it) }
        }
    }

    /** "Old copy of {system}", or the copy a stopped delete left. */
    private fun copyTitle(s: OldStage): String {
        val name = s.imageId?.let { Images.byId(it) }?.name
        return when {
            s.isTrash -> getString(R.string.space_old_trash)
            name != null -> getString(R.string.space_old_of, name)
            else -> getString(R.string.space_old_unknown)
        }
    }

    /** "10.3 GB · kept since Oct 23, 2026 · with its apps and data". */
    private fun describe(s: OldStage): String {
        val parts = mutableListOf(Units.bytes(s.kib * 1024))
        since(s)?.let { parts += it }
        if (s.hasData) parts += getString(R.string.space_old_has_data)
        if (s.busy) parts += getString(R.string.space_old_in_use)
        return parts.joinToString(" · ")
    }

    /** "kept since Oct 23, 2026" ("left on" for an unfinished delete), when the folder name says. */
    private fun since(s: OldStage): String? = s.setAsideEpoch?.let { e ->
        getString(if (s.isTrash) R.string.space_old_stopped_since else R.string.space_old_kept_since, CardRows.date(requireContext(), e * 1000))
    }

    private fun confirmDelete(s: OldStage) {
        val name = s.imageId?.let { Images.byId(it) }?.name
        val what = if (name != null && !s.isTrash) getString(R.string.space_delete_what_of, name) else getString(R.string.space_delete_what)
        // The size and the date tell two copies of the same system apart.
        val sizeAndDate = listOfNotNull(Units.bytes(s.kib * 1024), since(s)).joinToString(", ")
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.space_delete_title)
            .setMessage(getString(R.string.space_delete_message, what, sizeAndDate))
            .setPositiveButton(R.string.space_delete_run) { _, _ -> OldStagesJob.delete(requireContext(), s) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
