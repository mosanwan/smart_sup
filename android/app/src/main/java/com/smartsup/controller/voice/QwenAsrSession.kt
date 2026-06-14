package com.smartsup.controller.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QwenAsrSession(
    context: Context,
    private val onStatusChange: (String) -> Unit,
    private val onPartialText: (String) -> Unit,
    private val onFinalText: (String) -> Unit,
    private val onFinalSegment: (String, FloatArray) -> Unit = { text, _ -> onFinalText(text) },
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var recognizer: OfflineRecognizer? = null
    private var vad: Vad? = null
    private var loadedModelDir: File? = null
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var processJob: Job? = null
    private var samplesChannel: Channel<FloatArray>? = null
    @Volatile private var active = false

    fun start() {
        if (active) {
            return
        }
        active = true
        notifyStatus("Qwen ASR：初始化模型")
        scope.launch {
            val modelDir = QwenAsrModelFiles.findInstalledModelDir(appContext)
            if (modelDir == null) {
                active = false
                notifyStatus(QwenAsrModelFiles.missingModelMessage(appContext))
                return@launch
            }

            runCatching {
                ensureEngine(modelDir)
                startAudioPipeline()
            }.onFailure { error ->
                active = false
                stopAudioPipeline()
                notifyStatus("Qwen ASR 启动失败：${error.message ?: "未知错误"}")
            }
        }
    }

    fun stop() {
        active = false
        samplesChannel?.trySend(FloatArray(0))
        stopAudioPipeline()
        vad?.reset()
        notifyStatus("Qwen ASR：已暂停")
    }

    fun destroy() {
        active = false
        stopAudioPipeline()
        recognizer?.release()
        recognizer = null
        vad?.release()
        vad = null
        scope.cancel()
    }

    private fun ensureEngine(modelDir: File) {
        if (recognizer != null && vad != null && loadedModelDir == modelDir) {
            return
        }

        recognizer?.release()
        vad?.release()

        val modelConfig = OfflineModelConfig(
            qwen3Asr = OfflineQwen3AsrModelConfig(
                convFrontend = File(modelDir, "conv_frontend.onnx").absolutePath,
                encoder = File(modelDir, "encoder.int8.onnx").absolutePath,
                decoder = File(modelDir, "decoder.int8.onnx").absolutePath,
                tokenizer = File(modelDir, "tokenizer").absolutePath,
                maxTotalLen = 512,
                maxNewTokens = 128,
            ),
            tokens = "",
            numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 3),
            provider = "cpu",
        )

        recognizer = OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(modelConfig = modelConfig),
        )
        vad = Vad(
            assetManager = appContext.assets,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = VAD_ASSET_NAME,
                    threshold = 0.5f,
                    minSilenceDuration = 0.35f,
                    minSpeechDuration = 0.18f,
                    windowSize = VAD_WINDOW_SIZE,
                    maxSpeechDuration = 5.0f,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
            ),
        )
        loadedModelDir = modelDir
        notifyStatus("Qwen ASR：模型已加载，开始监听")
    }

    @SuppressLint("MissingPermission")
    private fun startAudioPipeline() {
        stopAudioPipeline()
        val channel = Channel<FloatArray>(capacity = Channel.UNLIMITED)
        samplesChannel = channel
        vad?.reset()

        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBufferBytes > 0) { "无法创建录音缓冲区" }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferBytes * 2,
        )

        recordJob = scope.launch(Dispatchers.IO) {
            val intervalSeconds = 0.1f
            val buffer = ShortArray((SAMPLE_RATE * intervalSeconds).toInt())
            audioRecord?.startRecording()
            notifyStatus("Qwen ASR：麦克风监听中")
            while (active && isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: break
                if (read > 0) {
                    val samples = FloatArray(read) { index -> buffer[index] / PCM_16BIT_SCALE }
                    channel.send(samples)
                }
            }
            channel.trySend(FloatArray(0))
        }

        processJob = scope.launch(Dispatchers.Default) {
            processSamples(channel)
        }
    }

    private fun stopAudioPipeline() {
        recordJob?.cancel()
        recordJob = null
        processJob?.cancel()
        processJob = null
        samplesChannel?.close()
        samplesChannel = null
        audioRecord?.runCatchingStop()
        audioRecord = null
    }

    private suspend fun processSamples(channel: Channel<FloatArray>) {
        val currentVad = requireNotNull(vad)
        val currentRecognizer = requireNotNull(recognizer)
        val buffer = ArrayList<Float>(SAMPLE_RATE * 5)
        var offset = 0
        var speechStarted = false
        var speechStartOffset = 0
        var lastDecodeAt = System.currentTimeMillis()
        var lastPartialText = ""

        for (samples in channel) {
            if (!active || samples.isEmpty()) {
                break
            }

            buffer.addAll(samples.asIterable())
            while (offset + VAD_WINDOW_SIZE < buffer.size) {
                currentVad.acceptWaveform(buffer.subList(offset, offset + VAD_WINDOW_SIZE).toFloatArray())
                offset += VAD_WINDOW_SIZE
                if (!speechStarted && currentVad.isSpeechDetected()) {
                    speechStarted = true
                    speechStartOffset = (offset - SPEECH_START_PADDING_SAMPLES).coerceAtLeast(0)
                    lastDecodeAt = 0L
                    notifyStatus("Qwen ASR：正在识别")
                }
            }

            val now = System.currentTimeMillis()
            if (
                speechStarted &&
                now - lastDecodeAt >= PARTIAL_DECODE_INTERVAL_MS &&
                offset - speechStartOffset >= MIN_PARTIAL_SAMPLES
            ) {
                val text = decode(currentRecognizer, buffer.subList(speechStartOffset, offset).toFloatArray())
                if (text.isNotBlank() && text != lastPartialText) {
                    lastPartialText = text
                    notifyPartial(text)
                }
                lastDecodeAt = now
            }

            while (!currentVad.empty()) {
                val segment = currentVad.front()
                val finalText = decode(currentRecognizer, segment.samples)
                currentVad.pop()

                speechStarted = false
                buffer.clear()
                offset = 0
                lastDecodeAt = System.currentTimeMillis()
                lastPartialText = ""

                if (finalText.isNotBlank()) {
                    notifyFinal(finalText, segment.samples.copyOf())
                    notifyStatus("Qwen ASR：继续监听")
                }
            }
        }
    }

    private fun decode(recognizer: OfflineRecognizer, samples: FloatArray): String {
        val stream = recognizer.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            recognizer.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun notifyStatus(message: String) {
        scope.launch(Dispatchers.Main) { onStatusChange(message) }
    }

    private fun notifyPartial(text: String) {
        scope.launch(Dispatchers.Main) { onPartialText(text) }
    }

    private fun notifyFinal(text: String, samples: FloatArray) {
        scope.launch(Dispatchers.Main) { onFinalSegment(text, samples) }
    }

    private fun AudioRecord.runCatchingStop() {
        runCatching { stop() }
        runCatching { release() }
    }

    companion object {
        const val MODEL_DIR_NAME = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
        private const val VAD_ASSET_NAME = "silero_vad.onnx"
        private const val SAMPLE_RATE = 16000
        private const val VAD_WINDOW_SIZE = 512
        private const val SPEECH_START_PADDING_SAMPLES = 6400
        private const val MIN_PARTIAL_SAMPLES = SAMPLE_RATE / 2
        private const val PARTIAL_DECODE_INTERVAL_MS = 900L
        private const val PCM_16BIT_SCALE = 32768.0f
    }
}

object QwenAsrModelFiles {
    private val requiredFiles = listOf(
        "conv_frontend.onnx",
        "encoder.int8.onnx",
        "decoder.int8.onnx",
        "tokenizer/merges.txt",
        "tokenizer/tokenizer_config.json",
        "tokenizer/vocab.json",
    )

    fun preferredModelDir(context: Context): File {
        return File(context.filesDir, "models/${QwenAsrSession.MODEL_DIR_NAME}")
    }

    fun findInstalledModelDir(context: Context): File? {
        val candidates = listOf(
            preferredModelDir(context),
            File(context.getExternalFilesDir(null) ?: context.filesDir, "models/${QwenAsrSession.MODEL_DIR_NAME}"),
        )
        return candidates.firstOrNull { dir ->
            requiredFiles.all { relativePath -> File(dir, relativePath).isFile }
        }
    }

    fun missingModelMessage(context: Context): String {
        return "Qwen ASR 模型未安装：请用 tools/prepare_qwen_asr_model.sh 导入到 ${preferredModelDir(context).absolutePath}"
    }
}
