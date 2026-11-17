package dev.localmusic.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import dev.localmusic.player.dsp.CompressorParams
import dev.localmusic.player.dsp.CompressorRanges
import dev.localmusic.player.dsp.DynamicRangeCompressor
import dev.localmusic.player.playback.CompressorAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

class CompressorTest {
    @Test
    fun defaultsAreDisabledAndInsideTheSafeRanges() {
        val params = CompressorParams()
        assertFalse(params.enabled)
        assertEquals(-18f, params.thresholdDb, 0f)
        assertEquals(4f, params.ratio, 0f)
        assertEquals(10f, params.attackMs, 0f)
        assertEquals(100f, params.releaseMs, 0f)
        assertEquals(6f, params.kneeDb, 0f)
        assertEquals(0f, params.makeupDb, 0f)
        assertTrue(params.thresholdDb in CompressorRanges.THRESHOLD.min..CompressorRanges.THRESHOLD.max)
        assertTrue(params.ratio in CompressorRanges.RATIO.min..CompressorRanges.RATIO.max)
    }

    @Test
    fun sanitizerClampsOutOfRangeAndNonFiniteValues() {
        val cleaned = CompressorParams(
            enabled = true,
            thresholdDb = Float.NaN,
            ratio = 100f,
            attackMs = -4f,
            releaseMs = Float.POSITIVE_INFINITY,
            kneeDb = 80f,
            makeupDb = Float.NEGATIVE_INFINITY,
        ).sanitized()
        assertTrue(cleaned.enabled)
        assertEquals(CompressorRanges.THRESHOLD.default, cleaned.thresholdDb, 0f)
        assertEquals(CompressorRanges.RATIO.max, cleaned.ratio, 0f)
        assertEquals(CompressorRanges.ATTACK.min, cleaned.attackMs, 0f)
        assertEquals(CompressorRanges.RELEASE.default, cleaned.releaseMs, 0f)
        assertEquals(CompressorRanges.KNEE.max, cleaned.kneeDb, 0f)
        assertEquals(CompressorRanges.MAKEUP.default, cleaned.makeupDb, 0f)
    }

    @Test
    fun hardKneeFollowsThresholdAndRatio() {
        val params = tone(thresholdDb = -20f, ratio = 4f, kneeDb = 0f)
        assertEquals(-30f, DynamicRangeCompressor.staticOutputDb(-30f, params), 1e-4f)
        assertEquals(-17.5f, DynamicRangeCompressor.staticOutputDb(-10f, params), 1e-3f)
        assertEquals(-20f, DynamicRangeCompressor.staticOutputDb(-20f, params), 1e-3f)
        val unity = tone(thresholdDb = -20f, ratio = 1f, kneeDb = 0f)
        assertEquals(-6f, DynamicRangeCompressor.staticOutputDb(-6f, unity), 1e-4f)
    }

    @Test
    fun softKneeIsContinuousAndRoundsTheCorner() {
        val params = tone(thresholdDb = -20f, ratio = 4f, kneeDb = 8f)
        val lower = -24f
        val upper = -16f
        assertEquals(lower, DynamicRangeCompressor.staticOutputDb(lower, params), 1e-3f)
        assertEquals(-30f, DynamicRangeCompressor.staticOutputDb(-30f, params), 1e-4f)
        val hardAtUpper = -20f + (upper - -20f) / 4f
        assertEquals(hardAtUpper, DynamicRangeCompressor.staticOutputDb(upper, params), 1e-3f)
        val hardAtThreshold = -20f
        val softAtThreshold = DynamicRangeCompressor.staticOutputDb(-20f, params)
        assertTrue(softAtThreshold < hardAtThreshold)
        val justBelow = DynamicRangeCompressor.staticOutputDb(lower + 0.5f, params)
        assertTrue(justBelow < lower + 0.5f)
    }

