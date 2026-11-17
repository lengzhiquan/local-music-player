package dev.localmusic.player.playback

data class QueueEdit<T>(
    val items: List<T>,
    val currentIndex: Int,
    val restart: Boolean,
)

object QueueEdits {
    fun <T> move(items: List<T>, current: Int, from: Int, delta: Int): QueueEdit<T>? {
        val to = from + delta
        if (items.isEmpty() || from !in items.indices || to !in items.indices || from == to) return null
        val next = items.toMutableList()
        val moved = next.removeAt(from)
        next.add(to, moved)
        val newCurrent = when {
            current == from -> to
            from < current && to >= current -> current - 1
            from > current && to <= current -> current + 1
            else -> current
        }.coerceIn(0, next.lastIndex)
        return QueueEdit(next, newCurrent, restart = false)
    }

    fun <T> remove(items: List<T>, current: Int, index: Int): QueueEdit<T>? {
        if (index !in items.indices) return null
        val next = items.toMutableList()
        next.removeAt(index)
        if (next.isEmpty()) return QueueEdit(emptyList(), -1, restart = false)
        val removedCurrent = current == index
        val newCurrent = when {
            removedCurrent -> index.coerceAtMost(next.lastIndex)
            index < current -> current - 1
            else -> current
        }.coerceIn(0, next.lastIndex)
        return QueueEdit(next, newCurrent, restart = removedCurrent)
    }
}
