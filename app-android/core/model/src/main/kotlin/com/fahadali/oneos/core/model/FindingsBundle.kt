package com.fahadali.oneos.core.model

import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** [name] is the app's label ("OneOS" or "OneOS Probe"): the root findings name it. */
data class AppInfo(val id: String, val versionName: String, val versionCode: Int, val name: String)

/** Everything the bundle needs besides the capture folder. */
data class FindingsInput(
    val app: AppInfo,
    /** ISO-8601 with offset, e.g. `2026-10-22T15:10:31+03:00`. */
    val generatedAt: String,
    /** `yyyy-MM-dd`, for the zip name. */
    val date: String,
    /** Non-root facts (FactProbe) with the `su -c id` result; null if they could not be read. */
    val facts: DeviceFacts?,
    val factsError: String? = null,
    val commands: List<ProbeCommand>,
    val runnerSha256: String,
    val commandsSha256: String,
    val stream: ProbeStreamOutcome,
    val installd: InstalldDerivation?,
)

data class BundleFile(val path: String, val bytes: Long, val sha256: String, val redacted: Boolean)

data class FindingsResult(
    /** `findings-<model>-<date>`. */
    val name: String,
    /** The redacted folder the zip was made from. */
    val folder: File,
    val zip: File,
    val files: List<BundleFile>,
    val report: CompatReport,
    val redaction: RedactionLog,
    val summary: String,
)

/**
 * Builds the findings bundle (schema `oneos.findings/1`,
 * docs/app/compat-safety-logs.md section 7.6) from the raw capture:
 *
 * 1. every text file goes through the [Redactor] (getprop first, so values
 *    removed by key name are removed everywhere; Bluetooth input device
 *    names are learned before any file); binary files (dex, jars)
 *    are system files and are copied as they are;
 * 2. installd/table.txt (format 1) and installd/host_installd.txt (the older
 *    layout) when the app could derive the table;
 * 3. redaction.txt, summary.txt and report.json (with the SHA-256 of every
 *    other file);
 * 4. `findings-<model>-<date>.zip` next to the folder.
 *
 * Only the redacted folder and the zip are ever shared; the capture folder is
 * deleted by the caller afterwards.
 */
class FindingsBuilder(private val redactor: Redactor) {

