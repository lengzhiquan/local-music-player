package dev.localmusic.player.playback

/**
 * Index moves that turn [currentIds] into [targetIds] using Player.moveMediaItem(from, to),
 * where [to] is the item's index after the move. Each id is one occurrence, so duplicates stay distinct.
 * Returns null when the two lists are not the same multiset and a structural replace is required.
 */
object PlaylistPermutation {
    fun moves(currentIds: List<String>, targetIds: List<String>): List<Pair<Int, Int>>? {
        if (currentIds.size != targetIds.size) return null
        if (currentIds.groupingBy { it }.eachCount() != targetIds.groupingBy { it }.eachCount()) return null
        if (currentIds == targetIds) return emptyList()
        val working = currentIds.toMutableList()
        val moves = mutableListOf<Pair<Int, Int>>()
        for (index in targetIds.indices) {
            if (working[index] == targetIds[index]) continue
            val from = (index + 1 until working.size).first { working[it] == targetIds[index] }
            moves += from to index
            val moved = working.removeAt(from)
            working.add(index, moved)
        }
        return moves
    }
}

/** Energy mode is the only play mode that reorders the queue. Repeat and shuffle only change transport flags. */
fun modeRetargetsQueue(previous: PlayMode, next: PlayMode): Boolean {
    return previous == PlayMode.ENERGY || next == PlayMode.ENERGY
}
