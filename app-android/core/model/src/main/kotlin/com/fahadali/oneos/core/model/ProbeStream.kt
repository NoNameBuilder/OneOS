package com.fahadali.oneos.core.model

import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

/** Result of one probe entry, from the exit code the runner reports. */
enum class ProbeStatus(val label: String) {
    PENDING("waiting"),
    RUNNING("running"),
    OK("ok"),
    NOT_PRESENT("not present"),
    TIMEOUT("timed out"),
    FAILED("failed"),
    ;

    val finished: Boolean get() = this != PENDING && this != RUNNING

    companion object {
        /**
         * 0 ok; 3 (the entry says so itself) or 127 (command not found) not
         * present; 124 (timeout), 137 or 143 (killed) timed out; else failed.
         */
        fun fromExit(rc: Int?): ProbeStatus = when (rc) {
            null -> FAILED
            0 -> OK
            3, 127 -> NOT_PRESENT
            124, 137, 143 -> TIMEOUT
            else -> FAILED
        }
    }
}

/** One file the probe produced, as written into the capture folder. */
data class CapturedFile(
    /** Relative path inside the capture folder (see [ProbePaths]). */
    val path: String,
    /** Where it came from on the phone: `-` for a command's output, a path, or `jar!entry`. */
    val source: String,
    val bytes: Long,
    val sha256: String,
    /** Exit code of the read (cat / unzip -p / the command). */
    val exitCode: Int?,
)

data class ProbeResult(
    val id: String,
    val status: ProbeStatus,
    val exitCode: Int?,
    val files: List<CapturedFile>,
    val notes: List<String>,
    val millis: Long,
)

/** Where the decoded files go (the app: its cache folder; tests: memory). */
interface ProbeSink {
    fun probeStarted(id: String, timeoutSec: Int) {}

    fun open(path: String): OutputStream

    fun probeFinished(result: ProbeResult) {}
}

data class ProbeStreamOutcome(
    /** `@@ONEOS-PROBE <n>` was seen (n = protocol version). */
    val version: Int?,
    /** `@@DONE` was seen: the runner reached the end of the list. */
    val done: Boolean,
    /** `@@ERROR` from the runner (it stopped before the list). */
    val error: String?,
    val results: List<ProbeResult>,
    /** Lines that were not part of the protocol (ignored). */
    val strayLines: Int,
)

/**
 * Local file names for captured files. A RUN entry writes its target; a FILES
 * entry writes `<folder>/<source path with / as _>`, and a `jar!entry` source
 * becomes `<folder>/<jar path with / as _>/<entry>`, so
 * `/system/framework/services.jar!classes.dex` lands at
 * `installd/system_framework_services.jar/classes.dex`.
 */
object ProbePaths {
    private val SAFE = Regex("[^A-Za-z0-9._+=,@-]")

    /** Null when the result would not be a safe relative path. */
    fun local(target: String, source: String): String? {
        val parts = mutableListOf<String>()
        parts += target.split('/')
        if (source != "-") {
            val bang = source.indexOf('!')
            val file = if (bang >= 0) source.substring(0, bang) else source
            parts += clean(file.trimStart('/').replace('/', '_'))
            if (bang >= 0) parts += clean(source.substring(bang + 1).replace('/', '_'))
        }
        if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
        return parts.joinToString("/") { clean(it) }
    }

    private fun clean(s: String): String = SAFE.replace(s, "_")
}

/**
 * Reads the runner's stdout (protocol `oneos.probe-stream/1`,
 * docs/app/compat-safety-logs.md section 7.3), one line at a time:
 *
 * ```
 * @@ONEOS-PROBE 1
 * @@PROBE <id> <timeout s>
 * @@FILE <target> <source>        source "-" for a command's output
 * <base64 lines>
 * @@END <exit code>
 * @@NOTE <text>
 * @@STATUS <id> <exit code>
 * @@ERROR <text>                  the runner stopped (no timeout or base64, list unreadable)
 * @@DONE
 * ```
 *
 * Files are decoded into [sink] with their size and SHA-256. Feed every line
 * to [accept], then call [finish].
 */
