package com.mangalens

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.json.JSONObject
import org.junit.Assert.assertTrue
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Only generated QA data is exported; preferences are restored by each owning test. */
internal class CoreScreenSmokeSupport(private val name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context: Context = instrumentation.targetContext
    val device: UiDevice = UiDevice.getInstance(instrumentation)
    private val runId = UUID.randomUUID().toString()
    private val evidence = File(context.getExternalFilesDir(null), "qa/core-smoke/$name").apply {
        if (exists() && listFiles()?.isNotEmpty() == true) {
            val previous = File(context.getExternalFilesDir(null), "qa/core-smoke-history/$name/$runId")
            previous.parentFile?.mkdirs()
            check(renameTo(previous)) { "Could not preserve previous QA evidence for $name" }
        }
        check(mkdirs() || isDirectory) { "Could not create QA evidence for $name" }
    }
    private var failureRecorded = false

    fun launchHome() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            device.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        }
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        })
        node(By.desc("Settings and protection"))
        node(By.desc("Home").pkg(context.packageName))
    }

    fun node(selector: BySelector, timeout: Long = 15_000): UiObject2 =
        device.wait(Until.findObject(selector), timeout)
            ?: throw AssertionError("Expected UI control $selector was not visible on ${device.currentPackageName}")

    fun tap(selector: BySelector): UiObject2 = node(selector).also { it.click() }

    fun tapSettled(selector: BySelector, timeout: Long = 15_000): UiObject2 =
        clickSettledUi(device, selector, timeout)

    fun waitFor(message: String, timeout: Long = 10_000, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (!predicate() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
        assertTrue(message, predicate())
    }

    fun scrollTo(selector: BySelector): UiObject2 {
        repeat(12) {
            val label = device.findObject(selector)?.takeIf { it.visibleBounds.height() > 0 }
            val scrollable = device.findObjects(By.scrollable(true).pkg(context.packageName))
                .maxByOrNull { it.visibleBounds.width().toLong() * it.visibleBounds.height() }
            val viewport = scrollable?.visibleBounds
            val bounds = label?.visibleBounds
            val margin = (24 * context.resources.displayMetrics.density).toInt()
            if (label != null && (viewport == null ||
                (bounds!!.top >= viewport.top + margin && bounds.bottom <= viewport.bottom - margin))) return label
            requireNotNull(scrollable) { "No scrollable container while looking for $selector" }
            val direction = if (bounds != null && bounds.centerY() < viewport!!.centerY()) Direction.UP else Direction.DOWN
            scrollable.scroll(direction, .45f)
        }
        return node(selector, 2_000)
    }

    fun openHomeShortcut(label: String, minimumWidthDp: Int) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        scrollHomeToStart(deadline)
        val search = HomeShortcutSearch(deadline)
        val selector = By.text(label).pkg(context.packageName)
            .hasAncestor(By.desc("Home section: Quick actions").pkg(context.packageName))
        val minimumWidthPx = (minimumWidthDp * context.resources.displayMetrics.density).toInt()
        while (search.canContinue(SystemClock.uptimeMillis())) {
            try {
                val row = homeShortcutRow()
                val target = device.findObject(selector)?.let(::homeClickableAncestor)
                if (homeShortcutFullyVisible(target?.actionBounds(), row?.actionBounds(), minimumWidthPx)) {
                    val remaining = deadline - SystemClock.uptimeMillis()
                    if (remaining <= 0) break
                    clickSettledUi(device, selector, remaining, ready = {
                        val current = device.findObject(selector)?.let(::homeClickableAncestor)
                        homeShortcutFullyVisible(current?.actionBounds(), homeShortcutRow()?.actionBounds(), minimumWidthPx)
                    }, beforeClick = { bounds ->
                        check(homeShortcutFullyVisible(bounds, homeShortcutRow()?.actionBounds(), minimumWidthPx)) {
                            "Home shortcut $label moved outside its row before the tap"
                        }
                    })
                    return
                }
                if (row == null) {
                    homeList()?.let { scrollHomeBeforeDeadline(it, Direction.DOWN, deadline) }
                } else {
                    scrollHomeBeforeDeadline(row, Direction.RIGHT, deadline)
                }
            } catch (_: StaleObjectException) {
                // Lazy composition replaced this frame. Reacquire the actual row/target.
            }
            SystemClock.sleep(minOf(50L, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
        }
        throw AssertionError("Home shortcut $label was not fully reachable within 15000 ms")
    }

    fun assertHomeSectionsInOrder(before: String, after: String, message: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        scrollHomeToStart(deadline)
        val probe = HomeModuleOrderProbe(before, after)
        val settled = SettledUiTarget()
        var previous: List<HomeSectionSample>? = null
        var observed: HomeOrderObservation = HomeOrderObservation.SEEKING
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val list = homeList() ?: throw AssertionError("Home module list was not visible")
                val sections = device.findObjects(By.descStartsWith("Home section: ").pkg(context.packageName))
                    .map { HomeSectionSample(it.contentDescription, it.actionBounds()) }
                    .filter { it.bounds.visible }.sortedBy { it.bounds.top }
                if (sections != previous) {
                    previous = sections
                    settled.observe(null, false, SystemClock.uptimeMillis())
                }
                if (settled.observe(list.actionBounds(), true, SystemClock.uptimeMillis())) {
                    observed = probe.observe(homeHeaderVisible(), sections)
                    if (observed != HomeOrderObservation.SEEKING) break
                    scrollHomeBeforeDeadline(list, Direction.DOWN, deadline)
                    previous = null
                    settled.observe(null, false, SystemClock.uptimeMillis())
                }
            } catch (_: StaleObjectException) {
                previous = null
                settled.observe(null, false, SystemClock.uptimeMillis())
            }
            SystemClock.sleep(minOf(50L, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
        }
        assertTrue(message, observed == HomeOrderObservation.IN_ORDER)
        // Restore the real header for the next Customize/Reset action and existing screenshots.
        scrollHomeToStart(deadline)
    }

    private fun scrollHomeToStart(deadline: Long) {
        val settled = SettledUiTarget()
        while (SystemClock.uptimeMillis() < deadline) {
            try {
                val header = device.findObject(By.desc("Customize Home").pkg(context.packageName))
                if (settled.observe(header?.actionBounds(), homeHeaderVisible(), SystemClock.uptimeMillis())) return
                if (!homeHeaderVisible()) homeList()?.let { scrollHomeBeforeDeadline(it, Direction.UP, deadline) }
            } catch (_: StaleObjectException) {
                settled.observe(null, false, SystemClock.uptimeMillis())
            }
            SystemClock.sleep(minOf(50L, (deadline - SystemClock.uptimeMillis()).coerceAtLeast(0)))
        }
        throw AssertionError("Home header was not restored within the existing action deadline")
    }

    private fun homeHeaderVisible(): Boolean =
        device.findObject(By.desc("MangaLens logo").pkg(context.packageName))?.actionBounds()?.visible == true &&
            device.findObject(By.text("MangaLens").pkg(context.packageName))?.actionBounds()?.visible == true &&
            device.findObject(By.desc("Customize Home").pkg(context.packageName))?.actionBounds()?.visible == true

    private fun homeList(): UiObject2? = device.findObjects(By.scrollable(true).pkg(context.packageName))
        .maxByOrNull { it.visibleBounds.width().toLong() * it.visibleBounds.height() }

    private fun homeShortcutRow(): UiObject2? {
        val section = device.findObject(By.desc("Home section: Quick actions").pkg(context.packageName)) ?: return null
        val candidates = section.findObjects(By.scrollable(true)) + if (section.isScrollable) listOf(section) else emptyList()
        return candidates.filter { it.visibleBounds.width() > it.visibleBounds.height() }
            .maxByOrNull { it.visibleBounds.width() }
    }

    private fun homeClickableAncestor(label: UiObject2): UiObject2? {
        var current: UiObject2? = label
        repeat(8) {
            val node = current ?: return null
            if (node.isClickable && node.isEnabled && !node.visibleBounds.isEmpty) return node
            current = node.parent
        }
        return null
    }

    private fun UiObject2.actionBounds(): UiActionBounds = visibleBounds.let {
        UiActionBounds(it.left, it.top, it.right, it.bottom)
    }

    private fun scrollHomeBeforeDeadline(list: UiObject2, direction: Direction, deadline: Long) {
        val started = SystemClock.uptimeMillis()
        val bounds = list.actionBounds()
        val measured = SystemClock.uptimeMillis()
        val swipeDirection = when (direction) {
            Direction.RIGHT -> HomeSwipeDirection.RIGHT
            Direction.UP -> HomeSwipeDirection.UP
            Direction.DOWN -> HomeSwipeDirection.DOWN
            else -> throw IllegalArgumentException("Unsupported Home traversal direction")
        }
        val speed = (600 * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val swipe = homePhysicalSwipe(bounds, swipeDirection, speed, deadline - measured)
        val accepted = swipe?.let { device.swipe(it.startX, it.startY, it.endX, it.endY, it.steps) } ?: false
        val finished = SystemClock.uptimeMillis()
        // Numeric QA trace distinguishes accessibility-bound query cost from physical injection.
        File(evidence, "home-gestures.jsonl").appendText(JSONObject()
            .put("direction", swipeDirection.name).put("queryMs", measured - started)
            .put("injectionMs", finished - measured).put("remainingMs", (deadline - finished).coerceAtLeast(0))
            .put("left", bounds.left).put("top", bounds.top).put("right", bounds.right).put("bottom", bounds.bottom)
            .put("steps", swipe?.steps ?: 0).put("accepted", accepted).toString() + "\n")
    }

    fun checkableBeside(label: String): UiObject2 {
        val labelNode = scrollTo(By.text(label))
        if (labelNode.isCheckable) return labelNode
        val bounds = labelNode.visibleBounds
        // Compose can omit layout-only Rows from accessibility. Match the real adjacent
        // checkable node rather than selecting the first radio in the enclosing Card.
        return device.findObjects(By.checkable(true))
            .filter { control ->
                val target = control.visibleBounds
                target.width() > 0 && target.centerX() >= bounds.right &&
                    abs(target.centerY() - bounds.centerY()) <= (target.height() + bounds.height()) / 2
            }
            .minByOrNull { abs(it.visibleBounds.centerY() - bounds.centerY()) }
            ?: throw AssertionError("No toggle or radio control beside $label")
    }

    fun assertSelected(label: String) {
        // Density changes can move this row outside the viewport. Reacquire the real
        // chip after reflow; preference persistence alone is not a UI-state assertion.
        scrollTo(By.text(label).pkg(context.packageName))
        waitFor("$label did not expose its selected state") {
            var current: UiObject2? = device.findObject(By.text(label).pkg(context.packageName))
            val bounds = current?.visibleBounds
            if (bounds != null && !bounds.isEmpty) {
                val control = device.findObjects(By.checkable(true).pkg(context.packageName))
                    .filter { it.visibleBounds.contains(bounds) }
                    .minByOrNull { it.visibleBounds.width().toLong() * it.visibleBounds.height() }
                if (control?.isChecked == true || control?.isSelected == true) return@waitFor true
            }
            repeat(3) {
                if (current?.isSelected == true || current?.isChecked == true) return@waitFor true
                current = current?.parent
            }
            false
        }
    }

    fun recreateActivity() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().single().recreate()
        }
        instrumentation.waitForIdleSync()
    }

    fun revealWebControls(): UiObject2 {
        val selector = By.text("URL").pkg(context.packageName)
        device.findObject(selector)?.let { return it }
        device.click(device.displayWidth / 2, device.displayHeight / 2)
        return node(selector, 5_000)
    }

    fun recordFailure(failure: Throwable) {
        if (failureRecorded) return
        failureRecorded = true
        File(evidence, "failure.txt").writeText(failure.stackTraceToString())
        capture("failure")
    }

    fun capture(label: String): File {
        device.waitForIdle(1_000)
        val file = File(evidence, "$label.png")
        assertTrue("Screenshot could not be captured: $label", device.takeScreenshot(file))
        device.dumpWindowHierarchy(File(evidence, "$label.xml"))
        if (label != "failure") assertNoBlockingSystemDialog()
        return file
    }

    fun assertNoBlockingSystemDialog() {
        val blocking = device.findObjects(By.pkg("android")).firstOrNull {
            isBlockingSmokeDialog(it.applicationPackage, it.text.orEmpty())
        }
        assertTrue("Android failure dialog covered the test: ${blocking?.text}", blocking == null)
    }

    fun finishActivity() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<MainActivity>().forEach { it.finishAndRemoveTask() }
        }
        instrumentation.waitForIdleSync()
    }

    fun export(status: String) {
        File(evidence, "identity.json").writeText(JSONObject().apply {
            put("test", name); put("status", status); put("source_sha", BuildConfig.SOURCE_SHA)
            put("version", BuildConfig.VERSION_NAME); put("channel", BuildConfig.BUILD_CHANNEL)
            put("api", android.os.Build.VERSION.SDK_INT); put("abi", android.os.Build.SUPPORTED_ABIS.first())
            put("run_id", runId)
        }.toString(2))
        // Preserve generated evidence before any Gradle target uninstall.
        val publicEvidence = "/sdcard/Download/mangalens-qa/core-smoke/$name"
        val previousPublic = "/sdcard/Download/mangalens-qa/core-smoke-history/$name/$runId"
        // UiAutomation on older Android executes one program, without parsing shell built-ins.
        // A failed Runtime.exec("if ...") can leave its output pipe open and stall the runner.
        if (device.executeShellCommand("ls -d $publicEvidence").trim() == publicEvidence) {
            device.executeShellCommand("mkdir -p /sdcard/Download/mangalens-qa/core-smoke-history/$name")
            device.executeShellCommand("mv $publicEvidence $previousPublic")
            check(device.executeShellCommand("ls -d $previousPublic").trim() == previousPublic) {
                "Could not preserve previous public QA evidence for $name"
            }
        }
        device.executeShellCommand("mkdir -p $publicEvidence")
        device.executeShellCommand("cp -R ${evidence.absolutePath}/. /sdcard/Download/mangalens-qa/core-smoke/$name/")
    }
}