    @Test
    fun settledSineFollowsTheStaticCurveMakeupAndCeiling() {
        val quiet = levelOf(dbToLinear(-40.0), tone(thresholdDb = -18f, ratio = 4f, kneeDb = 0f))
        assertEquals(-40.0, toDb(quiet), 0.15)

        val compressed = levelOf(dbToLinear(-10.0), tone(thresholdDb = -20f, ratio = 4f, kneeDb = 0f))
        assertEquals(-17.5, toDb(compressed), 0.2)

        val madeUp = levelOf(
            dbToLinear(-40.0),
            tone(thresholdDb = -18f, ratio = 4f, kneeDb = 0f, makeupDb = 6f),
        )
        assertEquals(-34.0, toDb(madeUp), 0.2)

        val clipped = levelOf(1.0, tone(thresholdDb = -12f, ratio = 1f, kneeDb = 0f, makeupDb = 24f))
        assertEquals(DynamicRangeCompressor.SAFETY_CEILING.toDouble(), clipped, 1e-4)
    }

    @Test
    fun attackAndReleaseMoveAboutSixtyThreePercentInOneTimeConstant() {
        val rate = 48_000
        val attackMs = 10f
        val params = tone(thresholdDb = -20f, ratio = 20f, kneeDb = 0f, attackMs = attackMs, releaseMs = 100f)
        val engine = engine(rate, params.enabled)
        engine.process(FloatArray(rate / 10), rate / 10, 1, params)
        val loud = FloatArray(480) { dbToLinear(-6.0).toFloat() }
        engine.process(loud, loud.size, 1, params)
        val target = targetGain(dbToLinear(-6.0).toFloat(), params)
        val expected = target + (1.0 - target) * exp(-1.0)
        assertEquals(expected, engine.currentEnvelope().toDouble(), 0.02)
        assertTrue(engine.currentEnvelope() > target)
        assertTrue(engine.currentEnvelope() < 0.9f)

        val releaseMs = 100f
        val settled = engine(rate, true)
        val hold = tone(thresholdDb = -20f, ratio = 20f, kneeDb = 0f, attackMs = 0.1f, releaseMs = releaseMs)
        settled.process(FloatArray(rate / 5) { dbToLinear(-6.0).toFloat() }, rate / 5, 1, hold)
        val start = settled.currentEnvelope()
        val silenceFrames = 4_800
        settled.process(FloatArray(silenceFrames), silenceFrames, 1, hold)
        val released = 1.0 + (start - 1.0) * exp(-silenceFrames / (releaseMs / 1000.0 * rate))
        assertEquals(released, settled.currentEnvelope().toDouble(), 0.02)
        assertTrue(settled.currentEnvelope() > start)
    }

    @Test
    fun higherSampleRateSlowsTheEnvelopeInSamples() {
        val params = tone(thresholdDb = -20f, ratio = 20f, kneeDb = 0f, attackMs = 10f, releaseMs = 100f)
        val frames = 480
        val slow = envelopeAfterStep(48_000, frames, params)
        val fastClock = envelopeAfterStep(96_000, frames, params)
        val target = targetGain(dbToLinear(-6.0).toFloat(), params)
        assertTrue(abs(slow - target) < abs(fastClock - target))
    }

