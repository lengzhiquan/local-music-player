package dev.localmusic.player.dsp

data class CompressorParams(
    val enabled: Boolean = false,
    val thresholdDb: Float = CompressorRanges.THRESHOLD.default,
    val ratio: Float = CompressorRanges.RATIO.default,
    val attackMs: Float = CompressorRanges.ATTACK.default,
    val releaseMs: Float = CompressorRanges.RELEASE.default,
    val kneeDb: Float = CompressorRanges.KNEE.default,
    val makeupDb: Float = CompressorRanges.MAKEUP.default,
) {
    fun sanitized(): CompressorParams {
        return copy(
            thresholdDb = CompressorRanges.THRESHOLD.clamp(thresholdDb),
            ratio = CompressorRanges.RATIO.clamp(ratio),
            attackMs = CompressorRanges.ATTACK.clamp(attackMs),
            releaseMs = CompressorRanges.RELEASE.clamp(releaseMs),
            kneeDb = CompressorRanges.KNEE.clamp(kneeDb),
            makeupDb = CompressorRanges.MAKEUP.clamp(makeupDb),
        )
    }
}

data class CompressorRange(
    val min: Float,
    val max: Float,
    val default: Float,
    val step: Float,
    val unit: String,
) {
    fun clamp(value: Float): Float {
        if (!value.isFinite()) return default
        return value.coerceIn(min, max)
    }
}

object CompressorRanges {
    val THRESHOLD = CompressorRange(-60f, 0f, -18f, 0.1f, "dBFS")
    val RATIO = CompressorRange(1f, 20f, 4f, 0.1f, ": 1")
    val ATTACK = CompressorRange(0.1f, 200f, 10f, 0.1f, "ms")
    val RELEASE = CompressorRange(5f, 2000f, 100f, 1f, "ms")
    val KNEE = CompressorRange(0f, 24f, 6f, 0.1f, "dB")
    val MAKEUP = CompressorRange(0f, 24f, 0f, 0.1f, "dB")
}
