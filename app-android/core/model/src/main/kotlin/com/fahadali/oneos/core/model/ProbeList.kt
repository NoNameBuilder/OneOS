package com.fahadali.oneos.core.model

enum class ProbeKind(val keyword: String) {
    /** stdout + stderr of the command is one file. */
    RUN("run"),

    /** The command prints paths (or `jar!entry`); each one is read and becomes a file. */
    FILES("files"),
}

/** One entry of the probe list (`app/src/main/assets/probe/commands.list`). */
data class ProbeCommand(
    val kind: ProbeKind,
    val id: String,
    val timeoutSec: Int,
    /** Output file (RUN) or output folder (FILES), relative to the capture root. */
    val target: String,
    /** Exactly what `sh -c` runs as root. */
    val command: String,
    val description: String,
    val group: String,
    val line: Int,
)

/**
 * Parser for the probe list: the one auditable file that holds every command
 * OneOS runs as root (docs/app/compat-safety-logs.md section 7.2). The list
 * is a shell file that `probe.sh` sources, restricted to this grammar:
 *
 * ```
 * #== <group title>
 * #: <description of the next entry (may span several #: lines)>
 * run   <id> <timeout s> <output file>   '<command>'
 * files <id> <timeout s> <output folder> '<command>'
 * # comment
 * ```
 *
 * The command sits between single quotes, may span lines and contains no
 * single quote. Anything else is a parse error, so nothing outside the
 * entries can run.
 */
object ProbeCommandList {
    class ParseException(message: String) : IllegalArgumentException(message)

    const val MAX_TIMEOUT_SEC = 600

    private val ENTRY = Regex("""^(run|files)\s+(\S+)\s+(\S+)\s+(\S+)\s+'(.*)$""")
    private val ID = Regex("""^[a-z][a-z0-9]*(\.[a-z0-9_-]+)+$""")
    private val TARGET = Regex("""^[A-Za-z0-9_-]+(/[A-Za-z0-9_.-]+)*$""")

    fun parse(text: String): List<ProbeCommand> {
        val lines = text.split('\n').map { it.trimEnd('\r') }
        val out = mutableListOf<ProbeCommand>()
        var group: String? = null
        var desc = ""
        var n = 0
        while (n < lines.size) {
            val lineNo = n + 1
            val trimmed = lines[n].trim()
            n++
            when {
                trimmed.isEmpty() -> Unit
                trimmed.startsWith("#==") -> group = trimmed.removePrefix("#==").trim()
                trimmed.startsWith("#:") -> desc = (desc + " " + trimmed.removePrefix("#:").trim()).trim()
                trimmed.startsWith("#") -> Unit
                else -> {
                    val m = ENTRY.matchEntire(trimmed) ?: throw ParseException("line $lineNo: not an entry: $trimmed")
                    val (kw, id, timeout, target) = m.destructured
                    val rest = m.groupValues[5]
                    val command: String
                    val q = rest.indexOf('\'')
                    if (q >= 0) {
                        if (rest.substring(q + 1).isNotBlank()) throw ParseException("line $lineNo: text after the closing quote")
                        command = rest.substring(0, q)
                    } else {
                        val sb = StringBuilder(rest)
                        var closed = false
                        while (n < lines.size) {
                            val l = lines[n]
                            n++
                            val qi = l.indexOf('\'')
                            if (qi < 0) {
                                sb.append('\n').append(l)
                                continue
                            }
                            if (l.substring(qi + 1).isNotBlank()) {
                                throw ParseException("line $n: text after the closing quote")
                            }
                            sb.append('\n').append(l.substring(0, qi))
                            closed = true
                            break
                        }
                        if (!closed) throw ParseException("line $lineNo: command quote not closed")
                        command = sb.toString()
                    }
                    if (!ID.matches(id)) throw ParseException("line $lineNo: bad id '$id'")
                    val t = timeout.toIntOrNull()
                    if (t == null || t < 1 || t > MAX_TIMEOUT_SEC) {
                        throw ParseException("line $lineNo: timeout must be 1..$MAX_TIMEOUT_SEC s")
                    }
                    if (!TARGET.matches(target) || target.split('/').any { it == "." || it == ".." }) {
                        throw ParseException("line $lineNo: bad output path '$target'")
                    }
                    if (command.isBlank()) throw ParseException("line $lineNo: empty command")
                    if (desc.isEmpty()) throw ParseException("line $lineNo: '$id' has no #: description")
                    val g = group ?: throw ParseException("line $lineNo: '$id' is not in a #== group")
                    val kind = ProbeKind.entries.first { it.keyword == kw }
                    out += ProbeCommand(kind, id, t, target, command.trim('\n'), desc, g, lineNo)
                    desc = ""
                }
            }
        }
        val dupIds = out.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (dupIds.isNotEmpty()) throw ParseException("duplicate ids: ${dupIds.joinToString()}")
        val dupTargets = out.groupBy { it.target }.filterValues { it.size > 1 }.keys
        if (dupTargets.isNotEmpty()) throw ParseException("duplicate outputs: ${dupTargets.joinToString()}")
        if (out.isEmpty()) throw ParseException("no entries")
        return out
    }

