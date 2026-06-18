package com.smartsup.controller.voice

import org.json.JSONArray
import org.json.JSONObject

sealed interface RealtimeVoiceControlEvent {
    val reason: String

    data class Stop(
        override val reason: String,
    ) : RealtimeVoiceControlEvent

    data class SetGear(
        val gear: String,
        override val reason: String,
    ) : RealtimeVoiceControlEvent

    data class PivotTurn(
        val direction: String,
        override val reason: String,
    ) : RealtimeVoiceControlEvent

    data class SetLimitedPower(
        val leftPercent: Int,
        val rightPercent: Int,
        val durationMs: Int?,
        override val reason: String,
    ) : RealtimeVoiceControlEvent

    data class AdjustHeadingTarget(
        val deltaDegrees: Int,
        val basePowerPercent: Int,
        val durationMs: Int,
        override val reason: String,
    ) : RealtimeVoiceControlEvent

    data class SetHeadingTarget(
        val targetHeadingDegrees: Int,
        val basePowerPercent: Int,
        override val reason: String,
    ) : RealtimeVoiceControlEvent

    data class CancelHeadingLock(
        override val reason: String,
    ) : RealtimeVoiceControlEvent

    data class DisableVoiceControl(
        override val reason: String,
    ) : RealtimeVoiceControlEvent

    data class ExplainStatus(
        override val reason: String,
    ) : RealtimeVoiceControlEvent

