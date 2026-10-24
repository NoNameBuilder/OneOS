package com.fahadali.oneos.session

import android.content.Context
import com.fahadali.oneos.core.root.OldStage
import com.fahadali.oneos.core.root.OldStages
import com.fahadali.oneos.core.root.RootScript
import com.fahadali.oneos.core.root.SuProbe
import com.fahadali.oneos.core.model.SuState
import com.fahadali.oneos.probe.ProbeSession

/**
 * Old stages (0.3.5): lists the stages `begin aside` left behind (root
 * `old-list`, read only) and deletes one the user confirmed (root `purge`,
 * which checks again that nothing is mounted, bridged or running there).
 */
object OldStagesJob : BackgroundJob<OldStagesJob.State>(State(), "oneos-old-stages") {
    enum class Phase { IDLE, LISTING, DELETING, DONE, FAILED }

    data class State(
        val phase: Phase = Phase.IDLE,
        val stages: List<OldStage>? = null,
        val deleting: String? = null,
        val message: String? = null,
        /** What the last delete freed (KiB), for Free up space's result line; null otherwise. */
        val freedKib: Long? = null,
        /** The last [delete] did not delete (its reason is [message]); a failed list leaves it false. */
        val deleteFailed: Boolean = false,
    ) {
        val busy: Boolean get() = phase == Phase.LISTING || phase == Phase.DELETING
    }

    private fun othersBusy() = StageCheck.state.busy || PrepareJob.state.busy || LaunchJob.state.busy || LogJob.state.busy

    fun refresh(ctx: Context) {
        if (state.busy || othersBusy()) return
        val app = ctx.applicationContext
        update(state.copy(phase = Phase.LISTING, message = null, freedKib = null, deleteFailed = false))
        executor.execute {
            val r = runCatching { list(app) }
            update(
                r.fold(
                    { state.copy(phase = Phase.DONE, stages = it) },
                    { state.copy(phase = Phase.FAILED, message = it.message ?: it.javaClass.simpleName) },
                ),
            )
        }
    }

    fun delete(ctx: Context, stage: OldStage) {
        if (state.busy || othersBusy()) return
        val app = ctx.applicationContext
        update(state.copy(phase = Phase.DELETING, deleting = stage.name, message = null, freedKib = null, deleteFailed = false))
        executor.execute {
            val r = runCatching {
                su(app)
                RootVerbs.run(app, RootScript.Verb.PURGE, listOf(stage.name), silenceMs = 10 * 60_000L)
            }
            val reply = r.getOrNull()
            val freed = if (reply?.ok == true) OldStages.freedKib(reply.reply.results, stage.name) ?: stage.kib else null
            val msg = when {
                reply == null -> r.exceptionOrNull()?.message
                reply.ok -> "Deleted ${stage.name}: ${gib(freed ?: stage.kib)} freed."
                else -> "Not deleted: ${reply.problem}"
            }
            val after = runCatching { list(app) }.getOrNull()
            update(
                state.copy(
                    phase = if (reply?.ok == true) Phase.DONE else Phase.FAILED, deleting = null, stages = after ?: state.stages,
                    message = msg, freedKib = freed, deleteFailed = reply?.ok != true,
                ),
            )
        }
    }

    private fun su(app: Context) {
        val s = SuProbe.runId()
        check(s.state == SuState.GRANTED) { ProbeSession.suText(app, s) }
    }

    private fun list(app: Context): List<OldStage> {
        su(app)
        val reply = RootVerbs.run(app, RootScript.Verb.OLD_LIST, emptyList(), silenceMs = 5 * 60_000L)
        check(reply.ok) { "Could not list old stages: ${reply.problem}" }
        return OldStages.parse(reply.reply.results)
    }

    fun gib(kib: Long): String = String.format(java.util.Locale.ROOT, "%.1f GB", kib / 1_048_576.0)
}