    /** Group titles in list order. */
    fun groups(list: List<ProbeCommand>): List<String> = list.map { it.group }.distinct()
}

/**
 * Read-only audit of the probe list and of the runner script. Runs in the unit
 * tests (the build fails if the shipped list breaks a rule) and again in the
 * app before every run (it refuses to start if the bundled list breaks one).
 *
 * The audit is an allow-list: every command word in command position (start
 * of a pipeline element, after `;`, `&&`, `||`, `then`, `do`, inside `$(...)`)
 * must be a known read-only command, with argument rules for the few that
 * could change state with other arguments (`dumpsys`, `service`, `getevent`,
 * `unzip`, `sed`, `awk`, `sort`, `uniq`, `cmd` (only `cmd overlay lookup` of
 * one framework resource), the root manager CLIs). On top: no
 * output redirection except to /dev/null or between stdout/stderr (whole
 * tokens only), no assignment to PATH, IFS, ENV or LD_*, no background
 * jobs, backticks, here-documents, `case`, arithmetic or nested quotes, and a
 * few named commands are refused outright (logcat, telephony dumps, setprop).
 */
object ProbeAudit {
    data class SimpleCommand(val name: String, val args: List<String>)

    data class Scan(val commands: List<SimpleCommand>, val problems: List<String>)

    /** Commands a probe entry may run. All of them only read. */
    val LIST_ALLOWED: Set<String> = setOf(
        "getprop", "cat", "ls", "sha256sum", "stat", "grep", "sed", "awk", "sort", "uniq", "wc", "head", "tail",
        "cut", "tr", "printf", "echo", "dumpsys", "getevent", "service", "uname", "df", "id", "unzip", "basename",
        "dirname", "readlink", "pidof", "getenforce", "test", "[", "true", "false", "exit", "command", "break",
        "continue", "magisk", "/data/adb/ksud", "/data/adb/apd", "cmd",
    )

    /** Commands the runner (probe.sh) itself runs, besides calling the entries. */
    val RUNNER_ALLOWED: Set<String> = setOf(
        "printf", "timeout", "base64", "stat", "command", "exit", "set", ".", "run", "files", "continue", "[", "true",
    )

    /**
     * dumpsys services an entry may read, each with the only arguments it may
     * get (SurfaceFlinger also takes state-changing ones such as
     * `--latency-clear` and `--timestats -enable`).
     */
    val DUMPSYS_ALLOWED: Map<String, Set<String>> = mapOf(
        "SurfaceFlinger" to setOf("--display-id", "--list"),
        "display" to emptySet(),
    )

    /** Assignments that would change how later command names resolve (the runner sets PATH itself). */
    private val ASSIGNMENT_REFUSED = Regex("""(^|[\s;&|(])(PATH|IFS|ENV|LD_[A-Z_]*)=""")

    private val FORBIDDEN_TEXT = listOf(
        "logcat", "telephony.registry", "setprop", "resetprop", "settings ", "service call", "/proc/sysrq", "<<",
    )

    private val LIST_REDIRECTS = listOf("2>&1", "1>&2", ">&2", "2>/dev/null", "1>/dev/null", ">/dev/null")
    private val RUNNER_REDIRECTS = listOf("2>&1", "3>&1", "4>&1", ">&3", ">&4", "2>/dev/null", ">/dev/null")

    fun audit(list: List<ProbeCommand>): List<String> = list.flatMap { c -> auditCommand(c.command).map { "${c.id}: $it" } }

