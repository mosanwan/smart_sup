package com.smartsup.controller.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

class RealtimeVoiceSession(
    private val context: Context,
    private val config: RealtimeVoiceConfig,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onReply: (String) -> Unit,
    private val onControlEvent: (String) -> Unit,
    private val onMetrics: (String) -> Unit,
) {
    private val client = OkHttpClient.Builder().build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val useVolcProtocol = config.endpoint.contains("openspeech.bytedance.com") && config.apiKey.isNotBlank()
    private var sessionId = UUID.randomUUID().toString()
    private var webSocket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var captureJob: Job? = null
    private var metricsJob: Job? = null
    private var uplinkBytes = 0L
    private var downlinkBytes = 0L
    private var controlEventCount = 0
    private var errorCount = 0

    fun start(mode: RealtimeVoiceModeValue) {
        if (webSocket != null) {
            return
        }
        if (config.endpoint.isBlank()) {
            onStatus("实时语音：未配置 WebSocket 地址")
            return
        }
        if (!hasRecordAudioPermission()) {
            onStatus("实时语音：等待录音权限")
            return
        }

        sessionId = UUID.randomUUID().toString()
        onStatus("实时语音：正在连接")
        val requestBuilder = Request.Builder().url(config.endpoint)
        if (useVolcProtocol) {
            requestBuilder.header("X-Api-Key", config.apiKey)
            requestBuilder.header("X-Api-Resource-Id", VOLC_RESOURCE_ID)
            requestBuilder.header("X-Api-Connect-Id", UUID.randomUUID().toString())
        } else if (config.apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
        }

        webSocket = client.newWebSocket(
            requestBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onStatus(if (useVolcProtocol) "实时语音：火山协议已连接" else "实时语音：已连接")
                    if (useVolcProtocol) {
                        sendVolcStartConnection(webSocket)
                        sendVolcStartSession(webSocket, mode)
                    } else {
                        sendGenericSessionStart(webSocket, mode)
                    }
                    startAudioPlayback()
                    startAudioCapture(webSocket)
                    startMetricsLoop()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleGenericTextMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (useVolcProtocol) {
                        handleVolcBinaryMessage(bytes.toByteArray())
                    } else {
                        handleAudioMessage(bytes.toByteArray())
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    onStatus("实时语音：正在关闭 $code $reason")
                    webSocket.close(code, reason)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onStatus("实时语音：已关闭 $code $reason")
                    stopLocalAudio()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    errorCount += 1
                    onStatus("实时语音：连接失败 ${t.message ?: "未知错误"}")
                    stopLocalAudio()
                }
            },
        )
    }

    fun stop() {
        webSocket?.let { ws ->
            if (useVolcProtocol) {
                sendVolcFinishSession(ws)
                sendVolcFinishConnection(ws)
            } else {
                ws.send("""{"type":"session_end"}""")
            }
        }
        webSocket?.close(1000, "client_stop")
        webSocket = null
        stopLocalAudio()
        onStatus("实时语音：未连接")
    }

    fun destroy() {
        stop()
        scope.cancel()
        client.dispatcher.executorService.shutdown()
    }

    private fun sendGenericSessionStart(webSocket: WebSocket, mode: RealtimeVoiceModeValue) {
        val json = JSONObject()
            .put("type", "session_start")
            .put("mode", mode.wireValue)
            .put("audio_format", "pcm16")
            .put("sample_rate_hz", INPUT_SAMPLE_RATE)
            .put("channels", 1)
            .put("model", config.model)
            .put("voice", config.voice)
            .put("tools", RealtimeVoiceControlEvent.toolDefinitions())
            .put("tool_choice", "auto")
            .put("system_prompt", REALTIME_SYSTEM_PROMPT)
        webSocket.send(json.toString())
    }

    private fun sendVolcStartConnection(webSocket: WebSocket) {
        val packet = volcFullRequest(event = VOLC_START_CONNECTION, payload = JSONObject())
        webSocket.send(packet.toByteString())
    }

    private fun sendVolcStartSession(webSocket: WebSocket, mode: RealtimeVoiceModeValue) {
        val payload = JSONObject()
            .put(
                "asr",
                JSONObject().put(
                    "extra",
                    JSONObject().put("end_smooth_window_ms", 1500),
                ),
            )
            .put(
                "tts",
                JSONObject()
                    .put("speaker", config.voice.ifBlank { VOLC_DEFAULT_SPEAKER })
                    .put(
                        "audio_config",
                        JSONObject()
                            .put("channel", 1)
                            .put("format", "pcm_s16le")
                            .put("sample_rate", OUTPUT_SAMPLE_RATE),
                    ),
            )
            .put(
                "dialog",
                JSONObject()
                    .put("bot_name", config.model.ifBlank { "智能桨板助手" })
                    .put("system_role", REALTIME_SYSTEM_PROMPT)
                    .put("speaking_style", "回复简短、明确。需要控制时优先调用工具，不要把控制写在普通回复里。")
                    .put("location", JSONObject().put("city", "北京"))
                    .put("tools", RealtimeVoiceControlEvent.toolDefinitions())
                    .put("tool_choice", "auto")
                    .put(
                        "extra",
                        JSONObject()
                            .put("strict_audit", false)
                            .put("recv_timeout", if (mode == RealtimeVoiceModeValue.Live) 120 else 10)
                            .put("input_mod", "audio"),
                    ),
            )
        val packet = volcFullRequest(
            event = VOLC_START_SESSION,
            payload = payload,
            includeSession = true,
        )
        webSocket.send(packet.toByteString())
    }

    private fun sendVolcFinishSession(webSocket: WebSocket) {
        webSocket.send(
            volcFullRequest(
                event = VOLC_FINISH_SESSION,
                payload = JSONObject(),
                includeSession = true,
            ).toByteString(),
        )
    }

    private fun sendVolcFinishConnection(webSocket: WebSocket) {
        webSocket.send(volcFullRequest(event = VOLC_FINISH_CONNECTION, payload = JSONObject()).toByteString())
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture(webSocket: WebSocket) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(INPUT_FRAME_BYTES * 4)
        val nextRecorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSize,
        )
        recorder = nextRecorder
        nextRecorder.startRecording()
        captureJob = scope.launch {
            val buffer = ByteArray(INPUT_FRAME_BYTES)
            while (isActive) {
                val read = nextRecorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    uplinkBytes += read
                    if (useVolcProtocol) {
                        webSocket.send(volcAudioRequest(buffer.copyOf(read)).toByteString())
                    } else {
                        webSocket.send(buffer.toByteString(0, read))
                    }
                } else {
                    delay(10)
                }
            }
        }
    }

    private fun startAudioPlayback() {
        val minBufferSize = AudioTrack.getMinBufferSize(
            OUTPUT_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(OUTPUT_FRAME_BYTES * 8)
        player = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(minBufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }

    private fun handleVolcBinaryMessage(bytes: ByteArray) {
        val response = parseVolcResponse(bytes)
        if (response == null) {
            errorCount += 1
            onStatus("实时语音：火山响应解析失败")
            return
        }
        if (response.errorCode != null) {
            errorCount += 1
            onStatus("实时语音：火山错误 ${response.errorCode} ${response.textPayload.orEmpty()}")
            return
        }
        val binaryPayload = response.binaryPayload
        if (response.messageType == SERVER_ACK && binaryPayload != null) {
            handleAudioMessage(binaryPayload)
            return
        }
        val json = response.jsonPayload ?: return
        val event = response.event
        if (event != null) {
            handleVolcEvent(event, json)
        }
    }

    private fun handleVolcEvent(event: Int, json: JSONObject) {
        if (dispatchToolCallIfPresent(json)) {
            return
        }

        when (event) {
            VOLC_EVENT_CLEAR_AUDIO -> {
                player?.pause()
                player?.flush()
                player?.play()
                onStatus("实时语音：用户打断，已清空下行音频")
            }
            VOLC_EVENT_TTS_ENDED -> onStatus("实时语音：回复播放结束")
            VOLC_EVENT_USER_QUERY_DONE -> onStatus("实时语音：用户语音结束")
        }

        val sentence = json.optString("sentence")
            .ifBlank { json.optString("text") }
            .ifBlank { json.optJSONObject("payload")?.optString("sentence").orEmpty() }
        if (sentence.isNotBlank()) {
            if (event == VOLC_EVENT_ASR_FINAL || json.optString("type").contains("asr", ignoreCase = true)) {
                onTranscript(sentence)
            } else {
                onReply(sentence)
            }
        }

        val controlEvent = json.optJSONObject("control_event")
        if (controlEvent != null) {
            controlEventCount += 1
            onControlEvent(controlEvent.put("type", "control_event").toString())
        } else if (json.optString("type") == "control_event") {
            controlEventCount += 1
            onControlEvent(json.toString())
        }
    }

    private fun handleGenericTextMessage(text: String) {
        runCatching {
            val json = JSONObject(text)
            if (dispatchToolCallIfPresent(json)) {
                return@runCatching
            }
            when (json.optString("type").ifBlank { json.optString("event") }) {
                "control_event" -> {
                    controlEventCount += 1
                    onControlEvent(text)
                }
                "transcript", "SentenceRecognized" -> {
                    val payload = json.optJSONObject("payload")
                    onTranscript(
                        json.optString("text")
                            .ifBlank { json.optString("sentence") }
                            .ifBlank { payload?.optString("sentence").orEmpty() },
                    )
                }
                "reply", "TTSSentenceStart" -> {
                    val payload = json.optJSONObject("payload")
                    onReply(
                        json.optString("text")
                            .ifBlank { json.optString("sentence") }
                            .ifBlank { payload?.optString("sentence").orEmpty() },
                    )
                }
                "error", "BotError" -> {
                    errorCount += 1
                    onStatus("实时语音：服务端错误 ${json.optString("message").ifBlank { text }}")
                }
            }
        }.onFailure {
            errorCount += 1
            onStatus("实时语音：文本消息解析失败")
        }
    }

    private fun dispatchToolCallIfPresent(json: JSONObject): Boolean {
        val type = json.optString("type").ifBlank { json.optString("event") }
        val hasToolCall = type.contains("tool_call", ignoreCase = true) ||
            type.contains("function_call", ignoreCase = true) ||
            json.has("tool_call") ||
            json.has("tool_calls") ||
            json.has("function_call") ||
            json.has("function") ||
            json.has("arguments")
        if (!hasToolCall) {
            return false
        }
        controlEventCount += 1
        onControlEvent(json.toString())
        return true
    }

    private fun handleAudioMessage(bytes: ByteArray) {
        downlinkBytes += bytes.size
        player?.write(bytes, 0, bytes.size)
    }

    private fun startMetricsLoop() {
        metricsJob?.cancel()
        metricsJob = scope.launch {
            while (isActive) {
                onMetrics(
                    "上行 ${pcmBytesToSeconds(uplinkBytes, INPUT_SAMPLE_RATE)}s；" +
                        "下行 ${pcmBytesToSeconds(downlinkBytes, OUTPUT_SAMPLE_RATE)}s；" +
                        "事件 $controlEventCount；错误 $errorCount",
                )
                delay(1_000)
            }
        }
    }

    private fun stopLocalAudio() {
        captureJob?.cancel()
        captureJob = null
        metricsJob?.cancel()
        metricsJob = null
        runCatching {
            recorder?.stop()
            recorder?.release()
        }
        recorder = null
        runCatching {
            player?.stop()
            player?.release()
        }
        player = null
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun volcFullRequest(
        event: Int,
        payload: JSONObject,
        includeSession: Boolean = false,
    ): ByteArray {
        val compressedPayload = gzip(payload.toString().encodeToByteArray())
        return buildPacket(
            header = generateVolcHeader(
                messageType = CLIENT_FULL_REQUEST,
                flags = MSG_WITH_EVENT,
                serialization = JSON_SERIALIZATION,
                compression = GZIP_COMPRESSION,
            ),
            event = event,
            includeSession = includeSession,
            payload = compressedPayload,
        )
    }

    private fun volcAudioRequest(audio: ByteArray): ByteArray {
        return buildPacket(
            header = generateVolcHeader(
                messageType = CLIENT_AUDIO_ONLY_REQUEST,
                flags = MSG_WITH_EVENT,
                serialization = NO_SERIALIZATION,
                compression = GZIP_COMPRESSION,
            ),
            event = VOLC_AUDIO_REQUEST,
            includeSession = true,
            payload = gzip(audio),
        )
    }

    private fun buildPacket(
        header: ByteArray,
        event: Int,
        includeSession: Boolean,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(header)
        out.writeInt(event)
        if (includeSession) {
            val sessionBytes = sessionId.encodeToByteArray()
            out.writeInt(sessionBytes.size)
            out.write(sessionBytes)
        }
        out.writeInt(payload.size)
        out.write(payload)
        return out.toByteArray()
    }

    private fun generateVolcHeader(
        messageType: Int,
        flags: Int,
        serialization: Int,
        compression: Int,
    ): ByteArray {
        return byteArrayOf(
            ((PROTOCOL_VERSION shl 4) or DEFAULT_HEADER_SIZE).toByte(),
            ((messageType shl 4) or flags).toByte(),
            ((serialization shl 4) or compression).toByte(),
            0x00,
        )
    }

    private fun parseVolcResponse(bytes: ByteArray): VolcResponse? = runCatching {
        if (bytes.size < 4) {
            return null
        }
        val headerSizeBytes = (bytes[0].toInt() and 0x0f) * 4
        val messageType = (bytes[1].toInt() ushr 4) and 0x0f
        val flags = bytes[1].toInt() and 0x0f
        val serialization = (bytes[2].toInt() ushr 4) and 0x0f
        val compression = bytes[2].toInt() and 0x0f
        var index = headerSizeBytes

        if (messageType == SERVER_ERROR_RESPONSE) {
            val code = bytes.readInt(index)
            index += 4
            val size = bytes.readInt(index)
            index += 4
            val payload = bytes.copyOfRange(index, index + size.coerceAtMost(bytes.size - index))
            val text = decodePayload(payload, serialization, compression)
            return VolcResponse(messageType = messageType, errorCode = code, textPayload = text as? String)
        }

        var event: Int? = null
        if ((flags and MSG_WITH_EVENT) != 0) {
            event = bytes.readInt(index)
            index += 4
        }
        val sessionSize = bytes.readInt(index)
        index += 4 + sessionSize
        val payloadSize = bytes.readInt(index)
        index += 4
        val payload = bytes.copyOfRange(index, index + payloadSize.coerceAtMost(bytes.size - index))
        val decoded = decodePayload(payload, serialization, compression)
        VolcResponse(
            messageType = messageType,
            event = event,
            jsonPayload = decoded as? JSONObject,
            binaryPayload = decoded as? ByteArray,
            textPayload = decoded as? String,
        )
    }.getOrNull()

    private fun decodePayload(
        payload: ByteArray,
        serialization: Int,
        compression: Int,
    ): Any {
        val data = if (compression == GZIP_COMPRESSION) gunzip(payload) else payload
        return when (serialization) {
            JSON_SERIALIZATION -> JSONObject(data.decodeToString())
            NO_SERIALIZATION -> data
            else -> data.decodeToString()
        }
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    private fun gunzip(data: ByteArray): ByteArray {
        return GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
    }

    private fun ByteArray.readInt(offset: Int): Int {
        return ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun pcmBytesToSeconds(bytes: Long, sampleRate: Int): Long {
        return bytes / (sampleRate * BYTES_PER_SAMPLE)
    }

    companion object {
        private const val INPUT_SAMPLE_RATE = 16_000
        private const val OUTPUT_SAMPLE_RATE = 24_000
        private const val BYTES_PER_SAMPLE = 2
        private const val FRAME_MS = 100
        private const val INPUT_FRAME_BYTES = INPUT_SAMPLE_RATE * BYTES_PER_SAMPLE * FRAME_MS / 1_000
        private const val OUTPUT_FRAME_BYTES = OUTPUT_SAMPLE_RATE * BYTES_PER_SAMPLE * FRAME_MS / 1_000

        private const val PROTOCOL_VERSION = 0b0001
        private const val DEFAULT_HEADER_SIZE = 0b0001
        private const val CLIENT_FULL_REQUEST = 0b0001
        private const val CLIENT_AUDIO_ONLY_REQUEST = 0b0010
        private const val SERVER_FULL_RESPONSE = 0b1001
        private const val SERVER_ACK = 0b1011
        private const val SERVER_ERROR_RESPONSE = 0b1111
        private const val MSG_WITH_EVENT = 0b0100
        private const val NO_SERIALIZATION = 0b0000
        private const val JSON_SERIALIZATION = 0b0001
        private const val GZIP_COMPRESSION = 0b0001

        private const val VOLC_RESOURCE_ID = "volc.speech.dialog"
        private const val VOLC_DEFAULT_SPEAKER = "zh_female_vv_uranus_bigtts"
        private const val VOLC_START_CONNECTION = 1
        private const val VOLC_FINISH_CONNECTION = 2
        private const val VOLC_START_SESSION = 100
        private const val VOLC_FINISH_SESSION = 102
        private const val VOLC_AUDIO_REQUEST = 200
        private const val VOLC_EVENT_ASR_FINAL = 350
        private const val VOLC_EVENT_CLEAR_AUDIO = 450
        private const val VOLC_EVENT_USER_QUERY_DONE = 459
        private const val VOLC_EVENT_TTS_ENDED = 359

        private const val REALTIME_SYSTEM_PROMPT =
            "你叫豆包，是智能桨板的实时语音助手。需要控制桨板时必须调用提供的工具；" +
                "普通语音回复只用于解释和确认，不能承载控制指令。" 
    }

    private data class VolcResponse(
        val messageType: Int,
        val event: Int? = null,
        val jsonPayload: JSONObject? = null,
        val binaryPayload: ByteArray? = null,
        val textPayload: String? = null,
        val errorCode: Int? = null,
    )
}

data class RealtimeVoiceConfig(
    val endpoint: String,
    val appId: String,
    val apiKey: String,
    val model: String,
    val voice: String,
)

enum class RealtimeVoiceModeValue(val wireValue: String) {
    PushToTalk("push_to_talk"),
    Live("live"),
}
