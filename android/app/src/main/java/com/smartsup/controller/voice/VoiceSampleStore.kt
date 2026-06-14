package com.smartsup.controller.voice

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject

data class VoiceSampleTarget(
    val label: String,
    val spokenText: String,
    val expectedCommand: String,
)

data class VoiceSampleMetadata(
    val target: VoiceSampleTarget,
    val asrText: String,
    val parsedCommand: String,
    val accepted: Boolean,
    val userJudgement: String,
    val sampleRate: Int,
    val sampleCount: Int,
)

object VoiceSampleStore {
    const val SAMPLE_RATE = 16_000

    fun samplesDir(context: Context): File {
        return File(context.filesDir, "voice_samples").apply { mkdirs() }
    }

    fun save(context: Context, samples: FloatArray, metadata: VoiceSampleMetadata): File {
        val dir = samplesDir(context)
        val baseName = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
            .format(ZonedDateTime.now())
        val wavFile = File(dir, "$baseName.wav")
        val jsonFile = File(dir, "$baseName.json")

        writeWav(wavFile, samples, metadata.sampleRate)
        jsonFile.writeText(buildJson(baseName, wavFile.name, metadata).toString(2))
        return jsonFile
    }

    private fun buildJson(
        sampleId: String,
        audioFileName: String,
        metadata: VoiceSampleMetadata,
    ): JSONObject {
        return JSONObject()
            .put("sample_id", sampleId)
            .put("audio_file", audioFileName)
            .put("expected_label", metadata.target.label)
            .put("expected_spoken_text", metadata.target.spokenText)
            .put("expected_command", metadata.target.expectedCommand)
            .put("asr_text", metadata.asrText)
            .put("parsed_command", metadata.parsedCommand)
            .put("accepted", metadata.accepted)
            .put("user_judgement", metadata.userJudgement)
            .put("sample_rate", metadata.sampleRate)
            .put("sample_count", metadata.sampleCount)
            .put("model", "qwen3-asr-0.6b-int8")
            .put("timestamp", ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
    }

    private fun writeWav(file: File, samples: FloatArray, sampleRate: Int) {
        val pcmBytes = ByteArray(samples.size * 2)
        val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { sample ->
            val pcm = (sample.coerceIn(-1.0f, 1.0f) * Short.MAX_VALUE).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
            buffer.putShort(pcm)
        }

        FileOutputStream(file).use { output ->
            output.write(wavHeader(dataBytes = pcmBytes.size, sampleRate = sampleRate))
            output.write(pcmBytes)
        }
    }

    private fun wavHeader(dataBytes: Int, sampleRate: Int): ByteArray {
        val byteRate = sampleRate * 2
        val blockAlign = 2
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        return buffer.array()
    }
}