    companion object {
        fun parse(jsonText: String): Result<RealtimeVoiceControlEvent> = runCatching {
            val json = JSONObject(jsonText)
            parseToolCall(json) ?: parseLegacyControlEvent(json)
        }

        fun toolDefinitions(): JSONArray {
            return JSONArray()
                .put(
                    functionTool(
                        name = TOOL_STOP,
                        description = "停/停止/停下/空档/空挡。只把左右推进器归零，保持当前主控解锁状态，不锁定主控",
                        properties = JSONObject()
                            .put("reason", stringProperty("原因")),
                        required = JSONArray().put("reason"),
                    ),
                )
                .put(
                    functionTool(
                        name = TOOL_SET_GEAR,
                        description = "设置固定档位。前进1/前进一档=forward_1，前进2=forward_2，前进3=forward_3，前进4=forward_4，后退/后退1/倒车=reverse_1，后退2=reverse_2，后退3=reverse_3，空档/停止=neutral",
                        properties = JSONObject()
                            .put("gear", enumProperty("档位", GEAR_VALUES))
                            .put("reason", stringProperty("原因")),
                        required = JSONArray()
                            .put("gear")
                            .put("reason"),
                    ),
                )
                .put(
                    functionTool(
                        name = TOOL_PIVOT_TURN,
                        description = "原地掉头/原地转向。左原地掉头=left，右原地掉头=right。工具内部会先空挡1秒，再用空挡航向锁定执行掉头",
                        properties = JSONObject()
                            .put("direction", enumProperty("方向", DIRECTION_VALUES))
                            .put("reason", stringProperty("原因")),
                        required = JSONArray()
                            .put("direction")
                            .put("reason"),
                    ),
                )
                .put(
                    functionTool(
                        name = TOOL_SET_LIMITED_POWER,
                        description = "直接设置左右推进百分比。仅用于用户明确要求左右推进器百分比；前进为正，后退为负；不需要持续时间",
                        properties = JSONObject()
                            .put("left_percent", numberProperty("左推进百分比，-100到100"))
                            .put("right_percent", numberProperty("右推进百分比，-100到100"))
                            .put("duration_ms", numberProperty("兼容旧字段，可不填"))
                            .put("reason", stringProperty("原因")),
                        required = JSONArray()
                            .put("left_percent")
                            .put("right_percent")
                            .put("reason"),
                    ),
                )
                .put(
                    functionTool(
                        name = TOOL_ADJUST_HEADING_TARGET,
                        description = "相对角度转向。只用于左转/右转多少度；左转必须给负数，右转必须给正数。不要用于目标航向",
                        properties = JSONObject()
                            .put("delta_deg", numberProperty("相对角度，-90到90"))
                            .put("base_power_percent", numberProperty("基础推进百分比，通常0到30"))
                            .put("duration_ms", numberProperty("持续毫秒，300到5000"))
                            .put("reason", stringProperty("原因")),
                        required = JSONArray()
                            .put("delta_deg")
                            .put("base_power_percent")
                            .put("duration_ms")
                            .put("reason"),
                    ),
                )
                .put(
                    functionTool(
                        name = TOOL_SET_HEADING_TARGET,
                        description = "设置绝对目标航向并持续进入航向锁定。用于目标航向/航向设为/保持多少度",
                        properties = JSONObject()
                            .put("target_heading_deg", numberProperty("绝对目标航向，0到359，北0东90南180西270"))
                            .put("base_power_percent", numberProperty("基础推进百分比，通常0到30；不确定用0"))
                            .put("reason", stringProperty("原因")),
                        required = JSONArray()
                            .put("target_heading_deg")
                            .put("base_power_percent")
                            .put("reason"),
                    ),
                )
                .put(
                    functionTool(
                        name = TOOL_CANCEL_HEADING_LOCK,
                        description = "只取消航向锁定，不解锁推进、不改变安全保护。用于取消/退出/解除航向锁定",
                        properties = JSONObject()
                            .put("reason", stringProperty("原因")),
                        required = JSONArray().put("reason"),
                    ),
                )
                .put(
                    functionTool(
                        name = TOOL_DISABLE_VOICE_CONTROL,
                        description = "关闭实时语音/停止声控/关闭声控。只关闭语音会话，不改变推进输出、不锁定主控、不取消航向锁定",
                        properties = JSONObject()
                            .put("reason", stringProperty("原因")),
                        required = JSONArray().put("reason"),
                    ),
                )
                .put(
                    functionTool(
                        name = TOOL_EXPLAIN_STATUS,
                        description = "只说明能力或状态，不改变推进输出",
                        properties = JSONObject()
                            .put("reason", stringProperty("原因")),
                        required = JSONArray().put("reason"),
                    ),
                )
        }

        private fun parseToolCall(json: JSONObject): RealtimeVoiceControlEvent? {
            parseToolCallObject(json)?.let { return it }
            val toolCalls = json.optJSONArray("tool_calls") ?: json.optJSONArray("toolCalls")
            if (toolCalls != null) {
                parseToolCallArray(toolCalls)?.let { return it }
            }
            json.optJSONObject("tool_call")?.let { parseToolCallObject(it) }?.let { return it }
            json.optJSONObject("function_call")?.let { parseToolCallObject(it) }?.let { return it }
            val choices = json.optJSONArray("choices")
            if (choices != null) {
                for (index in 0 until choices.length()) {
                    val message = choices.optJSONObject(index)?.optJSONObject("message") ?: continue
                    parseToolCall(message)?.let { return it }
                }
            }
            return null
        }

        private fun parseToolCallArray(toolCalls: JSONArray): RealtimeVoiceControlEvent? {
            for (index in 0 until toolCalls.length()) {
                val event = parseToolCallObject(toolCalls.optJSONObject(index) ?: continue)
                if (event != null) {
                    return event
                }
            }
            return null
        }

        private fun parseToolCallObject(json: JSONObject): RealtimeVoiceControlEvent? {
            val function = json.optJSONObject("function")
            val name = json.optString("name")
                .ifBlank { function?.optString("name").orEmpty() }
                .ifBlank { json.optString("function_name") }
            if (name.isBlank()) {
                return null
            }
            val arguments = parseArguments(
                json.opt("arguments")
                    ?: function?.opt("arguments")
                    ?: json.opt("args")
                    ?: JSONObject(),
            )
            return eventFromFunction(name, arguments)
        }

        private fun parseArguments(value: Any): JSONObject {
            return when (value) {
                is JSONObject -> value
                is String -> if (value.isBlank()) JSONObject() else JSONObject(value)
                else -> JSONObject()
            }
        }

        private fun eventFromFunction(
            name: String,
            args: JSONObject,
        ): RealtimeVoiceControlEvent {
            val reason = args.optString("reason").ifBlank { "云端实时语音工具调用" }
            return when (name) {
                TOOL_STOP -> Stop(reason)
                TOOL_SET_GEAR -> SetGear(
                    gear = normalizeGear(args.getString("gear")),
                    reason = reason,
                )
                TOOL_PIVOT_TURN -> PivotTurn(
                    direction = normalizeDirection(args.getString("direction")),
                    reason = reason,
                )
                TOOL_SET_LIMITED_POWER -> SetLimitedPower(
                    leftPercent = args.getInt("left_percent").coerceIn(-100, 100),
                    rightPercent = args.getInt("right_percent").coerceIn(-100, 100),
                    durationMs = args.optionalDurationMs(),
                    reason = reason,
                )
                TOOL_ADJUST_HEADING_TARGET -> AdjustHeadingTarget(
                    deltaDegrees = args.getInt("delta_deg").coerceIn(-90, 90),
                    basePowerPercent = args.optInt("base_power_percent", 0).coerceIn(-100, 100),
                    durationMs = args.optInt("duration_ms", DEFAULT_DURATION_MS)
                        .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS),
                    reason = reason,
                )
                TOOL_SET_HEADING_TARGET -> SetHeadingTarget(
                    targetHeadingDegrees = normalizeHeadingDegrees(args.getDouble("target_heading_deg")),
                    basePowerPercent = args.optInt("base_power_percent", 0).coerceIn(-100, 100),
                    reason = reason,
                )
                TOOL_CANCEL_HEADING_LOCK -> CancelHeadingLock(reason)
                TOOL_DISABLE_VOICE_CONTROL -> DisableVoiceControl(reason)
                TOOL_EXPLAIN_STATUS -> ExplainStatus(reason)
                else -> error("不支持的实时语音工具：$name")
            }
        }

