package com.airzelo.engine

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Minimal ZIP reader / writer (stored and deflate entries, no ZIP64), enough for AirZelo backups, which are the
 * desktop app's format: a .zip with manifest.json, agency.db, uploads/ and branding/. Port of Backup/Zip.swift,
 * with java.util.zip doing DEFLATE and CRC-32.
 */
object Zip {
    class Entry(val name: String, val method: Int, val crc32: Long, val compressedSize: Long, val size: Long, internal val localHeaderOffset: Long)

    class ZipError(message: String) : Exception(message) {
        companion object {
            fun notZip() = ZipError("File is not a valid zip archive")
            fun corrupt(s: String) = ZipError("Corrupt entry: $s")
            fun unsupported(s: String) = ZipError("Unsupported zip feature: $s")
            fun tooLarge() = ZipError("Archive is too large")
        }
    }

    private const val MAX_ENTRY_SIZE = Int.MAX_VALUE.toLong()

    fun crc32(data: ByteArray): Long = CRC32().apply { update(data) }.value

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int): Long = u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16)

    class Reader(val data: ByteArray) {
        val entries: List<Entry>

        init {
            val b = data
            if (b.size < 22) throw ZipError.notZip()
            var eocd = -1
            var i = b.size - 22
            val stop = maxOf(0, b.size - 22 - 65_535)
            while (i >= stop) {
                if (b[i] == 0x50.toByte() && b[i + 1] == 0x4B.toByte() && b[i + 2] == 0x05.toByte() && b[i + 3] == 0x06.toByte()) { eocd = i; break }
                i--
            }
            if (eocd < 0) throw ZipError.notZip()
            val count = u16(b, eocd + 10)
            var p = u32(b, eocd + 16).toInt()
            val out = ArrayList<Entry>()
            repeat(count) {
                if (p < 0 || p + 46 > b.size || u32(b, p) != 0x02014B50L) throw ZipError.notZip()
                val flags = u16(b, p + 8); val method = u16(b, p + 10)
                val crc = u32(b, p + 16); val csize = u32(b, p + 20); val size = u32(b, p + 24)
                val nlen = u16(b, p + 28); val xlen = u16(b, p + 30); val clen = u16(b, p + 32); val off = u32(b, p + 42)
                if (flags and 1 != 0) throw ZipError.unsupported("encrypted entry")
                if (csize == 0xFFFFFFFFL || size == 0xFFFFFFFFL || off == 0xFFFFFFFFL) throw ZipError.unsupported("ZIP64")
                if (p + 46 + nlen > b.size) throw ZipError.notZip()
                val name = String(b, p + 46, nlen, Charsets.UTF_8)
                out.add(Entry(name, method, crc, csize, size, off))
                p += 46 + nlen + xlen + clen
            }
            entries = out
        }

        val names: List<String> get() = entries.map { it.name }

        fun read(name: String): ByteArray = read(entries.firstOrNull { it.name == name } ?: throw ZipError.corrupt(name))

        fun read(e: Entry): ByteArray {
            if (e.size > MAX_ENTRY_SIZE || e.compressedSize > MAX_ENTRY_SIZE) throw ZipError.tooLarge()
            val o = e.localHeaderOffset
            if (o + 30 > data.size) throw ZipError.corrupt(e.name)
            val h = o.toInt()
            if (u32(data, h) != 0x04034B50L) throw ZipError.corrupt(e.name)
            val start = h + 30 + u16(data, h + 26) + u16(data, h + 28)
            if (start + e.compressedSize > data.size) throw ZipError.corrupt(e.name)
            val raw = data.copyOfRange(start, start + e.compressedSize.toInt())
            val out = when (e.method) {
                0 -> raw
                8 -> inflate(raw, e.size.toInt(), e.name)
                else -> throw ZipError.unsupported("compression method ${e.method}")
            }
            if (out.size.toLong() != e.size || crc32(out) != e.crc32) throw ZipError.corrupt(e.name)
            return out
        }
    }

    private fun inflate(data: ByteArray, size: Int, name: String): ByteArray {
        if (size == 0) return ByteArray(0)
        val inf = Inflater(true)
        try {
            // nowrap inflaters may need one extra byte after the stream
            inf.setInput(data + byteArrayOf(0))
            val out = ByteArray(size)
            var n = 0
            while (n < size) {
                val k = inf.inflate(out, n, size - n)
                if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                n += k
            }
            if (n != size) throw ZipError.corrupt(name)
            return out
        } catch (e: java.util.zip.DataFormatException) {
            throw ZipError.corrupt(name)
        } finally {
            inf.end()
        }
    }

    private fun deflate(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        try {
            d.setInput(data); d.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            val packed = out.toByteArray()
            return if (packed.size < data.size) packed else null
        } finally {
            d.end()
        }
    }

    class Writer {
        private val body = ByteArrayOutputStream()
        private val central = ByteArrayOutputStream()
        private var count = 0

        private fun ByteArrayOutputStream.le16(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }
        private fun ByteArrayOutputStream.le32(v: Long) { for (s in 0 until 32 step 8) write(((v shr s) and 0xFF).toInt()) }

        fun add(name: String, content: ByteArray) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = crc32(content)
            val packed = deflate(content)
            val method = if (packed == null) 0 else 8
            val stored = packed ?: content
            val offset = body.size().toLong()
            body.le32(0x04034B50); body.le16(20); body.le16(0x0800 /* UTF-8 names */); body.le16(method); body.le16(0); body.le16(0x21)
            body.le32(crc); body.le32(stored.size.toLong()); body.le32(content.size.toLong()); body.le16(nameBytes.size); body.le16(0)
            body.write(nameBytes); body.write(stored)
            central.le32(0x02014B50); central.le16(20); central.le16(20); central.le16(0x0800); central.le16(method); central.le16(0); central.le16(0x21)
            central.le32(crc); central.le32(stored.size.toLong()); central.le32(content.size.toLong()); central.le16(nameBytes.size)
            central.le16(0); central.le16(0); central.le16(0); central.le16(0); central.le32(0b1_000_000_110_100_100L shl 16); central.le32(offset)
            central.write(nameBytes)
            count++
        }

        fun finish(): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(body.toByteArray())
            val cdOffset = out.size().toLong()
            val cd = central.toByteArray()
            out.write(cd)
            out.le32(0x06054B50); out.le16(0); out.le16(0); out.le16(count); out.le16(count)
            out.le32(cd.size.toLong()); out.le32(cdOffset); out.le16(0)
            return out.toByteArray()
        }
    }
}
