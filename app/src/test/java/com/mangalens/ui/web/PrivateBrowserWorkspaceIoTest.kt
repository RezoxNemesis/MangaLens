package com.mangalens.ui.web

import org.junit.Assert.*
import org.junit.Test

class PrivateBrowserWorkspaceIoTest {
    @Test fun newPrivateLifetimeHasNoPriorJournal() { assertNull(PrivateBrowserWorkspaceIo().read(100)) }
    @Test fun callerCannotMutatePrivateWrittenOrReturnedBytes() { val io = PrivateBrowserWorkspaceIo(); val bytes = byteArrayOf(1,2,3); io.write(bytes); bytes[0] = 9; val read = io.read(3)!!; read[1] = 9; assertArrayEquals(byteArrayOf(1,2,3), io.read(3)) }
    @Test fun retiredPrivateIoCannotReadOrWriteOldWorkspace() { val io = PrivateBrowserWorkspaceIo(); io.write(byteArrayOf(1)); io.retire(); try { io.read(100); fail() } catch (_: IllegalStateException) {} ; try { io.write(byteArrayOf(2)); fail() } catch (_: IllegalStateException) {} }
    @Test fun retirementDoesNotLeakIntoANewPrivateLifetime() { val old = PrivateBrowserWorkspaceIo(); old.write(byteArrayOf(1)); old.retire(); assertNull(PrivateBrowserWorkspaceIo().read(100)) }
}
