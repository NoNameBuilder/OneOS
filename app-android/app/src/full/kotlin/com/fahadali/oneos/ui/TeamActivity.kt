package com.fahadali.oneos.ui

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import com.fahadali.oneos.R
import dev.oneuiproject.oneui.R as OuiIcons

/**
 * For the OneOS team (Advanced's last entry): the technical screens, each
 * row named like the screen it opens. All checks and Test run (every gate in
 * detail, the test run and the switch), Phone details, Run tests, and the
 * setup and file pages with their technical rows open (Setup details is the
 * old Prepare page: every check, the stage folder and the nine steps).
 */
class TeamActivity : ToolbarActivity() {
    override val screenTitle: CharSequence get() = getString(R.string.team_title)

    override fun createFragment(): Fragment = TeamFragment()
}

class TeamFragment : CardsFragment() {
    override fun build(column: LinearLayout) {
        val ctx = column.context
        fun open(intent: Intent): (View) -> Unit = { startActivity(intent) }

        val intro = Cards.section(column, title = null)
        CardRows.text(intro, getString(R.string.team_note), R.style.OneOS_Text_Caption)

        val checks = Cards.section(column, getString(R.string.team_card_checks), rows = true)
        Cards.row(checks, OuiIcons.drawable.ic_oui_labs_outline, getString(R.string.switch_title), getString(R.string.team_switch_summary), open(Intent(ctx, SwitchActivity::class.java)))
        Cards.row(checks, OuiIcons.drawable.ic_oui_phone_outline, getString(R.string.report_title), getString(R.string.team_phone_summary), open(Intent(ctx, DeviceReportActivity::class.java)))
        Cards.row(checks, OuiIcons.drawable.ic_oui_report, getString(R.string.tests_title), getString(R.string.team_tests_summary), open(Intent(ctx, RunTestsActivity::class.java)))

        val setup = Cards.section(column, getString(R.string.team_card_setup), rows = true)
        Cards.row(
            setup, OuiIcons.drawable.ic_oui_download, getString(R.string.prepare_title_team), getString(R.string.team_setup_summary),
            open(Intent(ctx, PrepareActivity::class.java).putExtra(PrepareActivity.EXTRA_TEAM, true)),
        )
        Cards.row(
            setup, OuiIcons.drawable.ic_oui_file_type_zip, getString(R.string.image_title_team), getString(R.string.team_file_summary),
            open(Intent(ctx, ImageActivity::class.java).putExtra(ImageActivity.EXTRA_TEAM, true)),
        )
    }
}
