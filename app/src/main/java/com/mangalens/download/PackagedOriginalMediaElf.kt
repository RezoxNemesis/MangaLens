package com.mangalens.download

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PackagedOriginalMediaElf(val machine: Int, val needed: Set<String>)

/** Reads only bounded ELF64 tables; no native library is loaded or executed during admission. */
internal object PackagedOriginalMediaElfReader {
    private data class Segment(val type: Int, val offset: Long, val address: Long, val bytes: Long)

    fun read(file: File, checkActive: () -> Unit): PackagedOriginalMediaElf = RandomAccessFile(file, "r").use { source ->
        checkActive()
        val length = source.length()
        fun range(offset: Long, count: Long) {
            require(offset >= 0 && count >= 0 && offset <= length && count <= length - offset) {
                "Packaged original-media executable has invalid ELF bounds."
            }
        }
        fun bytes(offset: Long, count: Int): ByteArray {
            checkActive(); range(offset, count.toLong())
            return ByteArray(count).also { source.seek(offset); source.readFully(it) }
        }
        fun table(offset: Long, count: Int) = ByteBuffer.wrap(bytes(offset, count)).order(ByteOrder.LITTLE_ENDIAN)
        fun positive(value: Long): Long = value.also { require(it >= 0) }
        val header = table(0, 64)
        require(header.get(0) == 0x7f.toByte() && header.get(1) == 'E'.code.toByte() &&
            header.get(2) == 'L'.code.toByte() && header.get(3) == 'F'.code.toByte() &&
            header.get(4) == 2.toByte() && header.get(5) == 1.toByte() && header.get(6) == 1.toByte() &&
            header.getShort(16).toInt() == 3 && header.getInt(20) == 1 && header.getShort(52).toInt() == 64) {
            "Packaged original-media executable is not an ELF64 PIE."
        }
        val machine = header.getShort(18).toInt() and 0xffff
        val tableOffset = positive(header.getLong(32))
        val entryBytes = header.getShort(54).toInt() and 0xffff
        val count = header.getShort(56).toInt() and 0xffff
        require(entryBytes == 56 && count in 1..64 && tableOffset >= 64)
        range(tableOffset, count.toLong() * entryBytes)
        val segments = (0 until count).map { index ->
            val entry = table(tableOffset + index.toLong() * entryBytes, entryBytes)
            Segment(entry.getInt(0), positive(entry.getLong(8)), positive(entry.getLong(16)), positive(entry.getLong(32)))
                .also { range(it.offset, it.bytes) }
        }
        val interpreter = segments.singleOrNull { it.type == 3 }
            ?: error("Packaged original-media executable has no unique Android interpreter.")
        require(interpreter.bytes in 1..64 && bytes(interpreter.offset, interpreter.bytes.toInt())
            .contentEquals("/system/bin/linker64\u0000".toByteArray(Charsets.US_ASCII))) {
            "Packaged original-media executable does not use the Android system linker."
        }
        val dynamic = segments.singleOrNull { it.type == 2 }
            ?: error("Packaged original-media executable has no unique dynamic table.")
        require(dynamic.bytes in 16..4096 && dynamic.bytes % 16 == 0L)
        val neededOffsets = mutableListOf<Long>()
        var stringAddress: Long? = null
        var stringBytes: Long? = null
        var terminated = false
        for (index in 0 until (dynamic.bytes / 16).toInt()) {
            val entry = table(dynamic.offset + index * 16L, 16)
            val tag = entry.getLong(0)
            val value = positive(entry.getLong(8))
            when (tag) {
                0L -> { terminated = true; break }
                1L -> { require(neededOffsets.size < 16); neededOffsets += value }
                5L -> { require(stringAddress == null); stringAddress = value }
                10L -> { require(stringBytes == null); stringBytes = value }
                15L, 29L -> error("Packaged original-media executable must not set a library search path.")
            }
        }
        require(terminated)
        val address = requireNotNull(stringAddress)
        val size = requireNotNull(stringBytes).also { require(it in 1..65_536) }
        val load = segments.filter { it.type == 1 }.singleOrNull { segment ->
            address >= segment.address && address - segment.address <= segment.bytes &&
                size <= segment.bytes - (address - segment.address)
        } ?: error("Packaged original-media ELF string table is not file-backed.")
        val strings = bytes(load.offset + (address - load.address), size.toInt())
        val needed = neededOffsets.map { offset ->
            require(offset < strings.size)
            val start = offset.toInt()
            val end = (start until minOf(strings.size, start + 256)).firstOrNull { strings[it] == 0.toByte() }
                ?: error("Packaged original-media ELF dependency name is not bounded.")
            require(end > start && (start until end).all { (strings[it].toInt() and 0xff) in 33..126 })
            String(strings, start, end - start, Charsets.US_ASCII)
        }
        require(needed.isNotEmpty() && needed.toSet().size == needed.size)
        PackagedOriginalMediaElf(machine, needed.toSet())
    }
}
