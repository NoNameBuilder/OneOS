package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InstalldTableTest {
    private val fixture = File(System.getProperty("oneos.installdFixture") ?: "../../../switcher/patches/host_installd.txt")
    private val fp = "samsung/q8qxxx/q8q:17/CP2A.260605.016/F976BXXS3AZIJ_OXM3AZIJ:user/release-keys"
    private val sha = "a".repeat(64)

    @Test
    fun f8uFixtureWritesAValidFormat1Table() {
        val methods = InstalldTableFormat.parseLegacy(fixture.readText())
        assertEquals(85, methods.size)
        assertEquals(InstalldMethod("createUserData", 1, "(Ljava/lang/String;III)V"), methods.first())
        val table = InstalldTableFormat.write(fp, "/system/framework/services.jar", sha, methods)
        assertEquals(emptyList<String>(), InstalldTableFormat.validate(table, fp))
        val lines = table.removeSuffix("\n").split('\n')
        assertEquals(InstalldTableFormat.MAGIC, lines[0])
        assertEquals("fingerprint $fp", lines[1])
        assertEquals("source /system/framework/services.jar $sha", lines[2])
        assertEquals("methods 85", lines[3])
        assertEquals("getHighDataList 85 (Ljava/lang/String;II)Ljava/util/List;", lines[88])
        assertTrue(lines.last().matches(Regex("sha256 [0-9a-f]{64}")))
        // Same input, same bytes (no timestamps).
        assertEquals(table, InstalldTableFormat.write(fp, "/system/framework/services.jar", sha, methods.shuffled()))
        // The legacy layout round-trips.
        assertEquals(methods, InstalldTableFormat.parseLegacy(InstalldTableFormat.legacy(methods, fp)))
    }

    @Test
    fun validationFailsClosed() {
        val methods = InstalldTableFormat.parseLegacy(fixture.readText())
        val table = InstalldTableFormat.write(fp, "/system/framework/services.jar", sha, methods)
        assertTrue(InstalldTableFormat.validate(table, "samsung/other:17/X/Y:user/release-keys").isNotEmpty())
        assertTrue(InstalldTableFormat.validate(table.replace("dexopt 20", "dexopt 21")).isNotEmpty())
        assertTrue(InstalldTableFormat.validate(table + "\n").isNotEmpty())
        assertTrue(InstalldTableFormat.validate(table.replace(InstalldTableFormat.MAGIC, "oneos.installd-table 2")).isNotEmpty())
        assertTrue(InstalldTableFormat.validate(table.lines().dropLast(2).joinToString("\n") + "\n").isNotEmpty())
    }

    @Test
    fun writerRefusesBadMethods() {
        fun refused(m: List<InstalldMethod>) = runCatching { InstalldTableFormat.write(fp, "/s.jar", sha, m) }.isFailure
        assertTrue(refused(emptyList()))
        assertTrue(refused(listOf(InstalldMethod("a", 1, "()V"), InstalldMethod("a", 2, "()V"))))
        assertTrue(refused(listOf(InstalldMethod("a", 1, "()V"), InstalldMethod("b", 1, "()V"))))
        assertTrue(refused(listOf(InstalldMethod("a", 0, "()V"))))
        assertTrue(refused(listOf(InstalldMethod("a b", 1, "()V"))))
        assertTrue(refused(listOf(InstalldMethod("a", 1, "V"))))
        assertTrue(runCatching { InstalldTableFormat.write("has space", "/s.jar", sha, listOf(InstalldMethod("a", 1, "()V"))) }.isFailure)
        assertTrue(runCatching { InstalldTableFormat.write(fp, "/s.jar", "xyz", listOf(InstalldMethod("a", 1, "()V"))) }.isFailure)
    }

    @Test
    fun jarsListingAndClassPath() {
        val jars = JarsListing.parse(
            """
            28190726 ${"b".repeat(64)}  /system/framework/services.jar
            41000000 ${"c".repeat(64)}  /system/framework/framework.jar
            28190726 ${"b".repeat(64)}  /system/framework/services.jar
            /system/framework:
            -rw-r--r-- 1 root root 28190726 2026-10-02 10:22 services.jar
            """.trimIndent(),
        )
        assertEquals(listOf("/system/framework/services.jar", "/system/framework/framework.jar"), jars.map { it.path })
        assertEquals(28190726L, jars[0].bytes)

        val order = ClassPath.order(
            "BOOTCLASSPATH=/apex/com.android.art/javalib/core-oj.jar:/system/framework/framework.jar\n" +
                "SYSTEMSERVERCLASSPATH=/system/framework/services.jar:/system/framework/ethernet-service.jar\n" +
                "DEX2OATBOOTCLASSPATH=/ignored.jar\n",
        )
        assertEquals(
            listOf(
                "/apex/com.android.art/javalib/core-oj.jar", "/system/framework/framework.jar",
                "/system/framework/services.jar", "/system/framework/ethernet-service.jar",
            ),
            order,
        )
        assertEquals(listOf("/system/framework/services.jar"), ClassPath.order(""))
    }
}
