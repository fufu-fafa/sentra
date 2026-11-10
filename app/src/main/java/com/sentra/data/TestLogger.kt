package com.sentra.data

import android.content.Context
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves what the tests of Desain Teknis Bab 10 need as CSV, one file per trial: obstacle distance
 * readings (§10.2), command-to-ACK round trips (§10.3) and the GPS track (§10.5).
 *
 * Rows: t_ms since the start, kind, then
 *   D    cm
 *   CMD  command
 *   ACK  command, round trip in ms
 *   GPS  lat, lon, accuracy m, speed m/s
 *   NAV  what the navigation did (command or event)
 *   LINK CONNECTED / CONNECTING / DISCONNECTED
 */
class TestLogger(context: Context) {
    val dir = File(context.filesDir, "recordings").apply { mkdirs() }

    private var out: BufferedWriter? = null
    private var startedAt = 0L
    var current: File? = null
        private set

    @Synchronized
    fun start(label: String): File {
        stop()
        val safe = label.trim().ifEmpty { "uji" }.replace(Regex("[^A-Za-z0-9_-]+"), "_").take(40)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "${stamp}_$safe.csv")
        out = file.bufferedWriter().apply { write("t_ms,kind,a,b,c,d\n") }
        startedAt = System.currentTimeMillis()
        current = file
        return file
    }

    @Synchronized
    fun log(kind: String, vararg values: Any?) {
        val w = out ?: return
        val t = System.currentTimeMillis() - startedAt
        w.write("$t,$kind,${values.joinToString(",") { it?.toString()?.replace(',', ' ') ?: "" }}\n")
    }

    @Synchronized
    fun stop() {
        out?.close()
        out = null
        current = null
    }

    fun list(): List<File> = dir.listFiles { f -> f.extension == "csv" }.orEmpty().sortedByDescending { it.name }
}
