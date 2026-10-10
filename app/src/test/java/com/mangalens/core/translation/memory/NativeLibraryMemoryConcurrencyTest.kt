package com.mangalens.core.translation.memory
import com.mangalens.core.reader.NativeLibraryMetadataBusyException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Held real synced preparation; authored and UNRUN, not native/device runtime evidence. */
class NativeLibraryMemoryConcurrencyTest {
    @Test fun heldOrdinaryWriterCannotLoseItsTermToNativePreparedPublication()=runBlocking {
        val root=Files.createTempDirectory("native-library-memory-").toFile()
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        try {
            val setup=SeriesMemoryStore(root);setup.createSeries("Series","series-one")
            val writer=MemoryJournalWriter { file,bytes ->
                val pending=AtomicMemoryJournalWriter.prepare(file,bytes)
                entered.countDown();check(release.await(5,TimeUnit.SECONDS));pending
            }
            val store=SeriesMemoryStore(root,writer)
            val ordinary=async(Dispatchers.IO) { store.upsertTerm("series-one",SeriesGlossaryTerm("old-user-term","Old","पुराना","hi")) }
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            var nativeRename=false
            val outcome=runCatching { SeriesMemoryStore.publishNativeLibraryMetadata { nativeRename=true } }
            assertTrue(outcome.exceptionOrNull() is NativeLibraryMetadataBusyException);assertFalse(nativeRename)
            release.countDown();ordinary.await()
            assertEquals("पुराना",SeriesMemoryStore(root).profile("series-one")!!.glossary.single().preferred)
            SeriesMemoryStore.publishNativeLibraryMetadata { nativeRename=true };assertTrue(nativeRename)
        } finally { release.countDown();root.deleteRecursively() }
    }
    @Test fun nativeFinalGateReleasesBothLocksAfterAnEffectFailure()=runBlocking {
        assertTrue(runCatching { SeriesMemoryStore.publishNativeLibraryMetadata<Unit> { error("Injected rename refusal") } }.isFailure)
        val root=Files.createTempDirectory("native-library-gate-release-").toFile()
        try { SeriesMemoryStore(root).createSeries("Available after refusal","series-two");assertNotNull(SeriesMemoryStore(root).profile("series-two")) }
        finally { root.deleteRecursively() }
    }
    @Test fun normalProfileEditDropsOldNativeReplayCredential() {
        val profile=SeriesMemoryProfile("series-one","Series",listOf(SeriesGlossaryTerm("term","x","y","en")))
        val base=org.json.JSONObject(SeriesMemoryCodec.profile(profile).toString(Charsets.UTF_8))
        val receipt=com.mangalens.core.reader.NativeLibraryOperationReceipt("request","a".repeat(64),"SET_TERM","b".repeat(64),
            com.mangalens.core.reader.NativeLibraryOperationReceipt.stateDigest(base),1)
        assertEquals(receipt,SeriesMemoryCodec.readProfile(SeriesMemoryCodec.profile(profile.copy(nativeLibraryOperation=receipt))).nativeLibraryOperation)
        val edited=profile.copy(title="Later user title",nativeLibraryOperation=receipt)
        assertNull(SeriesMemoryCodec.readProfile(SeriesMemoryCodec.profile(edited)).nativeLibraryOperation)
    }
}
