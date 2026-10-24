package com.fahadali.oneos.core.model

/**
 * A small JSON writer for report.json (no serialization library in
 * :core:model). Values: Map<String, *> (key order kept), List<*>, String,
 * Number, Boolean, null. Output is pretty-printed with two-space indents and
 * ends with a newline.
 */
object Json {
    fun write(value: Any?): String = StringBuilder().also { write(it, value, 0) }.append('\n').toString()

    private fun write(out: StringBuilder, value: Any?, depth: Int) {
        when (value) {
            null -> out.append("null")
            is String -> string(out, value)
            is Boolean -> out.append(value)
            is Int, is Long, is Short, is Byte -> out.append(value.toString())
            is Double -> out.append(if (value.isFinite()) value.toString() else "null")
            is Float -> out.append(if (value.isFinite()) value.toString() else "null")
            is Number -> out.append(value.toString())
            is Map<*, *> -> container(out, depth, '{', '}', value.entries.toList()) { e, d ->
                string(out, e.key.toString())
                out.append(": ")
                write(out, e.value, d)
            }
            is Iterable<*> -> container(out, depth, '[', ']', value.toList()) { v, d -> write(out, v, d) }
            is Array<*> -> write(out, value.toList(), depth)
            else -> string(out, value.toString())
        }
    }

    private fun <T> container(
        out: StringBuilder,
        depth: Int,
        open: Char,
        close: Char,
        items: List<T>,
        item: (T, Int) -> Unit,
    ) {
        if (items.isEmpty()) {
            out.append(open).append(close)
            return
        }
        out.append(open).append('\n')
        items.forEachIndexed { i, v ->
            indent(out, depth + 1)
            item(v, depth + 1)
            if (i < items.size - 1) out.append(',')
            out.append('\n')
        }
        indent(out, depth)
        out.append(close)
    }

    private fun indent(out: StringBuilder, depth: Int) {
        repeat(depth) { out.append("  ") }
    }

    fun string(out: StringBuilder, s: String) {
        out.append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000c' -> out.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    out.append("\\u").append(String.format(java.util.Locale.ROOT, "%04x", c.code))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }
}
