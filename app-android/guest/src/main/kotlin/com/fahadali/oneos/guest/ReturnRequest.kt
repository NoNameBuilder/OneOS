package com.fahadali.oneos.guest

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The request to go back to One UI: `files/oneos_return` in this app's own
 * data folder, `/data/user/0/com.fahadali.oneos.guest/files/oneos_return` in
 * the guest. The guest's /data is the stage's data tree, so the session's
 * watcher on the host (oneos_switch.sh) finds it at
 * `/data/local/tmp/oneos/data/user/0/com.fahadali.oneos.guest/files/oneos_return`,
 * checks for it every second, deletes it and returns. Only this app (and
 * root) can write there; nothing else is needed, no permission.
 *
 * One line: the reason ([REASON_APP] or [REASON_TILE]) and the time in epoch
 * seconds.
 */
object ReturnRequest {
    const val FILE_NAME = "oneos_return"
    const val REASON_APP = "app"
    const val REASON_TILE = "tile"

    fun pending(context: Context): Boolean = File(context.filesDir, FILE_NAME).isFile

    /** Written to a temporary name and renamed, so the watcher never reads half of it. */
    @Throws(IOException::class)
    fun send(context: Context, reason: String) {
        val dir = context.filesDir
        val tmp = File(dir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { out ->
            out.write("$reason ${System.currentTimeMillis() / 1000}\n".toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(File(dir, FILE_NAME))) {
            tmp.delete()
            throw IOException("could not create ${File(dir, FILE_NAME)}")
        }
    }
}
