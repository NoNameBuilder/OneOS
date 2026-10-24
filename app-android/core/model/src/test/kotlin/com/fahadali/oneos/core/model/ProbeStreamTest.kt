package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.Base64

class ProbeStreamTest {
    private class MemorySink : ProbeSink {
        val files = linkedMapOf<String, ByteArrayOutputStream>()
        val started = mutableListOf<String>()
        val finished = mutableListOf<ProbeResult>()

        override fun probeStarted(id: String, timeoutSec: Int) {
            started += id
        }

        override fun open(path: String): OutputStream = ByteArrayOutputStream().also { files[path] = it }

        override fun probeFinished(result: ProbeResult) {
            finished += result
        }
    }

    /** base64 wrapped at 76 columns, like toybox base64. */
    private fun b64(bytes: ByteArray): List<String> = Base64.getEncoder().encodeToString(bytes).chunked(76)

    private fun run(lines: List<String>): Pair<MemorySink, ProbeStreamOutcome> {
        val sink = MemorySink()
        var t = 1000L
        val p = ProbeStreamParser(sink) { t += 10; t }
        lines.forEach(p::accept)
        return sink to p.finish()
    }

    @Test
    fun decodesFramesIntoFilesWithHashes() {
        val props = "[ro.product.model]: [SM-F976B]\n".repeat(40).toByteArray()
        val dex = ByteArray(5000) { (it * 31).toByte() }
        val lines = buildList {
            add("@@ONEOS-PROBE 1")
            add("@@PROBE build.getprop 30")
            add("@@FILE props/getprop.txt -")
            addAll(b64(props))
            add("@@END 0")
            add("@@STATUS build.getprop 0")
            add("@@PROBE installd.dex 300")
            add("@@FILE installd /system/framework/services.jar!classes.dex")
            addAll(b64(dex))
            add("@@END 0")
            add("@@STATUS installd.dex 0")
            add("@@PROBE storage.stage 10")
            add("@@FILE storage/stage.txt -")
            add("@@END 3")
            add("@@STATUS storage.stage 3")
            add("@@PROBE display.sf 20")
            add("@@FILE display/surfaceflinger.txt -")
            add("@@END 124")
            add("@@STATUS display.sf 124")
            add("@@DONE")
        }
        val (sink, out) = run(lines)
        assertEquals(1, out.version)
        assertTrue(out.done)
        assertNull(out.error)
        assertEquals(0, out.strayLines)
        assertEquals(listOf("build.getprop", "installd.dex", "storage.stage", "display.sf"), sink.started)
        assertTrue(props.contentEquals(sink.files.getValue("props/getprop.txt").toByteArray()))
        val dexPath = "installd/system_framework_services.jar/classes.dex"
        assertTrue(dex.contentEquals(sink.files.getValue(dexPath).toByteArray()))
        val r = out.results.associateBy { it.id }
        assertEquals(ProbeStatus.OK, r.getValue("build.getprop").status)
        assertEquals(Hashes.sha256(props), r.getValue("build.getprop").files.single().sha256)
        assertEquals(props.size.toLong(), r.getValue("build.getprop").files.single().bytes)
        assertEquals("/system/framework/services.jar!classes.dex", r.getValue("installd.dex").files.single().source)
        assertEquals(Hashes.sha256(dex), r.getValue("installd.dex").files.single().sha256)
        assertEquals(ProbeStatus.NOT_PRESENT, r.getValue("storage.stage").status)
        assertEquals(ProbeStatus.TIMEOUT, r.getValue("display.sf").status)
        assertEquals(124, r.getValue("display.sf").exitCode)
        assertEquals(4, sink.finished.size)
        assertTrue(r.values.all { it.millis > 0 })
    }

    @Test
    fun base64LinesNeedNotBeMultiplesOfFour() {
        val data = "hello probe stream, odd wrapping".toByteArray()
        val enc = Base64.getEncoder().encodeToString(data)
        val (sink, out) = run(
            listOf("@@PROBE a.b 5", "@@FILE x/a.txt -", enc.substring(0, 5), enc.substring(5, 18), enc.substring(18), "@@END 0", "@@STATUS a.b 0"),
        )
        assertEquals(String(data), sink.files.getValue("x/a.txt").toString())
        assertTrue(out.results.single().notes.isEmpty())
    }

    @Test
    fun exitCodesMapToStatuses() {
        assertEquals(ProbeStatus.OK, ProbeStatus.fromExit(0))
        assertEquals(ProbeStatus.NOT_PRESENT, ProbeStatus.fromExit(3))
        assertEquals(ProbeStatus.NOT_PRESENT, ProbeStatus.fromExit(127))
        assertEquals(ProbeStatus.TIMEOUT, ProbeStatus.fromExit(124))
        assertEquals(ProbeStatus.TIMEOUT, ProbeStatus.fromExit(137))
        assertEquals(ProbeStatus.FAILED, ProbeStatus.fromExit(1))
        assertEquals(ProbeStatus.FAILED, ProbeStatus.fromExit(null))
    }

    @Test
    fun unsafePathsAreRefusedAndNothingIsWritten() {
        val (sink, out) = run(
            listOf("@@PROBE a.b 5", "@@FILE ../../etc -", "AAAA", "@@END 0", "@@FILE x /a/../../b", "AAAA", "@@END 0", "@@STATUS a.b 0"),
        )
        assertEquals(listOf("x/a_.._.._b"), sink.files.keys.toList()) // flattened: no traversal possible
        assertTrue(out.results.single().notes.any { it.contains("unsafe") })
        assertNull(ProbePaths.local("..", "-"))
        assertNull(ProbePaths.local("a/../b", "-"))
        assertEquals("display/vendor_etc_displayconfig_display_id_1.xml", ProbePaths.local("display", "/vendor/etc/displayconfig/display_id_1.xml"))
        assertEquals("installd/apex_com.android.art_javalib_core-oj.jar", ProbePaths.local("installd", "/apex/com.android.art/javalib/core-oj.jar"))
    }

    @Test
    fun runnerErrorsAndCutStreamsAreReported() {
        val (_, err) = run(listOf("@@ERROR no timeout command"))
        assertEquals("no timeout command", err.error)
        assertFalse(err.done)

        val (sink, cut) = run(listOf("@@ONEOS-PROBE 1", "@@PROBE a.b 5", "@@FILE x/y.txt -", "aGVsbG8="))
        assertFalse(cut.done)
        val r = cut.results.single()
        assertEquals(ProbeStatus.FAILED, r.status)
        assertTrue(r.notes.contains("the runner stopped"))
        assertEquals("hello", sink.files.getValue("x/y.txt").toString())

        val (_, stray) = run(listOf("Magisk: granted", "@@PROBE a.b 5", "@@STATUS a.b 0", "@@WHAT", "@@DONE"))
        assertEquals(2, stray.strayLines)
        assertEquals(ProbeStatus.OK, stray.results.single().status)
    }
}
