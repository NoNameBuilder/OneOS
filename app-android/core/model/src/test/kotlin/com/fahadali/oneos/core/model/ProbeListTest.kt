package com.fahadali.oneos.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ProbeListTest {
    private val probeDir = File(System.getProperty("oneos.probeDir") ?: "../../app/src/main/assets/probe")
    private val listText by lazy { File(probeDir, "commands.list").readText() }
    private val runnerText by lazy { File(probeDir, "probe.sh").readText() }

    @Test
    fun shippedListParsesAndPassesTheAudit() {
        val list = ProbeCommandList.parse(listText)
        val problems = ProbeAudit.audit(list)
        assertTrue("audit problems:\n${problems.joinToString("\n")}", problems.isEmpty())
        // Every probe area of the friend build is there.
        val ids = list.map { it.id }.toSet()
        for (id in listOf(
            "build.getprop", "build.key", "cpu.cpuinfo", "cpu.uname", "installd.classpath", "installd.jars",
            "installd.dex", "display.sf", "display.dm", "display.res", "display.ls", "display.xml", "input.getevent",
            "input.ports",
            "input.tsp", "backlight.sysfs", "hal.services", "hal.initsvc", "hal.vendorbin", "hal.vendorinit",
            "selinux.ls", "selinux.cil", "selinux.types", "storage.df", "storage.stage", "root.manager",
        )) {
            assertTrue("missing $id", id in ids)
        }
        assertTrue(list.all { it.description.isNotBlank() && it.timeoutSec in 1..ProbeCommandList.MAX_TIMEOUT_SEC })
        assertEquals(
            listOf(
                "Build and system properties", "CPU", "installd interface", "Displays", "Input and touch", "Backlight",
                "HALs and services", "SELinux policy", "Storage and root",
            ),
            ProbeCommandList.groups(list),
        )
        assertEquals(ProbeKind.FILES, list.first { it.id == "installd.dex" }.kind)
        assertTrue(list.first { it.id == "build.key" }.command.contains("ro.build.version.oneui"))
    }

    @Test
    fun shippedRunnerPassesItsAudit() {
        val problems = ProbeAudit.auditRunner(runnerText)
        assertTrue("runner problems:\n${problems.joinToString("\n")}", problems.isEmpty())
        val scan = ProbeAudit.scan(runnerText.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n"))
        val names = scan.commands.map { it.name }.toSet()
        assertTrue(names.containsAll(listOf("timeout", "base64", "printf", ".")))
    }

    @Test
    fun noTelephonyNoLogcatAnywhere() {
        for (bad in listOf("logcat", "telephony.registry", "setprop", "resetprop", "settings put")) {
            val code = listText.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n")
            assertTrue("list contains $bad", !code.contains(bad))
        }
    }

    private fun violations(cmd: String) = ProbeAudit.auditCommand(cmd)

    @Test
    fun auditRefusesWritingCommands() {
        val bad = listOf(
            "setprop persist.sys.x 1",
            "settings put global adb_enabled 1",
            "pm clear com.sec.android.app.launcher",
            "am force-stop com.android.settings",
            "cmd package list packages",
            "mount -o remount,rw /system",
            "rm -rf /data/local/tmp/x",
            "cp /system/framework/services.jar /sdcard/",
            "echo 1 > /sys/class/backlight/panel0-backlight/brightness",
            "cat /proc/cpuinfo >> /data/local/tmp/cpu.txt",
            "getprop | tee /sdcard/props.txt",
            "chmod 777 /data/adb",
            "resetprop ro.debuggable 1",
            "reboot",
            "svc wifi disable",
            "kill -9 1",
        )
        for (c in bad) assertTrue("not refused: $c", violations(c).isNotEmpty())
    }

    @Test
    fun auditRefusesPrivateDataAndEventCapture() {
        assertTrue(violations("dumpsys telephony.registry").isNotEmpty())
        assertTrue(violations("dumpsys activity").isNotEmpty())
        assertTrue(violations("logcat -d").isNotEmpty())
        assertTrue(violations("getevent -l").isNotEmpty()) // would read touch events
        assertTrue(violations("getevent -pl /dev/input/event3").isNotEmpty())
        assertTrue(violations("service call iphonesubinfo 1").isNotEmpty())
        assertTrue(violations("content query --uri content://sms").isNotEmpty())
    }

    @Test
    fun auditChecksArgumentsOfDualUseCommands() {
        assertTrue(violations("unzip -o /system/framework/services.jar -d /data/local/tmp").isNotEmpty())
        assertTrue(violations("sed -i \"s/a/b/\" /vendor/etc/init/x.rc").isNotEmpty())
        assertTrue(violations("sed -n \"s/a/b/w /data/local/tmp/x\" /proc/cpuinfo").isNotEmpty())
        assertTrue(violations("awk \"{ system(\\\"reboot\\\") }\" /proc/cpuinfo").isNotEmpty())
        assertTrue(violations("magisk --install-module x.zip").isNotEmpty())
        assertTrue(violations("/data/adb/ksud module install x.zip").isNotEmpty())
        // The read-only forms pass.
        assertTrue(violations("unzip -l /system/framework/services.jar").isEmpty())
        assertTrue(violations("magisk -v; magisk -V; /data/adb/ksud -V").isEmpty())
        assertTrue(violations("dumpsys SurfaceFlinger --display-id").isEmpty())
        assertTrue(violations("service list").isEmpty())
        assertTrue(violations("cmd overlay lookup android android:string/config_mainBuiltInDisplayCutout").isEmpty())
        for (bad in listOf(
            "cmd overlay enable com.example.overlay",
            "cmd overlay lookup com.android.systemui android:string/x",
            "cmd overlay lookup android android:string/x extra",
            "cmd package uninstall com.example",
            "cmd settings put global x 1",
        )) {
            assertTrue("allowed: $bad", violations(bad).isNotEmpty())
        }
        assertTrue(violations("getevent -pl").isEmpty())
    }

    @Test
    fun auditClosesWriteAndStateChangeLoopholes() {
        val bad = listOf(
            "sort -o /data/local/tmp/x /proc/cpuinfo",
            "sort -uo /data/local/tmp/x /proc/cpuinfo",
            "sort --output=/data/local/tmp/x /proc/cpuinfo",
            "uniq /proc/cpuinfo /data/local/tmp/x",
            "sed -n \"1w /data/local/tmp/x\" /proc/cpuinfo",
            "sed -n \"\$w /data/local/tmp/x\" /proc/cpuinfo",
            "sed -n \"s/a/b/gw /data/local/tmp/x\" /proc/cpuinfo",
            "sed -n \"s/a/b/e\" /proc/cpuinfo",
            "sed -f /data/local/tmp/x.sed /proc/cpuinfo",
            "sed -n \"p;w /data/local/tmp/x\" /proc/cpuinfo",
            "awk -f /data/local/tmp/x.awk /proc/cpuinfo",
            "cat /proc/cpuinfo >/dev/null_copy",
            "cat /proc/cpuinfo 2>/dev/nullx",
            "getprop 2>&10",
            "dumpsys SurfaceFlinger --latency-clear",
            "dumpsys SurfaceFlinger --timestats -clear",
            "dumpsys SurfaceFlinger --frametimeline -disable",
            "dumpsys display --some-arg",
            "getevent -p -c 5",
            "getevent -pc5",
            "PATH=/data/local/tmp getprop",
            "LD_PRELOAD=/data/local/tmp/x.so getprop",
        )
        for (c in bad) assertTrue("not refused: $c", violations(c).isNotEmpty())
        // The read-only forms the list uses still pass.
        for (ok in listOf(
            "sed -n -E \"s/^(BOOTCLASSPATH|SYSTEMSERVERCLASSPATH)=//p\" /x",
            "service list | sed -n -E \"s/^[0-9]+[[:space:]]+([^:]+):.*/\\1/p\"",
            "sed -n \"/^Features/p\" /proc/cpuinfo",
            "sort -u /proc/cpuinfo | uniq -c",
            "dumpsys SurfaceFlinger --display-id",
            "dumpsys display",
            "getevent -pl",
            "x=\$(cat /y 2>/dev/null); cat /x >/dev/null 2>&1",
        )) {
            assertEquals("refused: $ok", emptyList<String>(), violations(ok))
        }
    }

    @Test
    fun auditRefusesHiddenOrDynamicCommands() {
        assertTrue(violations("x=rm; \$x -rf /").isNotEmpty())
        assertTrue(violations("printf x | sh").isNotEmpty())
        assertTrue(violations("eval \"getprop\"").isNotEmpty())
        assertTrue(violations("cat `ls /data`").isNotEmpty())
        assertTrue(violations("getprop &").isNotEmpty())
        assertTrue(violations("echo \"\$(rm -rf /x)\"").isNotEmpty())
        assertTrue(violations("find /data -delete").isNotEmpty())
        assertTrue(violations("busybox rm /x").isNotEmpty())
        assertTrue(violations("timeout 5 rm /x").isNotEmpty())
        assertTrue(violations("printf \"%s\" \$((1+1))").isNotEmpty())
    }

    @Test
    fun scannerFindsCommandsInLoopsConditionsAndSubstitutions() {
        val scan = ProbeAudit.scan(
            """
            for d in /sys/class/backlight/*; do
              [ -d "${'$'}d" ] || continue
              printf "%s\n" "${'$'}(cat "${'$'}d/brightness" 2>/dev/null)"
            done | sort
            if command -v magisk >/dev/null 2>&1; then magisk -v; fi
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), scan.problems)
        assertEquals(listOf("[", "continue", "cat", "printf", "sort", "command", "magisk"), scan.commands.map { it.name })
        assertEquals(listOf("-v", "magisk"), scan.commands.first { it.name == "command" }.args)
    }

    @Test
    fun parserRejectsAnythingOutsideTheGrammar() {
        fun bad(text: String) {
            try {
                ProbeCommandList.parse(text)
                fail("parsed: $text")
            } catch (_: ProbeCommandList.ParseException) {
            }
        }
        val ok = "#== G\n#: d\nrun a.b 10 x/y.txt 'getprop'\n"
        assertEquals(1, ProbeCommandList.parse(ok).size)
        bad("#== G\n#: d\nrun a.b 10 x/y.txt 'getprop'\nrm -rf /\n") // stray shell line
        bad("#== G\nrun a.b 10 x/y.txt 'getprop'\n") // no description
        bad("#: d\nrun a.b 10 x/y.txt 'getprop'\n") // no group
        bad("#== G\n#: d\nrun a.b 0 x/y.txt 'getprop'\n") // timeout
        bad("#== G\n#: d\nrun a.b 10 ../y.txt 'getprop'\n") // path
        bad("#== G\n#: d\nrun a.b 10 /abs.txt 'getprop'\n")
        bad("#== G\n#: d\nrun a.b 10 x.txt 'getprop' ; rm x\n") // text after the quote
        bad("#== G\n#: d\nrun a.b 10 x.txt 'getprop\n") // quote not closed
        bad("#== G\n#: d\nrun a.b 10 x.txt 'a'\n#: d\nrun a.b 10 y.txt 'b'\n") // duplicate id
        bad("#== G\n#: d\nexec a.b 10 x.txt 'getprop'\n") // unknown kind
        val multi = ProbeCommandList.parse("#== G\n#: one\n#: two\nfiles a.c 20 out '\nprintf \"%s\\n\" /x\ntrue'\n")
        assertEquals("one two", multi[0].description)
        assertEquals("printf \"%s\\n\" /x\ntrue", multi[0].command)
        assertEquals(ProbeKind.FILES, multi[0].kind)
    }
}
