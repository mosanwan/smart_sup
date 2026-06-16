package com.smartsup.controller.control

import android.content.Context
import com.smartsup.controller.model.GpsTrackPoint
import java.io.File

class GpsTrackStore(context: Context) {
    private val trackDir = File(context.filesDir, "gps_tracks")
    private val pointFile = File(trackDir, "points.csv")
    private val knownKeys = mutableSetOf<String>()

    init {
        trackDir.mkdirs()
        if (!pointFile.exists()) {
            pointFile.writeText("session_id,seq,utc,lat_e7,lon_e7\n")
        }
        readPoints(limit = Int.MAX_VALUE).forEach { point ->
            knownKeys += point.key()
        }
    }

    fun appendIfNew(point: GpsTrackPoint): Boolean {
        val key = point.key()
        if (key in knownKeys) {
            return false
        }
        pointFile.appendText(
            "${point.sessionId},${point.sequence},${point.utcSeconds},${point.latitudeE7},${point.longitudeE7}\n",
        )
        knownKeys += key
        return true
    }

    fun lastSyncedSequence(): Int {
        return readPoints().maxOfOrNull { it.sequence } ?: 0
    }

    fun hasAnyPoint(): Boolean {
        return pointCount() > 0
    }

    fun readPoints(limit: Int = 2000): List<GpsTrackPoint> {
        if (!pointFile.exists()) {
            return emptyList()
        }
        return pointFile.useLines { lines ->
            lines.drop(1)
                .mapNotNull(::parsePointLine)
                .toList()
                .takeLast(limit)
        }
    }

    fun pointCount(): Int {
        if (!pointFile.exists()) {
            return 0
        }
        return pointFile.useLines { lines ->
            lines.drop(1).count()
        }
    }

    private fun parsePointLine(line: String): GpsTrackPoint? {
        val fields = line.split(',')
        if (fields.size != 5) {
            return null
        }
        return GpsTrackPoint(
            sessionId = fields[0].toIntOrNull() ?: return null,
            sequence = fields[1].toIntOrNull() ?: return null,
            utcSeconds = fields[2].toLongOrNull() ?: return null,
            latitudeE7 = fields[3].toIntOrNull() ?: return null,
            longitudeE7 = fields[4].toIntOrNull() ?: return null,
        )
    }

    private fun GpsTrackPoint.key(): String {
        return "$sessionId:$sequence"
    }
}