    fun auditCommand(command: String): List<String> {
        val problems = mutableListOf<String>()
        problems += redirectProblems(command, LIST_REDIRECTS)
        for (bad in FORBIDDEN_TEXT) if (command.contains(bad)) problems += "contains '$bad'"
        ASSIGNMENT_REFUSED.find(command)?.let { problems += "sets ${it.groupValues[2]}" }
        val scan = scan(command)
        problems += scan.problems
        for (c in scan.commands) problems += commandProblems(c, LIST_ALLOWED)
        return problems.distinct()
    }

    /** `sed` options an entry may use; the script must match [SED_SCRIPT]. */
    private val SED_OPTIONS = setOf("-n", "-E", "-r")

    /**
     * One sed command that only prints: an optional address (line, `$` or
     * `/regex/`, or a range) and `s/re/repl/` with flags g, p, I or a number,
     * or `p`, `d`, `q`, `=`. No `w`/`W`/`e`, no second command.
     */
    private val SED_SCRIPT = Regex(
        """^(?:(?:\d+|\$|/(?:[^/\\]|\\.)*/)(?:,(?:\d+|\$|/(?:[^/\\]|\\.)*/))?)?""" +
            """(?:s/(?:[^/\\]|\\.)*/(?:[^/\\]|\\.)*/[gpI0-9]*|[pdq=])$""",
    )

    private fun sedProblems(a: List<String>): List<String> {
        val out = mutableListOf<String>()
        val opts = a.filter { it.startsWith("-") }
        val words = a.filterNot { it.startsWith("-") }
        for (o in opts) if (o !in SED_OPTIONS) out += "sed $o (only ${SED_OPTIONS.joinToString(" ")})"
        val script = words.firstOrNull()
        if (script == null || !SED_SCRIPT.matches(script)) out += "sed script '${script.orEmpty()}' may write (only one s///, p, d, q or =)"
        return out
    }

    fun auditRunner(script: String): List<String> {
        // Full-line comments are documentation only (the shell never runs them).
        val code = script.split('\n').filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
        val problems = mutableListOf<String>()
        problems += redirectProblems(code, RUNNER_REDIRECTS)
        for (bad in FORBIDDEN_TEXT) if (code.contains(bad)) problems += "contains '$bad'"
        val scan = scan(code)
        problems += scan.problems
        for (c in scan.commands) {
            if (c.name !in RUNNER_ALLOWED) {
                problems += "runner runs '${c.name}', which is not on its allow-list"
                continue
            }
            val a = c.args.map(::unquote)
            when (c.name) {
                "timeout" -> problems += timeoutProblems(a)
                "set" -> if (a != listOf("-f")) problems += "set ${a.joinToString(" ")}"
                "command" -> if (a.firstOrNull() != "-v") problems += "command ${a.joinToString(" ")}"
                "." -> if (a != listOf("\$LIST")) problems += "sources ${a.joinToString(" ")}"
                "base64" -> if (a.isNotEmpty()) problems += "base64 ${a.joinToString(" ")}"
            }
        }
        return problems.distinct()
    }

    /** Inside the runner, `timeout` may only start `sh -c "$4"`, `cat <file>` or `unzip -p <jar> <entry>`. */
    private fun timeoutProblems(a: List<String>): List<String> {
        var i = 0
        while (i < a.size && a[i].startsWith("-")) i += 2 // -k N, -s SIG
        i++ // duration
        val program = a.getOrNull(i) ?: return listOf("timeout without a program")
        val rest = a.drop(i + 1).filterNot { it.all(Char::isDigit) }
        return when (program) {
            "sh" -> if (rest.take(2) == listOf("-c", "\$4")) emptyList() else listOf("timeout sh ${rest.joinToString(" ")}")
            "cat" -> emptyList()
            "unzip" -> if (rest.firstOrNull() == "-p") emptyList() else listOf("timeout unzip ${rest.joinToString(" ")}")
            else -> listOf("timeout runs '$program'")
        }
    }

