package com.smartsup.controller.model

data class GpsTrackPoint(
    val sequence: Int,
    val sessionId: Int,
    val utcSeconds: Long,
    val latitudeE7: Int,
    val longitudeE7: Int,
) {
    val latitude: Double
        get() = latitudeE7 / 10_000_000.0

    val longitude: Double
        get() = longitudeE7 / 10_000_000.0
}

data class TrackLogInfo(
    val recordSize: Int,
    val capacity: Int,
    val count: Int,
    val oldestSequence: Int,
    val newestSequence: Int,
    val sessionId: Int,
    val droppedInvalid: Int,
    val droppedDrift: Int,
    val writeErrors: Int,
)

data class GpsTrackSegment(
    val id: String,
    val startUtcSeconds: Long,
    val endUtcSeconds: Long,
    val pointCount: Int,
)

sealed interface TrackLogEvent {
    data class Info(val info: TrackLogInfo) : TrackLogEvent
    data class Begin(val fromSequence: Int, val count: Int) : TrackLogEvent
    data class Point(val point: GpsTrackPoint) : TrackLogEvent
    data class End(val nextSequence: Int) : TrackLogEvent
    data class Error(val message: String) : TrackLogEvent
}

data class GpsTrackUiState(
    val storedPointCount: Int = 0,
    val lastSyncedSequence: Int = 0,
    val syncMessage: String = "轨迹同步未开始",
    val syncing: Boolean = false,
    val syncStartSequence: Int? = null,
    val syncTargetSequence: Int? = null,
    val syncCurrentSequence: Int? = null,
    val latestInfo: TrackLogInfo? = null,
    val tracks: List<GpsTrackSegment> = emptyList(),
    val selectedTrackId: String? = null,
    val recentPoints: List<GpsTrackPoint> = emptyList(),
    val playbackIndex: Int = 0,
)
