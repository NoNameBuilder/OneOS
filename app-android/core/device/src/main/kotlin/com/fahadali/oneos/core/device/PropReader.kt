package com.fahadali.oneos.core.device

import android.annotation.SuppressLint
import com.fahadali.oneos.core.model.Getprop
import java.lang.reflect.Method
import java.util.concurrent.TimeUnit

/**
 * Reads system properties without root and without writing anything:
 * `android.os.SystemProperties.get` by reflection, and one `getprop` dump as
 * the fallback when reflection is blocked or returns nothing. SELinux may hide
 * some props from apps; those read as "".
 */
class PropReader {
    @SuppressLint("PrivateApi")
    private val getMethod: Method? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
    }.getOrNull()

    private val dump: Map<String, String> by lazy { readGetprop() }

    fun get(key: String): String {
        val viaReflection = getMethod?.let { m -> runCatching { m.invoke(null, key) as? String }.getOrNull() }
        if (!viaReflection.isNullOrEmpty()) return viaReflection
        return dump[key].orEmpty()
    }

    /** First non-empty value of [keys]. */
    fun first(vararg keys: String): String = keys.firstNotNullOfOrNull { k -> get(k).ifEmpty { null } }.orEmpty()

    private fun readGetprop(): Map<String, String> = runCatching {
        val proc = ProcessBuilder("/system/bin/getprop").redirectErrorStream(true).start()
        try {
            proc.outputStream.close()
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor(5, TimeUnit.SECONDS)
            Getprop.parse(text)
        } finally {
            proc.destroy()
        }
    }.getOrDefault(emptyMap())
}
