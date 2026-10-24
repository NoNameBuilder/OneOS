package com.fahadali.oneos.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView
import com.fahadali.oneos.R
import com.fahadali.oneos.core.model.Images
import com.fahadali.oneos.core.model.Profiles
import com.fahadali.oneos.session.SessionPrefs

/**
 * The still OneOS screen for the black gap at the start of a switch: a port
 * of the old switcher's SplashActivity (switcher/src/.../SplashActivity.java).
 *
 * oneos_switch.sh starts it as root with `am start -W -n
 * com.fahadali.oneos/.ui.GapSplashActivity` (ONEOS_SPLASH_COMPONENT; it is
 * not exported) while One UI still runs, waits for `Status: ok` and its first
 * frame, and then freezes One UI's surfaceflinger (SIGSTOP) on this frame
 * instead of killing it, so the 30 s while guest_prepare.sh runs show the
 * OneOS wordmark rather than black (run 32). It must look final from its
 * first frame: black window from the theme, no animation, nothing that
 * changes later. A switch that stops before that point leaves One UI
 * running, so the screen closes itself after [GIVE_UP_MS].
 *
 * The look is Splash.dc.html: the OneOS mark, the "OneOS" wordmark and
 * "Starting {system}". Root starts it with no extras, so the system's name
 * comes from the launch the app committed just before root ran
 * ([SessionPrefs.saveLaunch]), read synchronously in [onCreate]: the text is
 * final before the first frame. With no recent launch (a switch started from
 * a shell, or a stale one) the line names no system ([Images.systemName]).
 */
// Not a launch screen: root starts it in the middle of a switch.
@SuppressLint("CustomSplashScreen")
class GapSplashActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A switch can find the screen off or on the keyguard.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        // Target SDK 36 draws edge to edge: the black reaches behind the bars and the cutout.

        // Everything is set here, before the first frame (surfaceflinger freezes on it).
        setContentView(R.layout.splash_gap)
        findViewById<TextView>(R.id.splash_wordmark).text = SpannableString(WORDMARK).apply {
            setSpan(ForegroundColorSpan(getColor(R.color.oneos_splash_text)), 0, 3, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(ForegroundColorSpan(getColor(R.color.oneos_blue_on_black)), 3, WORDMARK.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        findViewById<TextView>(R.id.splash_starting).text = startingLine()

        window.insetsController?.let { c ->
            c.hide(WindowInsets.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        main.postDelayed(::finish, GIVE_UP_MS)
    }

    /**
     * "Starting Pixel 11 Fold" ("Test run: checking …" for a test run), from
     * the last launch when it is this switch's: saved less than [FRESH_MS] ago
     * on this boot. A switch the app did not start (adb, run 48 said "Starting
     * the other system") takes the system Home last showed as set up; with
     * neither, "Starting the other system".
     */
    private fun startingLine(): String {
        val launch = runCatching { SessionPrefs.lastLaunch(this) }.getOrNull()?.takeIf { l ->
            SystemClock.elapsedRealtime() - l.elapsedMs in 0L..FRESH_MS && !l.rebootedSince()
        }
        val image = launch?.let { Images.byId(it.imageId) }
            ?: runCatching { SessionPrefs.homeSnapshot(this)?.image }.getOrNull()
        val system = Images.systemName(image, launch?.let { Profiles.byId(it.profileId) })
        val line = if (image != null && launch?.dryRun == true) R.string.splash_test_run else R.string.splash_starting
        return getString(line, system)
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        if (Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private companion object {
        // The IMS gate alone can wait 90 s before the freeze.
        const val GIVE_UP_MS = 180_000L

        // The launch is committed just before root runs, and root starts this
        // screen early in the switch (its gates included), well inside this.
        const val FRESH_MS = 10 * 60_000L

        // Our own name, not a system's: "One" + "OS" in two colours.
        const val WORDMARK = "OneOS"
    }
}
