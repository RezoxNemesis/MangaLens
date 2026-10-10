package com.mangalens.orez

import java.io.IOException

/** Portable bounded non-ZIP64 envelope. Content/CRC/inflation is checked by the owned ZIP reader. */
internal object OrezLabZipEnvelope {
    fun validate(raw: ByteArray, check: () -> Unit = {}): Set<String> {
        if (raw.size !in 22..OrezEvaluationPackageCodec.MAX_PACKAGE_BYTES) throw IOException("Lab ZIP envelope bound")
        fun u16(at: Int): Int {
            if (at < 0 || at.toLong() + 2 > raw.size) throw IOException("Truncated lab ZIP")
            return (raw[at].toInt() and 255) or ((raw[at + 1].toInt() and 255) shl 8)
        }
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        var end = raw.size - 22
        val minimum = maxOf(0, end - 65535)
        while (end >= minimum && (u32(end) != 0x06054b50L || end.toLong() + 22 + u16(end + 20) != raw.size.toLong())) { check(); end-- }
        if (end < minimum || u16(end + 4) != 0 || u16(end + 6) != 0 || u16(end + 8) != u16(end + 10)) throw IOException("Unsupported lab ZIP end record")
        val count = u16(end + 10)
        if (count !in 1..OrezModelEvaluationValidator.MAX_ARTIFACTS + 1) throw IOException("Lab ZIP entry bound")
        val start = u32(end + 16); val size = u32(end + 12)
        if (start + size != end.toLong() || start > Int.MAX_VALUE || size > raw.size) throw IOException("Lab ZIP central-directory bound")
        var cursor = start.toInt(); val names = HashSet<String>(); val offsets = HashSet<Long>()
        repeat(count) {
            check()
            if (cursor.toLong() + 46 > end || u32(cursor) != 0x02014b50L) throw IOException("Invalid lab ZIP central record")
            val flags = u16(cursor + 8); val method = u16(cursor + 10)
            val nameSize = u16(cursor + 28); val extraSize = u16(cursor + 30); val commentSize = u16(cursor + 32)
            val local = u32(cursor + 42)
            if (flags and 1 != 0 || method !in setOf(0, 8) || u16(cursor + 34) != 0 || nameSize !in 1..512 ||
                cursor.toLong() + 46 + nameSize + extraSize + commentSize > end || local >= start || !offsets.add(local)) throw IOException("Unsupported lab ZIP entry")
            val nameBytes = raw.copyOfRange(cursor + 46, cursor + 46 + nameSize)
            if (nameBytes.any { (it.toInt() and 255) > 127 }) throw IOException("Lab ZIP requires portable ASCII entry names")
            val name = nameBytes.toString(Charsets.US_ASCII)
            if (!names.add(name)) throw IOException("Duplicate lab ZIP central entry")
            val position = local.toInt()
            if (position.toLong() + 30 > start || u32(position) != 0x04034b50L || u16(position + 6) != flags || u16(position + 8) != method || u16(position + 26) != nameSize) throw IOException("Lab ZIP local/central mismatch")
            val localNameStart = position + 30
            val dataStart = localNameStart.toLong() + nameSize + u16(position + 28)
            if (dataStart + u32(cursor + 20) > start || !raw.copyOfRange(localNameStart, localNameStart + nameSize).contentEquals(nameBytes)) throw IOException("Lab ZIP body/name bound")
            if (u32(cursor + 24) > OrezModelEvaluationValidator.MAX_BODY_BYTES) throw IOException("Lab ZIP declared body bound")
            val compressed = u32(cursor + 20); val crc = u32(cursor + 16); val expanded = u32(cursor + 24)
            if (flags and 8 == 0) {
                if (u32(position + 14) != crc || u32(position + 18) != compressed || u32(position + 22) != expanded) throw IOException("Lab ZIP local/central size or CRC mismatch")
            } else {
                var descriptor = (dataStart + compressed).toInt()
                if (descriptor.toLong() + 12 > start) throw IOException("Lab ZIP data descriptor bound")
                if (u32(descriptor) == 0x08074b50L) descriptor += 4
                if (descriptor.toLong() + 12 > start || u32(descriptor) != crc || u32(descriptor + 4) != compressed || u32(descriptor + 8) != expanded) throw IOException("Lab ZIP data descriptor mismatch")
            }
            cursor += 46 + nameSize + extraSize + commentSize
        }
        if (cursor.toLong() != start + size || offsets.minOrNull() != 0L) throw IOException("Lab ZIP directory/first-entry mismatch")
        return names.toSet()
    }
}
