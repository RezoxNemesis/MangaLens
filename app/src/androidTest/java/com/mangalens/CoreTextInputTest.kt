package com.mangalens

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreTextInputTest {
    @Test fun describedLayoutEditsOnlyItsActualFieldAmongMultipleInputs() = fixture { device ->
        val editable = typeIntoEditableUi(device, "Secondary fixture input", "second value")
        assertEquals("android.widget.EditText", editable.className)
        assertNotNull(device.wait(Until.findObject(By.text("Secondary result: second value")), 2_000))
        assertNotNull(device.findObject(By.text("Primary result: untouched")))
        assertEquals("untouched", readEditableUi(device, "Primary fixture input").text)
        assertEquals("second value", readEditableUi(device, "Secondary fixture input").text)
        typeIntoEditableUi(device, "Primary fixture input", "first value")
        assertNotNull(device.wait(Until.findObject(By.text("Primary result: first value")), 2_000))
        assertNotNull(device.findObject(By.text("Secondary result: second value")))
    }

    @Test fun missingDescriptionNeverFallsBackToAnUnrelatedEditableField() = fixture { device ->
        assertNotNull(device.wait(Until.findObject(By.desc("Primary fixture input")), 2_000))
        try {
            typeIntoEditableUi(device, "Absent fixture input", "wrong field", timeoutMs = 300)
            fail("A missing described field must not use any other input")
        } catch (expected: AssertionError) {
            assertTrue(expected.message.orEmpty().contains("did not accept"))
        }
        assertNotNull(device.findObject(By.text("Primary result: untouched")))
        assertNotNull(device.findObject(By.text("Secondary result: untouched")))
    }

    @Test fun duplicateDescriptionScopesNeverChooseTheFirstMatchingInput() = fixture("Duplicate fixture input", "Duplicate fixture input") { device ->
        assertNotNull(device.wait(Until.findObject(By.text("Primary result: untouched")), 2_000))
        try {
            typeIntoEditableUi(device, "Duplicate fixture input", "wrong field", timeoutMs = 300)
            fail("Ambiguous described fields must not choose the first input")
        } catch (expected: AssertionError) {
            assertTrue(expected.message.orEmpty().contains("did not accept"))
        }
        assertNotNull(device.findObject(By.text("Primary result: untouched")))
        assertNotNull(device.findObject(By.text("Secondary result: untouched")))
    }

    private fun fixture(
        primaryDescription: String = "Primary fixture input",
        secondaryDescription: String = "Secondary fixture input",
        check: (UiDevice) -> Unit
    ) {
        val configurator = Configurator.getInstance()
        val previousIdleTimeout = configurator.waitForIdleTimeout
        configurator.setWaitForIdleTimeout(100)
        try { fixtureWithBoundedQueries(primaryDescription, secondaryDescription, check) }
        finally { configurator.setWaitForIdleTimeout(previousIdleTimeout) }
    }

    private fun fixtureWithBoundedQueries(primaryDescription: String, secondaryDescription: String, check: (UiDevice) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                var primary by remember { mutableStateOf("untouched") }
                var secondary by remember { mutableStateOf("untouched") }
                MaterialTheme { Column {
                    OutlinedTextField(primary, { primary = it }, singleLine = true,
                        modifier = Modifier.semantics { contentDescription = primaryDescription })
                    OutlinedTextField(secondary, { secondary = it }, singleLine = true,
                        modifier = Modifier.semantics { contentDescription = secondaryDescription })
                    Text("Primary result: $primary")
                    Text("Secondary result: $secondary")
                } }
            } }
            check(UiDevice.getInstance(instrumentation))
        }
    }
}
