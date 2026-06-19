package com.smartsup.controller.voice

import com.smartsup.controller.model.CommandSource
import com.smartsup.controller.model.ControlCommand
import com.smartsup.controller.model.ControlCommandMode
import com.smartsup.controller.model.TurnDirection

enum class VoiceCommandAction {
    Control,
    EnableVoiceControl,
    DisableVoiceControl,
    FineTuneFaster,
    FineTuneSlower,
}

sealed interface VoiceParseResult {
    data class Accepted(
        val label: String,
        val command: ControlCommand?,
        val action: VoiceCommandAction = VoiceCommandAction.Control,
    ) : VoiceParseResult

    data class Rejected(
        val reason: String,
    ) : VoiceParseResult
}

data class VoiceCommandCandidate(
    val label: String,
    val score: Int,
    val matchedPhrase: String,
    val command: ControlCommand?,
    val action: VoiceCommandAction = VoiceCommandAction.Control,
)

data class VoiceCommandEvaluation(
    val normalizedText: String,
    val commandText: String,
    val candidates: List<VoiceCommandCandidate>,
    val result: VoiceParseResult,
)

object VoiceCommandParser {
    private const val FORWARD_GEAR_1 = 20
    private const val FORWARD_GEAR_2 = 30
    private const val FORWARD_GEAR_3 = 60
    private const val FORWARD_GEAR_4 = 80
    private const val REVERSE_GEAR_1 = -15
    private const val TURN_INNER = 10
    private const val TURN_OUTER = 25
    private const val MAX_TURN_ANGLE_DEGREES = 90
    private const val MIN_STOP_SCORE = 76
    private const val MIN_MOVE_SCORE = 78
    private const val MIN_GATE_SCORE = 86
    private const val MIN_DISPLAY_SCORE = 35
    private const val AMBIGUOUS_SCORE_GAP = 10