    private fun commandProblems(c: SimpleCommand, allowed: Set<String>): List<String> {
        val name = c.name
        if (name.any { it == '$' || it == '"' || it == '\\' || it == '`' }) {
            return listOf("command name '$name' is not a plain word")
        }
        if (name !in allowed) return listOf("'$name' is not on the read-only allow-list")
        val a = c.args.map(::unquote)
        val out = mutableListOf<String>()
        when (name) {
            "dumpsys" -> {
                val allowedArgs = DUMPSYS_ALLOWED[a.firstOrNull()]
                if (allowedArgs == null) {
                    out += "dumpsys ${a.firstOrNull()} is not allowed"
                } else {
                    for (x in a.drop(1)) if (x !in allowedArgs) out += "dumpsys ${a.first()} $x is not allowed"
                }
            }
            "service" -> if (a != listOf("list")) out += "service ${a.joinToString(" ")} (only 'service list')"
            "getevent" -> {
                // -p prints capabilities and exits; -c (or any other flag) could make it read events.
                if (a.none { it.startsWith("-") && 'p' in it }) out += "getevent without -p would read events"
                if (a.any { !GETEVENT_FLAGS.matches(it) }) out += "getevent ${a.joinToString(" ")} (only -p, -l, -i)"
            }
            "unzip" -> if (a.firstOrNull() !in setOf("-l", "-p")) out += "unzip ${a.firstOrNull()} (only -l or -p)"
            "sed" -> out += sedProblems(a)
            "awk" -> {
                if (a.any { s -> AWK_BAD.any { s.contains(it) } }) out += "awk program may write or run commands"
                if (a.any { it.startsWith("-f") || it.startsWith("--f") }) out += "awk -f runs a program from a file"
            }
            "sort" -> if (a.any { (it.startsWith("-") && !it.startsWith("--") && 'o' in it) || it.startsWith("--o") }) {
                out += "sort -o writes a file"
            }
            "uniq" -> if (a.count { !it.startsWith("-") } > 1) out += "uniq with an output file"
            "magisk", "/data/adb/ksud", "/data/adb/apd" ->
                if (a != listOf("-v") && a != listOf("-V")) out += "$name ${a.joinToString(" ")} (only -v / -V)"
            "command" -> if (a.firstOrNull() != "-v") out += "command ${a.joinToString(" ")} (only command -v)"
            "cmd" -> if (a.size != 4 || a.subList(0, 3) != listOf("overlay", "lookup", "android") ||
                !CMD_LOOKUP_RESOURCE.matches(a[3])
            ) {
                out += "cmd ${a.joinToString(" ")} (only 'cmd overlay lookup android android:<type>/<name>')"
            }
        }
        return out
    }

    private val GETEVENT_FLAGS = Regex("^-[pli]+$")

    /** One framework resource by name: `cmd overlay lookup` only reads it. */
    private val CMD_LOOKUP_RESOURCE = Regex("^android:(string|dimen|bool|integer)/[A-Za-z0-9_]+$")
    private val AWK_BAD = listOf("system", "|", ">", "getline", "close(", "fflush")

    private fun unquote(s: String): String = s.replace("\"", "")

    /**
     * Every `>` must be one of [allowed] as a whole token (`>/dev/null_x` or
     * `2>&10` is not `>/dev/null` or `2>&1`).
     */
    private fun redirectProblems(text: String, allowed: List<String>): List<String> {
        var t = text
        for (r in allowed.sortedByDescending { it.length }) t = t.replace(Regex(Regex.escape(r) + """(?=[\s;&|)]|$)"""), " ")
        return if (t.contains('>')) listOf("output redirection other than ${allowed.joinToString(", ")}") else emptyList()
    }

    /** Finds the simple commands of a shell text (see [ProbeAudit]). */
    fun scan(text: String): Scan = Scanner(text).run()

    private class Scanner(private val s: String) {
        private var i = 0
        private val commands = mutableListOf<SimpleCommand>()
        private val problems = mutableListOf<String>()

        private var expectCmd = true
        private var forHeader = false
        private var name: String? = null
        private var args: MutableList<String>? = null

        fun run(): Scan {
            parseList(stopAtParen = false)
            endCommand()
            return Scan(commands.toList(), problems.distinct())
        }

        private data class State(val expectCmd: Boolean, val forHeader: Boolean, val name: String?, val args: MutableList<String>?)

        private fun save() = State(expectCmd, forHeader, name, args)

        private fun restore(st: State) {
            expectCmd = st.expectCmd
            forHeader = st.forHeader
            name = st.name
            args = st.args
        }

        private fun endCommand() {
            name?.let { commands += SimpleCommand(it, args.orEmpty().toList()) }
            name = null
            args = null
        }

        private fun separator() {
            endCommand()
            expectCmd = true
        }

        private fun peek(off: Int = 1): Char? = s.getOrNull(i + off)

