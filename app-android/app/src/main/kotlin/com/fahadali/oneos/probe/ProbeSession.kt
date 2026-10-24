package com.fahadali.oneos.probe

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.fahadali.oneos.BuildConfig
import com.fahadali.oneos.R
import com.fahadali.oneos.core.device.FactProbe
import com.fahadali.oneos.core.model.AppInfo
import com.fahadali.oneos.core.model.ClassPath
import com.fahadali.oneos.core.model.FindingsBuilder
import com.fahadali.oneos.core.model.FindingsInput
import com.fahadali.oneos.core.model.Hashes
import com.fahadali.oneos.core.model.InstalldDerivation
import com.fahadali.oneos.core.model.JarsListing
import com.fahadali.oneos.core.model.ProbeAudit
import com.fahadali.oneos.core.model.ProbeCommand
import com.fahadali.oneos.core.model.ProbeCommandList
import com.fahadali.oneos.core.model.ProbeResult
import com.fahadali.oneos.core.model.ProbeSink
import com.fahadali.oneos.core.model.ProbeStatus
import com.fahadali.oneos.core.model.ProbeStreamParser
import com.fahadali.oneos.core.model.Redactor
import com.fahadali.oneos.core.model.SuProbeResult
import com.fahadali.oneos.core.model.SuState
import com.fahadali.oneos.core.root.RootProbe
import com.fahadali.oneos.core.root.RootProbeRunner
import com.fahadali.oneos.core.root.SuProbe
import com.fahadali.oneos.core.safety.HostInstalldTable
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Friend build 1: one probe run at a time, kept in the app process so it
 * survives screen rotation (docs/app/compat-safety-logs.md section 7).
 *
 * 1. Parse and audit the bundled list and runner (refuse to run if either
 *    breaks a read-only rule).
 * 2. `su -c id` (the user confirmed a dialog first).
 * 3. Copy the runner and the list into the app's files folder and run
 *    `su -c "sh probe.sh commands.list"`; the base64 stream is decoded into
 *    `cache/probe-capture/`.
 * 4. Read the facts (no root), derive the installd table from the captured
 *    dex, redact everything into `cache/findings/findings-<model>-<date>/`
 *    and zip it; delete the raw capture.
 *
 * Nothing outside the app's own folders is written at any step.
 */
object ProbeSession {
    enum class Phase { IDLE, ROOT_CHECK, ROOT_FAILED, RUNNING, BUILDING, DONE, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val statuses: Map<String, ProbeStatus> = emptyMap(),
        val exitCodes: Map<String, Int?> = emptyMap(),
        val current: String? = null,
        val finished: Int = 0,
        val total: Int = 0,
        val message: String? = null,
        val su: SuProbeResult? = null,
        val zip: File? = null,
    ) {
        val busy: Boolean get() = phase == Phase.ROOT_CHECK || phase == Phase.RUNNING || phase == Phase.BUILDING
    }

    /** The bundled probe files, parsed and audited. */
    data class Bundled(
        val listText: String,
        val runnerText: String,
        val commands: List<ProbeCommand>,
        /** Read-only rule violations; any one stops the run before root is asked. */
        val problems: List<String>,
    )

