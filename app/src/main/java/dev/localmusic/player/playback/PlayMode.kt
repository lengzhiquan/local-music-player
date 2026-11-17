package dev.localmusic.player.playback

enum class PlayMode {
    SEQUENTIAL,
    REPEAT_ONE,
    REPEAT_ALL,
    SHUFFLE,
    ENERGY,
    ;

    val label: String
        get() = when (this) {
            SEQUENTIAL -> "顺序播放"
            REPEAT_ONE -> "单曲循环"
            REPEAT_ALL -> "列表循环"
            SHUFFLE -> "随机播放"
            ENERGY -> "能量随机"
        }

    companion object {
        fun fromStored(value: String?): PlayMode {
            return entries.firstOrNull { it.name == value } ?: SEQUENTIAL
        }
    }
}