    fun build(input: FindingsInput, captureDir: File, outRoot: File): FindingsResult {
        val captured = listFiles(captureDir)
        val rawProps = captured.firstOrNull { it.endsWith("getprop.txt") }
            ?.let { Getprop.parse(File(captureDir, it).readText(Charsets.ISO_8859_1)) }
            .orEmpty()

        val model = (input.facts?.model ?: rawProps["ro.product.model"]).orEmpty().ifEmpty { "phone" }
        val name = "findings-${safeName(model)}-${input.date}"
        val folder = File(outRoot, name)
        val zip = File(outRoot, "$name.zip")
        folder.deleteRecursively()
        zip.delete()
        folder.mkdirs()

        // Facts: the CPU from the root read when the app could not read it.
        val capturedCpu = readText(captureDir, "cpu/cpuinfo.txt")?.let(CpuInfoParser::parse)?.takeIf { it.cores.isNotEmpty() }
        val facts = input.facts?.let { if (it.cpu == null && capturedCpu != null) it.copy(cpu = capturedCpu) else it }
        val cpu = facts?.cpu ?: capturedCpu

        val selinux = readText(captureDir, "selinux/types.txt")?.let(SelinuxTypes::parse)
        val results = input.stream.results
        val stage = results.firstOrNull { it.id == "storage.stage" }?.let {
            when (it.status) {
                ProbeStatus.OK -> true
                ProbeStatus.NOT_PRESENT -> false
                else -> null
            }
        }
        val rootManager = readText(captureDir, "root/manager.txt")?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        val probeData = ProbeData(results, selinux, stage, rootManager, input.installd)

        val findings = mutableListOf<Finding>()
        if (facts != null) {
            findings += CompatEvaluator.evaluate(facts, app = input.app.name).findings
        } else {
            findings += Finding(
                "facts-unread", Level.WARN, "Phone facts not read",
                input.factsError ?: "The app could not read the phone facts.",
            )
            findings += CpuRules.finding(cpu)
        }
        findings += ProbeFindings.evaluate(probeData)
        if (!input.stream.done) {
            findings += Finding(
                "probe-run-incomplete", Level.INFO, "The test run did not finish",
                input.stream.error ?: "The root shell stopped before the end of the list.",
            )
        }
        val report = CompatReport(findings)

        // 1. Redacted copies (getprop first; Bluetooth input device names are
        //    learned before any file, as getevent.txt sorts before them).
        readText(captureDir, INPUT_DEVICES)?.let(redactor::learnBluetoothInputNames)
        val ordered = captured.sortedWith(compareBy({ !it.endsWith("getprop.txt") }, { it }))
        val written = mutableListOf<Pair<String, Boolean>>()
        for (rel in ordered) {
            val src = File(captureDir, rel)
            val dst = File(folder, rel)
            dst.parentFile?.mkdirs()
            if (isBinary(rel)) {
                src.copyTo(dst, overwrite = true)
                written += rel to false
            } else {
                val text = src.readText(Charsets.ISO_8859_1)
                val clean = if (rel.endsWith("getprop.txt")) {
                    redactor.redactGetprop(text, rel)
                } else {
                    redactor.redactText(text, rel)
                }
                dst.writeText(clean, Charsets.ISO_8859_1)
                written += rel to true
            }
        }

        // 2. installd tables derived on the phone.
        val fingerprint = (facts?.fingerprint ?: "").ifEmpty { rawProps["ro.build.fingerprint"].orEmpty() }
        val ins = input.installd
        var tableNote: String? = null
        if (ins != null && ins.ok) {
            try {
                val table = InstalldTableFormat.write(fingerprint, ins.sourceJar.orEmpty(), ins.sourceSha256.orEmpty(), ins.methods)
                File(folder, TABLE).also { it.parentFile?.mkdirs() }.writeText(table, Charsets.US_ASCII)
                written += TABLE to false
            } catch (e: IllegalArgumentException) {
                tableNote = "table.txt not written: ${e.message}"
            }
            File(folder, LEGACY_TABLE).also { it.parentFile?.mkdirs() }
                .writeText(InstalldTableFormat.legacy(ins.methods, fingerprint), Charsets.US_ASCII)
            written += LEGACY_TABLE to false
        }

        // 3. redaction.txt, summary.txt, report.json.
        File(folder, REDACTION).writeText(redactor.log.render(), Charsets.UTF_8)
        written += REDACTION to false
        val pre = written.map { (rel, red) -> bundleFile(folder, rel, red) }
        val summary = summary(input, facts, cpu, report, probeData, pre, tableNote)
        File(folder, SUMMARY).writeText(summary, Charsets.UTF_8)
        val files = pre + bundleFile(folder, SUMMARY, false)
        val json = report(input, facts, cpu, report, probeData, files, fingerprint, tableNote)
        File(folder, REPORT).writeText(Json.write(json), Charsets.UTF_8)
        val all = files + bundleFile(folder, REPORT, false)

        // 4. The zip: entries under the bundle name, report and summary first.
        val zipOrder = all.sortedWith(compareBy({ it.path != REPORT && it.path != SUMMARY }, { it.path }))
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            for (f in zipOrder) {
                z.putNextEntry(ZipEntry("$name/${f.path}"))
                File(folder, f.path).inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
        return FindingsResult(name, folder, zip, all, report, redactor.log, summary)
    }

    private fun bundleFile(folder: File, rel: String, redacted: Boolean): BundleFile {
        val f = File(folder, rel)
        return BundleFile(rel, f.length(), Hashes.sha256(f), redacted)
    }

    private fun summary(
        input: FindingsInput,
        facts: DeviceFacts?,
        cpu: CpuInfo?,
        report: CompatReport,
        d: ProbeData,
        files: List<BundleFile>,
        tableNote: String?,
    ): String = buildString {
        append("OneOS findings (Friend build 1, probe)\n")
        append("App ${input.app.versionName} (${input.app.versionCode}), ${input.generatedAt}\n")
        append("Send this zip to the OneOS team. Nothing on the phone was changed.\n\n")
        if (facts != null) {
            append("Phone:    ${facts.model} (${facts.device}), ${facts.family.label}, ${facts.form.form.label}\n")
            append("Chip:     ${facts.soc.display}\n")
            append("One UI:   ${facts.oneUi?.toString() ?: "not found"} (${facts.oneUiRaw.ifEmpty { "-" }})\n")
            append("Android:  ${facts.androidRelease} (SDK ${facts.sdk}), build ${facts.buildId}\n")
            append("Build:    ${facts.fingerprint}\n")
            append("Root:     ${facts.rootKind.label}")
            facts.root.probe?.let { append("; su -c id: ${it.state.name.lowercase(Locale.ROOT)}${it.context?.let { c -> " ($c)" } ?: ""}") }
            d.rootManager?.let { append("; $it") }
            append('\n')
            append("Boot:     bootloader ${facts.flashLocked.ifEmpty { "?" }}, verified boot ${facts.verifiedBootState.ifEmpty { "?" }}\n")
        } else {
            append("Phone facts not read: ${input.factsError ?: "unknown error"}\n")
        }
        append('\n')
        append("CPU:      ${cpu?.summary ?: "not read"}\n")
        if (cpu != null) {
            append("Features: ${CpuRules.presence(cpu)}\n")
            val missing = CpuRules.missingForColorOs(cpu)
            append("Some system images need ${CpuRules.COLOROS_REQUIRED.joinToString(", ") { it.uppercase(Locale.ROOT) }}: ")
            append(if (missing.isEmpty()) "all present\n" else "missing ${missing.joinToString(", ") { it.uppercase(Locale.ROOT) }}\n")
        }
        append('\n')
        append("Verdict:  ${report.verdict.label}\n")
        for (f in report.sorted) {
            append("  [${f.level.name.padEnd(5)}] ${f.title}\n")
            if (f.detail.isNotEmpty()) append("          ${f.detail}\n")
        }
        append('\n')
        val ins = d.installd
        append("installd: ")
        when {
            ins == null -> append("not read\n")
            ins.ok -> append("${ins.methods.size} methods from ${ins.sourceJar}!${ins.sourceEntry} (jar sha256 ${ins.sourceSha256 ?: "?"})\n")
            else -> append("not derived on the phone: ${ins.error}\n")
        }
        tableNote?.let { append("          $it\n") }
        val se = d.selinux
        if (se == null || se.names.isEmpty()) {
            append("SELinux:  policy types not read\n")
        } else {
            append("SELinux:  ${se.declarations} types, ${se.names.size} names with aliases; generic app types ")
            append(if (se.missingGeneric.isEmpty()) "all present\n" else "missing ${se.missingGeneric.joinToString()}\n")
        }
        append('\n')
        val byStatus = d.results.groupingBy { it.status }.eachCount()
        append("Tests:    ${d.results.size} of ${input.commands.size} ran: ")
        append(ProbeStatus.entries.filter { (byStatus[it] ?: 0) > 0 }.joinToString(", ") { "${byStatus[it]} ${it.label}" })
        append('\n')
        val byId = d.results.associateBy { it.id }
        for (c in input.commands) {
            val r = byId[c.id]
            val st = r?.status?.label ?: "not run"
            append("  ${st.padEnd(12)} ${c.id.padEnd(22)} ${c.target}")
            r?.exitCode?.takeIf { it != 0 }?.let { append("  (exit $it)") }
            append('\n')
        }
        if (!input.stream.done) append("  The run stopped early: ${input.stream.error ?: "no end marker"}\n")
        append('\n')
        append("Redaction: ${redactor.log.total} values removed (${redactor.log.short()}); see redaction.txt\n")
        val total = files.sumOf { it.bytes }
        append("Files:    ${files.size + 1} (${Units.bytes(total)}); SHA-256 of each in report.json\n")
    }

    private fun report(
        input: FindingsInput,
        facts: DeviceFacts?,
        cpu: CpuInfo?,
        report: CompatReport,
        d: ProbeData,
        files: List<BundleFile>,
        fingerprint: String,
        tableNote: String?,
    ): Map<String, Any?> {
        val byId = d.results.associateBy { it.id }
        return linkedMapOf(
            "schema" to SCHEMA,
            "generatedAt" to input.generatedAt,
            "app" to linkedMapOf(
                "id" to input.app.id,
                "version" to input.app.versionName,
                "versionCode" to input.app.versionCode,
            ),
            "probe" to linkedMapOf(
                "stream" to (input.stream.version?.let { "oneos.probe-stream/$it" }),
                "completed" to input.stream.done,
                "error" to input.stream.error,
                "runnerSha256" to input.runnerSha256,
                "commandsSha256" to input.commandsSha256,
                "commands" to input.commands.size,
            ),
            "device" to facts?.let { deviceJson(it, d) },
            "cpu" to cpu?.let { cpuJson(it) },
            "verdict" to when (report.verdict) {
                Verdict.COMPATIBLE -> "OK"
                Verdict.WARNING -> "WARN"
                Verdict.NOT_SUPPORTED -> "BLOCK"
            },
            "verdictLabel" to report.verdict.label,
            "findings" to report.sorted.map {
                linkedMapOf("id" to it.id, "level" to it.level.name, "title" to it.title, "detail" to it.detail)
            },
            "probes" to input.commands.map { c ->
                val r = byId[c.id]
                linkedMapOf(
                    "id" to c.id,
                    "group" to c.group,
                    "kind" to c.kind.keyword,
                    "target" to c.target,
                    "status" to (r?.status?.label ?: "not run"),
                    "exitCode" to r?.exitCode,
                    "millis" to r?.millis,
                    "files" to r?.files?.map { f ->
                        linkedMapOf("path" to f.path, "source" to f.source, "bytes" to f.bytes, "exitCode" to f.exitCode)
                    }.orEmpty(),
                    "notes" to r?.notes.orEmpty(),
                )
            },
            "installd" to d.installd?.let { ins ->
                linkedMapOf(
                    "derived" to ins.ok,
                    "error" to ins.error,
                    "methods" to ins.methods.size,
                    "sourceJar" to ins.sourceJar,
                    "sourceEntry" to ins.sourceEntry,
                    "sourceSha256" to ins.sourceSha256,
                    "fingerprint" to fingerprint,
                    "table" to if (files.any { it.path == TABLE }) TABLE else null,
                    "tableNote" to tableNote,
                    "inputs" to ins.inputs,
                    "notes" to ins.notes,
                )
            },
            "selinux" to d.selinux?.let { se ->
                linkedMapOf(
                    "typeDeclarations" to se.declarations,
                    "typealiasDeclarations" to se.aliases,
                    "names" to se.names.size,
                    "genericAppTypes" to ProbeFindings.GENERIC_APP_TYPES.associateWith { it in se.names },
                )
            },
            "redaction" to redactor.log.toJson(),
            "files" to files.map {
                linkedMapOf("path" to it.path, "bytes" to it.bytes, "sha256" to it.sha256, "redacted" to it.redacted)
            },
        )
    }

    private fun deviceJson(f: DeviceFacts, d: ProbeData): Map<String, Any?> = linkedMapOf(
        "brand" to f.brand,
        "manufacturer" to f.manufacturer,
        "model" to f.model,
        "device" to f.device,
        "family" to f.family.name,
        "form" to f.form.form.name,
        "formEvidence" to f.form.evidence,
        "soc" to f.soc.model,
        "socVendor" to f.soc.vendor.name,
        "socPlatform" to f.soc.platform,
        "socName" to f.soc.marketingName,
        "oneui" to f.oneUi?.toString(),
        "oneuiRaw" to f.oneUiRaw,
        "androidRelease" to f.androidRelease,
        "sdk" to f.sdk,
        "buildId" to f.buildId,
        "fingerprint" to f.fingerprint,
        "vendorApiLevel" to f.vendorApiLevel,
        "abis64" to f.abis64,
        "kernel" to f.kernel,
        "flashLocked" to f.flashLocked,
        "verifiedBootState" to f.verifiedBootState,
        "screenLockSet" to f.screenLockSet,
        "panels" to f.panels.map {
            linkedMapOf(
                "id" to it.displayId,
                "name" to it.name,
                "widthPx" to it.widthPx,
                "heightPx" to it.heightPx,
                "xdpi" to it.xdpi.toDouble(),
                "ydpi" to it.ydpi.toDouble(),
                "inches" to it.diagonalInches,
            )
        },
        "root" to linkedMapOf(
            "kind" to f.rootKind.name,
            "managerPackages" to f.root.managerPackages,
            "manager" to d.rootManager,
            "suIdState" to f.root.probe?.state?.name,
            "suContext" to f.root.probe?.context,
        ),
    )

    private fun cpuJson(cpu: CpuInfo): Map<String, Any?> = linkedMapOf(
        "hardware" to cpu.hardware,
        "summary" to cpu.summary,
        "features" to cpu.features.sorted(),
        "heterogeneous" to cpu.heterogeneous,
        "reported" to CpuRules.REPORTED.associateWith { cpu.has(it) },
        "colorOsRequired" to CpuRules.COLOROS_REQUIRED,
        "missingForColorOs" to CpuRules.missingForColorOs(cpu),
        "cores" to cpu.cores.map {
            linkedMapOf(
                "cpu" to it.index,
                "implementer" to it.implementer,
                "part" to it.part,
                "variant" to it.variant,
                "revision" to it.revision,
                "label" to it.label,
            )
        },
    )

    companion object {
        const val SCHEMA = "oneos.findings/1"
        const val REPORT = "report.json"
        const val SUMMARY = "summary.txt"
        const val REDACTION = "redaction.txt"
        const val TABLE = "installd/table.txt"
        const val LEGACY_TABLE = "installd/host_installd.txt"
        const val INPUT_DEVICES = "input/proc_bus_input_devices.txt"

        private val BINARY_EXT = listOf(".dex", ".jar", ".apk", ".vdex", ".odex", ".art", ".so")

        fun safeName(s: String): String = s.replace(Regex("[^A-Za-z0-9-]"), "_").take(40)

        /**
         * System binaries (dex, jars) are copied unchanged. Everything else is
         * redacted as Latin-1 text, even if it holds NUL bytes, so no file
         * escapes the redactor by looking binary.
         */
        fun isBinary(rel: String): Boolean = BINARY_EXT.any { rel.endsWith(it) }

        private fun listFiles(root: File): List<String> =
            root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()

        private fun readText(root: File, rel: String): String? =
            File(root, rel).takeIf { it.isFile }?.readText(Charsets.ISO_8859_1)
    }
}
