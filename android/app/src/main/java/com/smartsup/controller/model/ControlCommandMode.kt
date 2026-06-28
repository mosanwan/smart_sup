package com.smartsup.controller.model

enum class ControlCommandMode(val wireValue: String) {
    Throttle("THROTTLE"),
    TurnAngle("TURN"),
    HeadingLock("HEADING_LOCK"),
    StationKeep("STATION_KEEP"),
    KeepAlive("KEEPALIVE"),
}
