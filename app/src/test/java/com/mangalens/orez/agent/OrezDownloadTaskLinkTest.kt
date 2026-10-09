package com.mangalens.orez.agent

import org.junit.Assert.*
import org.junit.Test

class OrezDownloadTaskLinkTest {
    @Test fun legacyAndBatchIdentitiesCanFindTheirOwningTask() {
        assertEquals(listOf("task"), OrezDownloadTaskLink.candidates("orez-task"))
        assertEquals(listOf("task", "task-step-1"), OrezDownloadTaskLink.candidates("orez-task-step-1"))
        assertTrue(OrezDownloadTaskLink.candidates("ordinary-native-download").isEmpty())
    }
}