    private val trimPattern = Regex("[\\s，。！？、,.!?；;：:（）()\\[\\]【】\"'“”‘’]+")
    private val digitAnglePattern = Regex("(左转|左拐|左转弯|向左|往左|右转|右拐|右转弯|向右|往右)([0-9]{1,3})度")
    private val chineseAnglePattern = Regex("(左转|左拐|左转弯|向左|往左|右转|右拐|右转弯|向右|往右)([零一二三四五六七八九十百]{1,5})度")
    private val fillerWords = listOf(
        "麻烦",
        "帮我",
        "请",
        "现在",
        "马上",
        "立刻",
        "执行",
        "一下子",
        "一下",
        "吧",
        "呢",
        "啊",
        "呀",
        "了",
    )
    private val lockWords = listOf("急停", "锁定", "锁主控", "刹车", "停住", "制动")
    private val neutralWords = listOf("停止", "停下", "停", "空档", "空挡", "回空档", "回空挡", "挂空档", "挂空挡")
    private val forwardWords = listOf("前进", "向前", "往前")
    private val reverseWords = listOf("后退", "倒车", "倒退", "向后", "往后")
    private val fasterWords = listOf("快点", "快一点", "快一点点", "快一些", "再快点", "再快一点", "再快一点点", "加速", "速度快点", "稍微快点")
    private val slowerWords = listOf("慢点", "慢一点", "慢一点点", "慢一些", "再慢点", "再慢一点", "再慢一点点", "减速", "速度慢点", "稍微慢点")
    private val leftWords = listOf("左转", "左拐", "左转弯", "往左", "向左")
    private val rightWords = listOf("右转", "右拐", "右转弯", "往右", "向右")
    private val holdHeadingWords = listOf(
        "保持航向",
        "保持当前航向",
        "锁定航向",
        "锁定当前航向",
        "锁住当前航向",
        "方向锁定",
        "锁定当前方向",
        "航向锁定",
        "开启航向锁定",
        "开启方向锁定",
    )
    private val cancelHoldHeadingWords = listOf(
        "取消保持航向",
        "退出保持航向",
        "取消方向锁定",
        "退出方向锁定",
        "取消航向锁定",
        "退出航向锁定",
    )
    private val highPowerWords = listOf(
        "全速",
        "最大油门",
        "满油门",
        "高速",
    )
    private val secondReverseWords = listOf("后退二档", "后退2档", "倒车二档", "倒车2档", "倒退二档", "倒退2档")
    private val commandSpecs = listOf(
        CommandSpec(
            label = "开始声控",
            minScore = MIN_GATE_SCORE,
            command = null,
            action = VoiceCommandAction.EnableVoiceControl,
            phrases = listOf("开始声控", "开启声控", "恢复声控", "启动声控"),
        ),
        CommandSpec(
            label = "停止声控",
            minScore = MIN_GATE_SCORE,
            command = null,
            action = VoiceCommandAction.DisableVoiceControl,
            phrases = listOf("停止声控", "关闭声控", "暂停声控", "禁用声控"),
        ),
        CommandSpec(
            label = "锁定 / 急停",
            minScore = MIN_STOP_SCORE,
            command = command(armed = false, left = 0, right = 0),
            phrases = lockWords,
        ),
        CommandSpec(
            label = "空档",
            minScore = MIN_MOVE_SCORE,
            command = command(armed = true, left = 0, right = 0),
            phrases = neutralWords,
        ),
        CommandSpec(
            label = "快点",
            minScore = MIN_MOVE_SCORE,
            command = null,
            action = VoiceCommandAction.FineTuneFaster,
            phrases = fasterWords,
        ),
        CommandSpec(
            label = "慢点",
            minScore = MIN_MOVE_SCORE,
            command = null,
            action = VoiceCommandAction.FineTuneSlower,
            phrases = slowerWords,
        ),
        CommandSpec(
            label = "前进一档",
            minScore = MIN_MOVE_SCORE,
            command = command(armed = true, left = FORWARD_GEAR_1, right = FORWARD_GEAR_1),
            phrases = listOf(
                "前进",
                "前进一档",
                "前进1档",
                "慢速前进",
                "低速前进",
                "向前",
                "向前一档",
                "向前1档",
                "往前",
                "往前一档",
                "往前1档",
            ),
        ),
        CommandSpec(
            label = "前进二档",
            minScore = MIN_MOVE_SCORE,
            command = command(armed = true, left = FORWARD_GEAR_2, right = FORWARD_GEAR_2),
            phrases = listOf(
                "前进二档",
                "前进2档",
                "向前二档",
                "向前2档",
                "往前二档",
                "往前2档",
            ),
        ),
        CommandSpec(
            label = "前进三档",
            minScore = MIN_MOVE_SCORE,
            command = command(armed = true, left = FORWARD_GEAR_3, right = FORWARD_GEAR_3),
            phrases = listOf(
                "前进三档",
                "前进3档",
                "前进第三档",
                "向前三档",
                "向前3档",
                "往前三档",
                "往前3档",
            ),
        ),
        CommandSpec(
            label = "前进四档",
            minScore = MIN_MOVE_SCORE,
            command = command(armed = true, left = FORWARD_GEAR_4, right = FORWARD_GEAR_4),
            phrases = listOf(
                "前进四档",
                "前进4档",
                "前进第四档",
                "向前四档",
                "向前4档",
                "往前四档",
                "往前4档",
            ),
        ),
        CommandSpec(
            label = "后退一档",
            minScore = MIN_MOVE_SCORE,
            command = command(armed = true, left = REVERSE_GEAR_1, right = REVERSE_GEAR_1),
            phrases = listOf(
                "后退",
                "后退一档",
                "后退1档",
                "慢速后退",
                "低速后退",
                "倒车",
                "倒车一档",
                "倒车1档",
                "倒退",
                "倒退一档",
                "倒退1档",
                "向后",
                "向后一档",
                "往后",
                "往后一档",
            ),
        ),
        CommandSpec(
            label = "左转",
            minScore = MIN_MOVE_SCORE,
            command = command(armed = true, left = TURN_INNER, right = TURN_OUTER),
            phrases = leftWords,
        ),
        CommandSpec(
            label = "右转",
            minScore = MIN_MOVE_SCORE,
            command = command(armed = true, left = TURN_OUTER, right = TURN_INNER),
            phrases = rightWords,
        ),
        CommandSpec(
            label = "保持航向",
            minScore = MIN_MOVE_SCORE,
            command = headingLockCommand(enabled = true),
            phrases = holdHeadingWords,
        ),
        CommandSpec(
            label = "取消航向锁定",
            minScore = MIN_MOVE_SCORE,
            command = headingLockCommand(enabled = false),
            phrases = cancelHoldHeadingWords,
        ),
    )

