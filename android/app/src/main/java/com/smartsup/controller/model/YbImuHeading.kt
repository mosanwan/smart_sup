package com.smartsup.controller.model

fun ybImuHeadingDegrees(
    telemetry: Telemetry,
    magneticDeclinationDegrees: Float = 0f,
): Float? {
    val controllerHeading = telemetry.ybHeadingDegrees ?: return null
    return normalizeCompassDegrees(controllerHeading + magneticDeclinationDegrees)
}

fun ybImuControllerHeadingDegrees(telemetry: Telemetry): Float? {
    return telemetry.ybHeadingDegrees?.let(::normalizeCompassDegrees)
}

fun normalizeCompassDegrees(degrees: Float): Float {
    return ((degrees % 360f) + 360f) % 360f
}

fun shortestHeadingDelta(target: Float, current: Float): Float {
    var delta = (target - current) % 360f
    if (delta > 180f) {
        delta -= 360f
    }
    if (delta < -180f) {
        delta += 360f
    }
    return delta
}
