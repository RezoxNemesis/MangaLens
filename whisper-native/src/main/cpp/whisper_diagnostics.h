#pragma once

#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <ggml-cpu.h>
#include <sched.h>
#include <time.h>
#include <unistd.h>
#if defined(__ANDROID__)
#include <android/log.h>
#endif
#if defined(__x86_64__) || defined(__i386__)
#include <cpuid.h>
#endif

// Fixed numeric diagnostics only: no paths, audio, text, headers or handle addresses.
namespace mangalens_whisper_diagnostics {
inline std::atomic<uint64_t> next_call{1};
inline int64_t wall_ms() noexcept {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
inline int64_t process_cpu_ms() noexcept {
    timespec value{};
    return clock_gettime(CLOCK_PROCESS_CPUTIME_ID, &value) == 0
        ? value.tv_sec * 1000LL + value.tv_nsec / 1000000LL : -1;
}
inline void emit(uint64_t call, const char * phase, const char * format = "", ...) noexcept {
    char fields[512]{};
    va_list args;
    va_start(args, format);
    std::vsnprintf(fields, sizeof(fields), format, args);
    va_end(args);
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_INFO, "MLWhisper", "call=%llu phase=%s %s",
        static_cast<unsigned long long>(call), phase, fields);
#else
    std::fprintf(stderr, "MLWhisper call=%llu phase=%s %s\n",
        static_cast<unsigned long long>(call), phase, fields);
#endif
}

inline void cpu_features(uint64_t call) noexcept {
    int affinity = -1;
    cpu_set_t allowed{};
    if (sched_getaffinity(0, sizeof(allowed), &allowed) == 0) affinity = CPU_COUNT(&allowed);
    // Query ggml itself, which may have different compiler flags from this JNI unit.
    // In this pinned build these getters report compiled backend features, not CPUID.
    int hardware_sse42 = -1, hardware_avx = -1, hardware_avx2 = -1, hardware_fma = -1, hardware_f16c = -1, os_avx_state = -1;
#if defined(__x86_64__) || defined(__i386__)
    unsigned a = 0, b = 0, c = 0, d = 0;
    if (__get_cpuid_count(1, 0, &a, &b, &c, &d)) {
        hardware_sse42 = (c >> 20) & 1;
        hardware_avx = (c >> 28) & 1;
        hardware_fma = (c >> 12) & 1;
        hardware_f16c = (c >> 29) & 1;
        os_avx_state = 0;
        // XGETBV is legal only after CPUID says the OS enabled XSAVE.
        if ((c & (1U << 27)) != 0) {
            unsigned xcr_low = 0, xcr_high = 0;
            __asm__ volatile("xgetbv" : "=a"(xcr_low), "=d"(xcr_high) : "c"(0));
            os_avx_state = (xcr_low & 6U) == 6U;
        }
    }
    if (__get_cpuid_count(7, 0, &a, &b, &c, &d)) hardware_avx2 = (b >> 5) & 1;
#endif
    emit(call, "cpu_features", "online_cores=%ld affinity_cores=%d backend_sse3=%d backend_avx=%d backend_avx2=%d backend_fma=%d backend_f16c=%d backend_neon=%d backend_fp16_vector=%d hardware_sse42=%d hardware_avx=%d hardware_avx2=%d hardware_fma=%d hardware_f16c=%d os_avx_state=%d",
        sysconf(_SC_NPROCESSORS_ONLN), affinity, ggml_cpu_has_sse3(), ggml_cpu_has_avx(), ggml_cpu_has_avx2(), ggml_cpu_has_fma(), ggml_cpu_has_f16c(), ggml_cpu_has_neon(), ggml_cpu_has_fp16_va(),
        hardware_sse42, hardware_avx, hardware_avx2, hardware_fma, hardware_f16c, os_avx_state);
}

struct Trace {
    const uint64_t call = next_call.fetch_add(1);
    const int64_t started = wall_ms();
    const int64_t cpu_started = process_cpu_ms();
    std::atomic<int> encoder_passes{0};
    std::atomic<int> logits_steps{0};
    std::atomic<bool> transcription_started{false};
    int64_t elapsed() const noexcept { return wall_ms() - started; }
    int64_t process_elapsed() const noexcept {
        const auto now = process_cpu_ms();
        return cpu_started < 0 || now < 0 ? -1 : now - cpu_started;
    }
    void encoder() noexcept {
        emit(call, "encoder_begin", "wall_ms=%lld process_cpu_ms=%lld pass=%d", static_cast<long long>(elapsed()),
            static_cast<long long>(process_elapsed()), encoder_passes.fetch_add(1) + 1);
    }
    void logits() noexcept {
        if (logits_steps.fetch_add(1) == 0)
            emit(call, "first_logits", "wall_ms=%lld process_cpu_ms=%lld", static_cast<long long>(elapsed()),
                static_cast<long long>(process_elapsed()));
    }
    void progress() noexcept {
        if (!transcription_started.exchange(true))
            emit(call, "transcription_begin", "wall_ms=%lld process_cpu_ms=%lld", static_cast<long long>(elapsed()),
                static_cast<long long>(process_elapsed()));
    }
};
}
