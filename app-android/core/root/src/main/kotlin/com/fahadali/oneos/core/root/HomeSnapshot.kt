package com.fahadali.oneos.core.root

import com.fahadali.oneos.core.model.FixAction
import com.fahadali.oneos.core.model.ImageRef
import com.fahadali.oneos.core.model.Images
import com.fahadali.oneos.core.model.Level
import com.fahadali.oneos.core.model.PlainMessage

/** One check tile as Home showed it: the gate ([com.fahadali.oneos.core.model.SwitchGates] id), its level and its value ("54 GB free"). */
data class HomeCheck(val id: String, val level: Level, val value: String)

/**
 * The Home of the last finished check ([HomeState.snapshot]), kept in the
 * app's private preferences so that a cold start shows it, read-only, while
 * its own check runs ([HomeInput.cached]). Only what Home draws: no finding,
 * nothing to act on. [encode] / [parse] is a small line format (`key=value`,
 * with `\\`, `\n` and `\t` escaped; a check is `check=id<TAB>LEVEL<TAB>value`);
 * anything malformed, of another schema, or naming an unknown kind is dropped
 * (no kept Home: the app shows "Checking your phone…" as before).
 */
data class HomeSnapshot(
    val kind: HomeState.Kind,
    val imageId: String?,
    val problem: PlainMessage?,
    val primary: FixAction?,
    val primaryLabel: String?,
    val checks: List<HomeCheck> = emptyList(),
    /** Wall clock when it was kept. */
    val wallMs: Long = 0,
) {
    val image: ImageRef? get() = imageId?.let { Images.byId(it) }

    /** The same Home on screen, whenever it was kept (the app writes it again only when this is false). */
    fun sameAs(other: HomeSnapshot?): Boolean = other != null && copy(wallMs = 0) == other.copy(wallMs = 0)

    fun encode(): String = buildString {
        fun put(key: String, value: String?) {
            if (value != null) append(key).append('=').append(escape(value)).append('\n')
        }
        put("schema", SCHEMA)
        put("kind", kind.name)
        put("image", imageId)
        problem?.let {
            put("title", it.title)
            put("line", it.line)
            put("action", it.action.name)
            put("actionLabel", it.actionLabel)
            put("level", it.level.name)
        }
        put("primary", primary?.name)
        put("primaryLabel", primaryLabel)
        for (c in checks) {
            append("check=").append(escape(c.id)).append('\t').append(c.level.name).append('\t').append(escape(c.value)).append('\n')
        }
        put("wall", wallMs.toString())
    }

    companion object {
        const val SCHEMA = "oneos.home/1"
        private const val MAX_CHARS = 8192
        private val KEYS = setOf("schema", "kind", "image", "title", "line", "action", "actionLabel", "level", "primary", "primaryLabel", "wall")

        /** The snapshot [encode] wrote; null for nothing, another schema or anything malformed. */
        fun parse(text: String?): HomeSnapshot? {
            if (text.isNullOrEmpty() || text.length > MAX_CHARS) return null
            val values = mutableMapOf<String, String>()
            val checks = mutableListOf<HomeCheck>()
            for (raw in text.split('\n')) {
                if (raw.isEmpty()) continue
                val eq = raw.indexOf('=')
                if (eq <= 0) return null
                val key = raw.substring(0, eq)
                val value = raw.substring(eq + 1)
                if (key == "check") {
                    val parts = value.split('\t')
                    if (parts.size != 3) return null
                    val level = enumOrNull<Level>(parts[1]) ?: return null
                    checks += HomeCheck(unescape(parts[0]) ?: return null, level, unescape(parts[2]) ?: return null)
                } else {
                    if (key !in KEYS || key in values) return null
                    values[key] = unescape(value) ?: return null
                }
            }
            if (values["schema"] != SCHEMA) return null
            val kind = enumOrNull<HomeState.Kind>(values["kind"]) ?: return null
            val problem = values["title"]?.let { title ->
                PlainMessage(
                    title = title,
                    line = values["line"] ?: return null,
                    action = enumOrNull<FixAction>(values["action"]) ?: return null,
                    actionLabel = values["actionLabel"],
                    level = enumOrNull<Level>(values["level"]) ?: return null,
                )
            }
            val primary = values["primary"]?.let { enumOrNull<FixAction>(it) ?: return null }
            return HomeSnapshot(
                kind = kind,
                imageId = values["image"],
                problem = problem,
                primary = primary,
                primaryLabel = values["primaryLabel"],
                checks = checks,
                wallMs = values["wall"]?.toLongOrNull() ?: 0,
            )
        }

        private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }

        private fun escape(s: String): String = buildString {
            for (ch in s) {
                when (ch) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    '\r' -> append("\\r")
                    else -> append(ch)
                }
            }
        }

        /** Null for a lone or unknown escape. */
        private fun unescape(s: String): String? {
            if ('\\' !in s) return s
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                if (ch != '\\') {
                    out.append(ch)
                    i++
                    continue
                }
                if (i + 1 >= s.length) return null
                out.append(
                    when (s[i + 1]) {
                        '\\' -> '\\'
                        'n' -> '\n'
                        't' -> '\t'
                        'r' -> '\r'
                        else -> return null
                    },
                )
                i += 2
            }
            return out.toString()
        }
    }
}
