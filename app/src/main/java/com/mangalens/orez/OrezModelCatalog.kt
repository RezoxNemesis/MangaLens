package com.mangalens.orez

enum class OrezModelTier(val displayName: String) {
    LITE("Lite"),
    CORE("Core"),
    MAX("Max")
}

data class OrezModelDescriptor(
    val id: String,
    val tier: OrezModelTier,
    val label: String,
    val fileName: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
    val minimumSuggestedRamBytes: Long
)

object OrezModelCatalog {
    const val MANIFEST_VERSION = 1
    const val RUNTIME_CONTEXT_TOKENS = 8_192

    // Historical publisher pin from 734db03638f2625785a945dbf26170b53c7dba56.
    // Retained for authenticated fallback only; it is not offered as a new download.
    internal val legacy = OrezModelDescriptor(
        id = "legacy-qwen2.5-0.5b-q6_k",
        tier = OrezModelTier.LITE,
        label = "Legacy Lite Q6_K",
        fileName = "qwen2.5-0.5b-q6_k.gguf",
        url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/9217f5db79a29953eb74d5343926648285ec7e67/qwen2.5-0.5b-instruct-q6_k.gguf?download=true",
        bytes = 650379104L,
        sha256 = "2f82233630c349ccf6b8daccf48f9a7865713d9f08a2eadfa456cebe9b97c7f5",
        minimumSuggestedRamBytes = 3L * 1024L * 1024L * 1024L
    )

    val lite = OrezModelDescriptor(
        id = "qwen2.5-0.5b-q4_k_m",
        tier = OrezModelTier.LITE,
        label = "Lite 0.5B",
        fileName = "qwen2.5-0.5b-q4_k_m.gguf",
        url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/872f8a96064a1242ac3a3359cad77c3042548405/qwen2.5-0.5b-instruct-q4_k_m.gguf?download=true",
        bytes = 491400032L,
        sha256 = "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db",
        minimumSuggestedRamBytes = 3L * 1024L * 1024L * 1024L
    )

    val core = OrezModelDescriptor(
        id = "qwen2.5-1.5b-q4_k_m",
        tier = OrezModelTier.CORE,
        label = "Core 1.5B",
        fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
        url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/a615a81362316d7b9f5a7a9c4313adfdf9b54588/qwen2.5-1.5b-instruct-q4_k_m.gguf?download=true",
        bytes = 1117320736L,
        sha256 = "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e",
        minimumSuggestedRamBytes = 6L * 1024L * 1024L * 1024L
    )

    val availableDescriptors: List<OrezModelDescriptor> = listOf(lite, core)

    fun descriptor(tier: OrezModelTier): OrezModelDescriptor? =
        availableDescriptors.firstOrNull { it.tier == tier }

    fun byId(id: String): OrezModelDescriptor? =
        availableDescriptors.firstOrNull { it.id == id }

    fun recommended(totalRamBytes: Long): OrezModelTier =
        if (totalRamBytes >= core.minimumSuggestedRamBytes) OrezModelTier.CORE else OrezModelTier.LITE
}
