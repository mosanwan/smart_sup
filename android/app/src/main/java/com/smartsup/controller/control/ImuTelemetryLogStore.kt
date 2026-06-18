package com.smartsup.controller.control

import android.content.Context
import com.smartsup.controller.model.Telemetry
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ImuTelemetryLogStore(context: Context) {
    private val logDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "imu_logs")
    private val logFile: File
    private var sampleCount = 0

    init {
        logDir.mkdirs()
        val fileStamp = FILE_TIME_FORMAT.format(Instant.now().atZone(ZoneId.systemDefault()))
        logFile = File(logDir, "imu_$fileStamp.csv")
        if (!logFile.exists()) {
            logFile.writeText(LOG_COLUMNS.joinToString(",") + "\n")
        }
    }

    val filePath: String
        get() = logFile.absolutePath

    fun append(telemetry: Telemetry, phoneHeadingDegrees: Float?): ImuTelemetryLogSnapshot? {
        val rawStatus = telemetry.lastReceivedStatus
        if (!rawStatus.startsWith("STATUS;")) {
            return null
        }
        val fields = telemetry.statusFields
        if (!fields.containsKey("IHDG") && !fields.containsKey("IMU")) {
            return null
        }

        val nowMs = System.currentTimeMillis()
        val row = LOG_COLUMNS.map { column ->
            when (column) {
                "phone_time_ms" -> nowMs.toString()
                "phone_time_iso" -> Instant.ofEpochMilli(nowMs).toString()
                "phone_time_local" -> LOCAL_TIME_FORMAT.format(Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()))
                "phone_heading_deg" -> phoneHeadingDegrees?.toString().orEmpty()
                "raw_status" -> rawStatus
                else -> fields[column].orEmpty()
            }.csvCell()
        }
        logFile.appendText(row.joinToString(",") + "\n")
        sampleCount += 1
        return ImuTelemetryLogSnapshot(
            filePath = filePath,
            sampleCount = sampleCount,
        )
    }

    private fun String.csvCell(): String {
        if (none { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            return this
        }
        return "\"" + replace("\"", "\"\"") + "\""
    }

    companion object {
        private val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        private val LOCAL_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS z")
        private val LOG_COLUMNS = listOf(
            "phone_time_ms",
            "phone_time_iso",
            "phone_time_local",
            "T",
            "GPS_TIME",
            "phone_heading_deg",
            "IHDG",
            "IMHDG",
            "IAHRS",
            "IROLL",
            "IPITCH",
            "IQUAL",
            "HSRC",
            "HDG",
            "PHDG",
            "PHDG_AGE",
            "IAX",
            "IAY",
            "IAZ",
            "IAN",
            "IGX",
            "IGY",
            "IGZ",
            "IGZB",
            "IMX",
            "IMY",
            "IMZ",
            "IMAG",
            "MCAL",
            "MCNT",
            "MRX",
            "MRY",
            "IMU",
            "MAG",
            "GPS",
            "PPS",
            "GPS_FIX",
            "GPS_SAT",
            "GPS_LAT",
            "GPS_LON",
            "GPS_SPD_KMH",
            "GPS_ANT",
            "ARMED",
            "L",
            "R",
            "LPWM",
            "RPWM",
            "CMD_SRC",
            "MODE",
            "TURN",
            "HLOCK",
            "TARGET",
            "TERR",
            "HERR",
            "HCORR",
            "raw_status",
        )
    }
}

data class ImuTelemetryLogSnapshot(
    val filePath: String,
    val sampleCount: Int,
)
