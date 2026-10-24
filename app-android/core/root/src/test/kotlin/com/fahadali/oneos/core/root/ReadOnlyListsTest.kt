package com.fahadali.oneos.core.root

import com.fahadali.oneos.core.model.ProbeAudit
import com.fahadali.oneos.core.model.ProbeCommandList
import com.fahadali.oneos.core.model.ProbeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The read-only lists build 2 adds (stage check, installd, log collection):
 * the probe grammar and ProbeAudit's read-only allow-list, like the probe
 * list itself; the app refuses to run a list that fails it.
 */
class ReadOnlyListsTest {
    /** probe/ is in both editions (src/main), root/ and logs/ in OneOS only (src/full). */
    private fun list(path: String) = File(if (path.startsWith("probe/")) Shipped.appAssets else Shipped.fullAssets, path).readText()

    private fun audited(path: String) = ProbeCommandList.parse(list(path)).also { cmds ->
        val p = ProbeAudit.audit(cmds)
        assertTrue("$path audit problems:\n${p.joinToString("\n")}", p.isEmpty())
    }

    @Test
    fun statusListIsReadOnlyAndHasEveryOutputTheParserReads() {
        val cmds = audited("root/status.list")
        assertEquals(StageStatus.OUTPUTS, cmds.associate { it.id to it.target })
        assertTrue(cmds.all { it.kind == ProbeKind.RUN })
    }

    @Test
    fun installdListIsTheProbesInstalldEntries() {
        val cmds = audited("root/installd.list")
        val probe = ProbeCommandList.parse(list("probe/commands.list")).filter { it.group == "installd interface" }
        assertEquals(probe.map { listOf(it.id, it.kind, it.timeoutSec, it.target, it.command) }, cmds.map { listOf(it.id, it.kind, it.timeoutSec, it.target, it.command) })
    }

    @Test
    fun collectListIsReadOnlyAndNeverTakesRecordingsOrScreenshots() {
        val cmds = audited("logs/collect.list")
        val ids = cmds.map { it.id }
        // The names to remove come first, before any log is read.
        assertEquals(listOf("logs.ssids", "logs.input"), ids.take(2))
        assertTrue(cmds.take(2).all { it.target.startsWith("memory/") })
        for (id in listOf("logs.reason", "logs.current", "logs.stage", "logs.guest", "logs.host", "logs.diag", "logs.capture")) assertTrue(id, id in ids)
        val code = cmds.joinToString("\n") { it.command }
        for (bad in listOf(".mp4", "oneos_rec", ".png", "shot_", "screencap", "/sdcard", "/storage", "PreSharedKey", "psk")) {
            assertFalse("collect.list mentions $bad", code.contains(bad, ignoreCase = true))
        }
        // The Wi-Fi entry prints only the name elements.
        val ssid = cmds.first { it.id == "logs.ssids" }.command
        assertTrue(ssid.contains("grep -h -o -i -E \"name=\\\"[a-z]*ssid\\\".[^<]*\""))
    }

    @Test
    fun runnerIsTheProbeRunner() {
        assertTrue(ProbeAudit.auditRunner(list("probe/probe.sh")).isEmpty())
    }
}
