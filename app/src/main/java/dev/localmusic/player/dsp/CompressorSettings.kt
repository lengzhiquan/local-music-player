package dev.localmusic.player.dsp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Persisted compressor state. The audio thread reads [live] without touching the player. */
class CompressorSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())

    val state: StateFlow<CompressorParams> = _state.asStateFlow()

    @Volatile
    var live: CompressorParams = _state.value
        private set

    fun setEnabled(enabled: Boolean) = update { it.copy(enabled = enabled) }

    fun setThreshold(value: Float) = update { it.copy(thresholdDb = value) }

    fun setRatio(value: Float) = update { it.copy(ratio = value) }

    fun setAttack(value: Float) = update { it.copy(attackMs = value) }

    fun setRelease(value: Float) = update { it.copy(releaseMs = value) }

    fun setKnee(value: Float) = update { it.copy(kneeDb = value) }

    fun setMakeup(value: Float) = update { it.copy(makeupDb = value) }

    private fun update(transform: (CompressorParams) -> CompressorParams) {
        val next = transform(live).sanitized()
        live = next
        _state.value = next
        prefs.edit()
            .putBoolean(KEY_ENABLED, next.enabled)
            .putFloat(KEY_THRESHOLD, next.thresholdDb)
            .putFloat(KEY_RATIO, next.ratio)
            .putFloat(KEY_ATTACK, next.attackMs)
            .putFloat(KEY_RELEASE, next.releaseMs)
            .putFloat(KEY_KNEE, next.kneeDb)
            .putFloat(KEY_MAKEUP, next.makeupDb)
            .apply()
    }

    private fun read(): CompressorParams {
        return CompressorParams(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            thresholdDb = prefs.getFloat(KEY_THRESHOLD, CompressorRanges.THRESHOLD.default),
            ratio = prefs.getFloat(KEY_RATIO, CompressorRanges.RATIO.default),
            attackMs = prefs.getFloat(KEY_ATTACK, CompressorRanges.ATTACK.default),
            releaseMs = prefs.getFloat(KEY_RELEASE, CompressorRanges.RELEASE.default),
            kneeDb = prefs.getFloat(KEY_KNEE, CompressorRanges.KNEE.default),
            makeupDb = prefs.getFloat(KEY_MAKEUP, CompressorRanges.MAKEUP.default),
        ).sanitized()
    }

    companion object {
        private const val PREFS = "compressor"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_THRESHOLD = "threshold_db"
        private const val KEY_RATIO = "ratio"
        private const val KEY_ATTACK = "attack_ms"
        private const val KEY_RELEASE = "release_ms"
        private const val KEY_KNEE = "knee_db"
        private const val KEY_MAKEUP = "makeup_db"
    }
}