internal inline fun coreScreenSmoke(name: String, block: CoreScreenSmokeSupport.() -> Unit) {
    val support = CoreScreenSmokeSupport(name)
    val configurator = Configurator.getInstance()
    val previousIdleTimeout = configurator.waitForIdleTimeout
    // UiObject2 refreshes wait for idle before every property read. Its default 10s
    // outlives the 2.4/2.5s Reader/Web chrome; explicit bounded state waits remain.
    configurator.setWaitForIdleTimeout(100)
    var status = "failed"
    try {
        support.block()
        support.assertNoBlockingSystemDialog()
        status = "passed"
    } catch (failure: Throwable) {
        runCatching { support.recordFailure(failure) }
        throw failure
    } finally {
        try {
            runCatching { support.export(status) }
            support.finishActivity()
        } finally { configurator.setWaitForIdleTimeout(previousIdleTimeout) }
    }
}

internal fun isBlockingSmokeDialog(packageName: String?, text: String): Boolean =
    packageName == "android" && listOf("isn't responding", "is not responding", "keeps stopping", "has stopped")
        .any { text.contains(it, ignoreCase = true) }

internal fun restorePreferencesAfter(prefs: SharedPreferences, vararg keys: String): () -> Unit {
    val before = keys.associateWith { prefs.all[it] }
    return {
        val editor = prefs.edit()
        before.forEach { (key, value) ->
            when (value) {
                null -> editor.remove(key)
                is String -> editor.putString(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                else -> throw AssertionError("Unsupported preference type for $key")
            }
        }
        assertTrue("Could not restore QA preferences", editor.commit())
    }
}
