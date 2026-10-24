package com.fahadali.oneos.core.safety

import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.value.ImmutableIntEncodedValue
import com.android.tools.smali.dexlib2.writer.io.MemoryDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.fahadali.oneos.core.model.CapturedFile
import com.fahadali.oneos.core.model.InstalldMethod
import com.fahadali.oneos.core.model.InstalldTableFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class HostInstalldTableTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val fixture = File(System.getProperty("oneos.installdFixture") ?: "../../../switcher/patches/host_installd.txt")

    private val sample = listOf(
        InstalldMethod("createUserData", 1, "(Ljava/lang/String;III)V"),
        InstalldMethod("destroyUserData", 2, "(Ljava/lang/String;II)V"),
        InstalldMethod("createAppData", 4, "(Landroid/os/CreateAppDataArgs;)Landroid/os/CreateAppDataResult;"),
        InstalldMethod("getAppSize", 13, "(Ljava/lang/String;[Ljava/lang/String;IIII[J[Ljava/lang/String;)[J"),
    )

    /** A dex with IInstalld$Stub (codes) and $Proxy (methods), like AIDL generates. */
    private fun installdDex(
        methods: List<InstalldMethod>,
        extraProxy: List<InstalldMethod> = emptyList(),
        withProxy: Boolean = true,
        otherClass: Boolean = true,
    ): ByteArray {
        val stubFields = methods.map {
            ImmutableField(
                HostInstalldTable.STUB, "TRANSACTION_${it.name}", "I",
                AccessFlags.STATIC.value or AccessFlags.FINAL.value, ImmutableIntEncodedValue(it.code), null, null,
            )
        }
        val classes = mutableListOf<ClassDef>(
            ImmutableClassDef(HostInstalldTable.STUB, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, "Landroid/os/Binder;", null, null, null, stubFields, null),
        )
        if (withProxy) {
            val proxyMethods = (methods + extraProxy).map { m ->
                val params = Regex("""\[*(L[^;]+;|[ZBSCIJFD])""").findAll(m.signature.substringAfter('(').substringBefore(')'))
                    .map { ImmutableMethodParameter(it.value, null, null) }.toList()
                ImmutableMethod(
                    HostInstalldTable.PROXY, m.name, params, m.signature.substringAfter(')'),
                    AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
                )
            } + ImmutableMethod(HostInstalldTable.PROXY, "asBinder", emptyList(), "Landroid/os/IBinder;", AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)
            classes += ImmutableClassDef(HostInstalldTable.PROXY, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, "Ljava/lang/Object;", null, null, null, null, proxyMethods)
        }
        if (otherClass) {
            classes += ImmutableClassDef("Lcom/android/server/pm/Installer;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, null)
        }
        val store = MemoryDataStore()
        DexPool.writeTo(store, ImmutableDexFile(Opcodes.getDefault(), classes))
        return store.data
    }

    private fun emptyDex(): ByteArray {
        val store = MemoryDataStore()
        val c = ImmutableClassDef("Lcom/example/Other;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, null)
        DexPool.writeTo(store, ImmutableDexFile(Opcodes.getDefault(), listOf(c)))
        return store.data
    }

    private fun file(name: String, bytes: ByteArray) = tmp.newFile(name).also { it.writeBytes(bytes) }

    private fun jar(name: String, vararg entries: Pair<String, ByteArray>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            for ((n, b) in entries) {
                z.putNextEntry(ZipEntry(n))
                z.write(b)
                z.closeEntry()
            }
        }
        return f
    }

    @Test
    fun readsCodesAndSignatures() {
        val t = HostInstalldTable.read(installdDex(sample))!!
        assertEquals(emptyList<String>(), t.problems)
        assertEquals(sample, t.methods)
        assertNull(HostInstalldTable.read(emptyDex()))
    }

    @Test
    fun derivesFromAnExtractedDexAndWritesAValidTable() {
        val dex = file("classes2.dex", installdDex(sample))
        val sha = "e".repeat(64)
        val d = HostInstalldTable.derive(
            listOf(HostInstalldTable.Input("/system/framework/services.jar", "classes2.dex", dex, "installd/x/classes2.dex")),
            mapOf("/system/framework/services.jar" to sha),
        )
        assertNull(d.error)
        assertEquals("/system/framework/services.jar", d.sourceJar)
        assertEquals("classes2.dex", d.sourceEntry)
        assertEquals(sha, d.sourceSha256)
        assertEquals(sample, d.methods)
        val fp = "samsung/q8qxxx/q8q:17/CP2A.260605.016/F976BXXS3AZIJ:user/release-keys"
        val table = InstalldTableFormat.write(fp, d.sourceJar!!, d.sourceSha256!!, d.methods)
        assertEquals(emptyList<String>(), InstalldTableFormat.validate(table, fp))
    }

    @Test
    fun derivesFromAWholeJarInEntryOrder() {
        val j = jar("services.jar", "classes.dex" to emptyDex(), "classes2.dex" to installdDex(sample), "res/x.txt" to byteArrayOf(1))
        val d = HostInstalldTable.derive(listOf(HostInstalldTable.Input("/system/framework/services.jar", null, j, "installd/services.jar")), emptyMap())
        assertNull(d.error)
        assertEquals("classes2.dex", d.sourceEntry)
        assertNull(d.sourceSha256)
        assertEquals(4, d.methods.size)
    }

    @Test
    fun firstInClassLoaderOrderWinsAndDifferentLaterCopiesAreAmbiguous() {
        val boot = file("boot.dex", installdDex(sample.take(2)))
        val services = file("services.dex", installdDex(sample))
        val captured = listOf(
            CapturedFile("installd/services/classes.dex", "/system/framework/services.jar!classes.dex", 1, "x", 0),
            CapturedFile("installd/boot/classes.dex", "/system/framework/framework.jar!classes.dex", 1, "x", 0),
        )
        val dir = tmp.newFolder("cap")
        File(dir, "installd/services").mkdirs()
        File(dir, "installd/boot").mkdirs()
        services.copyTo(File(dir, "installd/services/classes.dex"))
        boot.copyTo(File(dir, "installd/boot/classes.dex"))
        val inputs = HostInstalldTable.inputsFrom(dir, captured, listOf("/system/framework/framework.jar", "/system/framework/services.jar"))
        assertEquals(listOf("/system/framework/framework.jar", "/system/framework/services.jar"), inputs.map { it.jarPath })
        val d = HostInstalldTable.derive(inputs, emptyMap())
        assertTrue(d.error!!.startsWith("ambiguous"))

        // The same table twice is fine (noted).
        val same = HostInstalldTable.derive(
            listOf(
                HostInstalldTable.Input("/a.jar", "classes.dex", services, "a"),
                HostInstalldTable.Input("/b.jar", "classes.dex", file("same.dex", installdDex(sample)), "b"),
            ),
            emptyMap(),
        )
        assertNull(same.error)
        assertEquals("/a.jar", same.sourceJar)
        assertEquals(1, same.notes.size)
    }

    @Test
    fun refusesOverloadsMissingProxyAndNoClass() {
        val overloaded = installdDex(sample, extraProxy = listOf(InstalldMethod("destroyUserData", 2, "(Ljava/lang/String;I)V")))
        val overload = HostInstalldTable.derive(
            listOf(HostInstalldTable.Input("/s.jar", "classes.dex", file("o.dex", overloaded), "o")),
            emptyMap(),
        )
        assertTrue(overload.error!!.contains("destroyUserData appears 2 times"))

        val noProxy = HostInstalldTable.derive(
            listOf(HostInstalldTable.Input("/s.jar", "classes.dex", file("p.dex", installdDex(sample, withProxy = false)), "p")),
            emptyMap(),
        )
        assertTrue(noProxy.error!!.contains("Proxy not found"))

        val none = HostInstalldTable.derive(listOf(HostInstalldTable.Input("/s.jar", "classes.dex", file("n.dex", emptyDex()), "n")), emptyMap())
        assertTrue(none.error!!.contains("none of the captured"))
        assertTrue(HostInstalldTable.derive(emptyList(), emptyMap()).error!!.contains("no dex"))

        val garbage = HostInstalldTable.derive(listOf(HostInstalldTable.Input("/s.jar", "classes.dex", file("g.dex", ByteArray(100)), "g")), emptyMap())
        assertTrue(garbage.error!!.startsWith("could not read"))
    }

    /** Run with ONEOS_HOST_SERVICES_JAR=<F8U services.jar>: must equal host_installd.txt (85 methods). */
    @Test
    fun f8uServicesJarReproducesTheScriptTable() {
        val path = System.getProperty("oneos.hostServicesJar")
        assumeTrue("ONEOS_HOST_SERVICES_JAR not set", path != null && File(path).isFile)
        val expected = InstalldTableFormat.parseLegacy(fixture.readText())
        val jar = File(path!!)
        val d = HostInstalldTable.derive(listOf(HostInstalldTable.Input("/system/framework/services.jar", null, jar, "services.jar")), emptyMap())
        assertNull(d.error)
        assertEquals(85, d.methods.size)
        assertEquals(expected, d.methods)
        // Extracted the way the probe does it (unzip -p of the entry) gives the same.
        val entry = ZipFile(jar).use { z -> z.getInputStream(z.getEntry(d.sourceEntry)).readBytes() }
        val fromDex = HostInstalldTable.derive(listOf(HostInstalldTable.Input("/system/framework/services.jar", d.sourceEntry, file("services.dex", entry), "x")), emptyMap())
        assertEquals(expected, fromDex.methods)
    }
}
