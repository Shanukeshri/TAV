#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>

#include "llama.h"

#define TAG "TAV_LLAMA"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static struct llama_model   * g_model   = nullptr;
static struct llama_context * g_ctx     = nullptr;
static struct llama_sampler * g_sampler = nullptr;

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_teachablevoice_model_LlamaCppBackend_loadNative(JNIEnv *env, jobject thiz, jstring model_path) {
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model from: %s", path);

    // Model params
    struct llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU only on Android

    g_model = llama_model_load_from_file(path, mparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }
    LOGI("Model loaded successfully");

    // Context params
    struct llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx   = 0; // Use model's max context size
    cparams.n_batch = 1024;

    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
    LOGI("Context created");

    // Sampler: greedy (deterministic, fast)
    struct llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(g_sampler, llama_sampler_init_greedy());
    LOGI("Sampler initialized");

    return JNI_TRUE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_teachablevoice_model_LlamaCppBackend_generateNative(JNIEnv *env, jobject thiz, jstring prompt) {
    if (!g_model || !g_ctx || !g_sampler) {
        return env->NewStringUTF("{\"action\": \"ASK\", \"message\": \"Model not initialized\"}");
    }

    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    LOGI("Generating for prompt length: %d", (int)strlen(prompt_str));

    const struct llama_vocab * vocab = llama_model_get_vocab(g_model);

    // Tokenize the prompt
    int n_prompt_max = strlen(prompt_str) + 128;
    std::vector<llama_token> tokens(n_prompt_max);
    int n_tokens = llama_tokenize(vocab, prompt_str, strlen(prompt_str),
                                  tokens.data(), n_prompt_max, true, true);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    if (n_tokens < 0) {
        LOGE("Tokenization failed: %d", n_tokens);
        return env->NewStringUTF("{\"action\": \"ASK\", \"message\": \"Tokenization failed\"}");
    }
    tokens.resize(n_tokens);
    LOGI("Tokenized: %d tokens", n_tokens);

    // Clear KV cache for a fresh generation
    llama_memory_clear(llama_get_memory(g_ctx), true);

    // Decode the prompt in chunks
    uint32_t batch_size = 1024;
    for (int i = 0; i < n_tokens; i += batch_size) {
        int n_eval = n_tokens - i;
        if (n_eval > batch_size) n_eval = batch_size;
        
        struct llama_batch batch = llama_batch_get_one(&tokens[i], n_eval);
        if (llama_decode(g_ctx, batch) != 0) {
            LOGE("llama_decode failed at chunk %d", i);
            return env->NewStringUTF("{\"action\": \"ASK\", \"message\": \"Decode failed\"}");
        }
    }

    // Generate tokens
    std::string result;
    int max_new_tokens = 256;

    for (int i = 0; i < max_new_tokens; i++) {
        llama_token new_token = llama_sampler_sample(g_sampler, g_ctx, -1);

        // Check for end of generation
        if (llama_vocab_is_eog(vocab, new_token)) {
            LOGI("EOS reached at token %d", i);
            break;
        }

        // Convert token to text
        char buf[256];
        int n = llama_token_to_piece(vocab, new_token, buf, sizeof(buf), 0, true);
        if (n > 0) {
            result.append(buf, n);
        }

        // Decode the new token for the next iteration
        struct llama_batch next_batch = llama_batch_get_one(&new_token, 1);
        if (llama_decode(g_ctx, next_batch) != 0) {
            LOGE("llama_decode failed at token %d", i);
            break;
        }

        // Stop early if we see a closing brace (JSON complete)
        if (result.find('}') != std::string::npos) {
            LOGI("JSON closing brace found at token %d", i);
            break;
        }
    }

    LOGI("Generated %d chars: %.100s", (int)result.size(), result.c_str());
    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT void JNICALL
Java_com_example_teachablevoice_model_LlamaCppBackend_unloadNative(JNIEnv *env, jobject thiz) {
    LOGI("Unloading model");
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);     g_model   = nullptr; }
    LOGI("Model unloaded");
}
