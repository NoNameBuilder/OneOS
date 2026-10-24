package com.fahadali.oneos.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import com.fahadali.oneos.R
import com.fahadali.oneos.core.device.FactProbe
import com.fahadali.oneos.core.model.AndroidRelease
import com.fahadali.oneos.core.model.FixAction
import com.fahadali.oneos.core.model.ImageIdentifier
import com.fahadali.oneos.core.model.ImageIdentity
import com.fahadali.oneos.core.model.ImageRef
import com.fahadali.oneos.core.model.Images
import com.fahadali.oneos.core.model.Level
import com.fahadali.oneos.core.model.PhoneProfile
import com.fahadali.oneos.core.model.Plain
import com.fahadali.oneos.core.model.Profiles
import com.fahadali.oneos.core.model.Units
import com.fahadali.oneos.core.root.RootProbe
import com.fahadali.oneos.session.LaunchJob
import com.fahadali.oneos.session.LogJob
import com.fahadali.oneos.session.PrepareJob
import com.fahadali.oneos.session.SessionPrefs
import com.fahadali.oneos.session.StageCheck
import java.util.concurrent.Executors
import dev.oneuiproject.oneui.R as OuiIcons

/**
 * Choose a system (SystemPicker.dc.html; Home's "Choose a system", and the
 * fixes Choose a system, Choose another file and Set up again): one card per
 * system this phone takes (`profile.images()`), with its device glyph, name,
 * blurb, facts and state, and its buttons:
 * - set up already: "Use this one" (back to Home, which offers the switch);
 * - setting up now: "Show progress";
 * - its download picked: "Set up {system}", the same confirm as Home's
 *   (FixActions SET_UP; the job and root check everything again);
 * - else "Download" (opens [ImageRef.downloadUrl] in the browser; hidden
 *   without one) and "I have the file" (the system file picker, then the
 *   size identity check of the OS image page; the SHA-256 is checked by the
 *   setup's "Check the download", or by Check file on the file page).
 * After a good pick with nothing set up, Home's button becomes "Set up
 * {system}", so the screen goes back there. The OS image page stays the
 * technical file page (the last row here, and Advanced).
 */
class SystemsActivity : ToolbarActivity() {
    // The bar shows the title only once the hero has scrolled away (setBarTitle).
    override val screenTitle: CharSequence get() = ""

    override val layoutRes: Int get() = R.layout.activity_systems

    override fun createFragment(): Fragment = SystemsFragment()

    private lateinit var pick: ActivityResultLauncher<Unit>

    /** The system whose "I have the file" opened the picker (kept across recreation). */
    private var pickFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        pick = registerForActivityResult(SystemsNav.OpenGz()) { uri -> if (uri != null) picked(uri) }
        pickFor = savedInstanceState?.getString(KEY_PICK_FOR)
        super.onCreate(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pickFor?.let { outState.putString(KEY_PICK_FOR, it) }
    }

    fun setBarTitle(text: CharSequence) {
        toolbarLayout.setTitle(text)
    }

    /** "I have the file" of [imageId] (null: whichever system it is). */
    fun choose(imageId: String?) {
        pickFor = imageId
        try {
            pick.launch(Unit)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.fix_no_app, Toast.LENGTH_LONG).show()
        }
    }

    /** The same as the OS image page: a persistable read grant, the name and size, the source = this file. */
    private fun picked(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        var name: String? = null
        var size: Long? = null
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    name = c.getString(0)
                    size = if (c.isNull(1)) null else c.getLong(1)
                }
            }
        }
        SessionPrefs.setPicked(this, uri, name, size)
        SessionPrefs.setSource(this, SessionPrefs.Source.GZ)
        (supportFragmentManager.findFragmentById(R.id.fragment_container) as? SystemsFragment)?.afterPick(pickFor)
    }

    private companion object {
        const val KEY_PICK_FOR = "pick_for"
    }
}

/** Navigation shared by Choose a system and Setting up. */
internal object SystemsNav {
    /** Back to Home (the instance already open, else a new one), closing the screens above it. */
    fun home(a: Activity) {
        a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        a.finish()
    }

