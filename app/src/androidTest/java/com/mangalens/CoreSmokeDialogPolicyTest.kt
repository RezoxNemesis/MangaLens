package com.mangalens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreSmokeDialogPolicyTest {
    @Test fun systemAnrAndCrashDialogsInvalidateVisualEvidence() {
        assertTrue(isBlockingSmokeDialog("android", "System UI isn't responding"))
        assertTrue(isBlockingSmokeDialog("android", "MangaLens keeps stopping"))
    }

    @Test fun permissionAndProjectionConsentRemainValidTestSurfaces() {
        assertFalse(isBlockingSmokeDialog("android", "Allow MangaLens to access photos and videos?"))
        assertFalse(isBlockingSmokeDialog("android", "Start recording or casting with MangaLens?"))
        assertFalse(isBlockingSmokeDialog("android", "Open a document"))
    }

    @Test fun sourcePageTextCannotImpersonateAnAndroidFailureDialog() {
        assertFalse(isBlockingSmokeDialog("com.mangalens", "System UI isn't responding"))
    }
}
