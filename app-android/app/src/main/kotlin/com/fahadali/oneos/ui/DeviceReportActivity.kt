package com.fahadali.oneos.ui

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import com.fahadali.oneos.R
import com.fahadali.oneos.core.device.FactProbe
import com.fahadali.oneos.core.model.CompatEvaluator
import com.fahadali.oneos.core.model.CompatReport
import com.fahadali.oneos.core.model.CpuRules
import com.fahadali.oneos.core.model.DeviceFacts
import com.fahadali.oneos.core.model.Level
import com.fahadali.oneos.core.model.ModelFamily
import com.fahadali.oneos.core.model.PackageSets
import com.fahadali.oneos.core.model.Profiles
import com.fahadali.oneos.core.model.RootKind
import com.fahadali.oneos.core.model.SpaceRule
import com.fahadali.oneos.core.model.SuProbeResult
import com.fahadali.oneos.core.model.SuState
import com.fahadali.oneos.core.model.Units
import com.fahadali.oneos.core.model.Verdict
import com.fahadali.oneos.core.root.RootProbe
import com.fahadali.oneos.core.root.SuProbe
import com.fahadali.oneos.probe.CarrierProps
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import dev.oneuiproject.oneui.R as OuiIcons

/** Device report: the read-only facts plus the Compatible / Warning / Not supported summary. */
class DeviceReportActivity : ToolbarActivity() {
    override val screenTitle: CharSequence get() = getString(R.string.report_title)

    override fun createFragment(): Fragment = DeviceReportFragment()
}

class DeviceReportFragment : PreferenceFragmentCompat() {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Result of the user-started `su -c id`, kept across recreation. */
    private var probe: SuProbeResult? = null
    private var probing = false
    private var checkRootPref: Preference? = null

