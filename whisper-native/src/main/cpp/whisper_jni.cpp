#include <jni.h>
#include <whisper.h>
#include <atomic>
#include <string>
#include <vector>
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
extern "C" JNIEXPORT jobjectArray JNICALL Java_com_mangalens_whisper_WhisperNative_infer(JNIEnv *env, jobject, jlong handle, jfloatArray pcm, jstring lang, jint threads) {
    auto * session = reinterpret_cast<Session *>(handle);
    session->cancelled.store(false);
    const char * language = env->GetStringUTFChars(lang, nullptr);
    auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = threads; params.translate = true; params.language = language;
    params.print_progress = false; params.print_realtime = false; params.print_timestamps = false;
    params.no_context = true; params.single_segment = false; params.suppress_blank = true;
    params.abort_callback = [](void * data) { return static_cast<Session *>(data)->cancelled.load(); };
    params.abort_callback_user_data = session;
    const auto count = env->GetArrayLength(pcm);
    std::vector<float> samples(count); env->GetFloatArrayRegion(pcm, 0, count, samples.data());
    int result = whisper_full(session->ctx, params, samples.data(), count);
    env->ReleaseStringUTFChars(lang, language);
    if (session->cancelled.load()) return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    if (result != 0) { fail(env, "Speech inference failed."); return nullptr; }
    int n = whisper_full_n_segments(session->ctx);
    auto output = env->NewObjectArray(n, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < n; ++i) {
        std::string text = std::to_string(whisper_full_get_segment_t0(session->ctx, i) * 10) + "\t" +
            std::to_string(whisper_full_get_segment_t1(session->ctx, i) * 10) + "\t" + whisper_full_get_segment_text(session->ctx, i);
        auto line = env->NewStringUTF(text.c_str()); env->SetObjectArrayElement(output, i, line); env->DeleteLocalRef(line);
    }
    return output;
}
extern "C" JNIEXPORT void JNICALL Java_com_mangalens_whisper_WhisperNative_cancel(JNIEnv *, jobject, jlong h) { reinterpret_cast<Session *>(h)->cancelled.store(true); }
extern "C" JNIEXPORT void JNICALL Java_com_mangalens_whisper_WhisperNative_free(JNIEnv *, jobject, jlong h) { auto * s = reinterpret_cast<Session *>(h); whisper_free(s->ctx); delete s; }
