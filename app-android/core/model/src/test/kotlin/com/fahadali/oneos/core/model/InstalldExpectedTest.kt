package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InstalldExpectedTest {
    private val fixture = File(System.getProperty("oneos.installdFixture") ?: "../../../switcher/patches/host_installd.txt")

    @Test
    fun pinnedHashIsTheShippedTable() {
        val lines = InstalldExpected.nonCommentLines(fixture.readText())
        assertEquals(InstalldExpected.SHA256, Hashes.sha256(lines))
        assertEquals(InstalldExpected.METHODS, lines.lines().count { it.isNotEmpty() })
        // The derived layout reproduces the file's lines byte for byte.
        assertEquals(lines, InstalldExpected.methodLines(InstalldTableFormat.parseLegacy(fixture.readText())))
    }

    @Test
    fun everyBundledPhoneHasTheShippedTable() {
        val expected = fixture.readText()
        for (phone in Bundles.ALL) {
            val block = InstalldExpected.methodsBlock(phone.table, phone.facts.fingerprint)
            assertEquals(phone.name, InstalldExpected.nonCommentLines(expected), block)
            assertEquals(phone.name, emptyList<String>(), InstalldExpected.problems(phone.installd, expected))
            assertEquals(phone.name, emptyList<String>(), InstalldExpected.problems(phone.installd))
        }
        // The Galaxy S25 (0.3.4) too: 85 methods, the same codes.
        assertEquals(InstalldExpected.METHODS, Bundles.S25.installd.methods.size)
        // Each table is for its own phone.
        assertNull(InstalldExpected.methodsBlock(Bundles.S26U.table, Bundles.F8U.facts.fingerprint))
        assertNull(InstalldExpected.methodsBlock(Bundles.S25.table, Bundles.S25U.facts.fingerprint))
    }

    @Test
    fun oneChangedCodeBlocks() {
        val phone = Bundles.S26U
        val m = phone.installd.methods
        val changed = phone.installd.copy(methods = m.map { if (it.name == "dexopt") it.copy(code = 99) else it })
        val p = InstalldExpected.problems(changed, fixture.readText())
        assertTrue(p.contains("this phone's installd table differs from the one the OneOS files carry"))
        assertTrue(p.any { it.startsWith("phone has: dexopt 99 ") })
        assertTrue(p.any { it.startsWith("files expect: dexopt 20 ") })

        // A renamed signature, a missing method, an extra method.
        val sig = phone.installd.copy(methods = m.map { if (it.code == 1) it.copy(signature = "(Ljava/lang/String;II)V") else it })
        assertTrue(InstalldExpected.problems(sig).isNotEmpty())
        assertTrue(InstalldExpected.problems(phone.installd.copy(methods = m.drop(1))).isNotEmpty())
        assertTrue(InstalldExpected.problems(phone.installd.copy(methods = m + InstalldMethod("newCall", 86, "()V"))).isNotEmpty())
    }

    @Test
    fun failsClosed() {
        assertEquals(listOf("installd table not derived on this phone"), InstalldExpected.problems(null))
        assertTrue(InstalldExpected.problems(InstalldDerivation.failed("IInstalld\$Stub not found")).single().contains("not derived"))
        // A tampered host_installd.txt in the app is refused even when the phone's table equals it.
        val tampered = fixture.readText().replace("dexopt 20 ", "dexopt 21 ")
        val phone = Bundles.F8U.installd
        val t = phone.copy(methods = phone.methods.map { if (it.name == "dexopt") it.copy(code = 21) else it })
        assertTrue(InstalldExpected.problems(t, tampered).contains("the app's host_installd.txt is not the pinned table"))
        // A table with a broken trailer has no method block.
        assertNull(InstalldExpected.methodsBlock(Bundles.F8U.table.replace("dexopt 20", "dexopt 21")))
    }
}
