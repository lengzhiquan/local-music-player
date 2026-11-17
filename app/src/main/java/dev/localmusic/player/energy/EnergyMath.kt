package dev.localmusic.player.energy

import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

data class EnergyFeatures(
    val rms: Float,
    val onsetPerSec: Float,
    val bpm: Float,
    val bpmConfidence: Float,
    val score: Float,
)

enum class EnergyGroup {
    CALM,
    ENERGETIC,
    UNKNOWN,
}

data class ScoredItem<T>(
    val item: T,
    val group: EnergyGroup,
    val score: Float?,
)

/**
 * On-device energy score from decoded PCM.
 *
 * Loudness is RMS converted to dBFS. Motion is an onset rate (20 ms frame
 * energy, 10 ms hop; a jump of at least 70% above a small absolute floor,
 * with a 50 ms refractory) plus a tempo guess from autocorrelation of that
 * onset envelope between 60 and 180 BPM. The score is
 * 0.50 * loudness + 0.30 * tempo + 0.20 * onset density, each scaled to 0..1.
 * Playback order splits the queue at the median score and alternates
 * calm, energetic, calm, energetic, shuffled inside each half.
 * Tracks without a score stay at the end.
 */
object EnergyMath {
    fun features(samples: FloatArray, sampleRate: Int): EnergyFeatures {
        if (samples.isEmpty() || sampleRate <= 0) {
            return EnergyFeatures(0f, 0f, 0f, 0f, 0f)
        }
        var sumSq = 0.0
        for (sample in samples) sumSq += sample * sample
        val rms = sqrt(sumSq / samples.size).toFloat()

        val win = (sampleRate * 0.020).toInt().coerceAtLeast(1)
        val hop = (sampleRate * 0.010).toInt().coerceAtLeast(1)
        val energies = ArrayList<Float>(samples.size / hop + 1)
        var cursor = 0
        while (cursor + win <= samples.size) {
            var energy = 0.0
            for (i in 0 until win) {
                val value = samples[cursor + i]
                energy += value * value
            }
            energies += (energy / win).toFloat()
            cursor += hop
        }
        if (energies.size < 4) {
            return EnergyFeatures(rms, 0f, 0f, 0f, score(rms, 0f, 0f, 0f))
        }

        // Ignore the tiny ripple of a steady tone. An onset is an energy jump
        // of at least 70% that also clears a small absolute floor.
        val flux = FloatArray(energies.size)
        val refractory = 5 // 50 ms
        var peaks = 0
        var lastPeak = -refractory
        for (i in 1 until energies.size) {
            val previous = energies[i - 1]
            val current = energies[i]
            val rise = current - previous
            val ratio = when {
                previous > 1e-8f -> current / previous
                current > 1e-4f -> 8f
                else -> 1f
            }
            val onset = rise > 1e-4f && ratio >= 1.7f
            flux[i] = if (onset) rise else 0f
            if (onset && i - lastPeak >= refractory) {
                peaks++
                lastPeak = i
            }
        }
        val seconds = samples.size.toFloat() / sampleRate
        val onsetPerSec = if (seconds > 0f) peaks / seconds else 0f
        val hopSec = hop.toFloat() / sampleRate
        val (bpm, confidence) = estimateBpm(flux, hopSec)
        return EnergyFeatures(
            rms = rms,
            onsetPerSec = onsetPerSec,
            bpm = bpm,
            bpmConfidence = confidence,
            score = score(rms, onsetPerSec, bpm, confidence),
        )
    }

    fun score(rms: Float, onsetPerSec: Float, bpm: Float, bpmConfidence: Float): Float {
        val db = (20.0 * ln((rms + 1e-8).toDouble()) / ln(10.0)).toFloat()
        val loud = ((db + 42f) / 30f).coerceIn(0f, 1f)
        val onsetNorm = (onsetPerSec / 5f).coerceIn(0f, 1f)
        val tempoFromBpm = ((bpm - 60f) / 120f).coerceIn(0f, 1f)
        val tempo = if (bpmConfidence >= 0.18f && bpm > 0f) tempoFromBpm else onsetNorm
        return 0.50f * loud + 0.30f * tempo + 0.20f * onsetNorm
    }

    fun <T> arrange(
        items: List<T>,
        scoreOf: (T) -> Float?,
        random: Random = Random.Default,
    ): List<ScoredItem<T>> {
        if (items.isEmpty()) return emptyList()
        val known = items.filter { scoreOf(it) != null }
        val unknown = items.filter { scoreOf(it) == null }.shuffled(random)
        if (known.isEmpty()) {
            return unknown.map { ScoredItem(it, EnergyGroup.UNKNOWN, null) }
        }
        if (known.size == 1) {
            val only = known[0]
            return listOf(ScoredItem(only, EnergyGroup.CALM, scoreOf(only))) +
                unknown.map { ScoredItem(it, EnergyGroup.UNKNOWN, null) }
        }
        val (calm, energetic) = split(known, scoreOf)
        val calmQueue = calm.shuffled(random)
        val energeticQueue = energetic.shuffled(random)
        val ordered = ArrayList<ScoredItem<T>>(items.size)
        var c = 0
        var e = 0
        while (c < calmQueue.size || e < energeticQueue.size) {
            if (c < calmQueue.size) {
                val item = calmQueue[c++]
                ordered += ScoredItem(item, EnergyGroup.CALM, scoreOf(item))
            }
            if (e < energeticQueue.size) {
                val item = energeticQueue[e++]
                ordered += ScoredItem(item, EnergyGroup.ENERGETIC, scoreOf(item))
            }
        }
        unknown.forEach { ordered += ScoredItem(it, EnergyGroup.UNKNOWN, null) }
        return ordered
    }

    private fun <T> split(known: List<T>, scoreOf: (T) -> Float?): Pair<List<T>, List<T>> {
        val sorted = known.sortedBy { scoreOf(it) ?: 0f }
        val median = scoreOf(sorted[sorted.size / 2]) ?: 0f
        var calm = sorted.filter { (scoreOf(it) ?: 0f) < median }
        var energetic = sorted.filter { (scoreOf(it) ?: 0f) >= median }
        if (calm.isEmpty() || energetic.isEmpty()) {
            val mid = sorted.size / 2
            calm = sorted.take(mid)
            energetic = sorted.drop(mid)
        }
        return calm to energetic
    }

    private fun estimateBpm(flux: FloatArray, hopSec: Float): Pair<Float, Float> {
        if (hopSec <= 0f) return 0f to 0f
        val minLag = (60f / 180f / hopSec).toInt().coerceAtLeast(1)
        val maxLag = (60f / 60f / hopSec).toInt()
        if (flux.size <= maxLag + 2) return 0f to 0f
        var energy = 0.0
        for (value in flux) energy += value * value
        if (energy <= 1e-10) return 0f to 0f
        var best = 0.0
        var bestLag = minLag
        for (lag in minLag..maxLag) {
            var sum = 0.0
            val limit = flux.size - lag
            for (i in 0 until limit) sum += flux[i] * flux[i + lag]
            if (sum > best) {
                best = sum
                bestLag = lag
            }
        }
        val bpm = 60f / (bestLag * hopSec)
        val confidence = (best / energy).toFloat().coerceIn(0f, 1f)
        return bpm to confidence
    }
}
