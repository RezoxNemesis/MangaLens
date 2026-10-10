package com.mangalens.ui.web

import org.junit.Assert.*
import org.junit.Test

class BrowserProfileDialogAuthorityTest {
    @Test fun acceptedCreationCanSelectOnlyItsStillOpenDialog() { val a = BrowserProfileDialogAuthority(); val owner = BrowserProfileOwner(BrowserProfileChoice.Normal); a.open(owner); val ticket = a.capture(owner)!!; assertTrue(a.accepts(ticket, owner)) }
    @Test fun dismissedCreationMayBeDurableButCannotSelect() { val a = BrowserProfileDialogAuthority(); val owner = BrowserProfileOwner(BrowserProfileChoice.Normal); a.open(owner); val ticket = a.capture(owner)!!; a.retire(); assertFalse(a.accepts(ticket, owner)) }
    @Test fun closedAndReopenedSameProfileCannotReviveOldCreation() { val a = BrowserProfileDialogAuthority(); val owner = BrowserProfileOwner(BrowserProfileChoice.Normal); a.open(owner); val ticket = a.capture(owner)!!; a.retire(); a.open(owner); assertFalse(a.accepts(ticket, owner)) }
    @Test fun switchingToWorkRejectsEarlierNormalCompletion() { val a = BrowserProfileDialogAuthority(); val normal = BrowserProfileOwner(BrowserProfileChoice.Normal); a.open(normal); val ticket = a.capture(normal)!!; normal.retire(); val work = BrowserProfileOwner(BrowserProfileChoice.Work); a.open(work); assertFalse(a.accepts(ticket, work)) }
    @Test fun sameNamedNewOwnerCannotBorrowOldDialog() { val a = BrowserProfileDialogAuthority(); val old = BrowserProfileOwner(BrowserProfileChoice.Normal); a.open(old); val ticket = a.capture(old)!!; old.retire(); val replacement = BrowserProfileOwner(BrowserProfileChoice.Normal); a.open(replacement); assertFalse(a.accepts(ticket, replacement)) }
    @Test fun retiredOwnerCannotCaptureOrPublishAResult() { val a = BrowserProfileDialogAuthority(); val owner = BrowserProfileOwner(BrowserProfileChoice.Normal); a.open(owner); val ticket = a.capture(owner)!!; owner.retire(); assertNull(a.capture(owner)); assertFalse(a.accepts(ticket, owner)) }
}
