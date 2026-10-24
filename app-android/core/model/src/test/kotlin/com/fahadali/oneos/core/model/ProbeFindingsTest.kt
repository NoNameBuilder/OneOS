package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeFindingsTest {
    private fun data(
        selinux: SelinuxTypes? = SelinuxTypes.parse(ProbeFindings.GENERIC_APP_TYPES.joinToString("\n") { "(type $it" }),
        stage: Boolean? = false,
        installd: InstalldDerivation? = InstalldDerivation(
            listOf(InstalldMethod("destroyUserData", 2, "(Ljava/lang/String;II)V"), InstalldMethod("createAppData", 4, "(Landroid/os/CreateAppDataArgs;)Landroid/os/CreateAppDataResult;")),
            "/system/framework/services.jar", "classes.dex", "a".repeat(64), emptyList(), null,
        ),
        results: List<ProbeResult> = emptyList(),
    ) = ProbeData(results, selinux, stage, "kernelsu ksud 1.0.5", installd)

    private fun List<Finding>.level(id: String) = firstOrNull { it.id == id }?.level

    @Test
    fun selinuxTypesParsedLikeMasterPrepare() {
        val t = SelinuxTypes.parse("(type untrusted_app)\n  (type priv_app)\n(typealias app_data_file)\n(typeattribute x)\n(allow a b)\n")
        assertEquals(2, t.declarations)
        assertEquals(1, t.aliases)
        assertEquals(setOf("untrusted_app", "priv_app", "app_data_file"), t.names)
        assertEquals(listOf("platform_app", "system_app", "privapp_data_file", "system_app_data_file"), t.missingGeneric)
        assertEquals(Level.WARN, ProbeFindings.evaluate(data(selinux = t)).level("selinux-types-missing"))
        assertEquals(Level.OK, ProbeFindings.evaluate(data()).level("selinux-types"))
        assertEquals(Level.INFO, ProbeFindings.evaluate(data(selinux = null)).level("selinux-types-unread"))
    }

    @Test
    fun installdOutcomes() {
        assertEquals(Level.OK, ProbeFindings.evaluate(data()).level("installd-table"))
        assertEquals(Level.WARN, ProbeFindings.evaluate(data(installd = null)).level("installd-unknown"))
        assertEquals(Level.WARN, ProbeFindings.evaluate(data(installd = InstalldDerivation.failed("no dex"))).level("installd-unknown"))
        val odd = InstalldDerivation(listOf(InstalldMethod("foo", 1, "()V")), "/s.jar", "classes.dex", null, emptyList(), null)
        assertEquals(Level.WARN, ProbeFindings.evaluate(data(installd = odd)).level("installd-unexpected"))
    }

    @Test
    fun stageRootManagerAndFailures() {
        val f = ProbeFindings.evaluate(
            data(
                stage = true,
                results = listOf(
                    ProbeResult("a.b", ProbeStatus.OK, 0, emptyList(), emptyList(), 1),
                    ProbeResult("c.d", ProbeStatus.FAILED, 1, emptyList(), emptyList(), 1),
                ),
            ),
        )
        assertEquals(Level.INFO, f.level("stage-present"))
        assertEquals("Root manager: kernelsu ksud 1.0.5", f.first { it.id == "root-manager-version" }.title)
        assertEquals("1 of 2 tests failed or timed out", f.first { it.id == "probes-incomplete" }.title)
        assertNull(ProbeFindings.evaluate(data()).level("stage-present"))
        // Probe findings never block: nothing changed on the phone, the team verifies.
        assertTrue(ProbeFindings.evaluate(data(installd = null, selinux = null, stage = true)).none { it.level == Level.BLOCK })
    }
}
