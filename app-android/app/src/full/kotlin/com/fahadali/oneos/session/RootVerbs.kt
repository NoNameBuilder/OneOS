package com.fahadali.oneos.session

import android.content.Context
import com.fahadali.oneos.BuildConfig
import com.fahadali.oneos.core.root.RootReply
import com.fahadali.oneos.core.root.RootScript
import com.fahadali.oneos.core.root.RootShell

/**
 * Runs one verb of the root script through its launcher (RootScript) with the
 * pins from BuildConfig. This is the only way the app changes anything as
 * root; every call starts from a screen the user confirmed.
 */
object RootVerbs {
    data class Reply(val reply: RootReply, val end: RootShell.End) {
        val ok: Boolean get() = end.exitCode == RootScript.EXIT_OK && reply.ok != null && end.error == null
        val problem: String?
            get() = when {
                ok -> null
                end.error != null -> end.error
                end.cancelled -> "stopped"
                end.killed && reply.refused == null -> "root did not answer in time"
                else -> reply.problem(end.exitCode)
            }
    }

    @Volatile private var current: RootShell? = null

    fun cancel() {
        current?.cancel()
    }

    fun run(
        ctx: Context,
        verb: RootScript.Verb,
        args: List<String>,
        feed: RootShell.Feed? = null,
        silenceMs: Long = 120_000,
        totalMs: Long = 30 * 60_000L,
        onStep: (String) -> Unit = {},
    ): Reply {
        val script = AppAssets.rootScript(ctx)
        val command = RootScript.launcher(verb, args, BuildConfig.ROOT_SCRIPT_SHA256, BuildConfig.ROOT_SCRIPT_BYTES)
        val input = when (verb.stdin) {
            RootScript.Stdin.SCRIPT -> RootShell.Feed { it.write(script) }
            RootScript.Stdin.DATA -> requireNotNull(feed) { "${verb.word} needs its data" }
            RootScript.Stdin.NONE -> null
        }
        val shell = RootShell(command)
        current = shell
        val reply = RootReply()
        val end = try {
            shell.run(input, { line -> reply.accept(line)?.let(onStep) }, { silenceMs }, totalMs)
        } finally {
            current = null
        }
        return Reply(reply, end)
    }
}
