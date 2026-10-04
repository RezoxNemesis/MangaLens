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

extern "C" JNIEXPORT jstring JNICALL
Java_com_mangalens_oreznative_OrezNativeEngine_nativeGenerate(
        JNIEnv * env, jobject, jstring prompt, jint maxTokens, jlong requestId) {
    auto request = find_request(requestId);
    if (!request || request->cancelled.load()) return env->NewStringUTF("");
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_model == nullptr || request->cancelled.load()) return env->NewStringUTF("");
    std::string input = java_utf8(env, prompt);
    if (env->ExceptionCheck()) return nullptr;

    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int nPrompt = -llama_tokenize(vocab, input.c_str(), input.size(), nullptr, 0, true, true);
    if (nPrompt <= 0 || nPrompt > 4096) return env->NewStringUTF("");

    std::vector<llama_token> tokens(nPrompt);
    if (llama_tokenize(vocab, input.c_str(), input.size(), tokens.data(), tokens.size(), true, true) < 0) {
        return env->NewStringUTF("");
    }

    llama_context_params cp = llama_context_default_params();
    const int safeTokens = maxTokens < 1 ? 1 : (maxTokens > 512 ? 512 : maxTokens);
    const uint32_t requestedCtx = static_cast<uint32_t>(nPrompt + safeTokens + 64);
    cp.n_ctx = requestedCtx > 8192 ? 8192 : requestedCtx;
    // Bound prefill scratch allocations and leave CPU headroom for the UI/player.
    cp.n_batch = 256;
    cp.n_ubatch = 128;
    cp.n_threads = std::max(1u, std::min(4u, std::thread::hardware_concurrency() / 2));
    cp.n_threads_batch = cp.n_threads;
    cp.abort_callback = abort_generation;
    cp.abort_callback_data = request.get();
    llama_context * ctx = llama_init_from_model(g_model, cp);
    if (ctx == nullptr) return env->NewStringUTF("");

    auto sp = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    llama_batch batch;
    for (int offset = 0; offset < nPrompt; offset += 256) {
        batch = llama_batch_get_one(tokens.data() + offset, std::min(256, nPrompt - offset));
        if (request->cancelled.load() || llama_decode(ctx, batch) != 0) {
            llama_sampler_free(sampler);
            llama_free(ctx);
            request->running.store(false);
            return env->NewStringUTF("");
        }
    }

    std::string output;
    output.reserve(static_cast<size_t>(safeTokens) * 4);
    for (int i = 0; i < safeTokens; ++i) {
        if (request->cancelled.load()) break;
        llama_token id = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) break;
        char piece[512];
        int n = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
        if (n > 0) output.append(piece, n);
        batch = llama_batch_get_one(&id, 1);
        if (llama_decode(ctx, batch) != 0) break;
    }

    llama_sampler_free(sampler);
    llama_free(ctx);
    request->running.store(false);
    // Partial output from a cancelled request must never become an answer.
    if (request->cancelled.load()) return env->NewStringUTF("");
    // A token budget can stop inside a UTF-8 character split across model tokens.
    if (!output.empty()) {
        size_t lead = output.size() - 1;
        while (lead > 0 && (static_cast<unsigned char>(output[lead]) & 0xc0) == 0x80) --lead;
        const auto byte = static_cast<unsigned char>(output[lead]);
        const size_t expected = byte < 0x80 ? 1 : ((byte & 0xe0) == 0xc0 ? 2 : ((byte & 0xf0) == 0xe0 ? 3 : ((byte & 0xf8) == 0xf0 ? 4 : 1)));
        if (output.size() - lead < expected) output.resize(lead);
    }
    // llama emits standard UTF-8; NewStringUTF expects modified UTF-8 and can crash on emoji.
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(output.size()));
    if (bytes == nullptr) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(output.size()), reinterpret_cast<const jbyte *>(output.data()));
    jclass stringClass = env->FindClass("java/lang/String");
    jmethodID constructor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    jstring charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(stringClass, constructor, bytes, charset));
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(stringClass);
    return result;
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