        private fun parseLegacyControlEvent(json: JSONObject): RealtimeVoiceControlEvent {
            require(json.optString("type") == "control_event") {
                "必须是 function_call/tool_call，或兼容的 control_event"
            }
            val reason = json.optString("reason").ifBlank { "云端实时语音事件" }
            return when (val action = json.optString("action")) {
                "stop" -> Stop(reason)
                "set_gear" -> SetGear(
                    gear = normalizeGear(json.getString("gear")),
                    reason = reason,
                )
                "pivot_turn" -> PivotTurn(
                    direction = normalizeDirection(json.getString("direction")),
                    reason = reason,
                )
                "set_limited_power" -> SetLimitedPower(
                    leftPercent = json.getInt("left_percent").coerceIn(-100, 100),
                    rightPercent = json.getInt("right_percent").coerceIn(-100, 100),
                    durationMs = json.optionalDurationMs(),
                    reason = reason,
                )
                "adjust_heading_target" -> AdjustHeadingTarget(
                    deltaDegrees = json.getInt("delta_deg").coerceIn(-90, 90),
                    basePowerPercent = json.optInt("base_power_percent", 0).coerceIn(-100, 100),
                    durationMs = json.optInt("duration_ms", DEFAULT_DURATION_MS)
                        .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS),
                    reason = reason,
                )
                "set_heading_target" -> SetHeadingTarget(
                    targetHeadingDegrees = normalizeHeadingDegrees(json.getDouble("target_heading_deg")),
                    basePowerPercent = json.optInt("base_power_percent", 0).coerceIn(-100, 100),
                    reason = reason,
                )
                "cancel_heading_lock" -> CancelHeadingLock(reason)
                "disable_voice_control" -> DisableVoiceControl(reason)
                "explain_status" -> ExplainStatus(reason)
                else -> error("不支持的实时语音动作：$action")
            }
        }

        private fun functionTool(
            name: String,
            description: String,
            properties: JSONObject,
            required: JSONArray,
        ): JSONObject {
            return JSONObject()
                .put("type", "function")
                .put(
                    "function",
                    JSONObject()
                        .put("name", name)
                        .put("description", description)
                        .put(
                            "parameters",
                            JSONObject()
                                .put("type", "object")
                                .put("properties", properties)
                                .put("required", required),
                        ),
                )
        }

        private fun stringProperty(description: String): JSONObject {
            return JSONObject()
                .put("type", "string")
                .put("description", description)
        }

        private fun numberProperty(description: String): JSONObject {
            return JSONObject()
                .put("type", "number")
                .put("description", description)
        }

        private fun enumProperty(description: String, values: Array<String>): JSONObject {
            val enumValues = JSONArray()
            values.forEach { enumValues.put(it) }
            return JSONObject()
                .put("type", "string")
                .put("enum", enumValues)
                .put("description", description)
        }

        private fun normalizeHeadingDegrees(value: Double): Int {
            val normalized = value.mod(360.0).let { if (it < 0.0) it + 360.0 else it }
            return normalized.toInt().coerceIn(0, 359)
        }

        private fun JSONObject.optionalDurationMs(): Int? {
            return if (has("duration_ms") && !isNull("duration_ms")) {
                optInt("duration_ms", DEFAULT_DURATION_MS)
                    .coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
            } else {
                null
            }
        }

        private fun normalizeGear(value: String): String {
            return when (value.lowercase().replace("-", "_").replace(" ", "_")) {
                "forward_1", "forward1", "前进1", "前进一", "前进一档" -> "forward_1"
                "forward_2", "forward2", "前进2", "前进二", "前进二档" -> "forward_2"
                "forward_3", "forward3", "前进3", "前进三", "前进三档" -> "forward_3"
                "forward_4", "forward4", "前进4", "前进四", "前进四档" -> "forward_4"
                "reverse_1", "reverse1", "后退", "后退1", "后退一", "后退一档", "倒车", "倒车1" -> "reverse_1"
                "reverse_2", "reverse2", "后退2", "后退二", "后退二档", "倒车2" -> "reverse_2"
                "reverse_3", "reverse3", "后退3", "后退三", "后退三档", "倒车3" -> "reverse_3"
                "neutral", "空档", "空挡", "停止", "停" -> "neutral"
                else -> error("不支持的档位：$value")
            }
        }

        private fun normalizeDirection(value: String): String {
            return when (value.lowercase()) {
                "left", "左", "左转", "左原地掉头", "左掉头" -> "left"
                "right", "右", "右转", "右原地掉头", "右掉头" -> "right"
                else -> error("不支持的原地掉头方向：$value")
            }
        }

        private const val MIN_DURATION_MS = 300
        private const val DEFAULT_DURATION_MS = 3_000
        private const val MAX_DURATION_MS = 5_000
        private val GEAR_VALUES = arrayOf(
            "reverse_3",
            "reverse_2",
            "reverse_1",
            "neutral",
            "forward_1",
            "forward_2",
            "forward_3",
            "forward_4",
        )
        private val DIRECTION_VALUES = arrayOf("left", "right")
        private const val TOOL_STOP = "sup_stop"
        private const val TOOL_SET_GEAR = "sup_set_gear"
        private const val TOOL_PIVOT_TURN = "sup_pivot_turn"
        private const val TOOL_SET_LIMITED_POWER = "sup_set_limited_power"
        private const val TOOL_ADJUST_HEADING_TARGET = "sup_adjust_heading_target"
        private const val TOOL_SET_HEADING_TARGET = "sup_set_heading_target"
        private const val TOOL_CANCEL_HEADING_LOCK = "sup_cancel_heading_lock"
        private const val TOOL_DISABLE_VOICE_CONTROL = "sup_disable_voice_control"
        private const val TOOL_EXPLAIN_STATUS = "sup_explain_status"
    }
}
