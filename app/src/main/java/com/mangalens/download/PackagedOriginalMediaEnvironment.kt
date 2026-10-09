package com.mangalens.download

/** App-owned copy/probe tools link only to Android system libraries and never fetch media. */
internal object PackagedOriginalMediaEnvironment {
    fun configure(environment: MutableMap<String, String>) {
        environment.keys.filter { it.startsWith("LD_") || it.lowercase() in
            setOf("http_proxy", "https_proxy", "all_proxy", "no_proxy") }.forEach(environment::remove)
        environment["PATH"] = "/system/bin"
    }
}
