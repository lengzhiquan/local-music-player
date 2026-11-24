package dev.localmusic.player.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

object BluetoothAudioRoute {
    fun isBluetoothOutput(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            type == AudioDeviceInfo.TYPE_BLE_BROADCAST
    }

    /**
     * True when media would play out of a Bluetooth device.
     * API 31+ reports the routed output. Older releases report a connected
     * Bluetooth output, which is the closest signal those versions expose.
     */
    fun isOutputActive(context: Context): Boolean {
        val manager = context.getSystemService(AudioManager::class.java) ?: return false
        val devices = if (Build.VERSION.SDK_INT >= 31) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            manager.getAudioDevicesForAttributes(attributes)
        } else {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        return devices.any { isBluetoothOutput(it.type) }
    }
}
