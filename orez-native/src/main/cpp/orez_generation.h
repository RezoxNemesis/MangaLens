#pragma once

#include <chrono>
#include <cstdint>

// Included after generation_request and abort_generation: both JNI return paths use this same inference.
struct native_generation_result {
    std::string text;
    const char * termination = "UNAVAILABLE";
    int prompt_tokens = 0;
    int generated_tokens = 0;
    int token_limit = 0;
    int64_t lock_wait_us = 0;
    int64_t setup_us = 0;
    int64_t prefill_us = 0;
    int64_t decode_us = 0;
};

using generation_clock = std::chrono::steady_clock;
static int64_t generation_elapsed_us(generation_clock::time_point start) {
    return std::chrono::duration_cast<std::chrono::microseconds>(generation_clock::now() - start).count();
}

// The caller retains the model mutex. Decode timing includes sampler/context cleanup.
static native_generation_result generate_locked(const std::string & input, int max_tokens,
        const std::shared_ptr<generation_request> & request) {
    native_generation_result result;
    result.token_limit = max_tokens < 1 ? 1 : (max_tokens > 512 ? 512 : max_tokens);
    if (!request) { result.termination = "INVALID_REQUEST"; return result; }
    if (request->cancelled.load()) { result.termination = "CANCELLED"; return result; }
    if (g_model == nullptr) return result;
    const auto setup_start = generation_clock::now();
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    const int n_prompt = -llama_tokenize(vocab, input.c_str(), input.size(), nullptr, 0, true, true);
    if (n_prompt <= 0 || n_prompt > 4096) {
        result.termination = "INVALID_PROMPT";
        result.setup_us = generation_elapsed_us(setup_start);
        return result;
    }
    result.prompt_tokens = n_prompt;
    std::vector<llama_token> tokens(n_prompt);
    if (llama_tokenize(vocab, input.c_str(), input.size(), tokens.data(), tokens.size(), true, true) < 0) {
        result.termination = "TOKENIZATION_FAILURE";
        result.setup_us = generation_elapsed_us(setup_start);
        return result;
    }
    llama_context_params cp = llama_context_default_params();
    const uint32_t requested_ctx = static_cast<uint32_t>(n_prompt + result.token_limit + 64);
    cp.n_ctx = requested_ctx > 8192 ? 8192 : requested_ctx;
    cp.n_batch = 256;
    cp.n_ubatch = 128;
    cp.n_threads = std::max(1u, std::min(2u, std::thread::hardware_concurrency() / 2));
    cp.n_threads_batch = cp.n_threads;
    cp.abort_callback = abort_generation;
    cp.abort_callback_data = request.get();
    llama_context * ctx = llama_init_from_model(g_model, cp);
    if (ctx == nullptr) {
        result.termination = request->cancelled.load() ? "CANCELLED" : "CONTEXT_FAILURE";
        result.setup_us = generation_elapsed_us(setup_start);
        return result;
    }
    auto sp = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    result.setup_us = generation_elapsed_us(setup_start);
    const auto prefill_start = generation_clock::now();
    llama_batch batch;
    for (int offset = 0; offset < n_prompt; offset += 256) {
        batch = llama_batch_get_one(tokens.data() + offset, std::min(256, n_prompt - offset));
        if (request->cancelled.load() || llama_decode(ctx, batch) != 0) {
            llama_sampler_free(sampler);
            llama_free(ctx);
            request->running.store(false);
            result.termination = request->cancelled.load() ? "CANCELLED" : "PREFILL_FAILURE";
            result.prefill_us = generation_elapsed_us(prefill_start);
            return result;
        }
    }
    result.prefill_us = generation_elapsed_us(prefill_start);
    const auto decode_start = generation_clock::now();
    result.text.reserve(static_cast<size_t>(result.token_limit) * 4);
    result.termination = "TOKEN_LIMIT";
    bool piece_failure = false;
    for (int i = 0; i < result.token_limit; ++i) {
        if (request->cancelled.load()) { result.termination = "CANCELLED"; break; }
        llama_token id = llama_sampler_sample(sampler, ctx, -1);
        if (llama_vocab_is_eog(vocab, id)) { result.termination = "EOG"; break; }
        char piece[512];
        int n = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, true);
        if (n > 0) result.text.append(piece, n);
        else if (n < 0) piece_failure = true;
        ++result.generated_tokens;
        batch = llama_batch_get_one(&id, 1);
        if (llama_decode(ctx, batch) != 0) { result.termination = "DECODE_FAILURE"; break; }
    }
    llama_sampler_free(sampler);
    llama_free(ctx);
    request->running.store(false);
    result.decode_us = generation_elapsed_us(decode_start);
    // Preserve legacy text behavior, but never label a failed piece conversion as complete.
    if (piece_failure && std::string(result.termination) == "EOG") result.termination = "TOKEN_PIECE_FAILURE";
    if (request->cancelled.load()) {
        result.termination = "CANCELLED";
        result.text.clear();
    }
    // Preserve the existing UTF-8 tail rule for both APIs.
    if (!result.text.empty()) {
        size_t lead = result.text.size() - 1;
        while (lead > 0 && (static_cast<unsigned char>(result.text[lead]) & 0xc0) == 0x80) --lead;
        const auto byte = static_cast<unsigned char>(result.text[lead]);
        const size_t expected = byte < 0x80 ? 1 : ((byte & 0xe0) == 0xc0 ? 2 : ((byte & 0xf0) == 0xe0 ? 3 : ((byte & 0xf8) == 0xf0 ? 4 : 1)));
        if (result.text.size() - lead < expected) result.text.resize(lead);
    }
    return result;
}
