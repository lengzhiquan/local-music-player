package dev.localmusic.player.lyrics

/**
 * Reads lyrics stored inside the audio file.
 * Supports ID3v2 USLT/SYLT (including WAV id3 chunks), Vorbis/Opus comments (FLAC, OGG),
 * MP4 ©lyr / iTunes LYRICS atoms, and APEv2 LYRICS items.
 * Returns null when the container has no lyric text.
 */
object EmbeddedLyricsReader {
    private const val MAX_TAG = 2_000_000

    fun read(src: RandomRead): Lyrics? {
        val head = src.readBytes(0, 12) ?: return null
        val fromContainer = when {
            head.startsWithAscii("ID3") -> parseId3At(src, 0)
            head.startsWithAscii("fLaC") -> readFlac(src)
            head.startsWithAscii("OggS") -> readOgg(src)
            head.startsWithAscii("RIFF") -> readWav(src)
            head.size >= 8 && head.startsWithAscii("ftyp", 4) -> readMp4(src)
            else -> null
        }
        if (fromContainer != null && fromContainer.hasText()) return fromContainer
        return readApe(src)?.takeIf { it.hasText() }
    }

    fun parseId3Bytes(tag: ByteArray): Lyrics? = parseId3At(ByteSource(tag), 0)

    private fun parseId3At(src: RandomRead, offset: Long): Lyrics? {
        val header = src.readBytes(offset, 10) ?: return null
        if (!header.startsWithAscii("ID3")) return null
        val major = header[3].toInt() and 0xFF
        if (major !in 2..4) return null
        val flags = header[5].toInt() and 0xFF
        val tagSize = syncsafe(header, 6)
        if (tagSize <= 0) return null
        var body = src.readBytes(offset + 10, minOf(tagSize, MAX_TAG)) ?: return null
        if (flags and 0x80 != 0) body = deunsync(body)
        return if (major == 2) parseId3v22(body) else parseId3v23(body, major == 4)
    }

    private fun parseId3v22(body: ByteArray): Lyrics? {
        var unsynced: Lyrics? = null
        var pos = 0
        while (pos + 6 <= body.size) {
            val id = body.decodeToString(pos, pos + 3)
            if (id[0] == '\u0000' || id[0] !in 'A'..'Z') break
            val size = ((body[pos + 3].toInt() and 0xFF) shl 16) or
                ((body[pos + 4].toInt() and 0xFF) shl 8) or
                (body[pos + 5].toInt() and 0xFF)
            pos += 6
            if (size <= 0 || pos + size > body.size) break
            val frame = body.copyOfRange(pos, pos + size)
            pos += size
            when (id) {
                "SLT" -> parseSylt(frame)?.let { return it }
                "ULT" -> unsynced = parseUslt(frame) ?: unsynced
            }
        }
        return unsynced
    }

    private fun parseId3v23(body: ByteArray, v24: Boolean): Lyrics? {
        var unsynced: Lyrics? = null
        var pos = 0
        while (pos + 10 <= body.size) {
            val idBytes = body.copyOfRange(pos, pos + 4)
            if (idBytes.all { it == 0.toByte() }) break
            val id = idBytes.toString(Charsets.ISO_8859_1)
            if (id.any { it.code !in 32..126 }) break
            val size = if (v24) syncsafe(body, pos + 4) else be32(body, pos + 4)
            val flags1 = body[pos + 8].toInt() and 0xFF
            val flags2 = body[pos + 9].toInt() and 0xFF
            pos += 10
            if (size <= 0 || pos + size > body.size) break
            var frame = body.copyOfRange(pos, pos + size)
            pos += size
            val compressed = if (v24) flags2 and 0x08 != 0 else flags1 and 0x80 != 0
            val encrypted = if (v24) flags2 and 0x04 != 0 else flags1 and 0x40 != 0
            if (compressed || encrypted) continue
            if (v24 && flags2 and 0x01 != 0 && frame.size >= 4) {
                frame = frame.copyOfRange(4, frame.size)
            }
            if (v24 && flags2 and 0x02 != 0) frame = deunsync(frame)
            when (id) {
                "SYLT" -> parseSylt(frame)?.let { return it }
                "USLT" -> unsynced = parseUslt(frame) ?: unsynced
            }
        }
        return unsynced
    }

    private fun parseUslt(frame: ByteArray): Lyrics? {
        if (frame.isEmpty()) return null
        val encoding = frame[0].toInt() and 0xFF
        if (frame.size < 5) return null
        var pos = 4 // skip encoding + 3-byte language
        val desc = readEncodedString(frame, pos, encoding) ?: return null
        pos = desc.next
        val text = decodeToEnd(frame, pos, encoding).trim()
        if (text.isEmpty()) return null
        return interpretText(text)
    }

