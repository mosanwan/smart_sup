package com.smartsup.controller.model

enum class ThrottleGear(
    val label: String,
    val preferenceKey: String,
    val defaultThrottlePercent: Int,
) {
    Reverse3("后退 3", "reverse_3", -60),
    Reverse2("后退 2", "reverse_2", -40),
    Reverse1("后退 1", "reverse_1", -20),
    Neutral("空挡", "neutral", 0),
    Forward1("前进 1", "forward_1", 20),
    Forward2("前进 2", "forward_2", 40),
    Forward3("前进 3", "forward_3", 60),
    Forward4("前进 4", "forward_4", 80);

    val isReverse: Boolean
        get() = defaultThrottlePercent < 0

    val isForward: Boolean
        get() = defaultThrottlePercent > 0

    companion object {
        val Default = Neutral

        fun defaultPercents(): Map<ThrottleGear, Int> {
            return entries.associateWith { it.defaultThrottlePercent }
        }
    }
}
