package dev.localmusic.player.lyrics

interface RandomRead {
    val size: Long
    fun read(pos: Long, dst: ByteArray, offset: Int, length: Int): Int
}

class ByteSource(private val bytes: ByteArray) : RandomRead {
    override val size: Long = bytes.size.toLong()

    override fun read(pos: Long, dst: ByteArray, offset: Int, length: Int): Int {
        if (pos < 0 || pos >= size || length <= 0) return -1
        val start = pos.toInt()
        val n = minOf(length, bytes.size - start)
        bytes.copyInto(dst, offset, start, start + n)
        return n
    }
}

fun RandomRead.readBytes(pos: Long, length: Int): ByteArray? {
    if (length <= 0 || pos < 0 || pos >= size) return null
    val n = minOf(length.toLong(), size - pos).toInt()
    val dst = ByteArray(n)
    var got = 0
    while (got < n) {
        val r = read(pos + got, dst, got, n - got)
        if (r <= 0) return if (got == 0) null else dst.copyOf(got)
        got += r
    }
    return dst
}

fun ByteArray.startsWithAscii(text: String, offset: Int = 0): Boolean {
    val raw = text.toByteArray(Charsets.US_ASCII)
    if (offset < 0 || offset + raw.size > size) return false
    for (i in raw.indices) if (this[offset + i] != raw[i]) return false
    return true
}

fun le32(bytes: ByteArray, offset: Int): Int {
    return (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}

fun be32(bytes: ByteArray, offset: Int): Int {
    return ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
}

fun be64(bytes: ByteArray, offset: Int): Long {
    var value = 0L
    for (i in 0 until 8) {
        value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
    }
    return value
}

fun indexOf(data: ByteArray, pattern: ByteArray, from: Int = 0): Int {
    if (pattern.isEmpty() || data.size < pattern.size) return -1
    val last = data.size - pattern.size
    var i = from
    while (i <= last) {
        var matched = true
        for (j in pattern.indices) {
            if (data[i + j] != pattern[j]) {
                matched = false
                break
            }
        }
        if (matched) return i
        i++
    }
    return -1
}