class ProbeStreamParser(
    private val sink: ProbeSink,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Running(val id: String, val started: Long) {
        val files = mutableListOf<CapturedFile>()
        val notes = mutableListOf<String>()
    }

    private class Open(val path: String, val source: String, val out: OutputStream?) {
        val md: MessageDigest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        val carry = StringBuilder()
    }

    private var version: Int? = null
    private var done = false
    private var error: String? = null
    private var stray = 0
    private var running: Running? = null
    private var open: Open? = null
    private val results = mutableListOf<ProbeResult>()
    private val decoder = Base64.getDecoder()

    val finishedResults: List<ProbeResult> get() = results.toList()

    fun accept(rawLine: String) {
        val line = rawLine.trimEnd('\r')
        if (!line.startsWith("@@")) {
            val o = open
            if (o == null) {
                if (line.isNotBlank()) stray++
            } else {
                data(o, line.trim())
            }
            return
        }
        val sp = line.indexOf(' ')
        val tag = if (sp < 0) line else line.substring(0, sp)
        val rest = if (sp < 0) "" else line.substring(sp + 1)
        when (tag) {
            "@@ONEOS-PROBE" -> version = rest.trim().toIntOrNull()
            "@@PROBE" -> {
                closeFile(null)
                running?.let { finishProbe(it, null, "no status from the runner") }
                val p = rest.trim().split(' ')
                val r = Running(p[0], clock())
                running = r
                sink.probeStarted(r.id, p.getOrNull(1)?.toIntOrNull() ?: 0)
            }
            "@@FILE" -> {
                closeFile(null)
                val sp2 = rest.indexOf(' ')
                val target = if (sp2 < 0) rest else rest.substring(0, sp2)
                val source = if (sp2 < 0) "-" else rest.substring(sp2 + 1)
                val path = ProbePaths.local(target, source)
                if (path == null) {
                    running?.notes?.add("refused unsafe output path for $source")
                    open = Open("", source, null)
                } else {
                    open = Open(path, source, runCatching { sink.open(path) }.getOrElse { e ->
                        running?.notes?.add("could not save $path: ${e.message}")
                        null
                    })
                }
            }
            "@@END" -> closeFile(rest.trim().toIntOrNull())
            "@@NOTE" -> running?.notes?.add(rest.take(500))
            "@@STATUS" -> {
                closeFile(null)
                val p = rest.trim().split(' ')
                val r = running
                if (r != null && r.id == p.getOrNull(0)) finishProbe(r, p.getOrNull(1)?.toIntOrNull(), null)
            }
            "@@ERROR" -> error = rest.take(500)
            "@@DONE" -> {
                closeFile(null)
                running?.let { finishProbe(it, null, "no status from the runner") }
                done = true
            }
            else -> stray++
        }
    }

    /** Ends the stream: an open file is closed and a running probe counts as failed. */
    fun finish(): ProbeStreamOutcome {
        closeFile(null)
        running?.let { finishProbe(it, null, if (done) "no status from the runner" else "the runner stopped") }
        return ProbeStreamOutcome(version, done, error, results.toList(), stray)
    }

    private fun data(o: Open, chunk: String) {
        o.carry.append(chunk)
        val usable = o.carry.length - o.carry.length % 4
        if (usable == 0) return
        val text = o.carry.substring(0, usable)
        o.carry.delete(0, usable)
        val bytes = try {
            decoder.decode(text)
        } catch (e: IllegalArgumentException) {
            running?.notes?.add("bad base64 in ${o.path}")
            return
        }
        o.md.update(bytes)
        o.bytes += bytes.size
        o.out?.write(bytes)
    }

    private fun closeFile(rc: Int?) {
        val o = open ?: return
        open = null
        if (o.carry.isNotEmpty()) running?.notes?.add("truncated base64 in ${o.path}")
        runCatching { o.out?.close() }
        if (o.out == null) return
        running?.files?.add(CapturedFile(o.path, o.source, o.bytes, Hashes.hex(o.md.digest()), rc))
    }

    private fun finishProbe(r: Running, rc: Int?, note: String?) {
        running = null
        if (note != null) r.notes += note
        val result = ProbeResult(r.id, ProbeStatus.fromExit(rc), rc, r.files.toList(), r.notes.toList(), clock() - r.started)
        results += result
        sink.probeFinished(result)
    }
}
