package dev.localmusic.player.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import dev.localmusic.player.dsp.CompressorParams
import dev.localmusic.player.dsp.DynamicRangeCompressor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Always stays in the audio chain so enabling it does not rebuild the player.
 * Disabled and settled: the PCM bytes are copied unchanged.
 */
class CompressorAudioProcessor(
    private val currentParams: () -> CompressorParams,
) : BaseAudioProcessor() {
    private val engine = DynamicRangeCompressor()
    private var floatPcm = false
    private var passthroughOnly = false
    private var scratch = FloatArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        floatPcm = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        val pcm16 = inputAudioFormat.encoding == C.ENCODING_PCM_16BIT
        passthroughOnly = !floatPcm && !pcm16
        if (!passthroughOnly && inputAudioFormat.sampleRate > 0 && inputAudioFormat.channelCount > 0) {
            engine.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        }
        return inputAudioFormat
    }

    override fun onFlush() {
        engine.resetDynamics()
    }

    override fun onReset() {
        engine.resetDynamics()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        val params = currentParams()
        if (passthroughOnly || engine.bypassSettled(params.enabled) && !params.enabled) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val bytesPerFrame = inputAudioFormat.bytesPerFrame.coerceAtLeast(1)
        val frames = remaining / bytesPerFrame
        val samples = frames * channels
        if (frames == 0) {
            output.put(inputBuffer)
            output.flip()
            return
        }
        if (scratch.size < samples) scratch = FloatArray(samples)
        val source = inputBuffer.duplicate().order(ByteOrder.nativeOrder())
        if (floatPcm) {
            var index = 0
            while (index < samples && source.remaining() >= 4) {
                scratch[index++] = source.float
            }
        } else {
            var index = 0
            while (index < samples && source.remaining() >= 2) {
                scratch[index++] = source.short / 32768f
            }
        }
        inputBuffer.position(inputBuffer.limit())
        engine.process(scratch, frames, channels, params)
        if (floatPcm) {
            for (index in 0 until samples) output.putFloat(scratch[index])
        } else {
            for (index in 0 until samples) {
                val quantized = (scratch[index] * 32768f).toInt().coerceIn(-32768, 32767)
                output.putShort(quantized.toShort())
            }
        }
        output.flip()
    }
}
