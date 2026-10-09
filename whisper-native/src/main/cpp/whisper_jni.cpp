#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <string>
#include <vector>
#include <chrono>
#include <algorithm>
#include <cmath>
#include <cstring>
struct Session { whisper_context * ctx; std::atomic<bool> cancelled{false}; };
static void fail(JNIEnv * env, const char * message) { env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message); }
extern "C" JNIEXPORT jlong JNICALL Java_com_mangalens_whisper_WhisperNative_load(JNIEnv *env, jobject, jstring path) {
    const char * value = env->GetStringUTFChars(path, nullptr);
    auto params = whisper_context_default_params(); params.use_gpu = false;
    auto * ctx = whisper_init_from_file_with_params(value, params);
    env->ReleaseStringUTFChars(path, value);
    if (!ctx) { fail(env, "Cannot load Whisper model. Import a supported multilingual GGML Whisper model."); return 0; }
    if (!whisper_is_multilingual(ctx)) { whisper_free(ctx); fail(env, "English-only models cannot translate other languages. Use tiny.bin, base.bin or small.bin."); return 0; }
    return reinterpret_cast<jlong>(new Session{ctx});
}
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_mangalens_whisper_WhisperNative_infer(JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jstring lang, jboolean translateToEnglish, jint threads) {
    auto * session = reinterpret_cast<Session *>(handle);
    session->cancelled.store(false);
    const char * language = env->GetStringUTFChars(lang, nullptr);
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = std::max(1, std::min(4, static_cast<int>(threads)));
    params.translate = translateToEnglish == JNI_TRUE;
    params.language = language;
    // `language="auto"` already detects the language during normal transcription.
    // detect_language=true is the upstream detect-only mode and returns no captions.
    params.detect_language = false;
    // whisper.cpp normally keeps the model's 1500-frame (~30 s) audio context even
    // for a 3-8 second live-caption window. Scale the encoder context to the actual
    // clip length so phone CPUs are not doing near-30-second work for every tiny window.
    // Keep a conservative floor to protect recognition quality on very short speech.
    const auto count = env->GetArrayLength(pcm);
    const float seconds = static_cast<float>(count) / 16000.0f;
    params.audio_ctx = std::clamp(
        static_cast<int>(std::lround((seconds / 30.0f) * 1500.0f + 128.0f)),
        384,
        1500
    );
    params.print_progress = false; params.print_realtime = false; params.print_timestamps = false;
    params.no_speech_thold = 0.75f; params.logprob_thold = -1.0f; params.temperature_inc = 0.0f;
    params.no_context = true; params.single_segment = false; params.suppress_blank = true;
    params.suppress_nst = true; params.split_on_word = true; params.max_len = 84;
    params.abort_callback = [](void * data) { return static_cast<Session *>(data)->cancelled.load(); };
    params.abort_callback_user_data = session;
    std::vector<float> samples(count); env->GetFloatArrayRegion(pcm, 0, count, samples.data());
    int result = whisper_full(session->ctx, params, samples.data(), count);
    env->ReleaseStringUTFChars(lang, language);
    if (session->cancelled.load()) return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    if (result != 0) { fail(env, "Speech inference failed."); return nullptr; }
    int n = whisper_full_n_segments(session->ctx);
    std::vector<std::string> lines;
    for (int i = 0; i < n; ++i) {
        if (whisper_full_get_segment_no_speech_prob(session->ctx, i) > 0.78f) continue;
        lines.push_back(std::to_string(whisper_full_get_segment_t0(session->ctx, i) * 10) + "\t" +
            std::to_string(whisper_full_get_segment_t1(session->ctx, i) * 10) + "\t" + whisper_full_get_segment_text(session->ctx, i));
    }
    auto output = env->NewObjectArray(lines.size(), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < lines.size(); ++i) {
        auto line = env->NewStringUTF(lines[i].c_str()); env->SetObjectArrayElement(output, i, line); env->DeleteLocalRef(line);
    }
    return output;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_mangalens_whisper_WhisperNative_detectedLanguage(JNIEnv *env, jobject, jlong handle) {
    auto * session = reinterpret_cast<Session *>(handle);
    if (!session || !session->ctx) return env->NewStringUTF("auto");
    const int id = whisper_full_lang_id(session->ctx);
    const char * value = id >= 0 ? whisper_lang_str(id) : "auto";
    return env->NewStringUTF(value ? value : "auto");
}
extern "C" JNIEXPORT void JNICALL Java_com_mangalens_whisper_WhisperNative_cancel(JNIEnv *, jobject, jlong h) { reinterpret_cast<Session *>(h)->cancelled.store(true); }
extern "C" JNIEXPORT void JNICALL Java_com_mangalens_whisper_WhisperNative_free(JNIEnv *, jobject, jlong h) { auto * s = reinterpret_cast<Session *>(h); whisper_free(s->ctx); delete s; }
