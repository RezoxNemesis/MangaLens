#include "llama.h"
#include <jni.h>
#include <mutex>
#include <string>
#include <vector>
#include <atomic>
#include <memory>
#include <unordered_map>
#include <thread>
#include <algorithm>

static std::mutex g_mutex;
static llama_model * g_model = nullptr;

struct generation_request {
    std::atomic<bool> cancelled{false};
    std::atomic<bool> running{false};
};
// Cancellation must never wait for the model mutex held during CPU decoding.
static std::mutex g_requests_mutex;
static std::unordered_map<jlong, std::shared_ptr<generation_request>> g_requests;
static std::atomic<jlong> g_next_request{1};

static std::shared_ptr<generation_request> find_request(jlong id) {
    std::lock_guard<std::mutex> lock(g_requests_mutex);
    auto found = g_requests.find(id);
    return found == g_requests.end() ? nullptr : found->second;
}

static bool abort_generation(void * data) {
    auto * request = static_cast<generation_request *>(data);
    request->running.store(true);
    return request->cancelled.load();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_mangalens_oreznative_OrezNativeEngine_nativeCreateRequest(JNIEnv *, jobject) {
    const jlong id = g_next_request.fetch_add(1);
    std::lock_guard<std::mutex> lock(g_requests_mutex);
    g_requests.emplace(id, std::make_shared<generation_request>());
    return id;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mangalens_oreznative_OrezNativeEngine_nativeCancelRequest(JNIEnv *, jobject, jlong id) {
    if (auto request = find_request(id)) request->cancelled.store(true);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_mangalens_oreznative_OrezNativeEngine_nativeRequestRunning(JNIEnv *, jobject, jlong id) {
    auto request = find_request(id);
    return request && request->running.load() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mangalens_oreznative_OrezNativeEngine_nativeReleaseRequest(JNIEnv *, jobject, jlong id) {
    std::lock_guard<std::mutex> lock(g_requests_mutex);
    auto found = g_requests.find(id);
    if (found != g_requests.end()) {
        found->second->cancelled.store(true);
        g_requests.erase(found);
    }
}

// Java strings expose modified UTF-8 through GetStringUTFChars, which corrupts emoji.
static std::string java_utf8(JNIEnv * env, jstring value) {
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID getBytes = env->GetMethodID(stringClass, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, getBytes, charset));
    std::string result;
    if (bytes != nullptr && !env->ExceptionCheck()) {
        result.resize(static_cast<size_t>(env->GetArrayLength(bytes)));
        if (!result.empty()) env->GetByteArrayRegion(bytes, 0, static_cast<jsize>(result.size()), reinterpret_cast<jbyte *>(&result[0]));
        env->DeleteLocalRef(bytes);
    }
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(stringClass);
    return result;
}


extern "C" JNIEXPORT jboolean JNICALL
Java_com_mangalens_oreznative_OrezNativeEngine_nativeLoad(
        JNIEnv * env, jobject, jstring path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model != nullptr) return JNI_TRUE;
    const char * chars = env->GetStringUTFChars(path, nullptr);
    if (chars == nullptr) return JNI_FALSE;
    llama_backend_init();
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    params.load_mode = LLAMA_LOAD_MODE_MMAP;
    g_model = llama_model_load_from_file(chars, params);
    env->ReleaseStringUTFChars(path, chars);
    return g_model != nullptr ? JNI_TRUE : JNI_FALSE;
}

#include "orez_generation.h"

static native_generation_result generate_request(JNIEnv * env, jstring prompt, jint max_tokens, jlong request_id) {
    auto request = find_request(request_id);
    native_generation_result result;
    result.token_limit = max_tokens < 1 ? 1 : (max_tokens > 512 ? 512 : max_tokens);
    if (!request) { result.termination = "INVALID_REQUEST"; return result; }
    if (request->cancelled.load()) { result.termination = "CANCELLED"; return result; }
    const auto waiting = generation_clock::now();
    std::lock_guard<std::mutex> lock(g_mutex);
    const auto wait_us = generation_elapsed_us(waiting);
    if (g_model == nullptr || request->cancelled.load()) {
        result.termination = request->cancelled.load() ? "CANCELLED" : "UNAVAILABLE";
        result.lock_wait_us = wait_us;
        return result;
    }
    const std::string input = java_utf8(env, prompt);
    if (env->ExceptionCheck()) { result.termination = "INPUT_ENCODING_FAILURE"; return result; }
    result = generate_locked(input, max_tokens, request);
    result.lock_wait_us = wait_us;
    return result;
}

// llama emits standard UTF-8; NewStringUTF expects modified UTF-8 and can crash on emoji.
static jstring generation_java_text(JNIEnv * env, const std::string & text) {
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(text.size()));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(text.size()), reinterpret_cast<const jbyte *>(text.data()));
    jclass string_class = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(string_class, constructor, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_class);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_mangalens_oreznative_OrezNativeEngine_nativeGenerate(
        JNIEnv * env, jobject, jstring prompt, jint max_tokens, jlong request_id) {
    const auto result = generate_request(env, prompt, max_tokens, request_id);
    if (env->ExceptionCheck()) return nullptr;
    // Legacy chat still receives non-cancelled partial text at its token/decode boundary.
    return generation_java_text(env, result.text);
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_mangalens_oreznative_OrezNativeEngine_nativeGenerateWithReceipt(
        JNIEnv * env, jobject, jstring prompt, jint max_tokens, jlong request_id) {
    const auto result = generate_request(env, prompt, max_tokens, request_id);
    if (env->ExceptionCheck()) return nullptr;
    jstring text = generation_java_text(env, result.text);
    if (text == nullptr || env->ExceptionCheck()) return nullptr;
    jclass result_class = env->FindClass("com/mangalens/oreznative/NativeGenerationResult");
    if (result_class == nullptr) { env->DeleteLocalRef(text); return nullptr; }
    jmethodID constructor = env->GetMethodID(result_class, "<init>", "(Ljava/lang/String;Ljava/lang/String;IIIJJJJ)V");
    if (constructor == nullptr) { env->DeleteLocalRef(text); env->DeleteLocalRef(result_class); return nullptr; }
    jstring termination = env->NewStringUTF(result.termination);
    jobject receipt = env->NewObject(result_class, constructor, text, termination,
        static_cast<jint>(result.prompt_tokens), static_cast<jint>(result.generated_tokens), static_cast<jint>(result.token_limit),
        static_cast<jlong>(result.lock_wait_us), static_cast<jlong>(result.setup_us),
        static_cast<jlong>(result.prefill_us), static_cast<jlong>(result.decode_us));
    env->DeleteLocalRef(termination);
    env->DeleteLocalRef(text);
    env->DeleteLocalRef(result_class);
    return receipt;
}

extern "C" JNIEXPORT void JNICALL
Java_com_mangalens_oreznative_OrezNativeEngine_nativeUnload(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
        llama_backend_free();
    }
}
