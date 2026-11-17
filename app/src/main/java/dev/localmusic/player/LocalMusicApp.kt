package dev.localmusic.player

import android.app.Application
import dev.localmusic.player.data.AppDatabase
import dev.localmusic.player.data.MusicRepository
import dev.localmusic.player.dsp.CompressorSettings
import dev.localmusic.player.playback.PlayerSession

class LocalMusicApp : Application() {
    lateinit var repository: MusicRepository
        private set
    lateinit var session: PlayerSession
        private set
    lateinit var compressor: CompressorSettings
        private set

    override fun onCreate() {
        super.onCreate()
        compressor = CompressorSettings(this)
        repository = MusicRepository(this, AppDatabase.get(this))
        session = PlayerSession(this)
    }
}
