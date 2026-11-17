package dev.localmusic.player

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.localmusic.player.energy.EnergyAnalyzer
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class DeviceEnergyTest {
    @Test
    fun analyzerRanksBurstsAboveAQuietTone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val quiet = File(context.cacheDir, "quiet.wav").apply { writeBytes(toneWav(amplitude = 0.02, bursts = false)) }
        val loud = File(context.cacheDir, "loud.wav").apply { writeBytes(toneWav(amplitude = 0.9, bursts = true)) }
        val quietScore = EnergyAnalyzer.analyzeFile(quiet)
        val loudScore = EnergyAnalyzer.analyzeFile(loud)
        assertTrue("quiet=${quietScore.score} loud=${loudScore.score}", loudScore.score > quietScore.score)
        assertTrue(loudScore.onsetPerSec > quietScore.onsetPerSec)
    }

    @Test
    fun exoPlayerReachesReadyOnWav() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "play.wav").apply { writeBytes(toneWav(amplitude = 0.2, bursts = false)) }
        val latch = CountDownLatch(1)
        var failed: String? = null
        val playerHolder = arrayOfNulls<ExoPlayer>(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val player = ExoPlayer.Builder(context).build()
            playerHolder[0] = player
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) latch.countDown()
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    failed = error.message
                    latch.countDown()
                }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
        }
        assertTrue(latch.await(15, TimeUnit.SECONDS))
        assertNull(failed)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            playerHolder[0]?.release()
        }
    }
}

private fun toneWav(amplitude: Double, bursts: Boolean): ByteArray {
    val rate = 44100
    val count = rate * 4
    val pcm = ShortArray(count)
    for (i in pcm.indices) {
        val t = i.toDouble() / rate
        val gate = if (!bursts) 1.0 else if (t % 0.25 < 0.04) 1.0 else 0.0
        pcm[i] = (sin(2.0 * Math.PI * 160.0 * t) * amplitude * gate * 32767.0)
            .toInt()
            .coerceIn(-32767, 32767)
            .toShort()
    }
    val dataBytes = pcm.size * 2
    val out = ByteArrayOutputStream()
    out.write("RIFF".toByteArray())
    writeLe32(out, 36 + dataBytes)
    out.write("WAVE".toByteArray())
    out.write("fmt ".toByteArray())
    writeLe32(out, 16)
    writeLe16(out, 1)
    writeLe16(out, 1)
    writeLe32(out, rate)
    writeLe32(out, rate * 2)
    writeLe16(out, 2)
    writeLe16(out, 16)
    out.write("data".toByteArray())
    writeLe32(out, dataBytes)
    for (sample in pcm) writeLe16(out, sample.toInt() and 0xFFFF)
    return out.toByteArray()
}

private fun writeLe32(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value ushr 8) and 0xFF)
    out.write((value ushr 16) and 0xFF)
    out.write((value ushr 24) and 0xFF)
}

private fun writeLe16(out: ByteArrayOutputStream, value: Int) {
    out.write(value and 0xFF)
    out.write((value ushr 8) and 0xFF)
}
