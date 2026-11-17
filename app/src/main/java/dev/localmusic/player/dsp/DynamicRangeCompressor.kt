package dev.localmusic.player.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Feed-forward compressor. Threshold is dBFS with 0 dBFS = digital full scale.
 * One envelope drives every channel so the stereo image stays put.
 * Attack and release are one-pole time constants (about 63% of the way to the target gain).
 * Knee 0 is a hard knee. A fixed full-scale ceiling runs only on the processed path.
 */
class DynamicRangeCompressor {
    var sampleRate: Int = 48_000
        private set
    var channelCount: Int = 2
        private set

    private var envelope = 1f
    private var bypassMix = 0f
    private var attackCoeff = 0f
    private var releaseCoeff = 0f
    private var cachedAttack = Float.NaN
    private var cachedRelease = Float.NaN
    private var cachedRate = 0

    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate.coerceAtLeast(1)
        this.channelCount = channelCount.coerceAtLeast(1)
        cachedRate = 0
    }

    fun resetDynamics() {
        envelope = 1f
        bypassMix = 0f
    }

    /** Test hook: skip the enable crossfade so static measurements see the envelope. */
    fun snapBypass(enabled: Boolean) {
        bypassMix = if (enabled) 1f else 0f
        if (!enabled) envelope = 1f
    }

    fun bypassSettled(enabled: Boolean): Boolean {
        return if (enabled) bypassMix > 0.999f else bypassMix < 1e-4f
    }

    fun currentEnvelope(): Float = envelope

    /**
     * In-place, interleaved. When fully bypassed the buffer is left untouched,
     * including peaks above full scale. The safety ceiling is not applied then.
     */
    fun process(samples: FloatArray, frames: Int, channels: Int, params: CompressorParams) {
        if (frames <= 0 || channels <= 0) return
        val count = frames * channels
        if (count > samples.size) return
        if (!params.enabled && bypassMix < 1e-4f) {
            bypassMix = 0f
            envelope = 1f
            return
        }
        ensureCoeffs(params)
        val makeup = dbToLinear(params.makeupDb)
        val ramp = onePoleCoeff(BYPASS_RAMP_MS, sampleRate)
        val targetMix = if (params.enabled) 1f else 0f
        var frame = 0
        var offset = 0
        while (frame < frames) {
            var peak = 0f
            for (channel in 0 until channels) {
                val sample = samples[offset + channel]
                val magnitude = if (sample.isFinite()) abs(sample) else 0f
                if (magnitude > peak) peak = magnitude
            }
            val inputDb = if (peak < SILENCE_LINEAR) SILENCE_DB else 20f * log10(peak)
            val reductionDb = staticOutputDb(inputDb, params) - inputDb
            var target = dbToLinear(reductionDb) * makeup
            if (!target.isFinite() || target < 0f) target = 1f
            val coeff = if (target < envelope) attackCoeff else releaseCoeff
            envelope = coeff * envelope + (1f - coeff) * target
            if (!envelope.isFinite() || envelope < 0f) envelope = 1f
            bypassMix = ramp * bypassMix + (1f - ramp) * targetMix
            if (!bypassMix.isFinite()) bypassMix = targetMix
            val gain = envelope
            for (channel in 0 until channels) {
                val dryRaw = samples[offset + channel]
                val dry = if (dryRaw.isFinite()) dryRaw else 0f
                var wet = dry * gain
                if (!wet.isFinite()) wet = 0f
                if (wet > SAFETY_CEILING) wet = SAFETY_CEILING
                else if (wet < -SAFETY_CEILING) wet = -SAFETY_CEILING
                val mixed = dry * (1f - bypassMix) + wet * bypassMix
                samples[offset + channel] = if (mixed.isFinite()) mixed else 0f
            }
            offset += channels
            frame++
        }
        if (!params.enabled && bypassMix < 1e-4f) {
            bypassMix = 0f
            envelope = 1f
        }
    }

    private fun ensureCoeffs(params: CompressorParams) {
        if (cachedRate == sampleRate && cachedAttack == params.attackMs && cachedRelease == params.releaseMs) return
        cachedRate = sampleRate
        cachedAttack = params.attackMs
        cachedRelease = params.releaseMs
        attackCoeff = onePoleCoeff(params.attackMs, sampleRate)
        releaseCoeff = onePoleCoeff(params.releaseMs, sampleRate)
    }

    companion object {
        const val SAFETY_CEILING = 1f
        private const val SILENCE_LINEAR = 1e-8f
        private const val SILENCE_DB = -160f
        private const val BYPASS_RAMP_MS = 10f

        fun staticOutputDb(inputDb: Float, params: CompressorParams): Float {
            val threshold = params.thresholdDb
            val ratio = params.ratio.coerceAtLeast(1f)
            val knee = params.kneeDb.coerceAtLeast(0f)
            if (!inputDb.isFinite()) return threshold
            if (ratio == 1f) return inputDb
            if (knee <= 0.05f) {
                return if (inputDb < threshold) inputDb else threshold + (inputDb - threshold) / ratio
            }
            val half = knee / 2f
            return when {
                inputDb < threshold - half -> inputDb
                inputDb > threshold + half -> threshold + (inputDb - threshold) / ratio
                else -> {
                    val distance = inputDb - threshold + half
                    inputDb + (1f / ratio - 1f) * distance * distance / (2f * knee)
                }
            }
        }

        private fun dbToLinear(db: Float): Float {
            val linear = 10f.pow(db / 20f)
            return if (linear.isFinite()) linear else 1f
        }

        private fun onePoleCoeff(timeMs: Float, sampleRate: Int): Float {
            val samples = (timeMs / 1000f) * sampleRate
            if (samples <= 0.01f) return 0f
            val coeff = exp(-1.0 / samples).toFloat()
            return if (coeff.isFinite()) coeff.coerceIn(0f, 0.999999f) else 0f
        }
    }
}
