package com.fahadali.oneos.session

import android.content.Context
import com.fahadali.oneos.BuildConfig
import com.fahadali.oneos.core.model.Hashes
import com.fahadali.oneos.core.model.PackageSet
import com.fahadali.oneos.core.model.PackageSets
import com.fahadali.oneos.core.model.ShaList
import com.fahadali.oneos.core.root.RootScript
import java.io.InputStream

/**
 * What the APK carries for build 2 (app/build.gradle.kts, task oneosFiles):
 * `assets/root/oneos_root.sh` (pinned in BuildConfig), the per-set lists
 * `assets/oneos/sets/<set>.sha256` and `scripts.sha256` (their hashes pinned
 * in BuildConfig too), and every file once under `assets/oneos/files/<sha256>`
 * with its size in `index.txt`. Every list is checked against its pin before
 * use; every file is hashed again while it streams to root, and root checks
 * it once more.
 */
object AppAssets {
    data class Staged(val name: String, val sha256: String, val bytes: Long)

    class Broken(message: String) : IllegalStateException(message)

    private fun read(ctx: Context, path: String): ByteArray = ctx.applicationContext.assets.open(path).use { it.readBytes() }

    /** The root script, exactly the pinned bytes. */
    fun rootScript(ctx: Context): ByteArray {
        val b = read(ctx, RootScript.ASSET)
        if (b.size.toLong() != BuildConfig.ROOT_SCRIPT_BYTES || Hashes.sha256(b) != BuildConfig.ROOT_SCRIPT_SHA256) {
            throw Broken("the root script in the app is not the pinned one")
        }
        return b
    }

    private fun pinned(set: String): String = when (set) {
        PackageSets.ID_N6_F8U -> BuildConfig.SET_N6_F8U_SHA256
        PackageSets.ID_X9U_SULTRA -> BuildConfig.SET_X9U_SULTRA_SHA256
        PackageSets.ID_PIXEL_F8U -> BuildConfig.SET_PIXEL_F8U_SHA256
        "scripts" -> BuildConfig.SCRIPTS_SHA256
        else -> throw Broken("no pin for $set")
    }

    private fun list(ctx: Context, id: String): Map<String, String> {
        val text = read(ctx, "oneos/sets/$id.sha256")
        if (Hashes.sha256(text) != pinned(id)) throw Broken("the app's $id list is not the pinned one")
        val parsed = ShaList.parse(text.toString(Charsets.UTF_8))
        if (parsed.problems.isNotEmpty()) throw Broken("$id: ${parsed.problems.first()}")
        return parsed.entries
    }

    private fun sizes(ctx: Context): Map<String, Long> =
        read(ctx, "oneos/files/index.txt").toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
            .associate { it.substringBefore(' ') to (it.substringAfter(' ').toLongOrNull() ?: -1) }

    /** The session scripts and host_installd.txt, then the set's files, in staging order. */
    fun files(ctx: Context, set: PackageSet): List<Staged> {
        val scripts = list(ctx, "scripts")
        val files = list(ctx, set.id)
        val problems = set.check(ShaList.render(files))
        if (problems.isNotEmpty()) throw Broken(problems.first())
        if (scripts.keys != RootScript.SCRIPTS.toSet()) throw Broken("the app's script list is not ${RootScript.SCRIPTS}")
        val size = sizes(ctx)
        return (RootScript.SCRIPTS.map { it to scripts.getValue(it) } + set.names.map { it to files.getValue(it) }).map { (n, sha) ->
            val b = size[sha] ?: throw Broken("$n has no size in the app's index")
            Staged(n, sha, b)
        }
    }

    fun open(ctx: Context, f: Staged): InputStream = ctx.applicationContext.assets.open("oneos/files/${f.sha256}")

    /** switcher/patches/host_installd.txt as the app ships it (the installd gate's expected table). */
    fun hostInstalldTxt(ctx: Context): String {
        val sha = list(ctx, "scripts")["host_installd.txt"] ?: throw Broken("no host_installd.txt in the app")
        val b = read(ctx, "oneos/files/$sha")
        if (Hashes.sha256(b) != sha) throw Broken("host_installd.txt in the app is damaged")
        return b.toString(Charsets.UTF_8)
    }
}
