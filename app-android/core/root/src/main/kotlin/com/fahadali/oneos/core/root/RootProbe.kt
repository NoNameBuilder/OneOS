package com.fahadali.oneos.core.root

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.fahadali.oneos.core.model.RootRules
import com.fahadali.oneos.core.model.RootSignals
import com.fahadali.oneos.core.model.SuProbeResult
import com.fahadali.oneos.core.model.SwitchGates
import java.io.File

/**
 * Root facts without root: which manager apps are installed and which `su`
 * binaries the app can see. Only stats files; never runs su.
 */
class RootProbe(context: Context) {
    private val app = context.applicationContext

    fun signals(probe: SuProbeResult? = null): RootSignals =
        RootSignals(managerPackages = managerPackages(), suPaths = suPaths(), probe = probe)

    fun managerPackages(): List<String> = RootRules.MANAGER_PACKAGES.keys.filter(::isInstalled)

    /**
     * The old OneOS switcher (SwitchGates.OLD_SWITCHER_PACKAGE, visible through
     * the `<queries>` entry); null when Android could not be asked.
     */
    fun oldSwitcherInstalled(): Boolean? = runCatching { isInstalled(SwitchGates.OLD_SWITCHER_PACKAGE) }.getOrNull()

    private fun isInstalled(pkg: String): Boolean = try {
        val pm = app.packageManager
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, 0)
        }
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** `su` files visible to the app on PATH and the usual root locations. */
    fun suPaths(): List<String> {
        val dirs = System.getenv("PATH").orEmpty().split(':') + EXTRA_DIRS
        return dirs.filter { it.isNotBlank() }
            .distinct()
            .map { File(it, "su") }
            .filter { f -> runCatching { f.exists() }.getOrDefault(false) }
            .map { it.path }
    }

    private companion object {
        val EXTRA_DIRS = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/debug_ramdisk")
    }
}