    @Test
    fun stereoLinkUsesOneGainAndKeepsTheImage() {
        val params = tone(thresholdDb = -12f, ratio = 8f, kneeDb = 0f, attackMs = 0.1f, releaseMs = 5f)
        val engine = engine(48_000, true)
        val frames = 8_000
        val samples = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            samples[frame * 2] = 0.5f
            samples[frame * 2 + 1] = -0.2f
        }
        engine.process(samples, frames, 2, params)
        val left = samples[samples.size - 2]
        val right = samples[samples.size - 1]
        assertTrue(left < 0.5f)
        assertTrue(right < 0f)
        assertEquals(0.4, abs(right / left).toDouble(), 0.01)
        assertEquals(left / 0.5f, right / -0.2f, 0.001f)
    }

    @Test
    fun monoAndMultichannelShareTheFramePeak() {
        val params = tone(thresholdDb = -12f, ratio = 10f, kneeDb = 0f, attackMs = 0.1f, releaseMs = 5f)
        val mono = levelOf(0.5, params, channels = 1)
        val wide = engine(48_000, true)
        val frames = 12_000
        val samples = FloatArray(frames * 6)
        for (frame in 0 until frames) {
            samples[frame * 6] = 0.5f
            samples[frame * 6 + 1] = 0.1f
        }
        wide.process(samples, frames, 6, params)
        assertEquals(mono.toFloat(), samples[samples.size - 6], 0.01f)
        assertEquals(samples[samples.size - 6] / 0.5f, samples[samples.size - 5] / 0.1f, 0.001f)
    }

    @Test
    fun bypassIsBitExactAndDoesNotApplyTheCeiling() {
        val engine = engine(48_000, false)
        val original = floatArrayOf(1.5f, -1.25f, 0.2f, Float.NaN, Float.POSITIVE_INFINITY, 0f)
        val samples = original.copyOf()
        engine.process(samples, samples.size, 1, CompressorParams(enabled = false, makeupDb = 24f))
        for (index in original.indices) {
            assertEquals(original[index].toRawBits(), samples[index].toRawBits())
        }
        assertTrue(engine.bypassSettled(false))
    }

    @Test
    fun enabledPathReplacesNonFiniteSamplesAndClampsOnlyTheWetSignal() {
        val params = tone(thresholdDb = -60f, ratio = 1f, kneeDb = 0f, attackMs = 0.1f, releaseMs = 5f, makeupDb = 0f)
        val engine = engine(48_000, true)
        val samples = floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY, 0.25f, 0.25f)
        engine.process(samples, samples.size, 1, params)
        assertEquals(0f, samples[0], 0f)
        assertEquals(0f, samples[1], 0f)
        assertTrue(samples[3].isFinite())
        val hot = levelOf(1.0, tone(ratio = 1f, kneeDb = 0f, makeupDb = 24f, attackMs = 0.1f, releaseMs = 5f))
        assertTrue(hot <= 1.0 + 1e-5)
        val bypass = engine(48_000, false)
        val above = floatArrayOf(1.7f)
        bypass.process(above, 1, 1, CompressorParams(enabled = false))
        assertEquals(1.7f, above[0], 0f)
    }

    @Test
    fun enablingDoesNotJumpGainInOneSampleAndParameterEditsDoNotResetTheEnvelope() {
        val engine = engine(48_000, false)
        val dry = FloatArray(64) { 0.8f }
        engine.process(dry, dry.size, 1, tone(enabled = false))
        val params = tone(thresholdDb = -24f, ratio = 20f, kneeDb = 0f, attackMs = 50f, releaseMs = 200f)
        val first = floatArrayOf(0.8f)
        engine.process(first, 1, 1, params)
        assertTrue(abs(first[0] - 0.8f) < 0.02f)

        engine.snapBypass(true)
        engine.process(FloatArray(8_000) { 0.8f }, 8_000, 1, params)
        val compressed = engine.currentEnvelope()
        assertTrue(compressed < 0.5f)
        val eased = params.copy(ratio = 1f)
        engine.process(floatArrayOf(0.8f), 1, 1, eased)
        assertTrue(engine.currentEnvelope() < compressed + 0.05f)
        assertTrue(engine.currentEnvelope() > compressed)
    }

    @Test
    fun disabledProcessorCopiesPcmBytesAndEnabledProcessorClampsInt16() {
        val disabled = CompressorAudioProcessor { CompressorParams(enabled = false, makeupDb = 24f) }
        val format = AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_16BIT)
        disabled.configure(format)
        disabled.flush()
        val source = shortArrayOf(32767, -32768, 0, 1234, -50)
        val input = shorts(source)
        val expected = input.duplicate().order(ByteOrder.nativeOrder())
        disabled.queueInput(input)
        val output = disabled.getOutput()
        assertEquals(expected.remaining(), output.remaining())
        while (expected.hasRemaining()) {
            assertEquals(expected.get().toInt(), output.get().toInt())
        }

        val enabled = CompressorAudioProcessor {
            tone(thresholdDb = -60f, ratio = 1f, kneeDb = 0f, attackMs = 0.1f, releaseMs = 5f, makeupDb = 24f)
        }
        enabled.configure(format)
        enabled.flush()
        val hot = ShortArray(48_000 / 4) { 20000 }
        enabled.queueInput(shorts(hot))
        val processed = enabled.getOutput().order(ByteOrder.nativeOrder())
        var peak = 0
        var last = 0
        while (processed.hasRemaining()) {
            last = processed.short.toInt()
            if (abs(last) > peak) peak = abs(last)
        }
        assertTrue(peak <= 32767)
        assertEquals(32767, last)
    }

    @Test
    fun unknownEncodingIsCopiedAndFloatBypassKeepsSamplesAboveFullScale() {
        val processor = CompressorAudioProcessor { CompressorParams(enabled = false) }
        val odd = AudioProcessor.AudioFormat(44_100, 2, 99)
        processor.configure(odd)
        processor.flush()
        val raw = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        raw.putLong(0x1122334455667788L)
        raw.flip()
        val expected = raw.duplicate()
        processor.queueInput(raw)
        val output = processor.getOutput()
        assertEquals(expected.remaining(), output.remaining())
        while (expected.hasRemaining()) assertEquals(expected.get().toInt(), output.get().toInt())

        val floats = CompressorAudioProcessor { CompressorParams(enabled = false, makeupDb = 12f) }
        floats.configure(AudioProcessor.AudioFormat(48_000, 1, C.ENCODING_PCM_FLOAT))
        floats.flush()
        val buffer = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        buffer.putFloat(1.4f)
        buffer.putFloat(-1.2f)
        buffer.flip()
        floats.queueInput(buffer)
        val read = floats.getOutput().order(ByteOrder.nativeOrder())
        assertEquals(1.4f, read.float, 0f)
        assertEquals(-1.2f, read.float, 0f)
    }

    private fun tone(
        enabled: Boolean = true,
        thresholdDb: Float = -18f,
        ratio: Float = 4f,
        attackMs: Float = 0.1f,
        releaseMs: Float = 5f,
        kneeDb: Float = 0f,
        makeupDb: Float = 0f,
    ) = CompressorParams(enabled, thresholdDb, ratio, attackMs, releaseMs, kneeDb, makeupDb)

    private fun engine(sampleRate: Int, enabled: Boolean): DynamicRangeCompressor {
        return DynamicRangeCompressor().also {
            it.configure(sampleRate, 2)
            it.snapBypass(enabled)
        }
    }

    private fun levelOf(amplitude: Double, params: CompressorParams, channels: Int = 1, rate: Int = 48_000): Double {
        val rendered = engine(rate, true)
        val frames = rate / 4
        val data = FloatArray(frames * channels) { amplitude.toFloat() }
        rendered.process(data, frames, channels, params)
        return abs(data.last()).toDouble()
    }

    private fun envelopeAfterStep(rate: Int, frames: Int, params: CompressorParams): Double {
        val rendered = engine(rate, true)
        rendered.process(FloatArray(rate / 20), rate / 20, 1, params)
        rendered.process(FloatArray(frames) { dbToLinear(-6.0).toFloat() }, frames, 1, params)
        return rendered.currentEnvelope().toDouble()
    }

    private fun targetGain(amplitude: Float, params: CompressorParams): Double {
        val inputDb = 20f * kotlin.math.log10(amplitude)
        val outputDb = DynamicRangeCompressor.staticOutputDb(inputDb, params) + params.makeupDb
        return 10.0.pow((outputDb - inputDb) / 20.0)
    }

    private fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

    private fun toDb(linear: Double): Double = 20.0 * ln(linear) / ln(10.0)

    private fun shorts(values: ShortArray): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder())
        values.forEach { buffer.putShort(it) }
        buffer.flip()
        return buffer
    }
}
