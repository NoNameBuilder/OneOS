package com.fahadali.oneos.core.root

import com.fahadali.oneos.core.model.Hashes
import com.fahadali.oneos.core.model.ImageRef
import com.fahadali.oneos.core.model.PackageSet
import com.fahadali.oneos.core.model.PhoneProfile
import com.fahadali.oneos.core.model.ProbeStatus
import com.fahadali.oneos.core.model.SessionEnv
import com.fahadali.oneos.core.model.ShaList
import com.fahadali.oneos.core.model.StageMarker

/** Stage reads for the SetupPlan and HomeState tests (the same layout as StageStatusTest's). */
object StageFixtures {
    private val s = RootScript.STAGE

    fun status(out: Map<String, String>, missing: Set<String> = emptySet()): StageStatus {
        val statuses = StageStatus.OUTPUTS.keys.associateWith { id -> if (id in missing) ProbeStatus.NOT_PRESENT else ProbeStatus.OK }
        return StageStatus.parse(statuses) { path -> out[StageStatus.OUTPUTS.entries.first { it.value == path }.key] }
    }

    /** No stage at all. */
    val absent: StageStatus get() = status(mapOf("stage.mounts" to "root ok\n"), missing = StageStatus.OUTPUTS.keys - "stage.mounts")

    /**
     * [profile]'s stage prepared with [image] and [set] by app build [versionCode];
     * [rawVerified]: system.raw.id still describes the file; [guest]: stage.guest's line.
     */
    fun prepared(
        profile: PhoneProfile,
        image: ImageRef,
        set: PackageSet,
        versionCode: Int = 4,
        rawVerified: Boolean = true,
        guest: String = "packages com.oplus.",
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val names = RootScript.SCRIPTS + set.names
        val shas = names.associateWith { Hashes.sha256(it) }
        val id = if (rawVerified) "64773:1234:${image.rawBytes}:1792000000" else "64773:9999:${image.rawBytes}:1"
        return mapOf(
            "stage.dir" to "dir 0 0 755\n",
            "stage.list" to (names.map { "f 0 644 10 $it" } + listOf("f 0 644 ${image.rawBytes} system.raw", "d 0 755 4096 data", "d 0 755 4096 bin", "f 0 755 30000 bin/oneos_root.sh"))
                .joinToString("\n", postfix = "\n"),
            "stage.marker" to StageMarker(versionCode, profile.id, image.id, set.id, 1_792_000_000).toJson(),
            "stage.sums" to ShaList.render(shas),
            "stage.hashes" to (shas.entries.joinToString("") { "${it.value}  $s/${it.key}\n" } + "${"c".repeat(64)}  $s/bin/oneos_root.sh\n"),
            "stage.raw" to "now ${image.rawBytes} 64773:1234:${image.rawBytes}:1792000000\nid ${image.rawBytes} ${image.rawSha256} $id\n",
            "stage.env" to SessionEnv.generate(profile),
            "stage.table" to "${"d".repeat(64)}  $s/host_installd.table\n",
            "stage.mounts" to "root ok\n",
            "stage.bridges" to "",
            "stage.loops" to "",
            "stage.session" to "boot=11111111-2222\n",
            "stage.guest" to "$guest\n",
            "stage.old" to "",
            "stage.tools" to "/data/adb/ksu/bin/busybox\n/data/adb/ksu/bin/resetprop\n",
            "stage.props" to "ro.csc.sales_code=KSA\nro.boot.sales_code=KSA\nro.csc.countryiso_code=SA\ngsm.sim.operator.iso-country=sa\n",
        ) + extra
    }
}
