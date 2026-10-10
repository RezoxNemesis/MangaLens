package com.mangalens.core.router

import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class MangaLensAppEntryPolicyTest {
    private val id = "a".repeat(32)
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
    @Test fun savedIdentityCarriesNoSourceUrlOrAutomaticTranslation() {
        assertEquals(MangaLensAppEntry.Reader(id), MangaLensAppEntryPolicy.parse("mangalens://reader?chapter=$id"))
        assertNull(MangaLensAppEntryPolicy.parse("mangalens://reader?chapter=$id&translate=true"))
        assertNull(MangaLensAppEntryPolicy.parse("mangalens://reader?chapter=https%3A%2F%2Fexample.org"))
    }
    @Test fun literalOrezDraftKeepsPunctuationAndUnicode() {
        val text = "日本語 & https://example.org/?x=1+2\nExplain this"
        assertEquals(MangaLensAppEntry.Orez(text), MangaLensAppEntryPolicy.parse("mangalens://orez?request=" + encoded(text)))
        assertEquals(MangaLensAppEntry.Orez(""), MangaLensAppEntryPolicy.parse("mangalens://orez"))
    }
    @Test fun duplicateAndEncodedDuplicateQueryKeysFailClosed() {
        assertNull(MangaLensAppEntryPolicy.parse("mangalens://reader?chapter=$id&chapter=$id"))
        assertNull(MangaLensAppEntryPolicy.parse("mangalens://orez?request=first&%72equest=second"))
    }
    @Test fun unknownQueryOrRouteCannotBecomeAnAppCommand() {
        listOf("mangalens://orez?research=auto", "mangalens://orez?tool=download", "mangalens://downloads", "mangalens://reader/../../private?chapter=$id").forEach {
            assertNull(it, MangaLensAppEntryPolicy.parse(it))
        }
    }
    @Test fun malformedUtf8AndEscapesAreRejectedRatherThanReplaced() {
        listOf("%C0%AF", "%E2%28%A1", "%ED%A0%80", "%80", "%", "%GG").forEach {
            assertNull(it, MangaLensAppEntryPolicy.parse("mangalens://orez?request=$it"))
        }
    }
    @Test fun credentialsPortFragmentAndWrongSchemeAreRejected() {
        listOf("https://reader?chapter=$id", "mangalens://user@reader?chapter=$id", "mangalens://reader:12?chapter=$id", "mangalens://reader?chapter=$id#override", "mangalens://orez/path").forEach {
            assertNull(it, MangaLensAppEntryPolicy.parse(it))
        }
    }
    @Test fun sourceIdentityMustBeTheExactManagedLowercaseId() {
        listOf("a".repeat(31), "a".repeat(33), "A".repeat(32), "z".repeat(32), "../private").forEach {
            assertNull(it, MangaLensAppEntryPolicy.parse("mangalens://reader?chapter=" + encoded(it)))
        }
    }
    @Test fun encodedNonAsciiDraftCanReachItsAdvertisedBound() {
        assertNotNull(MangaLensAppEntryPolicy.parse("mangalens://orez?request=" + encoded("界".repeat(1024))))
        assertNull(MangaLensAppEntryPolicy.parse("mangalens://orez?request=" + encoded("界".repeat(1025))))
        assertNull(MangaLensAppEntryPolicy.parse("mangalens://orez?request=" + "a".repeat(MangaLensAppEntryPolicy.MAX_RAW_LENGTH)))
    }
    @Test fun controlBytesCannotBePassedToTheDraft() {
        listOf("%00", "%0D", "%01", "%7F").forEach { assertNull(MangaLensAppEntryPolicy.parse("mangalens://orez?request=$it")) }
        assertNotNull(MangaLensAppEntryPolicy.parse("mangalens://orez?request=first%0Asecond%09column"))
    }
}