    private fun parseSylt(frame: ByteArray): Lyrics? {
        if (frame.size < 6) return null
        val encoding = frame[0].toInt() and 0xFF
        val stampFormat = frame[4].toInt() and 0xFF
        var pos = 6 // encoding, lang(3), format, content type
        val desc = readEncodedString(frame, pos, encoding) ?: return null
        pos = desc.next
        val lines = mutableListOf<LyricLine>()
        while (pos < frame.size) {
            val text = readEncodedString(frame, pos, encoding) ?: break
            pos = text.next
            if (pos + 4 > frame.size) break
            val stamp = be32(frame, pos)
            pos += 4
            val timeMs = if (stampFormat == 2) stamp.toLong() else stamp.toLong() * 26L
            if (text.value.isNotEmpty()) {
                lines += LyricLine(timeMs, text.value)
            }
        }
        val grouped = linesFromSyncEvents(lines)
        if (grouped.isEmpty()) return null
        return Lyrics(synced = true, lines = grouped)
    }

    private fun readFlac(src: RandomRead): Lyrics? {
        var pos = 4L
        while (pos + 4 <= src.size) {
            val hdr = src.readBytes(pos, 4) ?: return null
            val headerByte = hdr[0].toInt() and 0xFF
            val isLast = headerByte and 0x80 != 0
            val type = headerByte and 0x7F
            val length = ((hdr[1].toInt() and 0xFF) shl 16) or
                ((hdr[2].toInt() and 0xFF) shl 8) or
                (hdr[3].toInt() and 0xFF)
            val dataPos = pos + 4
            if (type == 4 && length in 1..MAX_TAG) {
                val block = src.readBytes(dataPos, length) ?: return null
                parseVorbisComments(block, 0)?.let { return it }
            }
            pos = dataPos + length
            if (isLast) break
        }
        return null
    }

    private fun readOgg(src: RandomRead): Lyrics? {
        val payload = collectOggPayload(src, 256 * 1024)
        val vorbisMagic = byteArrayOf(0x03) + "vorbis".toByteArray(Charsets.US_ASCII)
        val vorbisAt = indexOf(payload, vorbisMagic)
        if (vorbisAt >= 0) {
            parseVorbisComments(payload, vorbisAt + vorbisMagic.size)?.let { return it }
        }
        val opusMagic = "OpusTags".toByteArray(Charsets.US_ASCII)
        val opusAt = indexOf(payload, opusMagic)
        if (opusAt >= 0) {
            parseVorbisComments(payload, opusAt + opusMagic.size)?.let { return it }
        }
        return null
    }

    private fun collectOggPayload(src: RandomRead, maxBytes: Int): ByteArray {
        val out = ArrayList<Byte>(maxBytes.coerceAtMost(64 * 1024))
        var pos = 0L
        while (pos + 27 <= src.size && out.size < maxBytes) {
            val marker = src.readBytes(pos, 4) ?: break
            if (!marker.startsWithAscii("OggS")) break
            val hdr = src.readBytes(pos, 27) ?: break
            val segments = hdr[26].toInt() and 0xFF
            val table = src.readBytes(pos + 27, segments) ?: break
            var payloadLen = 0
            for (b in table) payloadLen += b.toInt() and 0xFF
            val page = src.readBytes(pos + 27 + segments, payloadLen) ?: break
            val room = maxBytes - out.size
            val n = minOf(room, page.size)
            for (i in 0 until n) out += page[i]
            pos += 27 + segments + payloadLen
        }
        return out.toByteArray()
    }

    private fun parseVorbisComments(block: ByteArray, offset: Int): Lyrics? {
        var pos = offset
        if (pos + 4 > block.size) return null
        val vendorLen = le32(block, pos)
        pos += 4
        if (vendorLen < 0 || pos + vendorLen > block.size) return null
        pos += vendorLen
        if (pos + 4 > block.size) return null
        val count = le32(block, pos)
        pos += 4
        if (count < 0 || count > 10_000) return null
        var synced: String? = null
        var plain: String? = null
        repeat(count) {
            if (pos + 4 > block.size) return@repeat
            val len = le32(block, pos)
            pos += 4
            if (len < 0 || pos + len > block.size) return@repeat
            val entry = block.decodeToString(pos, pos + len)
            pos += len
            val eq = entry.indexOf('=')
            if (eq <= 0) return@repeat
            val key = entry.substring(0, eq).trim()
            val value = entry.substring(eq + 1).trim()
            if (value.isEmpty()) return@repeat
            when (key.uppercase()) {
                "SYNCEDLYRICS", "LYRICS_SYNCED" -> if (synced == null) synced = value
                "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS" -> if (plain == null) plain = value
            }
        }
        val chosen = synced ?: plain ?: return null
        return interpretText(chosen)
    }

