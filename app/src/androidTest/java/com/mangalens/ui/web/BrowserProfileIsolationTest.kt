package com.mangalens.ui.web

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Authored UNCOMPILED/UNRUN: real WebView/ProfileStore boundaries, no simulated isolation claim. */
@RunWith(AndroidJUnit4::class)
class BrowserProfileIsolationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun main(body: () -> Unit) = instrumentation.runOnMainSync(body)
    private fun supported() { var accepted = false; main { accepted = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) }; assumeTrue("Installed WebView lacks MULTI_PROFILE", accepted) }
    private data class Bound(val owner: BrowserProfileOwner, val lease: BrowserProfileRuntime.Lease, val view: WebView)
    private fun bind(choice: BrowserProfileChoice): Bound { var result: Bound? = null; main { BrowserProfileRuntime.select(choice); val owner = BrowserProfileOwner(choice); val lease = BrowserProfileRuntime.acquire(owner); val view = WebView(instrumentation.targetContext); lease.bind(view); assertEquals(choice.nativeName, WebViewCompat.getProfile(view).name); result = Bound(owner,lease,view) }; return result!! }
    private fun destroy(bound: Bound) { main { bound.owner.retire(); bound.view.destroy(); bound.lease.destroyed(true) } }
    private fun cookie(bound: Bound, origin: String, value: String) { val done = CountDownLatch(1); var accepted = false; main { BrowserProfileRuntime.cookies(bound.owner,bound.view).setCookie(origin,value) { accepted = it; done.countDown() } }; assertTrue(done.await(5,TimeUnit.SECONDS)); assertTrue(accepted) }
    private fun readCookie(bound: Bound, origin: String): String? { var result: String? = null; main { result = BrowserProfileRuntime.cookies(bound.owner,bound.view).getCookie(origin) }; return result }
    private fun custom() = BrowserProfileChoice(BrowserProfileKind.CUSTOM,browserId(),"Fixture")
    private fun cleanup(choice: BrowserProfileChoice) { main { assertTrue(ProfileStore.getInstance().getProfile(choice.nativeName) == null || ProfileStore.getInstance().deleteProfile(choice.nativeName)) } }
    private fun load(bound: Bound, origin: String) { val ready = CountDownLatch(1); main { bound.view.settings.javaScriptEnabled = true; bound.view.settings.domStorageEnabled = true; bound.view.webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView?, url: String?) { ready.countDown() } }; bound.view.loadDataWithBaseURL(origin,"<html><body>Profile fixture</body></html>","text/html","UTF-8",null) }; assertTrue("Actual document did not finish",ready.await(15,TimeUnit.SECONDS)) }
    private fun js(bound: Bound, script: String): String? { val done = CountDownLatch(1); var result: String? = null; main { bound.view.evaluateJavascript(script) { result = it; done.countDown() } }; assertTrue(done.await(5,TimeUnit.SECONDS)); return result }

    @Test fun actualCookiesAndLocalStorageStayInTheirBoundNativeProfile() {
        supported(); val choiceA = custom(); val choiceB = custom(); val a = bind(choiceA); val b = bind(choiceB); val origin = "https://fixture-" + browserId() + ".invalid/"
        try { cookie(a,origin,"account=A; Path=/; Secure"); cookie(b,origin,"account=B; Path=/; Secure"); assertTrue(readCookie(a,origin).orEmpty().contains("account=A")); assertFalse(readCookie(a,origin).orEmpty().contains("account=B")); assertTrue(readCookie(b,origin).orEmpty().contains("account=B")); load(a,origin); load(b,origin); assertEquals("\"A\"",js(a,"localStorage.setItem('profile','A'); localStorage.getItem('profile')")); assertEquals("null",js(b,"localStorage.getItem('profile')")); assertEquals("\"B\"",js(b,"localStorage.setItem('profile','B'); localStorage.getItem('profile')")); assertEquals("\"A\"",js(a,"localStorage.getItem('profile')")) }
        finally { destroy(a); destroy(b); cleanup(choiceA); cleanup(choiceB) }
    }
    @Test fun actualScopedClearPreservesTheOtherNativeProfileCookies() {
        supported(); val choiceA = custom(); val choiceB = custom(); val a = bind(choiceA); val b = bind(choiceB); val origin = "https://fixture-" + browserId() + ".invalid/"
        try { cookie(a,origin,"account=A; Path=/; Secure"); cookie(b,origin,"account=B; Path=/; Secure"); val done = CountDownLatch(1); var cleared = false; main { BrowserProfileRuntime.clearWebsiteData(a.owner,a.view) { cleared = it; done.countDown() } }; assertTrue(done.await(5,TimeUnit.SECONDS)); assertTrue(cleared); assertFalse(readCookie(a,origin).orEmpty().contains("account=A")); assertTrue(readCookie(b,origin).orEmpty().contains("account=B")) }
        finally { destroy(a); destroy(b); cleanup(choiceA); cleanup(choiceB) }
    }
    @Test fun privateDeletionAlsoWaitsForTheActualAcceptedMemoryActorSettlement() {
        supported(); val choice = BrowserProfileChoice.privateSession(); val bound = bind(choice)
        main { BrowserProfileRuntime.memoryClosing(choice); bound.owner.retire(); BrowserProfileRuntime.leave(choice) }
        destroy(bound); instrumentation.waitForIdleSync()
        main { assertNotNull(ProfileStore.getInstance().getProfile(choice.nativeName)) }
        BrowserProfileRuntime.memoryClosed(choice); instrumentation.waitForIdleSync()
        main { assertNull(ProfileStore.getInstance().getProfile(choice.nativeName)) }
    }
    @Test fun privateDeletionWaitsForTheActualWebViewDestroyReceipt() {
        supported(); val choice = BrowserProfileChoice.privateSession(); val bound = bind(choice)
        main { bound.owner.retire(); BrowserProfileRuntime.leave(choice) }
        instrumentation.waitForIdleSync()
        main { assertNotNull(ProfileStore.getInstance().getProfile(choice.nativeName)) }
        destroy(bound); instrumentation.waitForIdleSync()
        main { assertNull(ProfileStore.getInstance().getProfile(choice.nativeName)) }
    }
}
