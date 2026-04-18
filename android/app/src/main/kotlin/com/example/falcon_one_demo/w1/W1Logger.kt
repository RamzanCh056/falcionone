package com.example.falcon_one_demo.w1

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Structured logs: Logcat tag [W1] + JSON lines + optional rolling file + ring buffer for UI export.
 */
class W1Logger(
    private val tag: String = "W1",
    private val logDir: File?,
    private val ringMax: Int = 800,
) {
    private val ring = ConcurrentLinkedQueue<String>()
    private val lineCounter = AtomicInteger(0)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun i(
        sessionId: String,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        emit("I", sessionId, event, fields, null)
    }

    fun w(
        sessionId: String,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        emit("W", sessionId, event, fields, null)
    }

    fun e(
        sessionId: String,
        event: String,
        fields: Map<String, Any?> = emptyMap(),
        error: Throwable? = null,
    ) {
        emit("E", sessionId, event, fields, error)
    }

    private fun emit(
        level: String,
        sessionId: String,
        event: String,
        fields: Map<String, Any?>,
        error: Throwable?,
    ) {
        val json = JSONObject()
        json.put("ts", dateFmt.format(Date()))
        json.put("lvl", level)
        json.put("seq", lineCounter.incrementAndGet())
        json.put("sessionId", sessionId)
        json.put("event", event)
        for ((k, v) in fields) {
            if (v != null) json.put(k, v)
        }
        if (error != null) {
            json.put("error", error.message ?: error.javaClass.simpleName)
        }
        val line = json.toString()
        ring.offer(line)
        while (ring.size > ringMax) {
            ring.poll()
        }
        when (level) {
            "I" -> Log.i(tag, line)
            "W" -> Log.w(tag, line)
            else -> Log.e(tag, line, error)
        }
        appendFileLine(line)
    }

    private fun appendFileLine(line: String) {
        val dir = logDir ?: return
        if (!dir.exists()) dir.mkdirs()
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val f = File(dir, "w1-$day.log")
        try {
            FileWriter(f, true).use { it.appendLine(line) }
        } catch (_: Exception) {
            // Never crash pipeline on logging IO failure.
        }
    }

    fun recentLines(limit: Int): List<String> {
        val all = ring.toList()
        if (all.size <= limit) return all
        return all.takeLast(limit)
    }

    fun exportRingToFile(out: File): Boolean {
        return try {
            out.parentFile?.mkdirs()
            out.writeText(ring.joinToString("\n"))
            true
        } catch (_: Exception) {
            false
        }
    }
}
