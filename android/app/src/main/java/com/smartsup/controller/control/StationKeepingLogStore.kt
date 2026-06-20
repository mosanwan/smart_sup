package com.smartsup.controller.control

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class StationKeepingLogStore(context: Context) {
    private val logDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "station_keeping_logs")
    private var logFile: File? = null
    private var sampleCount = 0

    init {
        logDir.mkdirs()
    }

    val filePath: String
        get() = logFile?.absolutePath.orEmpty()

    fun startSession(): StationKeepingLogSnapshot {
        val fileStamp = FILE_TIME_FORMAT.format(Instant.now().atZone(ZoneId.systemDefault()))
        val nextFile = File(logDir, "station_keeping_$fileStamp.csv")
        nextFile.writeText(LOG_COLUMNS.joinToString(",") + "\n")
        logFile = nextFile
        sampleCount = 0
        return StationKeepingLogSnapshot(
            filePath = nextFile.absolutePath,
            sampleCount = sampleCount,
        )
    }

    fun append(entry: StationKeepingLogEntry): StationKeepingLogSnapshot {
        val file = logFile ?: startSession().let { logFile!! }
        val row = LOG_COLUMNS.map { column ->
            when (column) {
                "phone_time_ms" -> entry.phoneTimeMs.toString()
                "phone_time_iso" -> Instant.ofEpochMilli(entry.phoneTimeMs).toString()
                "phone_time_local" -> LOCAL_TIME_FORMAT.format(
                    Instant.ofEpochMilli(entry.phoneTimeMs).atZone(ZoneId.systemDefault()),
                )
                "reason" -> entry.reason
                "gps_fix" -> entry.gpsFix
                "gps_sat" -> entry.gpsSat.toString()
                "gps_speed_kmh" -> entry.gpsSpeedKmh
                "current_lat" -> entry.currentLatitude.toString()
                "current_lon" -> entry.currentLongitude.toString()
                "target_lat" -> entry.targetLatitude.toString()
                "target_lon" -> entry.targetLongitude.toString()
                "target_heading_deg" -> entry.targetHeadingDegrees.toString()
                "current_heading_deg" -> entry.currentHeadingDegrees.toString()
                "desired_heading_deg" -> entry.desiredHeadingDegrees.toString()
                "heading_offset_deg" -> entry.headingOffsetDegrees.toString()
                "heading_error_deg" -> entry.headingErrorDegrees.toString()
                "distance_m" -> entry.distanceMeters.toString()
                "forward_error_m" -> entry.forwardErrorMeters.toString()
                "lateral_error_m" -> entry.lateralErrorMeters.toString()
                "position_active" -> if (entry.positionActive) "1" else "0"
                "moving_reverse" -> if (entry.movingReverse) "1" else "0"
                "gear_index" -> entry.gearIndex.toString()
                "output_limit_pct" -> entry.outputLimitPercent.toString()
                "requested_base_pct" -> entry.requestedBasePercent.toString()
                "base_pct" -> entry.basePercent.toString()
                "base_correction_pct" -> entry.baseCorrectionPercent.toString()
                "left_output_pct" -> entry.leftOutputPercent.toString()
                "right_output_pct" -> entry.rightOutputPercent.toString()
                "left_command_pct" -> entry.leftCommandPercent.toString()
                "right_command_pct" -> entry.rightCommandPercent.toString()
                "status_armed" -> entry.statusArmed
                "status_l" -> entry.statusLeft
                "status_r" -> entry.statusRight
                "status_lpwm" -> entry.statusLeftPwm
                "status_rpwm" -> entry.statusRightPwm
                "raw_status" -> entry.rawStatus
                else -> ""
            }.csvCell()
        }
        file.appendText(row.joinToString(",") + "\n")
        sampleCount += 1
        return StationKeepingLogSnapshot(
            filePath = file.absolutePath,
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
            "reason",
            "gps_fix",
            "gps_sat",
            "gps_speed_kmh",
            "current_lat",
            "current_lon",
            "target_lat",
            "target_lon",
            "target_heading_deg",
            "current_heading_deg",
            "desired_heading_deg",
            "heading_offset_deg",
            "heading_error_deg",
            "distance_m",
            "forward_error_m",
            "lateral_error_m",
            "position_active",
            "moving_reverse",
            "gear_index",
            "output_limit_pct",
            "requested_base_pct",
            "base_pct",
            "base_correction_pct",
            "left_output_pct",
            "right_output_pct",
            "left_command_pct",
            "right_command_pct",
            "status_armed",
            "status_l",
            "status_r",
            "status_lpwm",
            "status_rpwm",
            "raw_status",
        )
    }
}

data class StationKeepingLogEntry(
    val phoneTimeMs: Long,
    val reason: String,
    val gpsFix: String,
    val gpsSat: Int,
    val gpsSpeedKmh: String,
    val currentLatitude: Double,
    val currentLongitude: Double,
    val targetLatitude: Double,
    val targetLongitude: Double,
    val targetHeadingDegrees: Float,
    val currentHeadingDegrees: Float,
    val desiredHeadingDegrees: Float,
    val headingOffsetDegrees: Double,
    val headingErrorDegrees: Float,
    val distanceMeters: Double,
    val forwardErrorMeters: Double,
    val lateralErrorMeters: Double,
    val positionActive: Boolean,
    val movingReverse: Boolean,
    val gearIndex: Int,
    val outputLimitPercent: Int,
    val requestedBasePercent: Int,
    val basePercent: Int,
    val baseCorrectionPercent: Int,
    val leftOutputPercent: Int,
    val rightOutputPercent: Int,
    val leftCommandPercent: Int,
    val rightCommandPercent: Int,
    val statusArmed: String,
    val statusLeft: String,
    val statusRight: String,
    val statusLeftPwm: String,
    val statusRightPwm: String,
    val rawStatus: String,
)

data class StationKeepingLogSnapshot(
    val filePath: String,
    val sampleCount: Int,
)
