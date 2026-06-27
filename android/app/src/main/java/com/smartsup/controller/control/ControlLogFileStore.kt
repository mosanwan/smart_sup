package com.smartsup.controller.control

import android.content.Context
import com.smartsup.controller.model.ControlLogEntry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONObject

class ControlLogFileStore(context: Context) {
    private val logDir = File(context.filesDir, "control_logs")
    private val logFile = File(logDir, "control_log.jsonl")

    init {
        logDir.mkdirs()
    }

    val filePath: String
        get() = logFile.absolutePath

    @Synchronized
    fun append(entry: ControlLogEntry) {
        logDir.mkdirs()
        val timestamp = Instant.ofEpochMilli(entry.timestampMs)
        val json = JSONObject()
            .put("id", entry.id)
            .put("timestamp_ms", entry.timestampMs)
            .put("timestamp_iso", timestamp.toString())
            .put("timestamp_local", LOCAL_TIME_FORMAT.format(timestamp.atZone(ZoneId.systemDefault())))
            .put("level", entry.level.name)
            .put("title", entry.title)
            .put("message", entry.message)
            .put("raw_line", entry.rawLine ?: "")
        logFile.appendText(json.toString() + "\n")
    }

    @Synchronized
    fun clear() {
        logDir.mkdirs()
        logFile.writeText("")
    }

    companion object {
        private val LOCAL_TIME_FORMAT: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z")
    }
}
