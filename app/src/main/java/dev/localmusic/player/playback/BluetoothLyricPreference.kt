package dev.localmusic.player.playback

import android.content.Context

/** Off unless the listener turns it on. The switch lives on the library and player screens. */
object BluetoothLyricPreference {
    const val PREFS = "player"
    const val KEY = "bluetooth_lyric_title"

    fun read(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    }
}
