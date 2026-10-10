package com.mangalens.orez

/** App-authored metadata versions are independent of activation-journal/catalog versions. */
internal sealed interface OrezMinimumApp {
    data object NotDeclared : OrezMinimumApp
    data class Declared(val minimumVersionCode: Int) : OrezMinimumApp
}

internal data class OrezModelGovernanceRecord(
    val pin: OrezModelPin,
    val publisherRepository: String,
    val publisherRevision: String,
    val publisherWeightFileName: String,
    val publisherMetadataSha256: String,
    val publisherMetadataAsset: String,
    val licenseId: String,
    val licenseSha256: String,
    val licenseAsset: String,
    val packMinimumApp: OrezMinimumApp,
    val governanceSchemaVersion: Int
)

internal data class OrezModelGovernanceDisplay(
    val publisher: String,
    val license: String,
    val compatibility: String,
    val evaluation: String,
    val resources: String
)

/**
 * Supply-chain correspondence only. No model activation, readiness, memory admission or quality
 * permission is derived from this registry. Historical minimum-app information remains unknown.
 */
internal object OrezModelGovernance {
    const val SCHEMA_VERSION = 1
    const val RUNTIME_REVISION = "4da6337767f973e2b4d0797e5b323d77d8565e4a"
    const val RUNTIME_LICENSE_SHA256 = "94f29bbed6a22c35b992c5c6ebf0e7c92f13b836b90f36f461c9cf2f0f1d010d"
    const val RUNTIME_LICENSE_ASSET = "orez/licenses/llama.cpp-MIT.txt"
    private const val QWEN_LICENSE_SHA256 = "832dd9e00a68dd83b3c3fb9f5588dad7dcf337a0db50f7d9483f310cd292e92e"
    private const val QWEN_LICENSE_ASSET = "orez/licenses/Qwen-GGUF-Apache-2.0.txt"

    // Full publisher pins are deliberately independent of mutable Catalog descriptors. A changed
    // Catalog pin cannot inherit the old publisher's metadata. Local file names remain unchanged.
    private val records = listOf(
        record("qwen2.5-0.5b-q4_k_m", "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db", 491400032L,
            "Qwen/Qwen2.5-0.5B-Instruct-GGUF", "872f8a96064a1242ac3a3359cad77c3042548405", "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            "dc413d2685ed7d0c39dfcff578d0bee2d0fa5fc982ed3a0ebd17c8287d6304af", "orez/model-governance/lite-publisher.json"),
        record("qwen2.5-1.5b-q4_k_m", "6a1a2eb6d15622bf3c96857206351ba97e1af16c30d7a74ee38970e434e9407e", 1117320736L,
            "Qwen/Qwen2.5-1.5B-Instruct-GGUF", "a615a81362316d7b9f5a7a9c4313adfdf9b54588", "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            "09bd6b8e04b5eca58456a17e92e68a0fbbff1f0f00c8c0261793410db214c6e1", "orez/model-governance/core-publisher.json"),
        record("legacy-qwen2.5-0.5b-q6_k", "2f82233630c349ccf6b8daccf48f9a7865713d9f08a2eadfa456cebe9b97c7f5", 650379104L,
            "Qwen/Qwen2.5-0.5B-Instruct-GGUF", "9217f5db79a29953eb74d5343926648285ec7e67", "qwen2.5-0.5b-instruct-q6_k.gguf",
            "86efe4503306b84d8e967398972ec12a1fd30add0c18f81b3269da43d603cd46", "orez/model-governance/legacy-publisher.json")
    )

    private fun record(id: String, sha256: String, bytes: Long, repository: String, revision: String,
        publisherFileName: String, metadataSha256: String, metadataAsset: String) = OrezModelGovernanceRecord(
        OrezModelPin(id, sha256, bytes), repository, revision, publisherFileName, metadataSha256, metadataAsset,
        "Apache-2.0", QWEN_LICENSE_SHA256, QWEN_LICENSE_ASSET, OrezMinimumApp.NotDeclared, SCHEMA_VERSION)

    fun find(pin: OrezModelPin): OrezModelGovernanceRecord? = records.firstOrNull { it.pin == pin }

    fun display(pin: OrezModelPin?): OrezModelGovernanceDisplay {
        val record = pin?.let(::find)
        return OrezModelGovernanceDisplay(
            publisher = record?.let { "Publisher revision ${it.publisherRevision.take(12)} • metadata v${it.governanceSchemaVersion}" }
                ?: "Publisher metadata unavailable for this exact model",
            license = record?.let { "${it.licenseId} • pinned publisher license recorded" } ?: "License metadata pending",
            compatibility = "Historical pack minimum app version: not declared",
            evaluation = "Model evaluation pending",
            resources = "Resource profile: estimate only")
    }
}