        private fun parseList(stopAtParen: Boolean) {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == ')' -> {
                        i++
                        if (stopAtParen) return
                        problems += "unbalanced )"
                    }
                    c == ' ' || c == '\t' || c == '\r' -> i++
                    c == '\\' && peek() == '\n' -> i += 2
                    c == '\n' || c == ';' -> {
                        i++
                        separator()
                        forHeader = false
                    }
                    c == '|' -> {
                        i++
                        if (peek(0) == '|') i++
                        separator()
                    }
                    c == '&' -> {
                        if (peek() == '&') {
                            i += 2
                        } else {
                            problems += "background job (&)"
                            i++
                        }
                        separator()
                    }
                    c == '(' -> {
                        i++
                        endCommand()
                        val st = save()
                        expectCmd = true
                        forHeader = false
                        parseList(stopAtParen = true)
                        endCommand()
                        restore(st)
                        expectCmd = false
                    }
                    c == '<' || c == '>' -> redirect()
                    c == '#' -> while (i < s.length && s[i] != '\n') i++
                    else -> {
                        val w = readWord()
                        val fd = w.isNotEmpty() && w.all(Char::isDigit) && (peek(0) == '<' || peek(0) == '>')
                        if (!fd) word(w)
                    }
                }
            }
            if (stopAtParen) problems += "missing )"
        }

        private fun redirect() {
            if (s[i] == '<' && peek() == '<') problems += "here-document"
            while (i < s.length && (s[i] == '<' || s[i] == '>')) i++
            if (i < s.length && s[i] == '&') i++
            while (i < s.length && (s[i] == ' ' || s[i] == '\t')) i++
            if (i < s.length && s[i] !in "\n;|&()") readWord()
        }

        private fun readWord(): String {
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                when {
                    c in " \t\r\n;|&()<>" -> break
                    c == '\\' -> {
                        if (peek() == '\n') {
                            i += 2
                        } else {
                            sb.append(c)
                            peek()?.let { sb.append(it) }
                            i += 2
                        }
                    }
                    c == '"' -> {
                        sb.append(c)
                        i++
                        readDquote(sb)
                    }
                    c == '\'' -> {
                        problems += "single quote inside a command"
                        sb.append(c)
                        i++
                    }
                    c == '`' -> {
                        problems += "backtick command substitution"
                        sb.append(c)
                        i++
                    }
                    c == '$' && peek() == '(' -> dollarParen(sb)
                    c == '$' && peek() == '{' -> braces(sb)
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return sb.toString()
        }

        private fun readDquote(sb: StringBuilder) {
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '\\' -> {
                        sb.append(c)
                        peek()?.let { sb.append(it) }
                        i += 2
                    }
                    c == '"' -> {
                        sb.append(c)
                        i++
                        return
                    }
                    c == '`' -> {
                        problems += "backtick command substitution"
                        sb.append(c)
                        i++
                    }
                    c == '$' && peek() == '(' -> dollarParen(sb)
                    c == '$' && peek() == '{' -> braces(sb)
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            problems += "unclosed double quote"
        }

        private fun dollarParen(sb: StringBuilder) {
            if (peek(2) == '(') {
                problems += "arithmetic \$(( ))"
                val end = s.indexOf("))", i)
                i = if (end < 0) s.length else end + 2
                sb.append("\$(())")
                return
            }
            i += 2
            sb.append("\$(")
            val st = save()
            expectCmd = true
            forHeader = false
            name = null
            args = null
            parseList(stopAtParen = true)
            endCommand()
            restore(st)
            sb.append(')')
        }

        private fun braces(sb: StringBuilder) {
            val end = s.indexOf('}', i)
            val stop = if (end < 0) s.length else end + 1
            sb.append(s, i, stop)
            i = stop
        }

        private fun word(w: String) {
            if (forHeader) return
            if (expectCmd) {
                when (w) {
                    "if", "then", "else", "elif", "do", "while", "until", "!", "{" -> return
                    "fi", "done", "esac", "}" -> {
                        endCommand()
                        expectCmd = false
                        return
                    }
                    "for" -> {
                        forHeader = true
                        expectCmd = false
                        return
                    }
                    "case", "select", "function", "eval", "exec", "source" -> problems += "'$w' is not allowed"
                }
                if (ASSIGNMENT.containsMatchIn(w)) return
                name = w
                args = mutableListOf()
                expectCmd = false
            } else {
                args?.add(w)
            }
        }

        private companion object {
            val ASSIGNMENT = Regex("^[A-Za-z_][A-Za-z0-9_]*=")
        }
    }
}