    fun progress(a: Activity) {
        a.startActivity(Intent(a, SetupProgressActivity::class.java))
    }

    /** "Download": the shared link in the browser (no in-app download). */
    fun open(a: Activity, url: String) {
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(a, R.string.fix_no_app, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(a, R.string.fix_no_app, Toast.LENGTH_LONG).show()
        }
    }

    /** ACTION_OPEN_DOCUMENT for the .img.gz (any type: providers label .gz differently), as the OS image page. */
    class OpenGz : ActivityResultContract<Unit, Uri?>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/gzip", "application/x-gzip", "application/octet-stream", "*/*"))

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == Activity.RESULT_OK) intent?.data else null
    }
}

class SystemsFragment : Fragment() {
    private val io = Executors.newSingleThreadExecutor()
    private val subs = mutableListOf<() -> Unit>()
    private var profile: PhoneProfile? = null
    private var profileRead = false
    private var lastKey: List<Any?>? = null
    private var barTitleShown = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.systems_screen, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val heroTitle = view.findViewById<TextView>(R.id.systems_hero_title)
        view.findViewById<NestedScrollView>(R.id.systems_scroll).setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { _, _, scrollY, _, _ ->
                val show = scrollY > heroTitle.bottom
                if (show != barTitleShown) {
                    barTitleShown = show
                    (activity as? SystemsActivity)?.setBarTitle(if (show) getString(R.string.systems_title) else "")
                }
            },
        )

        // This phone: from the last check when there is one, else read now (no root), as the OS image page does.
        StageCheck.state.result?.facts?.let { f -> Profiles.resolve(f).profile }?.let {
            profile = it
            profileRead = true
        }
        if (!profileRead) {
            val app = requireContext().applicationContext
            io.execute {
                val f = runCatching { FactProbe(app).collect(RootProbe(app).signals()) }.getOrNull()
                val p = f?.let { Profiles.resolve(it).profile }
                activity?.runOnUiThread {
                    profile = p
                    profileRead = true
                    redraw(force = true)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        lastKey = null
        subs += StageCheck.observe { redraw() }
        subs += PrepareJob.observe { redraw() }
    }

    override fun onStop() {
        subs.forEach { it() }
        subs.clear()
        super.onStop()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    /** After the file picker: a good pick goes back to Home (nothing set up) or shows "Set up {system}" here; a bad one says why. */
    fun afterPick(cardId: String?) {
        val ctx = context ?: return
        val a = activity as? SystemsActivity ?: return
        val p = profile
        val file = SessionPrefs.picked(ctx)
        if (p == null || file == null) {
            redraw(force = true)
            return
        }
        val good = FixActions.pickedImage(ctx, p)
        if (good == null) {
            redraw(force = true)
            badPick(a, p, ImageIdentifier.identify(file.check, p), cardId)
            return
        }
        if (cardId != null && good.id != cardId) {
            Toast.makeText(ctx, getString(R.string.systems_pick_other, Images.systemName(good, p)), Toast.LENGTH_LONG).show()
        }
        // Home offers "Set up {system}" only while nothing is set up; else that button is on this card.
        val st = StageCheck.state.result?.status
        if (st?.ours == true || PrepareJob.state.busy) redraw(force = true) else SystemsNav.home(a)
    }

    private fun badPick(a: SystemsActivity, p: PhoneProfile, id: ImageIdentity, cardId: String?) {
        val card = cardId?.let { cid -> p.images().firstOrNull { it.id == cid } }
        val b = AlertDialog.Builder(a)
        if (id.finding.reason == ImageIdentifier.R_SIZE_UNKNOWN) {
            // Only reading the whole file tells what it is: Check file on the file page.
            b.setTitle(R.string.systems_size_unknown_title)
                .setMessage(R.string.systems_size_unknown_line)
                .setPositiveButton(R.string.systems_open_file_page) { _, _ -> startActivity(Intent(a, ImageActivity::class.java)) }
        } else {
            val plain = Plain.of(id.finding, id.image ?: card, p)
            b.setTitle(plain.title).setMessage(plain.line)
            val label = plain.actionLabel
            if (label != null) {
                when (plain.action) {
                    FixAction.CHOOSE_FILE -> b.setPositiveButton(label) { _, _ -> a.choose(cardId) }
                    FixAction.DOWNLOAD_AGAIN -> (id.image ?: card)?.downloadUrl?.let { url ->
                        b.setPositiveButton(label) { _, _ -> SystemsNav.open(a, url) }
                    }
                    else -> Unit
                }
            }
        }
        b.setNegativeButton(R.string.systems_close, null).show()
    }