    /** Set once a report is drawn: the screen lock it showed (null = unknown). */
    private var rendered = false
    private var shownScreenLock: Boolean? = null

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        probe = savedInstanceState?.let(::restoreProbe)
        preferenceScreen = preferenceManager.createPreferenceScreen(preferenceManager.context)
        category(getString(R.string.report_cat_summary))
            .row(getString(R.string.report_reading), null)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        probe?.let { p ->
            outState.putString(KEY_STATE, p.state.name)
            p.uid?.let { outState.putInt(KEY_UID, it) }
            outState.putString(KEY_CONTEXT, p.context)
            outState.putString(KEY_OUTPUT, p.output)
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Settings after removing (or setting) the screen lock: read again.
        if (rendered && !probing && FactProbe.screenLockSet(requireContext()) != shownScreenLock) refresh()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Re-reads every fact off the main thread, then redraws. */
    private fun refresh() {
        val ctx = requireContext().applicationContext
        val probeNow = probe
        val app = getString(R.string.app_name)
        executor.execute {
            // An exception escaping execute() would kill the app: show it instead.
            val result = runCatching {
                val facts = CarrierProps.withCarrier(FactProbe(ctx).collect(RootProbe(ctx).signals(probeNow)))
                facts to CompatEvaluator.evaluate(facts, app = app)
            }
            main.post {
                if (!isAdded) return@post
                result.fold(
                    onSuccess = { (facts, report) -> render(facts, report) },
                    onFailure = ::renderError,
                )
            }
        }
    }

    private fun renderError(e: Throwable) {
        preferenceScreen.removeAll()
        category(getString(R.string.report_cat_summary))
            .row(getString(R.string.report_failed), "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(500))
    }

    private fun render(f: DeviceFacts, report: CompatReport) {
        val screen = preferenceScreen
        screen.removeAll()
        rendered = true
        shownScreenLock = f.screenLockSet
        (activity as? ToolbarActivity)?.setExpandedSubtitle("${f.model} · ${report.verdict.label}")

        // Summary + findings, worst first.
        val summary = category(getString(R.string.report_cat_summary))
        summary.row(verdictTitle(report.verdict), verdictSummary(report))
        for (finding in report.sorted) {
            summary.row(finding.title, finding.detail.ifEmpty { null }, icon = levelIcon(finding.level))
        }

        val phone = category(getString(R.string.report_cat_phone))
        val family = f.family
        phone.row("Model", if (family == ModelFamily.OTHER) f.model else "${f.model} · ${family.label}")
        phone.row("Codename", f.device.ifEmpty { NOT_REPORTED })
        phone.row("Maker", "${f.brand} / ${f.manufacturer}")
        phone.row("Form factor", buildString {
            append(f.form.form.label)
            append("\n")
            append(f.form.evidence.joinToString("; "))
            if (f.form.mismatch) append("\nModel number says ${f.form.prefixForm?.label}")
        })
        phone.row("Displays", displaysText(f))

        val chip = category(getString(R.string.report_cat_chip))
        chip.row("SoC", f.soc.display)
        chip.row("Chip maker", buildString {
            append(f.soc.vendor.label)
            if (f.socProps.manufacturer.isNotEmpty()) append(" (ro.soc.manufacturer ${f.socProps.manufacturer})")
        })
        chip.row("Graphics", f.socProps.egl.ifEmpty { NOT_REPORTED })
        chip.row("64-bit ABIs", f.abis64.joinToString().ifEmpty { "None" })
        // Every image this phone takes: its CPU rule and what preparing it needs.
        val profile = Profiles.resolve(f).profile
        val images = profile?.images().orEmpty()
        chip.row("CPU", f.cpu?.let { cpu ->
            if (images.isNotEmpty()) {
                "${cpu.summary}\n${CpuRules.presence(cpu)}\n" +
                    images.joinToString("\n") { "${it.shortName} image: ${CpuRules.forImage(it, cpu).title}" }
            } else {
                val missing = CpuRules.missingForColorOs(cpu)
                "${cpu.summary}\n${CpuRules.presence(cpu)}\nSome system images need " +
                    CpuRules.COLOROS_REQUIRED.joinToString(", ") { it.uppercase(Locale.ROOT) } + ": " +
                    if (missing.isEmpty()) "all present" else "missing ${missing.joinToString(", ") { it.uppercase(Locale.ROOT) }}"
            }
        } ?: NOT_REPORTED)

        val sw = category(getString(R.string.report_cat_software))
        sw.row("One UI", f.oneUi?.let { "$it (${f.oneUiRaw})" } ?: "Not found")
        sw.row("Android", "${f.androidRelease} (SDK ${f.sdk})")
        sw.row("Build ID", f.buildId.ifEmpty { NOT_REPORTED })
        sw.row("Fingerprint", f.fingerprint.ifEmpty { NOT_REPORTED })
        sw.row("Vendor API level", f.vendorApiLevel.ifEmpty { NOT_REPORTED })
        sw.row("Kernel", f.kernel.ifEmpty { NOT_REPORTED })

        val root = category(getString(R.string.report_cat_root))
        root.row("Root manager", rootText(f))
        checkRootPref = root.row(
            getString(R.string.check_root_title),
            if (probing) getString(R.string.check_root_running) else probeText(probe),
            onClick = ::confirmRootCheck,
        )

        val storage = category(getString(R.string.report_cat_storage))
        storage.row("Free space on /data", f.storage?.let {
            "${Units.bytes(it.freeBytes)} free of ${Units.bytes(it.totalBytes)}\n" +
                if (profile != null && images.isNotEmpty()) {
                    images.joinToString("\n") { image ->
                        "Preparing the ${image.shortName} image needs about " +
                            Units.bytes(SpaceRule.toStage(image, profile.choice(image.id)?.let { c -> PackageSets.byId(c.packageSetId) }))
                    }
                } else {
                    "A session with a typical ${Units.bytes(SpaceRule.TYPICAL_IMAGE_BYTES)} image needs about " + Units.bytes(SpaceRule.required())
                }
        } ?: NOT_REPORTED)
        storage.row("Battery", f.battery?.let { b ->
            buildString {
                append("${b.levelPercent}%")
                append(if (b.charging) ", charging" else ", not charging")
                b.temperatureC?.let { append(String.format(Locale.ROOT, ", %.1f °C", it)) }
            }
        } ?: NOT_REPORTED)

        val boot = category(getString(R.string.report_cat_boot))
        boot.row("Bootloader", when (f.flashLocked) {
            "1" -> "Locked (OneOS works locked; nothing is flashed)"
            "0" -> "Unlocked"
            else -> NOT_REPORTED
        })
        boot.row("Verified boot", f.verifiedBootState.ifEmpty { NOT_REPORTED })
        boot.row("Screen lock", when (f.screenLockSet) {
            true -> "Set (PIN, pattern or password). Remove it before switching."
            false -> "None"
            null -> NOT_REPORTED
        })
        boot.row(getString(R.string.report_readonly_title), getString(R.string.report_readonly_summary))
    }

    private fun displaysText(f: DeviceFacts): String {
        if (f.panels.isEmpty()) return "None visible"
        val lines = f.panels.map { p ->
            val size = p.diagonalInches?.let { String.format(Locale.ROOT, ", %.1f in", it) } ?: ""
            "#${p.displayId} ${p.name}: ${p.widthPx} x ${p.heightPx} px$size"
        }.toMutableList()
        if (!f.panelsComplete) {
            lines += "Only active displays are visible on this Android version"
        }
        return lines.joinToString("\n")
    }

    private fun rootText(f: DeviceFacts): String = buildString {
        append(f.rootKind.label)
        if (f.root.managerPackages.isNotEmpty()) append("\nApp: ${f.root.managerPackages.joinToString()}")
        append(
            if (f.root.suPaths.isNotEmpty()) "\nsu visible at ${f.root.suPaths.joinToString()}"
            else "\nsu not visible to ${getString(R.string.app_name)}",
        )
        if (f.rootKind == RootKind.KERNELSU && f.root.suPaths.isEmpty()) {
            append(" (KernelSU shows it only to apps it allows)")
        }
    }

    private fun probeText(p: SuProbeResult?): String = when (p?.state) {
        null -> getString(R.string.check_root_summary)
        SuState.GRANTED -> "Root works: ${p.output}"
        SuState.TIMEOUT -> "No answer in time. Allow ${getString(R.string.app_name)} in your root manager, then tap to try again."
        SuState.NOT_FOUND -> "su not found for ${getString(R.string.app_name)}: ${p.output}"
        SuState.DENIED -> "Root refused: ${p.output.ifEmpty { "no output" }}"
        SuState.ERROR -> "Check failed: ${p.output}"
    }

    private fun confirmRootCheck() {
        if (probing) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.check_root_dialog_title)
            .setMessage(R.string.check_root_dialog_message)
            .setPositiveButton(R.string.check_root_run) { _, _ -> runRootCheck() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The one root command of M1, started only from the dialog above. */
    private fun runRootCheck() {
        if (probing) return
        probing = true
        checkRootPref?.summary = getString(R.string.check_root_running)
        executor.execute {
            val result = SuProbe.runId()
            main.post {
                probing = false
                probe = result
                if (isAdded) refresh()
            }
        }
    }

    private fun verdictTitle(v: Verdict): CharSequence {
        val color = requireContext().getColor(
            when (v) {
                Verdict.COMPATIBLE -> R.color.verdict_ok
                Verdict.WARNING -> R.color.verdict_warn
                Verdict.NOT_SUPPORTED -> R.color.verdict_block
            },
        )
        return SpannableString(v.label).apply {
            setSpan(ForegroundColorSpan(color), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun verdictSummary(r: CompatReport): String {
        val warns = r.count(Level.WARN)
        val blocks = r.count(Level.BLOCK)
        return when (r.verdict) {
            Verdict.COMPATIBLE -> "Nothing found that stops OneOS on this phone. ${CompatEvaluator.TESTED_SCOPE}"
            Verdict.WARNING -> "Read the $warns warning${plural(warns)} below. ${getString(R.string.report_warning_next)}"
            Verdict.NOT_SUPPORTED -> "OneOS can't run on this phone: $blocks blocking finding${plural(blocks)} below."
        }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private fun levelIcon(level: Level): Drawable? {
        val ctx = requireContext()
        val (icon, color) = when (level) {
            Level.BLOCK -> OuiIcons.drawable.ic_oui_block to R.color.verdict_block
            Level.WARN -> OuiIcons.drawable.ic_oui_info to R.color.verdict_warn
            Level.OK -> OuiIcons.drawable.ic_oui_checkbox_checked to R.color.verdict_ok
            Level.INFO -> OuiIcons.drawable.ic_oui_info_outline to R.color.verdict_info
        }
        return ctx.getDrawable(icon)?.mutate()?.apply { setTint(ctx.getColor(color)) }
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

    private fun restoreProbe(b: Bundle): SuProbeResult? {
        val state = b.getString(KEY_STATE)?.let { runCatching { SuState.valueOf(it) }.getOrNull() } ?: return null
        return SuProbeResult(
            state = state,
            uid = if (b.containsKey(KEY_UID)) b.getInt(KEY_UID) else null,
            context = b.getString(KEY_CONTEXT),
            output = b.getString(KEY_OUTPUT).orEmpty(),
        )
    }

    private companion object {
        const val NOT_REPORTED = "Not reported"
        const val KEY_STATE = "probe_state"
        const val KEY_UID = "probe_uid"
        const val KEY_CONTEXT = "probe_context"
        const val KEY_OUTPUT = "probe_output"
    }
}
