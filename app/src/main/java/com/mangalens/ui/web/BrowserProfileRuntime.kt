package com.mangalens.ui.web

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Native WebView APIs stay on Main; metadata checks never inspect a page or lend cookies. */
internal object BrowserProfileRuntime {
    private val main = Handler(Looper.getMainLooper())
    private val leases = mutableSetOf<Lease>()
    private val deletePending = mutableSetOf<String>()
    private val memoryClosing = mutableSetOf<String>()
    private var initialized = false
    private var privateName: String? = null
    private val mutableNotice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = mutableNotice
    fun supported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
    private fun requireMain() = check(Looper.myLooper() == Looper.getMainLooper())
    /** Browser startup only; never trigger WebView initialization while merely showing Home/widgets. */
    private fun initialize() {
        requireMain(); check(supported()) { "This Android WebView does not support isolated profiles. Normal browsing remains available." }
        if (initialized) return
        val store = ProfileStore.getInstance()
        val owned = store.allProfileNames.filter(BrowserProfilePolicy::ownedPrivateName)
        check(owned.size <= 64) { "Too many old private profiles need cleanup. Isolated browsing is unavailable." }
        owned.forEach { name ->
            if (!store.deleteProfile(name)) {
                deletePending += name
                mutableNotice.value = "An earlier private WebView profile could not be deleted. Restart the app before opening Private."
            }
        }
        initialized = true
    }
    /** Reserve the private lifetime before Compose can coalesce another selection. */
    fun select(choice: BrowserProfileChoice) {
        requireMain(); choice.validate()
        if (choice.kind == BrowserProfileKind.NORMAL) return
        initialize()
        if (choice.ephemeral) {
            check(deletePending.isEmpty() && privateName == null) { "A private profile is still closing or could not be deleted. Close it before opening another." }
            privateName = choice.nativeName
        }
    }
    fun acquire(owner: BrowserProfileOwner): Lease {
        requireMain(); check(owner.isCurrent()); check(leases.size < BrowserProfilePolicy.MAX_BINDINGS) { "A browser view is still closing. Close another view before retrying." }
        if (owner.choice.kind != BrowserProfileKind.NORMAL) initialize()
        if (owner.choice.ephemeral) check(privateName == owner.choice.nativeName) { "The private profile lifetime is no longer current." }
        return Lease(owner).also { leases += it }
    }
    class Lease internal constructor(private val owner: BrowserProfileOwner) {
        private var view: WebView? = null
        private var bound = false
        private var settled = false
        val profileKey get() = owner.choice.key
        fun bind(value: WebView) {
            requireMain(); check(!settled && view == null && owner.isCurrent()); view = value
            if (owner.choice.kind != BrowserProfileKind.NORMAL) {
                ProfileStore.getInstance().getOrCreateProfile(owner.choice.nativeName)
                WebViewCompat.setProfile(value, owner.choice.nativeName)
                check(WebViewCompat.getProfile(value).name == owner.choice.nativeName) { "WebView did not bind the requested isolated profile." }
            }
            bound = true
        }
        fun owns(value: WebView?) = !settled && bound && owner.isCurrent() && value != null && value === view
        /** Call only after actual destroy returns; failed views remain strongly retained in this lease. */
        fun destroyed(success: Boolean) {
            requireMain(); if (settled) return
            if (!success) {
                mutableNotice.value = "A browser view did not close safely. Its profile data is retained; restart the app before retrying."
                return
            }
            settled = true; view = null; leases.remove(this)
            settleDeletes()
        }
        /** A constructor failure acquired no WebView. */
        fun creationFailed() { requireMain(); check(view == null); destroyed(true) }
        internal fun belongsTo(value: BrowserProfileOwner, nativeView: WebView) = owner === value && owns(nativeView)
        internal fun nativeName() = owner.choice.nativeName
    }
    fun memoryClosing(choice: BrowserProfileChoice) { requireMain(); check(choice.ephemeral); memoryClosing += choice.nativeName }
    /** IO completion is an observation, never a requester-driven release of an unfinished actor. */
    fun memoryClosed(choice: BrowserProfileChoice) { main.post { memoryClosing.remove(choice.nativeName); settleDeletes() } }
    fun leave(choice: BrowserProfileChoice) {
        requireMain()
        if (!choice.ephemeral) return
        deletePending += choice.nativeName
        // Run after child disposal callbacks; delete waits for actual settled leases regardless of order.
        main.post { settleDeletes() }
    }
    private fun settleDeletes() {
        requireMain(); if (!supported()) return
        val store = ProfileStore.getInstance()
        deletePending.toList().forEach { name ->
            if (name in memoryClosing || leases.any { it.nativeName() == name }) return@forEach
            val deleted = try { store.getProfile(name) == null || store.deleteProfile(name) } catch (_: Exception) { false }
            if (deleted) { deletePending.remove(name); if (privateName == name) privateName = null }
            else mutableNotice.value = "Private WebView profile deletion did not complete. Its data is retained; restart the app before retrying."
        }
    }
    private fun currentBinding(owner: BrowserProfileOwner, view: WebView): Boolean =
        owner.isCurrent() && leases.any { it.belongsTo(owner, view) }
    fun cookies(owner: BrowserProfileOwner, view: WebView): android.webkit.CookieManager {
        requireMain(); check(currentBinding(owner, view))
        if (owner.choice.kind == BrowserProfileKind.NORMAL && !supported()) return android.webkit.CookieManager.getInstance()
        val profile = WebViewCompat.getProfile(view); check(profile.name == owner.choice.nativeName)
        return profile.cookieManager
    }
    /** Scoped native managers only; cache/service-worker removal is not claimed by this API. */
    fun clearWebsiteData(owner: BrowserProfileOwner, view: WebView, done: (Boolean) -> Unit) {
        requireMain(); if (!currentBinding(owner, view)) { done(false); return }
        try {
            if (owner.choice.kind == BrowserProfileKind.NORMAL && !supported()) {
                android.webkit.CookieManager.getInstance().removeAllCookies {
                    if (!currentBinding(owner, view)) done(false) else try {
                        android.webkit.CookieManager.getInstance().flush(); android.webkit.WebStorage.getInstance().deleteAllData(); android.webkit.GeolocationPermissions.getInstance().clearAll(); done(true)
                    } catch (_: Exception) { done(false) }
                }
            } else {
                val profile = WebViewCompat.getProfile(view)
                check(profile.name == owner.choice.nativeName)
                profile.cookieManager.removeAllCookies {
                    if (!currentBinding(owner, view)) done(false) else try {
                        check(WebViewCompat.getProfile(view).name == owner.choice.nativeName)
                        profile.cookieManager.flush(); profile.webStorage.deleteAllData(); profile.geolocationPermissions.clearAll(); done(true)
                    } catch (_: Exception) { done(false) }
                }
            }
        } catch (_: Exception) { done(false) }
    }
}
