package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test

class PackagedOriginalMediaEnvironmentTest {
    @Test fun ambientLoaderOverridesAndProxyRoutesCannotReachCopyOnlyTools() {
        val environment = mutableMapOf("LD_LIBRARY_PATH" to "old/python/usr/lib", "LD_PRELOAD" to "/untrusted.so",
            "LD_AUDIT" to "/audit.so", "http_proxy" to "http://proxy.invalid", "HTTPS_PROXY" to "http://proxy.invalid",
            "ALL_PROXY" to "http://proxy.invalid", "No_Proxy" to "*", "PATH" to "/custom/bin")
        PackagedOriginalMediaEnvironment.configure(environment)
        assertEquals(mapOf("PATH" to "/system/bin"), environment)
    }
    @Test fun ordinaryProcessLocaleAndTemporaryDirectoryRemainAvailable() {
        val environment = mutableMapOf("LANG" to "C.UTF-8", "TMPDIR" to "/private/tmp", "PATH" to "/old")
        PackagedOriginalMediaEnvironment.configure(environment)
        assertEquals("C.UTF-8", environment["LANG"])
        assertEquals("/private/tmp", environment["TMPDIR"])
        assertEquals("/system/bin", environment["PATH"])
    }
}
