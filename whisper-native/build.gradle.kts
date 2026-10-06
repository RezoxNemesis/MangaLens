plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.mangalens.whisper"
    compileSdk = 35
    ndkVersion = "29.0.13113456"
    defaultConfig {
        minSdk = 26
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    externalNativeBuild { cmake { path("src/main/cpp/CMakeLists.txt") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