    private fun redraw(force: Boolean = false) {
        val root = view ?: return
        val ctx = context ?: return
        val p = profile
        val check = StageCheck.state
        val prep = PrepareJob.state
        val picked = SessionPrefs.picked(ctx)
        val jobsBusy = prep.busy || check.busy || LaunchJob.state.busy || LogJob.state.busy
        // PrepareJob reports every few MB: redraw only when something on the cards changes.
        val key = listOf(
            p?.id, profileRead, check.busy, check.result?.at, prep.phase, prep.image?.id,
            picked?.uri, picked?.sha256, jobsBusy, resources.configuration.screenWidthDp,
        )
        if (!force && key == lastKey) return
        lastKey = key

        root.findViewById<TextView>(R.id.systems_hero_subtitle).text =
            if (p != null) getString(R.string.systems_subtitle, p.label) else getString(R.string.systems_subtitle_unknown)
        val column = root.findViewById<LinearLayout>(R.id.systems_column)
        column.removeAllViews()

        val images = p?.images().orEmpty()
        if (p == null || images.isEmpty()) {
            if (!profileRead) {
                note(column, getString(R.string.systems_reading), null, Level.INFO)
            } else {
                note(column, getString(R.string.systems_none_title), getString(R.string.systems_none_line), Level.BLOCK)
            }
            return
        }

        val st = check.result?.status
        val setUpId = st?.marker?.takeIf { st.ours && it.profileId == p.id }?.imageId
        val pickedId = FixActions.pickedImage(ctx, p)?.id
        val settingUpId = prep.image?.id?.takeIf { prep.busy }

        // One column on the cover screen, two side by side on the inner screen.
        val columns = if (resources.configuration.screenWidthDp >= 600) 2 else 1
        val gap = resources.getDimension(R.dimen.oneos_card_gap).toInt()
        var line: LinearLayout? = null
        for ((i, image) in images.withIndex()) {
            if (i % columns == 0) {
                val l = LinearLayout(ctx)
                l.orientation = LinearLayout.HORIZONTAL
                l.isBaselineAligned = false
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                if (i > 0) lp.topMargin = gap
                column.addView(l, lp)
                line = l
            }
            val row = line ?: continue
            val card = card(
                row, p, image, i,
                setUp = image.id == setUpId,
                picked = image.id == pickedId,
                settingUp = image.id == settingUpId,
                prepBusy = prep.busy,
                jobsBusy = jobsBusy,
            )
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            if (i % columns > 0) lp.marginStart = gap
            row.addView(card, lp)
        }
        // A short last line keeps its cards at one column's width.
        val rest = images.size % columns
        if (rest != 0) {
            line?.let { l ->
                repeat(columns - rest) {
                    val lp = LinearLayout.LayoutParams(0, 1, 1f)
                    lp.marginStart = gap
                    l.addView(View(ctx), lp)
                }
            }
        }

        // The technical file page: Check file (SHA-256), or the system already on this phone.
        val more = Cards.section(column, title = null, rows = true)
        Cards.row(more, OuiIcons.drawable.ic_oui_folder_open, getString(R.string.systems_more_title), getString(R.string.systems_more_summary)) {
            startActivity(Intent(ctx, ImageActivity::class.java))
        }
    }

