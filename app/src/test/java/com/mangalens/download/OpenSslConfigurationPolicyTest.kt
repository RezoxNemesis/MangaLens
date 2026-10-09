package com.mangalens.download

import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class OpenSslConfigurationPolicyTest {
    @Test fun readableInheritedOpenSslConfigurationCannotExpandTrust() {
        assertThrows(IOException::class.java) {
            NativeExtractorNetworkPolicy.requireNoExtraCaConfiguration("/private/native.cnf", "/missing/default.cnf") {
                it == "/private/native.cnf"
            }
        }
    }

    @Test fun aReadableDefaultOpenSslConfigurationCannotExpandTrust() {
        assertThrows(IOException::class.java) {
            NativeExtractorNetworkPolicy.requireNoExtraCaConfiguration(null, "/readable/default.cnf") {
                it == "/readable/default.cnf"
            }
        }
    }

    @Test fun anExplicitlyDisabledOpenSslConfigurationDoesNotUseTheDefault() {
        NativeExtractorNetworkPolicy.requireNoExtraCaConfiguration("", "/readable/default.cnf") { true }
    }
}