    fun parse(rawText: String): VoiceParseResult {
        return evaluate(rawText).result
    }

    fun evaluate(rawText: String): VoiceCommandEvaluation {
        val normalizedText = normalize(rawText)
        val commandText = stripFillerWords(normalizedText)
        val angleCommand = parseAngleCommand(commandText)
        val candidates = buildCandidates(commandText, angleCommand)
        val result = resolveCommand(
            normalizedText = normalizedText,
            commandText = commandText,
            angleCommand = angleCommand,
            candidates = candidates,
        )
        return VoiceCommandEvaluation(
            normalizedText = normalizedText,
            commandText = commandText,
            candidates = candidates,
            result = result,
        )
    }

    private fun resolveCommand(
        normalizedText: String,
        commandText: String,
        angleCommand: ParsedAngleCommand?,
        candidates: List<VoiceCommandCandidate>,
    ): VoiceParseResult {
        if (normalizedText.isBlank()) {
            return VoiceParseResult.Rejected("ASR 文本为空")
        }

        if (highPowerWords.any { it in normalizedText || it in commandText }) {
            return VoiceParseResult.Rejected("语音控制不开放高油门命令")
        }
        if ("度" in normalizedText && angleCommand == null) {
            return VoiceParseResult.Rejected("角度转向命令不明确")
        }
        if (angleCommand != null && angleCommand.angleDegrees !in 1..MAX_TURN_ANGLE_DEGREES) {
            return VoiceParseResult.Rejected("语音角度转向只开放 1-${MAX_TURN_ANGLE_DEGREES} 度")
        }
        if (secondReverseWords.any { it in normalizedText || it in commandText }) {
            return VoiceParseResult.Rejected("语音控制只开放后退一档")
        }

        val top = candidates.firstOrNull()
            ?: return VoiceParseResult.Rejected("未匹配到白名单语音命令")
        val spec = commandSpecs.firstOrNull { it.label == top.label }
        val minScore = spec?.minScore ?: MIN_MOVE_SCORE
        if (top.score < minScore) {
            return VoiceParseResult.Rejected("命令置信度不足")
        }

        val second = candidates.drop(1).firstOrNull { it.label != top.label }
        if (second != null && top.score - second.score < AMBIGUOUS_SCORE_GAP) {
            return VoiceParseResult.Rejected("命令候选不够明确")
        }

        if (top.action != VoiceCommandAction.Control) {
            return VoiceParseResult.Accepted(
                label = top.label,
                command = top.command,
                action = top.action,
            )
        }

        val hasCancelHoldHeading = cancelHoldHeadingWords.any { it in commandText }
        val hasHoldHeading = !hasCancelHoldHeading && holdHeadingWords.any { it in commandText }
        val hasStop = !hasHoldHeading && !hasCancelHoldHeading && lockWords.any { it in commandText }
        val hasNeutral = !hasStop && neutralWords.any { it in commandText }
        val hasForward = forwardWords.any { it in commandText }
        val hasReverse = reverseWords.any { it in commandText }
        val hasFaster = fasterWords.any { it in commandText }
        val hasSlower = slowerWords.any { it in commandText }
        val hasLeft = leftWords.any { it in commandText }
        val hasRight = rightWords.any { it in commandText }
        val actionCount = listOf(
            hasStop,
            hasNeutral,
            hasForward,
            hasReverse,
            hasFaster,
            hasSlower,
            hasLeft,
            hasRight,
            hasHoldHeading,
            hasCancelHoldHeading,
        ).count { it }

        if (actionCount > 1) {
            return VoiceParseResult.Rejected("语音命令包含多个动作")
        }
        if (commandText.isBlank()) {
            return VoiceParseResult.Rejected("未匹配到白名单语音命令")
        }

        if (actionCount == 0 && top.score < 92) {
            return VoiceParseResult.Rejected("语音命令不明确")
        }

        return VoiceParseResult.Accepted(
            label = top.label,
            command = top.command,
            action = top.action,
        )
    }

