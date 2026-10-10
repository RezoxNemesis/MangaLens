package com.mangalens.ui.reader
import org.junit.Assert.*
import org.junit.Test
/** Authored UNRUN. Preferences are scalar display choices, never native receipts. */
class ReaderDisplayOptionsTest {
    @Test fun emptyPreferencesKeepEveryOldDefault() { val d=ReaderDisplayOptionsCodec.decode(emptyMap()); assertEquals(ReaderDisplayOptions(),d); assertEquals(ReaderWindowSettings(),d.effectiveWindow("vertical")) }
    @Test fun everyExplicitChoiceRoundTripsIncludingSystemBrightness() { for (o in ReaderWindowOrientation.entries) for (c in ReaderComparison.entries) { val d=ReaderDisplayOptions(ReaderWindowSettings(o,true,.37f,true),64,.15f,true,c,.8f,true); assertEquals(d,ReaderDisplayOptionsCodec.decode(ReaderDisplayOptionsCodec.encode(d))) }; assertNull(ReaderDisplayOptionsCodec.decode(ReaderDisplayOptionsCodec.encode(ReaderDisplayOptions())).window.brightnessOverride) }
    @Test fun invalidFiniteRangesCannotBecomeWindowOrImageSettings() { val d=ReaderDisplayOptionsCodec.decode(mapOf("crop" to Float.NaN,"brightness" to Float.POSITIVE_INFINITY,"split" to -1f,"spacing" to 65)); assertEquals(ReaderDisplayOptions(),d) }
    @Test fun unknownEnumsAndWrongTypesFallBackSafely() { assertEquals(ReaderDisplayOptions(),ReaderDisplayOptionsCodec.decode(mapOf("orientation" to "AUTO_SECRET","comparison" to 3,"rotation_lock" to "true","spacing" to 8L))) }
    @Test fun landscapeIsRequestedOnlyByTheExplicitSpreadMode() { val d=ReaderDisplayOptions(); assertEquals(ReaderWindowOrientation.LANDSCAPE,d.effectiveWindow("spread").orientation); for (m in listOf("vertical","ltr","rtl","single","horizontal")) assertEquals(ReaderWindowOrientation.SYSTEM,d.effectiveWindow(m).orientation) }
    @Test fun explicitPortraitIsRetainedWhenSpreadFallsBackToOnePage() { val d=ReaderDisplayOptions(window=ReaderWindowSettings(ReaderWindowOrientation.PORTRAIT)); assertEquals(ReaderWindowOrientation.PORTRAIT,d.effectiveWindow("spread").orientation) }
}