    private fun readWav(src: RandomRead): Lyrics? {
        if (src.size < 12) return null
        var pos = 12L
        while (pos + 8 <= src.size) {
            val hdr = src.readBytes(pos, 8) ?: return null
            val id = hdr.decodeToString(0, 4)
            val chunkSize = le32(hdr, 4).toLong() and 0xFFFFFFFFL
            val dataPos = pos + 8
            if (id.equals("id3 ", ignoreCase = true) || id == "ID3 ") {
                val data = src.readBytes(dataPos, minOf(chunkSize, MAX_TAG.toLong()).toInt()) ?: return null
                val lyrics = if (data.startsWithAscii("ID3")) parseId3Bytes(data) else parseId3Bytes(data)
                if (lyrics != null && lyrics.hasText()) return lyrics
            }
            val padded = chunkSize + (chunkSize and 1L)
            pos = dataPos + padded
        }
        return null
    }

    private fun readMp4(src: RandomRead): Lyrics? {
        var found: ByteArray? = null
        fun walk(start: Long, end: Long, depth: Int) {
            if (depth > 10 || found != null) return
            var pos = start
            while (pos + 8 <= end && found == null) {
                val hdr = src.readBytes(pos, 8) ?: return
                var atomSize = be32(hdr, 0).toLong() and 0xFFFFFFFFL
                val type = iso88591(hdr, 4, 8)
                var headerLen = 8
                if (atomSize == 1L) {
                    val ext = src.readBytes(pos + 8, 8) ?: return
                    atomSize = be64(ext, 0)
                    headerLen = 16
                } else if (atomSize == 0L) {
                    atomSize = end - pos
                }
                if (atomSize < headerLen || pos + atomSize > end + 8) return
                val body = pos + headerLen
                val atomEnd = pos + atomSize
                when (type) {
                    "moov", "udta", "trak", "mdia", "minf", "stbl", "edts", "ilst" ->
                        walk(body, atomEnd, depth + 1)
                    "meta" -> walk(body + 4, atomEnd, depth + 1)
                    "----" -> parseFreeform(src, body, atomEnd)?.let { found = it.toByteArray(Charsets.UTF_8) }
                    else -> if (type == "\u00A9lyr" || type == "lyrc") {
                        found = src.readBytes(body, minOf((atomEnd - body).toInt(), MAX_TAG))
                    }
                }
                pos = atomEnd
            }
        }
        walk(0, src.size, 0)
        val body = found ?: return null
        if (body.isEmpty()) return null
        // ©lyr body is a list of child atoms, normally a single 'data' atom.
        if (body.size > 8) {
            val text = extractMp4DataText(body)
            if (!text.isNullOrBlank()) return interpretText(text)
        }
        val direct = body.toString(Charsets.UTF_8).trim('\u0000', ' ', '\n')
        return if (direct.isNotBlank()) interpretText(direct) else null
    }

    private fun parseFreeform(src: RandomRead, start: Long, end: Long): String? {
        var name: String? = null
        var value: String? = null
        var pos = start
        while (pos + 8 <= end) {
            val hdr = src.readBytes(pos, 8) ?: break
            var size = be32(hdr, 0).toLong() and 0xFFFFFFFFL
            if (size < 8 || pos + size > end) break
            val type = iso88591(hdr, 4, 8)
            val raw = src.readBytes(pos + 8, (size - 8).toInt()) ?: break
            val text = extractDataPayload(raw)
            if (type == "name" && text != null) name = text
            if (type == "data" && text != null) value = text
            pos += size
        }
        val key = name?.uppercase() ?: return null
        if (key != "LYRICS" && key != "UNSYNCEDLYRICS" && key != "SYNCEDLYRICS") return null
        return value
    }

    private fun extractMp4DataText(body: ByteArray): String? {
        var pos = 0
        val chunks = mutableListOf<String>()
        while (pos + 8 <= body.size) {
            val size = be32(body, pos)
            if (size < 8 || pos + size > body.size) break
            val type = iso88591(body, pos + 4, pos + 8)
            if (type == "data") {
                val payload = body.copyOfRange(pos + 8, pos + size)
                extractDataPayload(payload)?.let { chunks += it }
            }
            pos += size
        }
        return chunks.joinToString("\n").ifBlank { null }
    }

    private fun extractDataPayload(payload: ByteArray): String? {
        if (payload.size <= 8) return null
        val text = payload.copyOfRange(8, payload.size)
        val decoded = runCatching { text.toString(Charsets.UTF_8) }.getOrNull() ?: return null
        val trimmed = decoded.trim('\u0000', ' ', '\n', '\r')
        return trimmed.ifBlank { null }
    }