    private fun buildCandidates(
        commandText: String,
        angleCommand: ParsedAngleCommand?,
    ): List<VoiceCommandCandidate> {
        if (commandText.isBlank()) {
            return emptyList()
        }
        val fixedCandidates = commandSpecs
            .mapNotNull { spec -> spec.toCandidate(commandText) }
        val angleCandidate = angleCommand
            ?.takeIf { it.angleDegrees in 1..MAX_TURN_ANGLE_DEGREES }
            ?.toCandidate()
        return (fixedCandidates + listOfNotNull(angleCandidate))
            .filter { it.score >= MIN_DISPLAY_SCORE }
            .sortedWith(compareByDescending<VoiceCommandCandidate> { it.score }.thenBy { it.label })
    }

    private fun CommandSpec.toCandidate(commandText: String): VoiceCommandCandidate? {
        val bestMatch = phrases
            .map { phrase -> phrase to scorePhrase(commandText, phrase) }
            .maxByOrNull { it.second }
            ?: return null
        return VoiceCommandCandidate(
            label = label,
            score = bestMatch.second,
            matchedPhrase = bestMatch.first,
            command = command,
            action = action,
        )
    }

    private fun ParsedAngleCommand.toCandidate(): VoiceCommandCandidate {
        return VoiceCommandCandidate(
            label = "${direction.label} ${angleDegrees} 度",
            score = 100,
            matchedPhrase = matchedPhrase,
            command = ControlCommand(
                armed = true,
                source = CommandSource.Voice,
                mode = ControlCommandMode.TurnAngle,
                turnDirection = direction,
                turnAngleDegrees = angleDegrees,
                turnRequestId = 1,
            ),
        )
    }

    private fun parseAngleCommand(commandText: String): ParsedAngleCommand? {
        val match = digitAnglePattern.find(commandText)
            ?: chineseAnglePattern.find(commandText)
            ?: return null
        val direction = parseTurnDirection(match.groupValues[1]) ?: return null
        val angle = parseAngleNumber(match.groupValues[2]) ?: return null
        return ParsedAngleCommand(
            direction = direction,
            angleDegrees = angle,
            matchedPhrase = match.value,
        )
    }

    private fun parseTurnDirection(value: String): TurnDirection? {
        return when {
            leftWords.any { it == value } -> TurnDirection.Left
            rightWords.any { it == value } -> TurnDirection.Right
            else -> null
        }
    }

    private fun parseAngleNumber(value: String): Int? {
        value.toIntOrNull()?.let { return it }
        return parseChineseNumber(value)
    }

    private fun parseChineseNumber(value: String): Int? {
        if (value.isBlank()) {
            return null
        }
        var remaining = value
        var result = 0
        val hundredIndex = remaining.indexOf('百')
        if (hundredIndex >= 0) {
            val hundreds = if (hundredIndex == 0) 1 else chineseDigit(remaining.substring(0, hundredIndex))
            result += (hundreds ?: return null) * 100
            remaining = remaining.substring(hundredIndex + 1)
        }
        val tenIndex = remaining.indexOf('十')
        if (tenIndex >= 0) {
            val tens = if (tenIndex == 0) 1 else chineseDigit(remaining.substring(0, tenIndex))
            result += (tens ?: return null) * 10
            remaining = remaining.substring(tenIndex + 1)
        }
        if (remaining.isNotBlank()) {
            result += chineseDigit(remaining) ?: return null
        }
        return result.takeIf { it > 0 }
    }

    private fun chineseDigit(value: String): Int? {
        return when (value) {
            "零" -> 0
            "一" -> 1
            "二" -> 2
            "三" -> 3
            "四" -> 4
            "五" -> 5
            "六" -> 6
            "七" -> 7
            "八" -> 8
            "九" -> 9
            else -> null
        }
    }

