package com.mangalens.ui.video

import com.mangalens.core.translation.TranslationStyleProfile
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Actual bounded native Store cold-restart/owner contract; no player, ASR or native inference claim. */
class SubtitleCapturedProfileRestoreTest {
    private val source = SubtitleSourceIdentity(SubtitleMediaSource("content://fixture/captured-profile-restore"), "a".repeat(64))
    private val options = SubtitleTargetOptions("hi", style="faithful", localRefinement=true,
        refinementPin=SubtitleRefinementPin("captured-model", "b".repeat(64), 1000000), capturedStyle=TranslationStyleProfile.FAITHFUL)
    private val base = SubtitleGenerationConfig(modelSha256="c".repeat(64), threads=2).withTarget(options)
    private fun store(root:File) = SubtitleGenerationStore(root, object:SubtitleJournalIo {
        override fun read(file:File) = file.readBytes()
        override fun write(file:File, bytes:ByteArray) { file.writeBytes(bytes) }
    })
    private fun restore(store:SubtitleGenerationStore, config:SubtitleGenerationConfig):SubtitleGenerationTask? {
        val method=store.javaClass.methods.firstOrNull { it.name.startsWith("findSavedCapturedRequest$") && it.parameterCount==2 }
        // Pre-fix staged bind uses exact find: this is the real RED fallback after new-request capture.
        return if(method==null) store.find(source, config) else method.invoke(store, source, config) as SubtitleGenerationTask?
    }
    @Test fun nullCallerOptionsColdRestoreTheUniqueStoredExplicitProfileWithoutUpgradingItsRequest() {
        val root=Files.createTempDirectory("subtitle-profile-restore").toFile()
        try {
            assertNull(options.refinementInputProfileRevision)
            val captured=SubtitleGenerationConfig(modelSha256="c".repeat(64), threads=2).withTarget(options.captureForNewRequest())
            assertEquals("orez-localization-v2",captured.refinementInputProfileRevision)
            val original=store(root).start(source,captured)
            val reopened=store(root)
            assertNull("The caller never captured a version; exact old fingerprint intentionally differs",reopened.find(source,base))
            val restored=restore(reopened,base)
            assertNotNull("A fresh captured task must remain discoverable after cold bind with the unchanged caller options",restored)
            assertEquals(original.id,restored!!.id);assertEquals(original.generation,restored.generation)
            assertEquals(captured,restored.config);assertNull(options.refinementInputProfileRevision)
            assertEquals(captured,store(root).get(original.id)!!.config)
        } finally {root.deleteRecursively()}
    }
    @Test fun restoreDoesNotBorrowAnotherOwnerOrChooseAmongAmbiguousVersionsAndKeepsExactHistoricalPrecedence() {
        val root=Files.createTempDirectory("subtitle-profile-scope").toFile()
        try {
            val v2=base.copy(refinementInputProfileRevision="orez-localization-v2")
            val owned=store(root).start(source,v2,ownerRequestId="orez-owned-task")
            assertNull(restore(store(root),base))
            assertEquals("orez-owned-task",store(root).get(owned.id)!!.ownerRequestId)
            root.listFiles()!!.forEach{it.delete()}
            val s=store(root);val first=s.start(source,v2);val second=s.start(source,base.copy(refinementInputProfileRevision="orez-localization-v99"))
            assertNull("Unknown caller cannot choose latest/current between multiple stored versions",restore(store(root),base))
            assertEquals(first.generation,store(root).get(first.id)!!.generation);assertEquals(second.generation,store(root).get(second.id)!!.generation)
            assertEquals(first.id,restore(store(root),v2)!!.id)
            val historical=s.start(source,base)
            assertEquals("Exact requested historical-null identity takes precedence without converting old G1",historical.id,restore(store(root),base)!!.id)
            assertNull(store(root).get(historical.id)!!.config.refinementInputProfileRevision)
            assertEquals(first.config,store(root).get(first.id)!!.config)
        } finally {root.deleteRecursively()}
    }
}