    private fun card(
        parent: ViewGroup,
        p: PhoneProfile,
        image: ImageRef,
        index: Int,
        setUp: Boolean,
        picked: Boolean,
        settingUp: Boolean,
        prepBusy: Boolean,
        jobsBusy: Boolean,
    ): View {
        val ctx = parent.context
        val inflater = LayoutInflater.from(ctx)
        val v = inflater.inflate(R.layout.systems_card, parent, false)
        if (setUp) v.setBackgroundResource(R.drawable.oneos_systems_card_selected_bg)

        v.findViewById<ImageView>(R.id.systems_glyph).setImageResource(GLYPHS[index % GLYPHS.size])
        v.findViewById<ChipFlowLayout>(R.id.systems_name_row).gap = dp(8)
        v.findViewById<TextView>(R.id.systems_name).text = image.name
        v.findViewById<TextView>(R.id.systems_variant).apply {
            text = image.variant
            visibility = if (image.variant.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        v.findViewById<TextView>(R.id.systems_blurb).text = image.blurb

        val badge = v.findViewById<TextView>(R.id.systems_badge)
        fun softBadge(@StringRes text: Int) {
            badge.setText(text)
            badge.setBackgroundResource(R.drawable.oneos_systems_badge_soft_bg)
            badge.setTextColor(ctx.getColor(R.color.oneos_primary_text))
            badge.visibility = View.VISIBLE
        }
        when {
            settingUp -> softBadge(R.string.systems_badge_setting_up)
            setUp -> {
                badge.setText(R.string.systems_badge_set_up)
                badge.visibility = View.VISIBLE
            }
            picked -> softBadge(R.string.systems_badge_picked)
            else -> badge.visibility = View.GONE
        }

        val chips = v.findViewById<ChipFlowLayout>(R.id.systems_chips)
        val facts = buildList {
            add(getString(R.string.systems_chip_android, AndroidRelease.name(image.sdk)))
            add(getString(if (p.choice(image.id)?.tested == true) R.string.systems_chip_tested else R.string.systems_chip_untested))
            if (image.googleServices) add(getString(R.string.systems_chip_google))
            add(getString(R.string.systems_chip_download, Units.bytes(image.gzBytes)))
        }
        for (fact in facts) {
            val chip = inflater.inflate(R.layout.systems_chip, chips, false) as TextView
            chip.text = fact
            chips.addView(chip)
        }

        val download = v.findViewById<Button>(R.id.systems_download)
        val haveFile = v.findViewById<Button>(R.id.systems_have_file)
        val primary = v.findViewById<Button>(R.id.systems_primary)
        val a = activity as? SystemsActivity
        fun only(text: CharSequence, enabled: Boolean, onClick: () -> Unit) {
            download.visibility = View.GONE
            haveFile.visibility = View.GONE
            primary.visibility = View.VISIBLE
            primary.text = text
            primary.isEnabled = enabled
            primary.setOnClickListener { onClick() }
        }
        when {
            settingUp -> only(getString(R.string.systems_show_progress), true) { a?.let { SystemsNav.progress(it) } }
            setUp -> only(getString(R.string.systems_use), true) { a?.let { SystemsNav.home(it) } }
            // The same action and confirm as Home's "Set up {system}"; off while a job or the check runs (as on Home).
            picked -> only(getString(R.string.systems_set_up, Images.systemName(image, p)), !jobsBusy) {
                a?.let { FixActions.run(it, FixAction.SET_UP, image) }
            }
            else -> {
                primary.visibility = View.GONE
                val url = image.downloadUrl
                download.visibility = if (url != null) View.VISIBLE else View.GONE
                download.setOnClickListener { if (url != null && a != null) SystemsNav.open(a, url) }
                haveFile.visibility = View.VISIBLE
                // A setup reads the picked file: no new pick while one runs.
                haveFile.isEnabled = !prepBusy
                (haveFile.layoutParams as? LinearLayout.LayoutParams)?.marginStart = if (url != null) dp(8) else 0
                haveFile.setOnClickListener { a?.choose(image.id) }
            }
        }
        return v
    }

    /** A card with a header and an optional line (reading this phone, or no system for it). */
    private fun note(column: LinearLayout, title: String, line: String?, level: Level) {
        val card = Cards.section(column, title, null, level)
        if (line != null) {
            val t = TextView(column.context)
            t.setTextAppearance(R.style.OneOS_Text_Body)
            t.text = line
            card.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** Device glyphs by the system's place in the phone's list (never by brand). */
        val GLYPHS = intArrayOf(R.drawable.ic_oneos_system_0, R.drawable.ic_oneos_system_1, R.drawable.ic_oneos_system_2)
    }
}
