package com.mangalens.orez

import com.mangalens.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** Request metadata only. Unknown actual binary/build/corpus/device evidence is never invented. */
internal object OrezTrainingLabRequest {
    fun create(pin: OrezModelPin): ByteArray {
        require(OrezPinnedModelPolicy.valid(pin))
        val record = OrezModelGovernance.find(pin)
        return JSONObject().put("schema", 1).put("kind", "OREZ_EVALUATION_REQUEST")
            .put("qualification", "PENDING").put("model", JSONObject().put("model_id", pin.modelId).put("sha256", pin.sha256).put("bytes", pin.bytes))
            .put("app_version_code", BuildConfig.VERSION_CODE).put("app_base_source_revision", BuildConfig.SOURCE_SHA)
            .put("runtime_source_revision", OrezModelGovernance.RUNTIME_REVISION)
            .put("input_profile", OrezLocalizationV2.REVISION).put("requested_output_limit", OrezLocalizationV2.MAX_TOKENS)
            .put("publisher_revision", record?.publisherRevision ?: JSONObject.NULL)
            .put("license_sha256", record?.licenseSha256 ?: JSONObject.NULL)
            .put("required_actual_evidence", JSONArray(listOf("FULL_BUILD_INPUT_MANIFEST", "APK_SHA256", "NATIVE_BINARY_SHA256",
                "TENSOR_RECEIPT", "LICENSED_CORPUS_AND_GROUPED_HELD_OUT", "EXACT_CAPTURED_TASK_SOURCE_PROMPTS", "DEVICE_CAPABILITIES",
                "ACTUAL_NATIVE_SETTINGS_RESULTS_LATENCY_RSS", "INDEPENDENT_REVIEWED_REGISTRATION_AND_APPROVAL")))
            .put("scope_notice", "Base source revision and requested settings are not full build identity or measured native settings. Host training/evaluation cannot qualify this Android profile.")
            .toString(2).toByteArray(Charsets.UTF_8)
    }
}
