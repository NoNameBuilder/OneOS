package com.fahadali.oneos.ui

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.TypefaceSpan
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.fahadali.oneos.R
import com.fahadali.oneos.core.model.ProbeCommand
import com.fahadali.oneos.core.model.ProbeCommandList
import com.fahadali.oneos.core.model.ProbeKind
import com.fahadali.oneos.core.model.ProbeStatus
import com.fahadali.oneos.probe.ProbeSession
import com.fahadali.oneos.probe.ProbeSession.Phase
import dev.oneuiproject.oneui.R as OuiIcons

/** Run tests (Friend build 1): read-only probes as root, then the findings to share. */
class RunTestsActivity : ToolbarActivity() {
    override val screenTitle: CharSequence get() = getString(R.string.tests_title)

    override fun createFragment(): Fragment = RunTestsFragment()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setExpandedSubtitle(getString(R.string.tests_subtitle))
    }
}

class RunTestsFragment : PreferenceFragmentCompat() {
    private var unsubscribe: (() -> Unit)? = null
    private val rows = mutableMapOf<String, Preference>()
    private lateinit var runPref: Preference
    private lateinit var stopPref: Preference
    private lateinit var viewPref: Preference
    private lateinit var bundled: ProbeSession.Bundled

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = preferenceManager.context
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx)
        bundled = ProbeSession.bundled(requireContext())

        val about = category(getString(R.string.tests_cat_about))
        about.row(getString(R.string.tests_what_title), getString(R.string.tests_what_summary), icon(OuiIcons.drawable.ic_oui_labs_outline))
        about.row(getString(R.string.tests_readonly_title), getString(R.string.tests_readonly_summary), icon(OuiIcons.drawable.ic_oui_security_outline))
        about.row(getString(R.string.tests_privacy_title), getString(R.string.tests_privacy_summary), icon(OuiIcons.drawable.ic_oui_privacy))

        val run = category(getString(R.string.tests_cat_run))
        if (bundled.problems.isNotEmpty()) {
            run.row(getString(R.string.tests_list_broken_title), bundled.problems.take(5).joinToString("\n"))
        }
        runPref = run.row(getString(R.string.tests_title), getString(R.string.tests_run_summary), onClick = ::confirmRun)
        stopPref = run.row(getString(R.string.tests_stop_title), getString(R.string.tests_stop_summary), onClick = ProbeSession::cancel)
        viewPref = run.row(getString(R.string.tests_view_title), getString(R.string.tests_view_summary), onClick = ::openFindings)

        for (group in ProbeCommandList.groups(bundled.commands)) {
            val cat = category(group)
            for (c in bundled.commands.filter { it.group == group }) {
                rows[c.id] = cat.row(c.description, c.id, onClick = { showCommand(c) })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        unsubscribe = ProbeSession.observe(::render)
    }

    override fun onStop() {
        unsubscribe?.invoke()
        unsubscribe = null
        super.onStop()
    }

    private fun render(s: ProbeSession.State) {
        if (!isAdded) return
        val ctx = requireContext()
        val canRun = bundled.problems.isEmpty() && !s.busy
        runPref.isEnabled = canRun
        runPref.title = getString(if (s.phase == Phase.DONE) R.string.tests_run_again else R.string.tests_title)
        runPref.summary = when (s.phase) {
            Phase.IDLE -> s.message ?: getString(R.string.tests_run_summary)
            Phase.ROOT_CHECK -> getString(R.string.tests_checking_root)
            Phase.ROOT_FAILED, Phase.FAILED -> s.message
            Phase.RUNNING -> "Running ${minOf(s.finished + 1, s.total)} of ${s.total}" + (s.current?.let { ": $it" } ?: "")
            Phase.BUILDING -> getString(R.string.tests_building)
            Phase.DONE -> s.message
        }
        stopPref.isVisible = s.phase == Phase.ROOT_CHECK || s.phase == Phase.RUNNING
        val zip = if (s.busy) null else (s.zip ?: ProbeSession.latestZip(ctx))
        viewPref.isEnabled = zip != null
        viewPref.summary = if (zip != null) getString(R.string.tests_view_summary) else getString(R.string.tests_view_none)

        for ((id, pref) in rows) {
            val st = s.statuses[id] ?: ProbeStatus.PENDING
            val code = s.exitCodes[id]
            pref.summary = buildString {
                append(id)
                if (s.statuses.isNotEmpty()) {
                    append(" · ").append(st.label)
                    if (st == ProbeStatus.FAILED && code != null) append(" (exit $code)")
                }
            }
            pref.icon = if (s.statuses.isEmpty()) null else statusIcon(st)
            pref.isIconSpaceReserved = s.statuses.isNotEmpty()
        }
    }

    private fun confirmRun() {
        if (ProbeSession.state.busy) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.tests_dialog_title)
            .setMessage(R.string.tests_dialog_message)
            .setPositiveButton(R.string.tests_run) { _, _ -> ProbeSession.start(requireContext()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openFindings() {
        startActivity(Intent(requireContext(), FindingsActivity::class.java))
    }

    /** The exact text `sh -c` runs as root for this entry. */
    private fun showCommand(c: ProbeCommand) {
        val text = "Runs as root, read only, for at most ${c.timeoutSec} s. Output: ${c.target}" +
            (if (c.kind == ProbeKind.FILES) " (one file per path it prints)" else "") + "\n\n"
        val msg = SpannableString(text + c.command.trim())
        msg.setSpan(TypefaceSpan("monospace"), text.length, msg.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        AlertDialog.Builder(requireContext())
            .setTitle(c.id)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun statusIcon(st: ProbeStatus): Drawable? = when (st) {
        ProbeStatus.PENDING -> icon(OuiIcons.drawable.ic_oui_checkbox_unchecked, R.color.verdict_info)
        ProbeStatus.RUNNING -> icon(OuiIcons.drawable.ic_oui_refresh, R.color.verdict_info)
        ProbeStatus.OK -> icon(OuiIcons.drawable.ic_oui_checkbox_checked, R.color.verdict_ok)
        ProbeStatus.NOT_PRESENT -> icon(OuiIcons.drawable.ic_oui_minus, R.color.verdict_info)
        ProbeStatus.TIMEOUT -> icon(OuiIcons.drawable.ic_oui_error, R.color.verdict_warn)
        ProbeStatus.FAILED -> icon(OuiIcons.drawable.ic_oui_error, R.color.verdict_block)
    }

    private fun icon(res: Int, color: Int? = null): Drawable? {
        val ctx = requireContext()
        return ctx.getDrawable(res)?.mutate()?.apply { if (color != null) setTint(ctx.getColor(color)) }
    }

    private fun category(title: String): PreferenceCategory {
        val c = PreferenceCategory(preferenceManager.context)
        c.title = title
        c.isIconSpaceReserved = false
        preferenceScreen.addPreference(c)
        return c
    }

    private fun PreferenceCategory.row(
        title: CharSequence,
        summary: CharSequence?,
        icon: Drawable? = null,
        onClick: (() -> Unit)? = null,
    ): Preference {
        val p = Preference(context)
        p.title = title
        p.summary = summary
        p.icon = icon
        p.isIconSpaceReserved = icon != null
        if (onClick == null) {
            p.isSelectable = false
        } else {
            p.setOnPreferenceClickListener { onClick(); true }
        }
        addPreference(p)
        return p
    }
}
