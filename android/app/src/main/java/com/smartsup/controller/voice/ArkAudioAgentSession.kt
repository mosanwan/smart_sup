package com.smartsup.controller.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import androidx.core.content.ContextCompat
import com.smartsup.controller.model.RealtimeTtsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

class ArkAudioAgentSession(
    private val context: Context,
    private val config: ArkAudioAgentConfig,
    private val systemStateProvider: () -> String,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onReply: (String) -> Unit,
    private val onControlEvent: (String) -> String,
    private val onMetrics: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recorder: AudioRecord? = null
    private var recordJob: Job? = null
    private var pcmBuffer = ByteArrayOutputStream()
    private var tts: TextToSpeech? = null
    private var cloudTtsPlayer: MediaPlayer? = null
    private var startedAtMs = 0L
    private var activeTiming: ArkTiming? = null
    private var activeMode: RealtimeVoiceModeValue? = null
    private val recentAgentReplies = ArrayDeque<String>()
    @Volatile private var uploadInFlight = false
    @Volatile private var vadSuppressedUntilMs = 0L

    fun start(mode: RealtimeVoiceModeValue) {
        if (recordJob != null) {
            return
        }
        if (config.arkApiKey.isBlank()) {
            onStatus("Ark Agent：未配置火山引擎 API Key")
            return
        }
        if (!hasRecordAudioPermission()) {
            onStatus("Ark Agent：等待录音权限")
            return
        }
        startTtsIfNeeded()
        activeMode = mode
        if (mode == RealtimeVoiceModeValue.Live) {
            startVadListening()
        } else {
            startPushToTalkRecording()
        }
    }

    fun stop() {
        if (activeMode == RealtimeVoiceModeValue.Live) {
            stopLocalRecording()
            activeMode = null
            onStatus("Ark Agent：实时监听已关闭")
            return
        }
        if (recordJob == null && pcmBuffer.size() == 0) {
            return
        }
        val captured = stopLocalRecording()
        activeMode = null
        if (captured.isEmpty()) {
            onStatus("Ark Agent：未录到音频")
            return
        }
        if (captured.size > MAX_PCM_BYTES) {
            onStatus("Ark Agent：音频过长，已拒绝上传")
            onMetrics("音频 ${captured.size.toKb()}KB 超过上限；请控制在 ${MAX_AUDIO_MS / 1000}s 内")
            return
        }
        submitAudio(captured)
    }

    fun destroy() {
        stopLocalRecording()
        runCatching { cloudTtsPlayer?.release() }
        cloudTtsPlayer = null
        runCatching { tts?.shutdown() }
        tts = null
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    @SuppressLint("MissingPermission")
    private fun startPushToTalkRecording() {
        pcmBuffer = ByteArrayOutputStream()
        val nextRecorder = createAudioRecord()
        recorder = nextRecorder
        nextRecorder.startRecording()
        startedAtMs = System.currentTimeMillis()
        activeTiming = ArkTiming(recordStartMs = startedAtMs)
        onStatus("Ark Agent：录音中，松开发送方舟")
        onTranscript("录音中")
        recordJob = scope.launch {
            val buffer = ByteArray(FRAME_BYTES)
            while (isActive) {
                val read = nextRecorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    pcmBuffer.write(buffer, 0, read)
                    val seconds = pcmBuffer.size() / (SAMPLE_RATE * BYTES_PER_SAMPLE)
                    onMetrics("录音 ${seconds}s；方舟模型 ${config.model}")
                } else {
                    delay(10)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startVadListening() {
        val nextRecorder = createAudioRecord()
        recorder = nextRecorder
        nextRecorder.startRecording()
        onStatus("Ark Agent：实时监听中")
        onTranscript("等待人声")
        onMetrics("VAD 监听中；静音不上传")
        recordJob = scope.launch {
            val buffer = ByteArray(FRAME_BYTES)
            var speechActive = false
            var speechFrames = 0
            var silenceFrames = 0
            var preRoll = ArrayDeque<ByteArray>()
            var segment = ByteArrayOutputStream()
            while (isActive) {
                val read = nextRecorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    delay(10)
                    continue
                }
                val frame = buffer.copyOf(read)
                val rms = pcm16Rms(frame)
                val vadSuppressed = System.currentTimeMillis() < vadSuppressedUntilMs
                val voiced = !vadSuppressed && rms >= VAD_RMS_THRESHOLD

                if (!speechActive) {
                    if (vadSuppressed) {
                        speechFrames = 0
                        preRoll.clear()
                        publishVadMetrics(rms, speechActive, suppressed = true)
                        continue
                    }
                    preRoll.addLast(frame)
                    while (preRoll.size > VAD_PREROLL_FRAMES) {
                        preRoll.removeFirst()
                    }
                    if (voiced) {
                        speechFrames += 1
                    } else {
                        speechFrames = 0
                    }
                    if (speechFrames >= VAD_START_FRAMES) {
                        speechActive = true
                        silenceFrames = 0
                        segment = ByteArrayOutputStream()
                        while (preRoll.isNotEmpty()) {
                            segment.write(preRoll.removeFirst())
                        }
                        startedAtMs = System.currentTimeMillis()
                        activeTiming = ArkTiming(recordStartMs = startedAtMs)
                        onStatus("Ark Agent：检测到人声")
                        onTranscript("检测到人声")
                    }
                    publishVadMetrics(rms, speechActive)
                    continue
                }

                segment.write(frame)
                if (voiced) {
                    silenceFrames = 0
                } else {
                    silenceFrames += 1
                }

                val durationMs = segment.size() * 1_000L / (SAMPLE_RATE * BYTES_PER_SAMPLE)
                publishVadMetrics(rms, speechActive, durationMs)
                if (durationMs >= MAX_AUDIO_MS || silenceFrames >= VAD_END_SILENCE_FRAMES) {
                    val captured = segment.toByteArray()
                    speechActive = false
                    speechFrames = 0
                    silenceFrames = 0
                    preRoll = ArrayDeque()
                    segment = ByteArrayOutputStream()
                    if (captured.size < MIN_PCM_BYTES) {
                        onStatus("Ark Agent：片段太短，已忽略")
                    } else if (uploadInFlight) {
                        onStatus("Ark Agent：上一段处理中，本段已忽略")
                        onMetrics("VAD 片段 ${durationMs}ms；上一段方舟请求未完成，已忽略")
                    } else {
                        onStatus("Ark Agent：人声结束，提交方舟")
                        submitAudio(captured)
                    }
                    onTranscript("等待人声")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(FRAME_BYTES * 4)
        return AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize,
        )
    }

    private fun stopLocalRecording(): ByteArray {
        recordJob?.cancel()
        recordJob = null
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        recorder = null
        val pcm = pcmBuffer.toByteArray()
        pcmBuffer = ByteArrayOutputStream()
        return pcm
    }

    private fun publishVadMetrics(
        rms: Int,
        speechActive: Boolean,
        segmentMs: Long = 0L,
        suppressed: Boolean = false,
    ) {
        val state = when {
            suppressed -> "抑制中"
            speechActive -> "说话中"
            else -> "监听中"
        }
        val segmentText = if (segmentMs > 0L) "；片段 ${segmentMs}ms" else ""
        onMetrics("VAD $state；rms $rms；阈值 $VAD_RMS_THRESHOLD$segmentText")
    }

    private fun pcm16Rms(bytes: ByteArray): Int {
        if (bytes.size < 2) {
            return 0
        }
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < bytes.size) {
            val sample = (bytes[index].toInt() and 0xff) or (bytes[index + 1].toInt() shl 8)
            val signed = sample.toShort().toInt()
            sum += signed.toDouble() * signed.toDouble()
            count += 1
            index += 2
        }
        return kotlin.math.sqrt(sum / count.coerceAtLeast(1)).toInt()
    }

    private fun submitAudio(pcm: ByteArray) {
        val durationMs = (pcm.size * 1_000L) / (SAMPLE_RATE * BYTES_PER_SAMPLE)
        activeTiming = (activeTiming ?: ArkTiming()).copy(
            recordEndMs = System.currentTimeMillis(),
            audioDurationMs = durationMs,
        )
        onStatus("Ark Agent：上传 ${durationMs}ms 音频")
        publishTiming("准备上传")
        uploadInFlight = true
        scope.launch {
            runCatching {
                activeTiming = activeTiming?.copy(encodeStartMs = System.currentTimeMillis())
                val wav = wavFromPcm(pcm)
                val estimatedBase64Bytes = ((wav.size + 2) / 3) * 4
                activeTiming = activeTiming?.copy(
                    pcmBytes = pcm.size,
                    wavBytes = wav.size,
                    base64Bytes = estimatedBase64Bytes,
                )
                activeTiming = activeTiming?.copy(
                    encodeEndMs = System.currentTimeMillis(),
                    uploadStartMs = System.currentTimeMillis(),
                )
                if (config.wakeWordRequired) {
                    val asrText = recognizeWakeWordText(wav)
                    onTranscript(asrText.ifBlank { "唤醒识别为空" })
                    if (!hasWakeWordPrefix(asrText)) {
                        onStatus("Ark Agent：未听到“豆包”，不发送方舟")
                        onReply("未听到唤醒词，已忽略")
                        onMetrics("唤醒词未命中；未发送方舟；PCM ${pcm.size.toKb()}KB；WAV ${wav.size.toKb()}KB")
                        return@runCatching
                    }
                    onStatus("Ark Agent：已听到“豆包”，提交方舟")
                }
                publishTiming("上传中")
                val result = callArkStreaming(wav)
                handleArkStreamResult(result)
            }.onFailure { error ->
                val message = when (error) {
                    is ArkHttpException -> "HTTP ${error.code}: ${error.body.take(160)}"
                    is IOException -> "网络失败：${error.message ?: "IO 错误"}"
                    else -> error.message ?: "未知错误"
                }
                onStatus("Ark Agent：请求失败 $message")
                publishTiming("失败")
            }.also {
                uploadInFlight = false
            }
        }
    }

    private fun recognizeWakeWordText(wav: ByteArray): String {
        if (config.wakeWordAsrApiKey.isBlank()) {
            error("唤醒词模式缺少豆包语音 ASR API Key")
        }
        onStatus("Ark Agent：唤醒词识别中")
        return DoubaoFlashAsrClient(client).recognizeWav(
            apiKey = config.wakeWordAsrApiKey,
            wav = wav,
        ).trim()
    }

    private fun hasWakeWordPrefix(text: String): Boolean {
        val normalized = text
            .trim()
            .replace(Regex("^[，,。.!！?？\\s]+"), "")
            .replace(Regex("\\s+"), "")
        return normalized.startsWith(WAKE_WORD)
    }

    private fun callArkStreaming(wav: ByteArray): ArkStreamResult {
        val audioBase64 = Base64.encodeToString(wav, Base64.NO_WRAP)
        val payload = JSONObject()
            .put("model", config.model.ifBlank { DEFAULT_MODEL })
            .put("max_tokens", MAX_OUTPUT_TOKENS)
            .put("stream", true)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("thinking", JSONObject().put("type", "disabled"))
            .put("reasoning", JSONObject().put("effort", "minimal"))
            .put("reasoning_effort", "minimal")
            .put("tools", RealtimeVoiceControlEvent.toolDefinitions())
            .put("tool_choice", "auto")
            .put("messages", buildMessages(audioBase64))

        val requestJson = payload.toString()
        activeTiming = activeTiming?.copy(jsonBytes = requestJson.encodeToByteArray().size)
        val request = Request.Builder()
            .url(ARK_CHAT_COMPLETIONS_URL)
            .header("Authorization", "Bearer ${config.arkApiKey}")
            .header("Content-Type", "application/json")
            .post(
                TimedJsonRequestBody(
                    json = requestJson,
                    mediaType = JSON_MEDIA_TYPE,
                    onUploadStart = {
                        activeTiming = activeTiming?.copy(uploadStartMs = System.currentTimeMillis())
                        publishTiming("上传中")
                    },
                    onUploadEnd = {
                        activeTiming = activeTiming?.copy(uploadEndMs = System.currentTimeMillis())
                        publishTiming("已上传")
                    },
                ),
            )
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw ArkHttpException(response.code, body)
            }
            val source = response.body?.source() ?: error("empty response body")
            val reply = StringBuilder()
            val toolCallBuilders = linkedMapOf<Int, StreamingToolCallBuilder>()
            var audioTokens = 0
            var finishReason = ""
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith("data:")) {
                    continue
                }
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    break
                }
                val chunk = JSONObject(data)
                val usage = chunk.optJSONObject("usage")
                if (usage != null) {
                    audioTokens = usage.optJSONObject("prompt_tokens_details")?.optInt("audio_tokens", 0)
                        ?: audioTokens
                }
                val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                finishReason = choice.optString("finish_reason").ifBlank { finishReason }
                val delta = choice.optJSONObject("delta") ?: continue
                val content = delta.optString("content")
                if (content.isNotBlank()) {
                    if (activeTiming?.firstTokenMs == 0L) {
                        activeTiming = activeTiming?.copy(firstTokenMs = System.currentTimeMillis())
                    }
                    reply.append(content)
                    onStatus("Ark Agent：流式回复中")
                    onReply(reply.toString())
                    publishTiming("首字/流式")
                }
                val deltaToolCalls = delta.optJSONArray("tool_calls")
                if (deltaToolCalls != null) {
                    if (activeTiming?.firstTokenMs == 0L) {
                        activeTiming = activeTiming?.copy(firstTokenMs = System.currentTimeMillis())
                    }
                    for (index in 0 until deltaToolCalls.length()) {
                        val deltaToolCall = deltaToolCalls.optJSONObject(index) ?: continue
                        val toolIndex = deltaToolCall.optInt("index", index)
                        toolCallBuilders.getOrPut(toolIndex) { StreamingToolCallBuilder() }
                            .append(deltaToolCall)
                    }
                    onStatus("Ark Agent：工具调用生成中")
                    publishTiming("首字/工具")
                }
            }
            val toolCalls = JSONArray()
            toolCallBuilders.values
                .mapNotNull { it.buildOrNull() }
                .forEach { toolCalls.put(it) }
            activeTiming = activeTiming?.copy(
                responseEndMs = System.currentTimeMillis(),
                audioTokens = audioTokens,
            )
            publishTiming("流式完成")
            return ArkStreamResult(
                content = reply.toString(),
                toolCalls = toolCalls.takeIf { it.length() > 0 },
                finishReason = finishReason,
            )
        }
    }

    private fun handleArkStreamResult(result: ArkStreamResult) {
        if (result.finishReason == "length") {
            val resultText = "方舟工具调用被截断，请重试"
            addAgentHistory(resultText)
            onStatus("Ark Agent：工具调用被截断")
            onReply(resultText)
            speak(resultText)
            return
        }
        val toolCalls = result.toolCalls
        if (toolCalls != null && toolCalls.length() > 0) {
            onStatus("Ark Agent：收到工具调用")
            onTranscript("音频已提交方舟")
            val resultText = onControlEvent(JSONObject().put("tool_calls", toolCalls).toString())
                .ifBlank { "控制事件已提交 App 校验" }
            addAgentHistory(resultText)
            onReply(resultText)
            speak(resultText)
            return
        }

        val content = result.content.ifBlank { "没有听到有效命令" }
        onStatus("Ark Agent：收到文本回复")
        onTranscript("音频已提交方舟")
        if (result.content.isNotBlank()) {
            addAgentHistory(content)
        }
        onReply(content)
        speak(content)
    }

    private fun buildMessages(audioBase64: String): JSONArray {
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put("content", buildSystemPrompt()),
            )
        val history = recentHistoryPrompt()
        if (history.isNotBlank()) {
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", history),
            )
        }
        messages.put(
            JSONObject()
                .put("role", "user")
                .put(
                    "content",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("type", "input_audio")
                                .put(
                                    "input_audio",
                                    JSONObject()
                                        .put("data", audioBase64)
                                        .put("format", "wav"),
                                ),
                        ),
                ),
        )
        return messages
    }

    private fun buildSystemPrompt(): String {
        val wakeWordPrompt = if (config.wakeWordRequired) {
            "\n当前为唤醒词模式：用户语音已由App确认以“豆包”开头。回答和执行时忽略开头的“豆包”。"
        } else {
            ""
        }
        return ARK_SYSTEM_PROMPT + wakeWordPrompt + "\n\n" + systemStateProvider().trim()
    }

    private fun recentHistoryPrompt(): String {
        if (recentAgentReplies.isEmpty()) {
            return ""
        }
        return recentAgentReplies
            .takeLast(MAX_HISTORY_REPLIES)
            .mapIndexed { index, text -> "${index + 1}. $text" }
            .joinToString(
                separator = "\n",
                prefix = "最近Agent回复，仅作上下文参考：\n",
            )
    }

    private fun addAgentHistory(text: String) {
        val compact = text.trim().replace(Regex("\\s+"), " ").take(MAX_HISTORY_REPLY_CHARS)
        if (compact.isBlank()) {
            return
        }
        recentAgentReplies.addLast(compact)
        while (recentAgentReplies.size > MAX_HISTORY_REPLIES) {
            recentAgentReplies.removeFirst()
        }
    }

    private fun startTtsIfNeeded() {
        if (tts != null) {
            return
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.CHINA
                tts?.setOnUtteranceProgressListener(ttsProgressListener())
            }
        }
    }

    private fun speak(text: String) {
        vadSuppressedUntilMs = System.currentTimeMillis() + VAD_TTS_SUPPRESS_MAX_MS
        activeTiming = activeTiming?.copy(ttsRequestMs = System.currentTimeMillis())
        publishTiming("TTS 请求")
        val clipped = text.take(MAX_TTS_CHARS)
        if (config.ttsMode == RealtimeTtsMode.Cloud) {
            speakCloud(clipped)
        } else {
            speakLocal(clipped)
        }
    }

    private fun speakLocal(text: String) {
        startTtsIfNeeded()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ark_agent_reply")
    }

    private fun speakCloud(text: String) {
        if (config.cloudTtsApiKey.isBlank()) {
            onStatus("云端 TTS：未配置豆包语音 API Key，回退本地 TTS")
            speakLocal(text)
            return
        }
        scope.launch {
            runCatching {
                val voice = config.voice.ifBlank { DEFAULT_CLOUD_TTS_VOICE }
                onStatus("云端 TTS：请求音色 $voice")
                val audioBytes = VolcCloudTtsClient(client).synthesizeMp3(
                    apiKey = config.cloudTtsApiKey,
                    voice = voice,
                    text = text,
                ) { firstAudio ->
                    if (firstAudio && activeTiming?.ttsStartMs == 0L) {
                        activeTiming = activeTiming?.copy(ttsStartMs = System.currentTimeMillis())
                        publishTiming("云TTS首包")
                    }
                }
                onStatus("云端 TTS：播放 ${audioBytes.size.toKb()}KB")
                playCloudTtsMp3(audioBytes)
            }.onFailure { error ->
                onStatus("云端 TTS 失败：${error.message ?: "未知错误"}，回退本地 TTS")
                speakLocal(text)
            }
        }
    }

    private fun playCloudTtsMp3(audioBytes: ByteArray) {
        val file = File.createTempFile("ark_cloud_tts_", ".mp3", context.cacheDir)
        file.writeBytes(audioBytes)
        runCatching { cloudTtsPlayer?.release() }
        cloudTtsPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnPreparedListener {
                vadSuppressedUntilMs = System.currentTimeMillis() + VAD_TTS_SUPPRESS_MAX_MS
                if (activeTiming?.ttsStartMs == 0L) {
                    activeTiming = activeTiming?.copy(ttsStartMs = System.currentTimeMillis())
                }
                publishTiming("云TTS播放")
                it.start()
            }
            setOnCompletionListener {
                vadSuppressedUntilMs = System.currentTimeMillis() + VAD_TTS_SUPPRESS_TAIL_MS
                activeTiming = activeTiming?.copy(ttsDoneMs = System.currentTimeMillis())
                publishTiming("完成")
                runCatching { file.delete() }
                it.release()
                if (cloudTtsPlayer === it) {
                    cloudTtsPlayer = null
                }
            }
            setOnErrorListener { player, _, _ ->
                vadSuppressedUntilMs = System.currentTimeMillis() + VAD_TTS_SUPPRESS_TAIL_MS
                activeTiming = activeTiming?.copy(ttsDoneMs = System.currentTimeMillis())
                publishTiming("云TTS播放错误")
                runCatching { file.delete() }
                player.release()
                if (cloudTtsPlayer === player) {
                    cloudTtsPlayer = null
                }
                true
            }
            prepareAsync()
        }
    }

    private fun ttsProgressListener(): UtteranceProgressListener {
        return object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                vadSuppressedUntilMs = System.currentTimeMillis() + VAD_TTS_SUPPRESS_MAX_MS
                activeTiming = activeTiming?.copy(ttsStartMs = System.currentTimeMillis())
                publishTiming("TTS 播放")
            }

            override fun onDone(utteranceId: String?) {
                vadSuppressedUntilMs = System.currentTimeMillis() + VAD_TTS_SUPPRESS_TAIL_MS
                activeTiming = activeTiming?.copy(ttsDoneMs = System.currentTimeMillis())
                publishTiming("完成")
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                vadSuppressedUntilMs = System.currentTimeMillis() + VAD_TTS_SUPPRESS_TAIL_MS
                activeTiming = activeTiming?.copy(ttsDoneMs = System.currentTimeMillis())
                publishTiming("TTS 错误")
            }
        }
    }

    private fun publishTiming(stage: String) {
        val timing = activeTiming ?: return
        onMetrics(timing.format(stage))
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun wavFromPcm(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val dataSize = pcm.size
        val byteRate = SAMPLE_RATE * BYTES_PER_SAMPLE
        out.writeAscii("RIFF")
        out.writeIntLE(36 + dataSize)
        out.writeAscii("WAVE")
        out.writeAscii("fmt ")
        out.writeIntLE(16)
        out.writeShortLE(1)
        out.writeShortLE(1)
        out.writeIntLE(SAMPLE_RATE)
        out.writeIntLE(byteRate)
        out.writeShortLE(BYTES_PER_SAMPLE)
        out.writeShortLE(16)
        out.writeAscii("data")
        out.writeIntLE(dataSize)
        out.write(pcm)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.encodeToByteArray())
    }

    private fun ByteArrayOutputStream.writeIntLE(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private fun ByteArrayOutputStream.writeShortLE(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private data class ArkTiming(
        val recordStartMs: Long = 0L,
        val recordEndMs: Long = 0L,
        val encodeStartMs: Long = 0L,
        val encodeEndMs: Long = 0L,
        val uploadStartMs: Long = 0L,
        val uploadEndMs: Long = 0L,
        val responseEndMs: Long = 0L,
        val firstTokenMs: Long = 0L,
        val ttsRequestMs: Long = 0L,
        val ttsStartMs: Long = 0L,
        val ttsDoneMs: Long = 0L,
        val audioDurationMs: Long = 0L,
        val audioTokens: Int = 0,
        val pcmBytes: Int = 0,
        val wavBytes: Int = 0,
        val base64Bytes: Int = 0,
        val jsonBytes: Int = 0,
    ) {
        fun arkRoundTripMs(): Long = delta(uploadStartMs, responseEndMs)

        fun format(stage: String): String {
            return listOf(
                "阶段 $stage",
                "录音 ${audioDurationMs.msText()}",
                "封装 ${delta(encodeStartMs, encodeEndMs).msText()}",
                "上传 ${delta(uploadStartMs, uploadEndMs).msText()}",
                "方舟 ${arkRoundTripMs().msText()}",
                "首字 ${delta(uploadStartMs, firstTokenMs).msText()}",
                "TTS排队 ${delta(ttsRequestMs, ttsStartMs).msText()}",
                "TTS播放 ${delta(ttsStartMs, ttsDoneMs).msText()}",
                "总计 ${delta(recordEndMs, ttsDoneMs.ifZero(responseEndMs)).msText()}",
                "PCM ${pcmBytes.toKb()}KB",
                "WAV ${wavBytes.toKb()}KB",
                "B64 ${base64Bytes.toKb()}KB",
                "JSON ${jsonBytes.toKb()}KB",
                "audio tokens $audioTokens",
            ).joinToString("；")
        }

        private fun delta(startMs: Long, endMs: Long): Long {
            return if (startMs > 0L && endMs > 0L && endMs >= startMs) endMs - startMs else 0L
        }

        private fun Long.ifZero(fallback: Long): Long = if (this == 0L) fallback else this

        private fun Long.msText(): String = if (this <= 0L) "--" else "${this}ms"
    }

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val MAX_AUDIO_MS = 8_000
        private const val MAX_PCM_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * MAX_AUDIO_MS / 1_000
        private const val MIN_AUDIO_MS = 600
        private const val MIN_PCM_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * MIN_AUDIO_MS / 1_000
        private const val FRAME_MS = 100
        private const val FRAME_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * FRAME_MS / 1_000
        private const val VAD_RMS_THRESHOLD = 700
        private const val VAD_START_FRAMES = 2
        private const val VAD_END_SILENCE_FRAMES = 9
        private const val VAD_PREROLL_FRAMES = 3
        private const val VAD_TTS_SUPPRESS_TAIL_MS = 700L
        private const val VAD_TTS_SUPPRESS_MAX_MS = 30_000L
        private const val DEFAULT_MODEL = "doubao-seed-2-0-lite-260428"
        private const val DEFAULT_CLOUD_TTS_VOICE = "zh_female_vv_uranus_bigtts"
        private const val WAKE_WORD = "豆包"
        private const val MAX_OUTPUT_TOKENS = 160
        private const val MAX_TTS_CHARS = 80
        private const val MAX_HISTORY_REPLIES = 5
        private const val MAX_HISTORY_REPLY_CHARS = 120
        private const val ARK_SYSTEM_PROMPT =
            "你叫豆包，是智能桨板语音Agent。直接回应用户语音，不描述音频内容。" +
                "需要控制时只调用工具；不能解锁、输出PWM/GPIO或关闭保护。" +
                "前进1/2/3/4、后退/后退1/2/3调用sup_set_gear；停/停止/空挡调用sup_stop。" +
                "左/右原地掉头调用sup_pivot_turn，只填direction为left或right。" +
                "目标航向/航向设为X度调用sup_set_heading_target；左转/右转X度调用sup_adjust_heading_target，左负右正。" +
                "取消航向锁定调用sup_cancel_heading_lock；关闭/停止声控调用sup_disable_voice_control，不取消锁航。" +
                "非控制问题简短中文回答。"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val ARK_CHAT_COMPLETIONS_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"
    }
}