    private fun normalize(rawText: String): String {
        return rawText
            .trim()
            .lowercase()
            .replace(trimPattern, "")
            .replace("挡", "档")
            .replace("１", "1")
            .replace("２", "2")
            .replace("３", "3")
            .replace("４", "4")
            .replace("５", "5")
            .replace("６", "6")
            .replace("７", "7")
            .replace("８", "8")
            .replace("９", "9")
            .replace("０", "0")
            .replace("两", "二")
            .replace("兩", "二")
            .replace("幺", "一")
            .replace("1当", "1档")
            .replace("1旦", "1档")
            .replace("1段", "1档")
            .replace("一当", "一档")
            .replace("一旦", "一档")
            .replace("一段", "一档")
            .replace("2当", "2档")
            .replace("2段", "2档")
            .replace("二当", "二档")
            .replace("二段", "二档")
            .replace("3当", "3档")
            .replace("3旦", "3档")
            .replace("3段", "3档")
            .replace("三当", "三档")
            .replace("三旦", "三档")
            .replace("三段", "三档")
            .replace("4当", "4档")
            .replace("4旦", "4档")
            .replace("4段", "4档")
            .replace("四当", "四档")
            .replace("四旦", "四档")
            .replace("四段", "四档")
            .replace("到车", "倒车")
            .replace("挺止", "停止")
            .replace("听止", "停止")
            .replace("生控", "声控")
            .replace("声孔", "声控")
            .replace("左传", "左转")
            .replace("左专", "左转")
            .replace("左砖", "左转")
            .replace("右传", "右转")
            .replace("右专", "右转")
            .replace("右砖", "右转")
    }

    private fun stripFillerWords(text: String): String {
        return fillerWords.fold(text) { result, filler -> result.replace(filler, "") }
    }

    private fun scorePhrase(commandText: String, phrase: String): Int {
        if (commandText.isBlank() || phrase.isBlank()) {
            return 0
        }
        if (commandText == phrase) {
            return 100
        }
        return maxOf(editSimilarity(commandText, phrase), diceSimilarity(commandText, phrase))
    }

    private fun editSimilarity(left: String, right: String): Int {
        val maxLength = maxOf(left.length, right.length)
        if (maxLength == 0) {
            return 100
        }
        val distance = levenshteinDistance(left, right)
        return (((maxLength - distance) * 100) / maxLength).coerceIn(0, 100)
    }

    private fun diceSimilarity(left: String, right: String): Int {
        val leftPairs = bigrams(left)
        val rightPairs = bigrams(right)
        if (leftPairs.isEmpty() || rightPairs.isEmpty()) {
            return 0
        }
        val rightCounts = rightPairs.groupingBy { it }.eachCount().toMutableMap()
        var matches = 0
        for (pair in leftPairs) {
            val count = rightCounts[pair] ?: 0
            if (count > 0) {
                matches += 1
                rightCounts[pair] = count - 1
            }
        }
        return ((2 * matches * 100) / (leftPairs.size + rightPairs.size)).coerceIn(0, 100)
    }

    private fun bigrams(text: String): List<String> {
        if (text.length < 2) {
            return emptyList()
        }
        return (0 until text.lastIndex).map { index -> text.substring(index, index + 2) }
    }

    private fun levenshteinDistance(left: String, right: String): Int {
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitutionCost = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    previous[rightIndex + 1] + 1,
                    current[rightIndex] + 1,
                    previous[rightIndex] + substitutionCost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    private fun command(armed: Boolean, left: Int, right: Int): ControlCommand {
        return ControlCommand(
            armed = armed,
            leftThrottlePercent = left,
            rightThrottlePercent = right,
            source = CommandSource.Voice,
        )
    }

    private fun headingLockCommand(enabled: Boolean): ControlCommand {
        return ControlCommand(
            armed = true,
            source = CommandSource.Voice,
            mode = ControlCommandMode.HeadingLock,
            headingLockEnabled = enabled,
            headingLockRequestId = if (enabled) 1 else null,
        )
    }

    private data class CommandSpec(
        val label: String,
        val minScore: Int,
        val command: ControlCommand?,
        val phrases: List<String>,
        val action: VoiceCommandAction = VoiceCommandAction.Control,
    )

    private data class ParsedAngleCommand(
        val direction: TurnDirection,
        val angleDegrees: Int,
        val matchedPhrase: String,
    )
}
