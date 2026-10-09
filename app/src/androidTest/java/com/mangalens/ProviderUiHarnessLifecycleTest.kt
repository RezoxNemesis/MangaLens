package com.mangalens

import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mangalens.download.DownloadDatabase
import com.mangalens.download.DownloadEntity
import com.mangalens.download.DownloadState
import com.mangalens.download.MediaDownloadManager
import com.mangalens.download.MediaDownloadWorker
import java.io.File
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real lifecycle/Room/WorkManager regressions; no provider access, transfer or decoded media claim. */
@RunWith(AndroidJUnit4::class)
class ProviderUiHarnessLifecycleTest {
    @Test fun ownsTheNewActivityAndItsRecreationThenClosesBothInstances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidence = File(context.cacheDir, "provider-lifecycle-${UUID.randomUUID()}")
        val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
        var unrelated: MainActivity? = null
        scenario.onActivity { unrelated = it }
        val ui = ProviderUiHarness(evidence)
        try {
            ui.openHome()
            val original = ui.ownedActivity()
            assertNotSame("The new launch queried a previous global resumed activity", unrelated, original)
            ui.recreateOwnedActivity()
            assertNotSame(original, ui.ownedActivity())
            ui.assertBackReturnedHome()
        } finally {
            ui.close()
            ui.waitFor("Owned activities/listener survived harness cleanup") { ui.cleanupComplete() }
            scenario.close()
            evidence.deleteRecursively()
        }
    }

    @Test fun acceptedPauseSurvivesActivityReplacementAndRejectsLateDownloadMutations(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "qa-provider-lifecycle-${UUID.randomUUID()}"
        val source = "http://127.0.0.1:9/controlled-never-requested.mp4"
        val work = OneTimeWorkRequestBuilder<MediaDownloadWorker>()
            .setInitialDelay(Duration.ofDays(1))
            .setInputData(workDataOf(MediaDownloadWorker.KEY_ID to id, MediaDownloadWorker.KEY_URL to source,
                MediaDownloadWorker.KEY_TITLE to "Controlled lifecycle row", MediaDownloadWorker.KEY_MIME to "video/mp4"))
            .addTag(id).build()
        val workManager = WorkManager.getInstance(context)
        val dao = DownloadDatabase.get(context).downloads()
        val ui = ProviderUiHarness(File(context.cacheDir, id))
        try {
            dao.upsert(DownloadEntity(id, source, "Controlled lifecycle row", "video/mp4", bytesDownloaded = 17))
            workManager.enqueueUniqueWork("mangalens-download-$id", ExistingWorkPolicy.REPLACE, work)
                .result.get(5, TimeUnit.SECONDS)
            MediaDownloadManager(context).pause(id)
            assertEquals(DownloadState.PAUSED, dao.get(id)!!.state)
            ui.waitFor("Pause did not cancel its actual delayed WorkSpec") {
                workManager.getWorkInfoById(work.id).get(5, TimeUnit.SECONDS)?.state == WorkInfo.State.CANCELLED
            }
            ui.openHome()
            ui.recreateOwnedActivity()
            ui.close()
            ui.waitFor("Harness cleanup did not complete") { ui.cleanupComplete() }
            val reopened = Room.databaseBuilder(context, DownloadDatabase::class.java, "mangalens_downloads.db").build()
            try {
                val saved = reopened.downloads().get(id)!!
                assertEquals(DownloadState.PAUSED, saved.state)
                assertEquals(17L, saved.bytesDownloaded)
                assertEquals(source, saved.sourceUrl)
            } finally { reopened.close() }
            assertEquals("A late worker revived the paused download", 0, dao.progressIfActive(id, 123, 123))
            assertEquals("A late completion bypassed Pause", 0, dao.completeIfActive(id, "content://controlled/unused", 123))
            assertEquals(DownloadState.PAUSED, dao.get(id)!!.state)
        } finally {
            ui.close()
            workManager.cancelWorkById(work.id).result.get(5, TimeUnit.SECONDS)
            dao.delete(id)
            File(context.cacheDir, id).deleteRecursively()
        }
    }
}