    const val LIST_ASSET = "probe/commands.list"
    const val RUNNER_ASSET = "probe/probe.sh"

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "oneos-probe") }
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()

    @Volatile var state = State()
        private set

    @Volatile private var runner: RootProbeRunner? = null

    @Volatile private var cancelled = false

    @Volatile private var bundledCache: Bundled? = null

    fun bundled(context: Context): Bundled = bundledCache ?: load(context).also { bundledCache = it }

    private fun load(context: Context): Bundled {
        val assets = context.applicationContext.assets
        val list = assets.open(LIST_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val runner = assets.open(RUNNER_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
        val problems = mutableListOf<String>()
        val commands = try {
            ProbeCommandList.parse(list)
        } catch (e: ProbeCommandList.ParseException) {
            problems += "commands.list: ${e.message}"
            emptyList()
        }
        problems += ProbeAudit.audit(commands)
        problems += ProbeAudit.auditRunner(runner).map { "probe.sh: $it" }
        return Bundled(list, runner, commands, problems)
    }

    /** Calls [listener] now and on every change (main thread); returns the unsubscribe call. */
    fun observe(listener: (State) -> Unit): () -> Unit {
        listeners += listener
        listener(state)
        return { listeners -= listener }
    }

    fun findingsDir(context: Context) = File(context.cacheDir, "findings")

    private fun captureDir(context: Context) = File(context.cacheDir, "probe-capture")

    /** The newest findings zip in the cache, if any. */
    fun latestZip(context: Context): File? =
        findingsDir(context).listFiles { f -> f.isFile && f.name.startsWith("findings-") && f.name.endsWith(".zip") }
            ?.maxByOrNull { it.lastModified() }

    /** The redacted folder the zip was made from. */
    fun folderOf(zip: File): File = File(zip.parentFile, zip.name.removeSuffix(".zip"))

    fun deleteFindings(context: Context) {
        if (state.busy) return
        findingsDir(context).deleteRecursively()
        captureDir(context).deleteRecursively()
        update(State())
    }

    fun start(context: Context) {
        if (state.busy) return
        val app = context.applicationContext
        cancelled = false
        update(State(phase = Phase.ROOT_CHECK))
        executor.execute {
            try {
                run(app)
            } catch (e: Throwable) {
                captureDir(app).deleteRecursively()
                update(state.copy(phase = Phase.FAILED, current = null, message = "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(500)))
            }
        }
    }

    fun cancel() {
        cancelled = true
        runner?.cancel()
    }

    private fun update(s: State) {
        state = s
        main.post { listeners.forEach { it(s) } }
    }

    private fun run(app: Context) {
        val b = bundled(app)
        if (b.problems.isNotEmpty()) {
            update(
                State(
                    phase = Phase.FAILED,
                    message = "The bundled test list failed its read-only check, so nothing ran:\n" +
                        b.problems.take(5).joinToString("\n"),
                ),
            )
            return
        }
        val pending = b.commands.associate { it.id to ProbeStatus.PENDING }
        update(State(phase = Phase.ROOT_CHECK, statuses = pending, total = b.commands.size))

        val su = SuProbe.runId()
        if (cancelled) {
            update(State(message = "Stopped."))
            return
        }
        if (su.state != SuState.GRANTED) {
            update(State(phase = Phase.ROOT_FAILED, statuses = pending, total = b.commands.size, su = su, message = suText(app, su)))
            return
        }

        // The runner and the list run from the app's own files folder.
        val dir = File(app.filesDir, "probe").apply { mkdirs() }
        val runnerFile = File(dir, "probe.sh").apply { writeText(b.runnerText) }
        val listFile = File(dir, "commands.list").apply { writeText(b.listText) }
        check(Hashes.sha256(runnerFile) == Hashes.sha256(b.runnerText) && Hashes.sha256(listFile) == Hashes.sha256(b.listText)) {
            "the probe files did not copy exactly"
        }
        val capture = captureDir(app).apply {
            deleteRecursively()
            mkdirs()
        }
        findingsDir(app).deleteRecursively()
        update(state.copy(phase = Phase.RUNNING, su = su))

        val silence = AtomicLong(SILENCE_MARGIN_MS)
        val root = capture.canonicalPath + File.separator
        val sink = object : ProbeSink {
            override fun probeStarted(id: String, timeoutSec: Int) {
                silence.set(timeoutSec * 1000L + SILENCE_MARGIN_MS)
                update(state.copy(current = id, statuses = state.statuses + (id to ProbeStatus.RUNNING)))
            }

            override fun open(path: String): OutputStream {
                val f = File(capture, path)
                check(f.canonicalPath.startsWith(root)) { "output outside the capture folder: $path" }
                f.parentFile?.mkdirs()
                return f.outputStream().buffered(1 shl 16)
            }

            override fun probeFinished(result: ProbeResult) {
                update(
                    state.copy(
                        statuses = state.statuses + (result.id to result.status),
                        exitCodes = state.exitCodes + (result.id to result.exitCode),
                        finished = state.finished + 1,
                    ),
                )
            }
        }
        val parser = ProbeStreamParser(sink)
        val r = RootProbeRunner(runnerFile, listFile)
        runner = r
        val end = r.run(parser) { silence.get() }
        runner = null
        val outcome = parser.finish()
        if (end.cancelled || cancelled) {
            capture.deleteRecursively()
            update(State(message = "Stopped. Nothing was kept from this run."))
            return
        }

        update(state.copy(phase = Phase.BUILDING, current = null))
        val facts = runCatching { FactProbe(app).collect(RootProbe(app).signals(su)) }
        val installd = deriveInstalld(capture, outcome.results)
        val now = OffsetDateTime.now()
        val input = FindingsInput(
            app = appInfo(app),
            generatedAt = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")),
            date = LocalDate.now().toString(),
            facts = facts.getOrNull(),
            factsError = facts.exceptionOrNull()?.let { "${it.javaClass.simpleName}: ${it.message}" },
            commands = b.commands,
            runnerSha256 = Hashes.sha256(b.runnerText),
            commandsSha256 = Hashes.sha256(b.listText),
            stream = outcome.copy(error = outcome.error ?: end.error ?: if (end.killed) "stopped: no output in time" else null),
            installd = installd,
        )
        val result = FindingsBuilder(Redactor.forBundle()).build(input, capture, findingsDir(app))
        // The raw capture held personal data; only the redacted folder and zip stay.
        capture.deleteRecursively()

        val counts = outcome.results.groupingBy { it.status }.eachCount()
        val line = buildString {
            append(result.report.verdict.label).append(". ")
            append(ProbeStatus.entries.filter { (counts[it] ?: 0) > 0 }.joinToString(", ") { "${counts[it]} ${it.label}" })
            if (!outcome.done) append(". The run stopped early: ${input.stream.error ?: "no end marker"}")
            append(". ${result.redaction.total} personal values removed.")
        }
        update(state.copy(phase = Phase.DONE, current = null, zip = result.zip, message = line))
    }

    /** installd table from the captured dex files, in system_server's class-loader order. */
    private fun deriveInstalld(capture: File, results: List<ProbeResult>): InstalldDerivation {
        val dex = results.firstOrNull { it.id == "installd.dex" }
        if (dex == null || dex.files.isEmpty()) {
            return InstalldDerivation.failed("the installd test captured no dex file (${dex?.status?.label ?: "not run"})")
        }
        return runCatching {
            val cp = File(capture, "installd/classpath.txt").takeIf { it.isFile }?.readText().orEmpty()
            val jars = File(capture, "installd/jars.txt").takeIf { it.isFile }?.readText().orEmpty()
            HostInstalldTable.derive(
                HostInstalldTable.inputsFrom(capture, dex.files, ClassPath.order(cp)),
                JarsListing.parse(jars).associate { it.path to it.sha256 },
            )
        }.getOrElse { InstalldDerivation.failed("${it.javaClass.simpleName}: ${it.message}", dex.files.map { f -> f.path }) }
    }

    /** This edition's id, version and name ("OneOS" or "OneOS Probe", the label the root manager lists). */
    fun appInfo(context: Context) =
        AppInfo(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, context.getString(R.string.app_name))

    /** What a failed `su -c id` means, naming this app as the root manager lists it. */
    fun suText(context: Context, p: SuProbeResult): String = suText(p, context.getString(R.string.app_name))

    private fun suText(p: SuProbeResult, app: String): String = when (p.state) {
        SuState.GRANTED -> "Root works: ${p.output}"
        SuState.TIMEOUT -> "Root did not answer in time. Allow $app in your root manager, then try again."
        SuState.NOT_FOUND -> "su not found for $app: ${p.output}. Allow $app in KernelSU or APatch first."
        SuState.DENIED -> "Root refused: ${p.output.ifEmpty { "no output" }}"
        SuState.ERROR -> "Root check failed: ${p.output}"
    }

    /** Extra wait after an entry's own timeout before the run counts as stuck. */
    private const val SILENCE_MARGIN_MS = 30_000L
}
