package com.mangalens.ui.library

import com.mangalens.core.reader.ReadingStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class LibrarySettingsTest {
    private class FileStorage(private val file: File) : LibrarySettingsStorage {
        var fail = false
        override fun read() = if (file.isFile) file.readText() else null
        override fun write(value: String): Boolean { if (fail) return false; file.writeText(value); return true }
    }
    @Test fun acceptedCombinedChoicesPersistAcrossActualFileStoreRecreation() {
        val file = Files.createTempFile("library-options-", ".json").toFile()
        try {
            val options = LibraryOptions(ReadingStatus.DROPPED, true,"host:manga.example.org", "Hindi practice", LibraryOfflineFilter.INCOMPLETE, LibrarySort.LAST_READ)
            LibrarySettingsStore(FileStorage(file)).save(options)
            assertEquals(options, LibrarySettingsStore(FileStorage(file)).load())
            LibrarySettingsStore(FileStorage(file)).save(LibraryOptions())
            assertEquals(LibraryOptions(), LibrarySettingsStore(FileStorage(file)).load())
        } finally { file.delete() }
    }
    @Test fun corruptUnknownFutureAndOverlargeOptionsRestoreSafeDefaults() {
        val encoded = LibraryOptionsCodec.encode(LibraryOptions(status=ReadingStatus.PLAN_TO_READ))
        listOf(null,"", "{broken", JSONObject(encoded).put("version",2).toString(), JSONObject(encoded).put("sort","unknown").toString(), JSONObject(encoded).put("status","UNKNOWN").toString(), "x".repeat(4_097)).forEach {
            assertEquals(LibraryOptions(), LibraryOptionsCodec.decode(it))
        }
    }
    @Test fun failedPreferenceWriteKeepsAcceptedStateAndReportsRetryableError() {
        val file = Files.createTempFile("library-options-", ".json").toFile()
        try {
            val storage=FileStorage(file);val store=LibrarySettingsStore(storage);val accepted=LibraryOptions(bookmarksOnly=true)
            store.save(accepted);storage.fail=true
            assertThrows(IOException::class.java) { store.save(accepted.copy(sort=LibrarySort.TITLE_ASC)) }
            assertEquals(accepted,store.load())
        } finally { file.delete() }
    }
    @Test fun badScopeKeysOrOversizedCollectionCannotReplaceAcceptedChoice() {
        val file = Files.createTempFile("library-options-", ".json").toFile()
        try {
            val store=LibrarySettingsStore(FileStorage(file));store.save(LibraryOptions());val before=file.readBytes()
            assertThrows(IllegalArgumentException::class.java) { store.save(LibraryOptions(sourceKey="host:private/path?token")) }
            assertArrayEquals(before,file.readBytes())
            assertThrows(IllegalArgumentException::class.java) { store.save(LibraryOptions(collection="x".repeat(81))) }
            assertArrayEquals(before,file.readBytes())
        } finally { file.delete() }
    }
}
