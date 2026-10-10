package com.mangalens.widget

import org.junit.Assert.*
import org.junit.Test

class WidgetReadingPointerCodecTest {
    @Test fun NativeIdAndVisitTimeRoundTripWithoutSourceUrlsOrPageCopies() {
        val pointer = WidgetReadingPointer("c".repeat(32), Long.MAX_VALUE)
        val bytes = WidgetReadingPointerCodec.encode(pointer)
        assertEquals(pointer, WidgetReadingPointerCodec.decode(bytes))
        val text = bytes.toString(Charsets.UTF_8); assertFalse(text.contains("https:")); assertFalse(text.contains("localPath"))
    }
    @Test fun OversizeAndExtraKeysCannotBecomeAnOpenIdentity() {
        assertThrows(IllegalArgumentException::class.java) { WidgetReadingPointerCodec.decode(ByteArray(4097)) }
        val valid = WidgetReadingPointerCodec.encode(WidgetReadingPointer("c".repeat(32), 1)).toString(Charsets.UTF_8)
        assertThrows(IllegalArgumentException::class.java) { WidgetReadingPointerCodec.decode((valid.dropLast(1) + ",\"source\":\"https://example.org\"}").toByteArray()) }
    }
    @Test fun InvalidIdAndNegativeVisitAreRejectedBeforeWriting() {
        assertThrows(IllegalArgumentException::class.java) { WidgetReadingPointerCodec.encode(WidgetReadingPointer("../source", 1)) }
        assertThrows(IllegalArgumentException::class.java) { WidgetReadingPointerCodec.encode(WidgetReadingPointer("c".repeat(32), -1)) }
    }
}