    private fun readApe(src: RandomRead): Lyrics? {
        if (src.size < 64) return null
        val tailOffsets = longArrayOf(0L, 128L)
        for (endPad in tailOffsets) {
            val footerPos = src.size - 32 - endPad
            if (footerPos < 0) continue
            val footer = src.readBytes(footerPos, 32) ?: continue
            if (!footer.startsWithAscii("APETAGEX")) continue
            val version = le32(footer, 8)
            if (version != 2000 && version != 1000) continue
            val tagSize = le32(footer, 12)
            val count = le32(footer, 16)
            if (tagSize <= 32 || count < 0 || count > 5_000) continue
            var pos = footerPos + 32 - tagSize
            if (pos < 0) continue
            val maybeHeader = src.readBytes(pos, 8)
            if (maybeHeader != null && maybeHeader.startsWithAscii("APETAGEX")) pos += 32
            var plain: String? = null
            var synced: String? = null
            repeat(count) {
                val itemHdr = src.readBytes(pos, 8) ?: return@repeat
                val valueLen = le32(itemHdr, 0)
                pos += 8
                if (valueLen < 0 || valueLen > MAX_TAG) return@repeat
                val keyBytes = ArrayList<Byte>()
                while (keyBytes.size < 512) {
                    val one = src.readBytes(pos, 1) ?: return@repeat
                    pos += 1
                    if (one[0] == 0.toByte()) break
                    keyBytes += one[0]
                }
                val value = src.readBytes(pos, valueLen) ?: return@repeat
                pos += valueLen
                val key = keyBytes.toByteArray().toString(Charsets.UTF_8).uppercase()
                val text = value.toString(Charsets.UTF_8).trim()
                when (key) {
                    "SYNCEDLYRICS", "LYRICS_SYNCED" -> if (synced == null) synced = text
                    "LYRICS", "UNSYNCEDLYRICS", "UNSYNCED LYRICS" -> if (plain == null) plain = text
                }
            }
            val chosen = synced ?: plain
            if (!chosen.isNullOrBlank()) return interpretText(chosen)
        }
        return null
    }

    private fun interpretText(text: String): Lyrics? {
        val trimmed = text.trim('\u0000', ' ', '\n', '\r', '\uFEFF')
        if (trimmed.isBlank()) return null
        val parsed = LrcParser.parse(trimmed)
        if (parsed.synced && parsed.hasText()) return parsed
        val lines = trimmed.split('\n').map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        return Lyrics(synced = false, lines = lines.map { LyricLine(null, it) })
    }

    private data class Decoded(val value: String, val next: Int)

    private fun readEncodedString(data: ByteArray, start: Int, encoding: Int): Decoded? {
        if (start > data.size) return null
        if (start == data.size) return Decoded("", start)
        val wide = encoding == 1 || encoding == 2
        var i = start
        if (wide) {
            while (i + 1 < data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                    val text = decodeRange(data, start, i, encoding)
                    return Decoded(text, i + 2)
                }
                i += 2
            }
            return Decoded(decodeRange(data, start, data.size, encoding), data.size)
        }
        while (i < data.size) {
            if (data[i] == 0.toByte()) {
                return Decoded(decodeRange(data, start, i, encoding), i + 1)
            }
            i++
        }
        return Decoded(decodeRange(data, start, data.size, encoding), data.size)
    }

    private fun decodeToEnd(data: ByteArray, start: Int, encoding: Int): String {
        if (start >= data.size) return ""
        var end = data.size
        while (end > start && data[end - 1] == 0.toByte()) end--
        return decodeRange(data, start, end, encoding)
    }

    private fun decodeRange(data: ByteArray, start: Int, end: Int, encoding: Int): String {
        if (end <= start) return ""
        val slice = data.copyOfRange(start, end)
        return when (encoding) {
            0 -> slice.toString(Charsets.ISO_8859_1)
            1 -> {
                val charset = if (slice.size >= 2 && slice[0] == 0xFE.toByte() && slice[1] == 0xFF.toByte()) {
                    Charsets.UTF_16
                } else if (slice.size >= 2 && slice[0] == 0xFF.toByte() && slice[1] == 0xFE.toByte()) {
                    Charsets.UTF_16
                } else {
                    Charsets.UTF_16LE
                }
                slice.toString(charset).trim('\uFEFF')
            }
            2 -> slice.toString(Charsets.UTF_16BE).trim('\uFEFF')
            else -> slice.toString(Charsets.UTF_8).trim('\uFEFF')
        }.trim('\u0000')
    }

    private fun iso88591(data: ByteArray, start: Int, end: Int): String {
        return String(data, start, end - start, Charsets.ISO_8859_1)
    }

    private fun syncsafe(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    private fun deunsync(data: ByteArray): ByteArray {
        val out = ArrayList<Byte>(data.size)
        var i = 0
        while (i < data.size) {
            out += data[i]
            if (data[i] == 0xFF.toByte() && i + 1 < data.size && data[i + 1] == 0.toByte()) {
                i += 2
            } else {
                i += 1
            }
        }
        return out.toByteArray()
    }
}
