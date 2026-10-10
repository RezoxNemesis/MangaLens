package com.mangalens.ui.web

import com.mangalens.download.ProviderCaptionFormat
import com.mangalens.download.ProviderCaptionInventory
import com.mangalens.download.ProviderCaptionKind
import com.mangalens.download.ProviderCaptionTrack
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class BrowserProfileReceiptTest {
    private val url = "https://source.invalid/page"
    private val tab = "a".repeat(32)
    private val view = "b".repeat(32)
    @Test fun normalDomPageIdRemainsByteCompatible() { val owner = BrowserDomOwner(tab, 7, url, view); val old = MessageDigest.getInstance("SHA-256").digest(listOf(tab,"7",url,view).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }; assertEquals(old, owner.pageId) }
    @Test fun sameUrlTabEpochAndViewCannotCrossProfileDomAuthority() { val owner = BrowserDomOwner(tab,7,url,view); val work = owner.copy(profileKey="work"); assertNotEquals(owner, work); assertNotEquals(owner.pageId, work.pageId) }
    @Test fun pendingFilePickerCannotDeliverIntoAnotherProfile() { val gate = BrowserUploadGate(); val scope = BrowserUploadScope(tab,7,url,"work"); var selected: List<String>? = listOf("old"); val request = gate.begin(scope,listOf("image/png"),false) { selected = it }!!; assertEquals(BrowserUploadFinish.STALE, gate.finish(request.token,scope.copy(profileKey="normal"),listOf(BrowserUploadSelection("content://source/image","image/png","image.png",true)))); assertNull(selected) }
    @Test fun exactProfileFilePickerPositiveIsPreserved() { val gate = BrowserUploadGate(); val scope = BrowserUploadScope(tab,7,url,"work"); var selected: List<String>? = null; val request = gate.begin(scope,listOf("image/png"),false) { selected = it }!!; assertEquals(BrowserUploadFinish.ACCEPTED,gate.finish(request.token,scope,listOf(BrowserUploadSelection("content://source/image","image/png","image.png",true)))); assertEquals(listOf("content://source/image"),selected) }
    @Test fun sourceCaptionScopeDoesNotAcceptAnotherProfileAtTheSameClock() {
        val owner = BrowserCaptionPageOwner(tab,7,url,view); val clock = BrowserCaptionClockSample(url,"c".repeat(32),"d".repeat(32),"e".repeat(32),"https://source.invalid/video.mp4",null,"en",100,8000,1.0,false,false,4)
        val inventory = ProviderCaptionInventory(url,null,"en","en",8000,listOf(ProviderCaptionTrack("https://source.invalid/a.vtt","en",ProviderCaptionKind.MANUAL,ProviderCaptionFormat.VTT)))
        val source = BrowserSourceCaptionScope(owner,clock,inventory); val work = owner.copy(profileKey="work"); assertFalse(source.accepts(work,clock)); assertNotEquals(source.sourceId,BrowserSourceCaptionScope(work,clock,inventory).sourceId); assertTrue(source.accepts(owner,clock))
    }
}
