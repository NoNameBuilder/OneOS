package com.fahadali.oneos.core.root

import com.fahadali.oneos.core.model.RootRules
import com.fahadali.oneos.core.model.SuProbeResult
import com.fahadali.oneos.core.model.SuState
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * The only root command in M1: `su -c id`, run once when the user taps
 * Check root. `id` prints the uid and SELinux context and changes nothing.
 * Blocks for up to [DEFAULT_TIMEOUT_MS] (Magisk shows a grant prompt), so call
 * it off the main thread.
 */
object SuProbe {
    const val DEFAULT_TIMEOUT_MS = 20_000L
    const val COMMAND = "su -c id"

    fun runId(timeoutMs: Long = DEFAULT_TIMEOUT_MS): SuProbeResult {
        val proc = try {
            ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        } catch (e: IOException) {
            return SuProbeResult(SuState.NOT_FOUND, null, null, e.message.orEmpty().take(500))
        }
        return try {
            proc.outputStream.close()
            val out = AtomicReference("")
            val reader = thread(name = "su-id-reader", isDaemon = true) {
                runCatching { out.set(proc.inputStream.bufferedReader().use { it.readText() }) }
            }
            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                SuProbeResult(SuState.TIMEOUT, null, null, "no answer after ${timeoutMs / 1000} s")
            } else {
                reader.join(2_000)
                RootRules.parseIdOutput(out.get(), proc.exitValue())
            }
        } catch (e: Exception) {
            SuProbeResult(SuState.ERROR, null, null, (e.message ?: e.javaClass.simpleName).take(500))
        } finally {
            proc.destroy()
        }
    }
}
