package com.smartsup.controller.control

import android.content.Context
import com.smartsup.controller.model.GpsTrackPoint
import com.smartsup.controller.model.GpsTrackSegment
import java.io.File

class GpsTrackStore(context: Context) {
    private val trackDir = File(context.filesDir, "gps_tracks")
    private val pointFile = File(trackDir, "points.csv")
    private val syncedSequenceFile = File(trackDir, "last_synced_seq.txt")
    private val knownKeys = mutableSetOf<String>()

    init {
        trackDir.mkdirs()
        if (!pointFile.exists()) {
            pointFile.writeText("session_id,seq,utc,lat_e7,lon_e7\n")
        }
        readAllPoints().forEach { point ->
            knownKeys += point.key()
        }
    }

    fun appendIfNew(point: GpsTrackPoint): Boolean {
        rememberSyncedSequence(point.sequence)
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
        return maxOf(readAllPoints().maxOfOrNull { it.sequence } ?: 0, readRememberedSyncedSequence())
    }

    fun hasAnyPoint(): Boolean {
        return pointCount() > 0
    }

    fun readPoints(limit: Int = TRACK_POINT_LOAD_LIMIT): List<GpsTrackPoint> {
        return readAllPoints().takeLast(limit)
    }

    fun readTracks(): List<GpsTrackSegment> {
        return buildTrackGroups(readAllPoints()).map { it.segment }
    }

    fun readTrackPoints(trackId: String?, limit: Int = TRACK_POINT_LOAD_LIMIT): List<GpsTrackPoint> {
        val groups = buildTrackGroups(readAllPoints())
        val selected = trackId?.let { id -> groups.firstOrNull { it.segment.id == id } } ?: groups.lastOrNull()
        return selected?.points.orEmpty().takeLast(limit)
    }

    fun deleteTrack(trackId: String): Boolean {
        val points = readAllPoints()
        val groups = buildTrackGroups(points)
        val removedKeys = groups.firstOrNull { it.segment.id == trackId }
            ?.points
            ?.mapTo(mutableSetOf()) { it.key() }
            ?: return false
        val remaining = points.filterNot { it.key() in removedKeys }

        pointFile.writeText(TRACK_HEADER)
        remaining.forEach { point ->
            pointFile.appendText(point.csvLine())
        }
        knownKeys.clear()
        remaining.forEach { knownKeys += it.key() }
        return true
    }

    private fun readAllPoints(): List<GpsTrackPoint> {
        if (!pointFile.exists()) {
            return emptyList()
        }
        return pointFile.useLines { lines ->
            lines.drop(1)
                .mapNotNull(::parsePointLine)
                .sortedWith(compareBy<GpsTrackPoint> { it.utcSeconds }.thenBy { it.sequence })
                .toList()
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

    private fun GpsTrackPoint.csvLine(): String {
        return "$sessionId,$sequence,$utcSeconds,$latitudeE7,$longitudeE7\n"
    }

    private fun rememberSyncedSequence(sequence: Int) {
        if (sequence > readRememberedSyncedSequence()) {
            syncedSequenceFile.writeText(sequence.toString())
        }
    }

    private fun readRememberedSyncedSequence(): Int {
        return syncedSequenceFile.takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.toIntOrNull()
            ?: 0
    }

    private fun buildTrackGroups(points: List<GpsTrackPoint>): List<TrackGroup> {
        if (points.isEmpty()) {
            return emptyList()
        }

        val groups = mutableListOf<TrackGroup>()
        var current = mutableListOf<GpsTrackPoint>()
        points.forEach { point ->
            val previous = current.lastOrNull()
            if (previous != null && point.utcSeconds - previous.utcSeconds > TRACK_SPLIT_GAP_SECONDS) {
                groups += current.toTrackGroup()
                current = mutableListOf()
            }
            current += point
        }
        if (current.isNotEmpty()) {
            groups += current.toTrackGroup()
        }
        return groups
    }

    private fun List<GpsTrackPoint>.toTrackGroup(): TrackGroup {
        val first = first()
        val last = last()
        return TrackGroup(
            segment = GpsTrackSegment(
                id = "${first.utcSeconds}:${first.sessionId}:${first.sequence}",
                startUtcSeconds = first.utcSeconds,
                endUtcSeconds = last.utcSeconds,
                pointCount = size,
            ),
            points = this,
        )
    }

    private data class TrackGroup(
        val segment: GpsTrackSegment,
        val points: List<GpsTrackPoint>,
    )

    companion object {
        private const val TRACK_HEADER = "session_id,seq,utc,lat_e7,lon_e7\n"
        private const val TRACK_SPLIT_GAP_SECONDS = 2L * 3600L
        private const val TRACK_POINT_LOAD_LIMIT = 20_000
    }
}
