package com.smartsup.controller.model

data class NavigationRoutePoint(
    val latitude: Double,
    val longitude: Double,
)

data class NavigationRoute(
    val id: String,
    val name: String,
    val createdAtEpochSeconds: Long,
    val updatedAtEpochSeconds: Long,
    val points: List<NavigationRoutePoint>,
)

data class AutoNavigationUiState(
    val routes: List<NavigationRoute> = emptyList(),
    val selectedRouteId: String? = null,
    val editingRouteId: String? = null,
    val editingRouteName: String = "",
    val editingPoints: List<NavigationRoutePoint> = emptyList(),
    val editingNewRoute: Boolean = false,
    val executingRouteId: String? = null,
    val targetPointIndex: Int = 0,
    val gearIndex: Int = 0,
    val distanceToTargetMeters: Double? = null,
    val headingErrorDegrees: Float? = null,
    val trackLineLockEnabled: Boolean = false,
    val trackLineOrigin: NavigationRoutePoint? = null,
    val trackLineBearingDegrees: Float? = null,
    val trackLineTargetHeadingDegrees: Float? = null,
    val trackLineCrossTrackErrorMeters: Double? = null,
    val trackLineAlongTrackMeters: Double? = null,
    val stationKeepingEnabled: Boolean = false,
    val stationKeepingTarget: NavigationRoutePoint? = null,
    val stationKeepingTargetHeadingDegrees: Float? = null,
    val stationKeepingForwardErrorMeters: Double? = null,
    val stationKeepingLateralErrorMeters: Double? = null,
    val stationKeepingPositionActive: Boolean = false,
    val leftOutputPercent: Int = 0,
    val rightOutputPercent: Int = 0,
    val message: String = "自动导航未启动",
) {
    val editing: Boolean
        get() = editingRouteId != null

    val executing: Boolean
        get() = executingRouteId != null

    val active: Boolean
        get() = executing || trackLineLockEnabled || stationKeepingEnabled
}