data class ArkAudioAgentConfig(
    val arkApiKey: String,
    val model: String,
    val voice: String,
    val ttsMode: RealtimeTtsMode,
    val cloudTtsApiKey: String,
    val wakeWordRequired: Boolean,
    val wakeWordAsrApiKey: String,
)

private class DoubaoFlashAsrClient(
    private val client: OkHttpClient,
) {
    fun recognizeWav(apiKey: String, wav: ByteArray): String {
        val payload = JSONObject()
            .put("user", JSONObject().put("uid", "smart_sup_android"))
            .put(
                "audio",
                JSONObject()
                    .put("data", Base64.encodeToString(wav, Base64.NO_WRAP))
                    .put("format", "wav"),
            )
            .put("request", JSONObject().put("model_name", "bigmodel"))
        val request = Request.Builder()
            .url(ASR_FLASH_URL)
            .header("Content-Type", "application/json")
            .header("X-Api-Key", apiKey)
            .header("X-Api-Resource-Id", ASR_RESOURCE_ID)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .header("X-Api-Sequence", "-1")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ArkHttpException(response.code, body)
            }
            val json = JSONObject(body)
            val code = json.optInt("code", 0)
            if (code != 0 && code != ASR_DONE_CODE) {
                error("ASR 错误 $code ${json.optString("message")}")
            }
            return extractText(json).ifBlank {
                error("ASR 未返回识别文本")
            }
        }
    }

    private fun extractText(json: JSONObject): String {
        json.optJSONObject("result")?.let { result ->
            result.optString("text").takeIf { it.isNotBlank() }?.let { return it }
            result.optJSONArray("utterances")?.let { utterances ->
                val text = buildString {
                    for (index in 0 until utterances.length()) {
                        append(utterances.optJSONObject(index)?.optString("text").orEmpty())
                    }
                }
                if (text.isNotBlank()) {
                    return text
                }
            }
        }
        json.optString("text").takeIf { it.isNotBlank() }?.let { return it }
        json.optJSONArray("utterances")?.let { utterances ->
            return buildString {
                for (index in 0 until utterances.length()) {
                    append(utterances.optJSONObject(index)?.optString("text").orEmpty())
                }
            }
        }
        return ""
    }

    companion object {
        private const val ASR_FLASH_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash"
        private const val ASR_RESOURCE_ID = "volc.bigasr.auc_turbo"
        private const val ASR_DONE_CODE = 20_000_000
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private class VolcCloudTtsClient(
    private val client: OkHttpClient,
) {
    suspend fun synthesizeMp3(
        apiKey: String,
        voice: String,
        text: String,
        onAudioChunk: (firstAudio: Boolean) -> Unit,
    ): ByteArray {
        val resourceId = resourceIdForVoice(voice)
        val payload = JSONObject()
            .put("user", JSONObject().put("uid", "smart_sup_android"))
            .put(
                "req_params",
                JSONObject()
                    .put("text", text)
                    .put("speaker", voice)
                    .put(
                        "audio_params",
                        JSONObject()
                            .put("format", "mp3")
                            .put("sample_rate", 24_000)
                            .put("speech_rate", 0)
                            .put("loudness_rate", 0),
                    ),
            )
        val request = Request.Builder()
            .url(TTS_HTTP_URL)
            .header("X-Api-Key", apiKey)
            .header("Authorization", "Bearer $apiKey")
            .header("X-Api-Resource-Id", resourceId)
            .header("X-Api-Request-Id", UUID.randomUUID().toString())
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body ?: error("云端 TTS 响应为空")
            val bytes = body.bytes()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${bytes.decodeToString().take(300)}")
            }
            if (isMp3(bytes)) {
                onAudioChunk(true)
                return bytes
            }
            return parseChunkedJsonAudio(bytes.decodeToString(), onAudioChunk).also {
                if (it.isEmpty()) {
                    error("云端 TTS 未返回音频")
                }
            }
        }
    }

    private fun resourceIdForVoice(voice: String): String {
        return when {
            voice.startsWith("S_") -> "seed-icl-2.0"
            voice.contains("_uranus_bigtts") -> "seed-tts-2.0"
            voice.startsWith("saturn_") -> "seed-tts-2.0"
            else -> "seed-tts-1.0"
        }
    }

    private fun parseChunkedJsonAudio(text: String, onAudioChunk: (firstAudio: Boolean) -> Unit): ByteArray {
        val out = ByteArrayOutputStream()
        var hasAudio = false
        extractJsonObjects(text).forEach { jsonText ->
            val item = runCatching { JSONObject(jsonText) }.getOrNull() ?: return@forEach
            val code = item.optInt("code", 0)
            if (code != 0 && code != TTS_DONE_CODE) {
                error("火山 TTS 错误 $code ${item.optString("message")}")
            }
            val data = item.optString("data")
            if (data.isNotBlank() && data != "null") {
                out.write(Base64.decode(data, Base64.DEFAULT))
                onAudioChunk(!hasAudio)
                hasAudio = true
            }
        }
        return out.toByteArray()
    }

    private fun extractJsonObjects(text: String): List<String> {
        val objects = mutableListOf<String>()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        text.forEachIndexed { index, char ->
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                return@forEachIndexed
            }
            when (char) {
                '"' -> inString = true
                '{' -> {
                    if (depth == 0) {
                        start = index
                    }
                    depth += 1
                }
                '}' -> {
                    if (depth > 0) {
                        depth -= 1
                        if (depth == 0 && start >= 0) {
                            objects.add(text.substring(start, index + 1))
                            start = -1
                        }
                    }
                }
            }
        }
        return objects
    }

    private fun isMp3(bytes: ByteArray): Boolean {
        return bytes.size >= 3 &&
            ((bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) ||
                ((bytes[0].toInt() and 0xff) == 0xff && (bytes[1].toInt() and 0xe0) == 0xe0))
    }

    companion object {
        private const val TTS_HTTP_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"
        private const val TTS_DONE_CODE = 20_000_000
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

private data class ArkStreamResult(
    val content: String,
    val toolCalls: JSONArray?,
    val finishReason: String,
)

private class StreamingToolCallBuilder {
    private var id: String = ""
    private var type: String = "function"
    private var name: String = ""
    private val arguments = StringBuilder()

    fun append(delta: JSONObject) {
        id = delta.optNonBlankString("id") ?: id
        type = delta.optNonBlankString("type") ?: type
        val function = delta.optJSONObject("function")
        if (function != null) {
            name = function.optNonBlankString("name") ?: name
            arguments.append(function.optNonBlankString("arguments").orEmpty())
        }
        name = delta.optNonBlankString("name") ?: name
        arguments.append(delta.optNonBlankString("arguments").orEmpty())
    }

    fun buildOrNull(): JSONObject? {
        if (name.isBlank()) {
            return null
        }
        val function = JSONObject()
            .put("name", name)
            .put("arguments", arguments.toString())
        return JSONObject()
            .put("id", id.ifBlank { "ark_tool_call_$name" })
            .put("type", type.ifBlank { "function" })
            .put("function", function)
    }
}

private fun JSONObject.optNonBlankString(name: String): String? {
    if (!has(name) || isNull(name)) {
        return null
    }
    return optString(name).takeIf { it.isNotBlank() }
}

private class ArkHttpException(
    val code: Int,
    val body: String,
) : RuntimeException("HTTP $code")

private class TimedJsonRequestBody(
    private val json: String,
    private val mediaType: MediaType,
    private val onUploadStart: () -> Unit,
    private val onUploadEnd: () -> Unit,
) : RequestBody() {
    private val bytes = json.encodeToByteArray()

    override fun contentType(): MediaType = mediaType

    override fun contentLength(): Long = bytes.size.toLong()

    override fun writeTo(sink: BufferedSink) {
        onUploadStart()
        sink.write(bytes)
        sink.flush()
        onUploadEnd()
    }
}

private fun Int.toKb(): Int = (this + 1023) / 1024
