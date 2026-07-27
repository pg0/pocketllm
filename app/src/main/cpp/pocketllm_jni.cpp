// JNI bridge between PocketLLM's Kotlin layer and llama.cpp + libmtmd.
//
// Threading contract: every nativeXxx call for a given handle must come from a
// single thread (the Kotlin side funnels them through one dispatcher). The only
// exception is nativeCancel, which is safe to call from anywhere.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <string>
#include <utility>
#include <vector>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "PocketLLM-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct pll_session {
    llama_model        * model = nullptr;
    llama_context      * lctx  = nullptr;
    const llama_vocab  * vocab = nullptr;
    mtmd_context       * mctx  = nullptr;

    std::string tmpl;   // chat template pulled from the GGUF metadata
    std::string arch;   // general.architecture, for the UI to report
    std::string tmpl_kind;  // which renderer render_chat() will use

    // Full conversation, kept so the chat template can be re-rendered. Only the
    // newly appended suffix is ever tokenized -- the KV cache holds the rest.
    std::vector<std::pair<std::string, std::string>> messages;
    // The rendered transcript already sitting in the KV cache. Kept as text, not
    // just a length, so the next render can be checked against it: incremental
    // evaluation is only valid while each render extends the previous one, and
    // not every chat template is append-only.
    std::string prev_render;
    llama_pos   n_past = 0;

    int32_t n_batch = 512;

    // Snapshot taken before each turn is appended, so a turn can be undone.
    // Used when an answer comes back as "I don't know" and the app wants to
    // retry the same question with web context instead of leaving a wrong
    // answer in the history for the model to stay consistent with.
    size_t    undo_messages = 0;
    size_t    undo_render   = 0;
    llama_pos undo_n_past   = 0;
    bool      undo_valid    = false;

    // Timings of the last turn, for the stats line in the UI.
    int64_t last_prompt_ms  = 0;
    int64_t last_decode_ms  = 0;
    int     last_decoded    = 0;
    int     last_media_toks = 0;

    std::atomic<bool> cancel{false};
};

// ---------------------------------------------------------------- utilities

// Wall-clock milliseconds. Used only for the timing breakdown logged after each
// turn -- "voice is slow" needs a number saying *which* stage is slow before it
// is worth swapping the audio path for something else.
int64_t now_ms() {
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

std::string jstr(JNIEnv * env, jstring s) {
    if (s == nullptr) return {};
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

// Length in bytes of the UTF-8 sequence starting with `c`, or 0 if `c` is a
// continuation byte.
int utf8_seq_len(unsigned char c) {
    if ((c & 0x80) == 0x00) return 1;
    if ((c & 0xE0) == 0xC0) return 2;
    if ((c & 0xF0) == 0xE0) return 3;
    if ((c & 0xF8) == 0xF0) return 4;
    return 0;
}

// How many trailing bytes of `s` form an incomplete UTF-8 sequence. Token
// pieces routinely split multi-byte characters, and NewStringUTF on a partial
// sequence produces mojibake, so those bytes are held back until completed.
size_t incomplete_utf8_tail(const std::string & s) {
    const size_t n = s.size();
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        const int len = utf8_seq_len(static_cast<unsigned char>(s[n - back]));
        if (len == 0) continue;               // continuation byte, keep walking
        return (len > static_cast<int>(back)) ? back : 0;
    }
    return 0;
}

std::string token_to_piece(const llama_vocab * vocab, llama_token tok) {
    char buf[256];
    const int n = llama_token_to_piece(vocab, tok, buf, sizeof(buf), 0, /*special=*/false);
    if (n < 0) {
        std::vector<char> big(static_cast<size_t>(-n));
        const int n2 = llama_token_to_piece(vocab, tok, big.data(), static_cast<int32_t>(big.size()), 0, false);
        return (n2 > 0) ? std::string(big.data(), static_cast<size_t>(n2)) : std::string();
    }
    return std::string(buf, static_cast<size_t>(n));
}

std::string trim(const std::string & s) {
    const size_t b = s.find_first_not_of(" \t\n\r");
    if (b == std::string::npos) return {};
    const size_t e = s.find_last_not_of(" \t\n\r");
    return s.substr(b, e - b + 1);
}

/**
 * Renders the conversation in Gemma 4's chat format.
 *
 * This does NOT go through llama_chat_apply_template. That function does not
 * parse Jinja -- it pattern-matches the template string against a fixed list of
 * known formats. Gemma 4's template is an 18 KB Jinja document with tool-calling
 * macros and uses `<|turn>role ... <turn|>`, which matches nothing in that list,
 * so the call returns -1. Worse, the older "gemma" entry it *might* have been
 * forced onto emits `<start_of_turn>`, which Gemma 4 does not use at all -- that
 * would have produced a silently wrong prompt instead of an error.
 *
 * Format verified against tokenizer.chat_template in the GGUF. All markers are
 * real single vocab tokens: `<|turn>` = 105, `<turn|>` = 106 (also the EOT
 * token, which is why end-of-generation detection works), `<|image|>` = 258880,
 * `<|audio|>` = 258881.
 *
 * BOS is deliberately not emitted here; the tokenizer adds it via add_special on
 * the first segment, and emitting it too would double it.
 */
void render_gemma4(pll_session * s, bool add_assistant, std::string & out) {
    for (const auto & m : s->messages) {
        // Gemma 4 calls the assistant "model". It does have a real system role,
        // unlike Gemma 3 where system had to be folded into the first user turn.
        const std::string role = (m.first == "assistant") ? "model" : m.first;
        out += "<|turn>";
        out += role;
        out += "\n";
        out += trim(m.second);
        out += "<turn|>\n";
    }
    if (add_assistant) {
        out += "<|turn>model\n";
    }
}

/**
 * Decides how this model's prompts get built, once, at load time.
 *
 * Three outcomes, in descending order of how much we trust them:
 *
 *   "gemma4" - the hand-written renderer above. Chosen on the marker rather
 *              than the architecture name so a re-quantised or renamed Gemma 4
 *              still lands here.
 *   "gguf"   - the model's own template, run through llama_chat_apply_template.
 *              Only chosen after a probe render actually succeeds, because that
 *              function returns -1 for anything outside its fixed list and we
 *              would rather find that out now than mid-conversation.
 *   "chatml" - last resort for a GGUF with no template, or one llama.cpp does
 *              not recognise. ChatML is what most instruction tunes are trained
 *              on these days, so it is a decent guess, but it *is* a guess and
 *              the UI says so.
 */
std::string pick_template(const std::string & tmpl) {
    if (tmpl.find("<|turn>") != std::string::npos) return "gemma4";
    if (!tmpl.empty()) {
        const llama_chat_message probe{"user", "hi"};
        char buf[512];
        if (llama_chat_apply_template(tmpl.c_str(), &probe, 1, true, buf, sizeof(buf)) >= 0) {
            return "gguf";
        }
        LOGI("chat template not recognised by llama.cpp, falling back to ChatML");
    }
    return "chatml";
}

/**
 * Renders the whole conversation as a prompt string.
 *
 * @return the rendered length, or -1 if the template could not be applied.
 */
int32_t render_chat(pll_session * s, bool add_assistant, std::string & out) {
    out.clear();

    if (s->tmpl_kind == "gemma4") {
        render_gemma4(s, add_assistant, out);
        return static_cast<int32_t>(out.size());
    }

    // llama_chat_apply_template takes borrowed char pointers, so the trimmed
    // copies have to outlive the message array it reads from.
    std::vector<std::string> bodies;
    bodies.reserve(s->messages.size());
    for (const auto & m : s->messages) bodies.push_back(trim(m.second));

    std::vector<llama_chat_message> msgs;
    msgs.reserve(s->messages.size());
    size_t want = 256;
    for (size_t i = 0; i < s->messages.size(); ++i) {
        msgs.push_back({s->messages[i].first.c_str(), bodies[i].c_str()});
        want += bodies[i].size() + s->messages[i].first.size() + 64;
    }

    const char * tmpl = (s->tmpl_kind == "gguf") ? s->tmpl.c_str() : "chatml";

    std::vector<char> buf(want);
    int32_t n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant,
                                          buf.data(), static_cast<int32_t>(buf.size()));
    // The documented way to size the buffer: ask, then ask again with room.
    if (n > static_cast<int32_t>(buf.size())) {
        buf.resize(static_cast<size_t>(n));
        n = llama_chat_apply_template(tmpl, msgs.data(), msgs.size(), add_assistant,
                                      buf.data(), static_cast<int32_t>(buf.size()));
    }
    if (n < 0) {
        LOGE("llama_chat_apply_template failed for kind=%s", s->tmpl_kind.c_str());
        return -1;
    }
    out.assign(buf.data(), static_cast<size_t>(n));
    return n;
}

// Decode a plain token run in n_batch-sized chunks, advancing n_past.
bool eval_tokens(pll_session * s, const std::vector<llama_token> & toks, bool logits_last) {
    const size_t total = toks.size();
    for (size_t off = 0; off < total; off += static_cast<size_t>(s->n_batch)) {
        if (s->cancel.load()) return false;

        const int32_t n = static_cast<int32_t>(std::min<size_t>(static_cast<size_t>(s->n_batch), total - off));
        const bool    is_last_chunk = (off + static_cast<size_t>(n) >= total);

        llama_batch b = llama_batch_init(n, 0, 1);
        for (int32_t i = 0; i < n; ++i) {
            b.token[i]     = toks[off + static_cast<size_t>(i)];
            b.pos[i]       = s->n_past + i;
            b.n_seq_id[i]  = 1;
            b.seq_id[i][0] = 0;
            b.logits[i]    = 0;
        }
        b.n_tokens = n;
        if (logits_last && is_last_chunk) b.logits[n - 1] = 1;

        const int rc = llama_decode(s->lctx, b);
        llama_batch_free(b);
        if (rc != 0) {
            LOGE("llama_decode failed: %d", rc);
            return false;
        }
        s->n_past += n;
    }
    return true;
}

bool abort_cb(void * data) {
    auto * s = static_cast<pll_session *>(data);
    return s != nullptr && s->cancel.load();
}

void llama_log_to_logcat(ggml_log_level level, const char * text, void *) {
    if (text == nullptr) return;
    const int prio = (level == GGML_LOG_LEVEL_ERROR) ? ANDROID_LOG_ERROR
                   : (level == GGML_LOG_LEVEL_WARN)  ? ANDROID_LOG_WARN
                                                     : ANDROID_LOG_DEBUG;
    __android_log_print(prio, "PocketLLM-llama", "%s", text);
}

// Wraps the Java progress callback so llama.cpp can drive the load bar.
struct progress_ctx {
    JNIEnv  * env;
    jobject   cb;
    jmethodID mid;
};

bool progress_trampoline(float p, void * data) {
    auto * pc = static_cast<progress_ctx *>(data);
    if (pc == nullptr || pc->cb == nullptr) return true;
    return pc->env->CallBooleanMethod(pc->cb, pc->mid, static_cast<jfloat>(p)) == JNI_TRUE;
}

} // namespace

extern "C" {

#define JNI_FN(name) Java_com_redcoralstudios_pocketllm_llm_LlamaBridge_##name

JNIEXPORT void JNICALL
JNI_FN(nativeInit)(JNIEnv *, jobject) {
    llama_log_set(llama_log_to_logcat, nullptr);
    mtmd_helper_log_set(llama_log_to_logcat, nullptr);
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
JNI_FN(nativeLoadModel)(JNIEnv * env, jobject, jstring jpath,
                        jint n_ctx, jint n_threads, jint n_gpu_layers,
                        jobject progress_cb) {
    const std::string path = jstr(env, jpath);
    auto * s = new pll_session();

    progress_ctx pc{env, progress_cb, nullptr};
    if (progress_cb != nullptr) {
        jclass cls = env->GetObjectClass(progress_cb);
        pc.mid = env->GetMethodID(cls, "onProgress", "(F)Z");
    }

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = n_gpu_layers;
    if (pc.mid != nullptr) {
        mp.progress_callback           = progress_trampoline;
        mp.progress_callback_user_data = &pc;
    }

    s->model = llama_model_load_from_file(path.c_str(), mp);
    if (s->model == nullptr) {
        LOGE("failed to load model: %s", path.c_str());
        delete s;
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx               = static_cast<uint32_t>(n_ctx);
    cp.n_batch             = 512;
    cp.n_ubatch            = 512;
    cp.n_threads           = n_threads;
    cp.n_threads_batch     = n_threads;
    cp.no_perf             = true;
    cp.abort_callback      = abort_cb;
    cp.abort_callback_data = s;

    s->lctx = llama_init_from_model(s->model, cp);
    if (s->lctx == nullptr) {
        LOGE("failed to create context");
        llama_model_free(s->model);
        delete s;
        return 0;
    }

    s->vocab   = llama_model_get_vocab(s->model);
    s->n_batch = static_cast<int32_t>(llama_n_batch(s->lctx));

    if (const char * t = llama_model_chat_template(s->model, nullptr)) {
        s->tmpl = t;
    }
    s->tmpl_kind = pick_template(s->tmpl);

    char arch_buf[128];
    if (llama_model_meta_val_str(s->model, "general.architecture",
                                 arch_buf, sizeof(arch_buf)) > 0) {
        s->arch = arch_buf;
    }

    LOGI("model loaded: arch=%s template=%s n_ctx=%u n_batch=%d",
         s->arch.empty() ? "?" : s->arch.c_str(), s->tmpl_kind.c_str(),
         llama_n_ctx(s->lctx), s->n_batch);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jboolean JNICALL
JNI_FN(nativeLoadProjector)(JNIEnv * env, jobject, jlong handle, jstring jpath, jboolean use_gpu,
                            jint image_max_tokens) {
    auto * s = reinterpret_cast<pll_session *>(handle);
    if (s == nullptr) return JNI_FALSE;

    const std::string path = jstr(env, jpath);

    if (s->mctx != nullptr) {
        mtmd_free(s->mctx);
        s->mctx = nullptr;
    }

    mtmd_context_params p = mtmd_context_params_default();
    p.use_gpu       = (use_gpu == JNI_TRUE);
    p.print_timings = false;
    p.n_threads     = 4;
    p.media_marker  = mtmd_default_marker();

    // How much of an image the model actually gets to see.
    //
    // clip resizes every image down to image_max_tokens * patch_area pixels,
    // and for Gemma 4 llama.cpp caps that at 280 tokens - a photographed A4
    // page arrives around 0.6 MP, which is where body text stops being
    // legible. Raising it is the only lever that helps; downscaling less on
    // our side does nothing, because clip resizes to this budget regardless.
    //
    // -1 keeps the model's own default.
    if (image_max_tokens > 0) {
        p.image_max_tokens = image_max_tokens;
    }

    s->mctx = mtmd_init_from_file(path.c_str(), s->model, p);
    if (s->mctx == nullptr) {
        LOGE("failed to load projector: %s", path.c_str());
        return JNI_FALSE;
    }
    LOGI("projector loaded: vision=%d audio=%d",
         mtmd_support_vision(s->mctx), mtmd_support_audio(s->mctx));
    return JNI_TRUE;
}

/**
 * {architecture, template kind} of the loaded model.
 *
 * Both are answers to "what did I just download": a GGUF the app has never seen
 * before either renders with its own template or gets a ChatML guess, and the
 * difference is worth showing rather than leaving the user to infer it from
 * strange replies.
 */
JNIEXPORT jobjectArray JNICALL
JNI_FN(nativeModelInfo)(JNIEnv * env, jobject, jlong handle) {
    auto * s = reinterpret_cast<pll_session *>(handle);
    jclass str_cls = env->FindClass("java/lang/String");
    jobjectArray out = env->NewObjectArray(2, str_cls, env->NewStringUTF(""));
    if (out == nullptr || s == nullptr) return out;

    env->SetObjectArrayElement(out, 0, env->NewStringUTF(s->arch.c_str()));
    env->SetObjectArrayElement(out, 1, env->NewStringUTF(s->tmpl_kind.c_str()));
    return out;
}

JNIEXPORT jboolean JNICALL
JNI_FN(nativeSupportsVision)(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<pll_session *>(handle);
    return (s && s->mctx && mtmd_support_vision(s->mctx)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
JNI_FN(nativeSupportsAudio)(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<pll_session *>(handle);
    return (s && s->mctx && mtmd_support_audio(s->mctx)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
JNI_FN(nativeResetChat)(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<pll_session *>(handle);
    if (s == nullptr) return;
    llama_memory_clear(llama_get_memory(s->lctx), true);
    s->messages.clear();
    s->prev_render.clear();
    s->n_past = 0;
}

JNIEXPORT void JNICALL
JNI_FN(nativeCancel)(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<pll_session *>(handle);
    if (s != nullptr) s->cancel.store(true);
}

JNIEXPORT jint JNICALL
JNI_FN(nativeContextUsed)(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<pll_session *>(handle);
    return s ? static_cast<jint>(s->n_past) : 0;
}

JNIEXPORT jint JNICALL
JNI_FN(nativeContextSize)(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<pll_session *>(handle);
    return (s && s->lctx) ? static_cast<jint>(llama_n_ctx(s->lctx)) : 0;
}

/**
 * Timings of the last turn as {prompt_ms, decode_ms, decoded_tokens,
 * media_tokens}.
 *
 * One array rather than four calls across the JNI boundary, and one snapshot
 * rather than four that could straddle a turn.
 */
JNIEXPORT jlongArray JNICALL
JNI_FN(nativeLastTurnStats)(JNIEnv * env, jobject, jlong handle) {
    auto * s = reinterpret_cast<pll_session *>(handle);
    jlong values[4] = {0, 0, 0, 0};
    if (s != nullptr) {
        values[0] = static_cast<jlong>(s->last_prompt_ms);
        values[1] = static_cast<jlong>(s->last_decode_ms);
        values[2] = static_cast<jlong>(s->last_decoded);
        values[3] = static_cast<jlong>(s->last_media_toks);
    }
    jlongArray out = env->NewLongArray(4);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, 4, values);
    return out;
}

JNIEXPORT void JNICALL
JNI_FN(nativeFree)(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<pll_session *>(handle);
    if (s == nullptr) return;
    if (s->mctx)  mtmd_free(s->mctx);
    if (s->lctx)  llama_free(s->lctx);
    if (s->model) llama_model_free(s->model);
    delete s;
}

/**
 * Appends a user turn, evaluates it, and streams the reply back token by token.
 * Returns the complete reply, or a string starting with "\x01" to signal an
 * error (the leading byte cannot appear in normal model output).
 */
JNIEXPORT jstring JNICALL
JNI_FN(nativeGenerate)(JNIEnv * env, jobject, jlong handle,
                       jstring juser, jobjectArray jmedia, jstring jsystem,
                       jfloat temp, jfloat top_p, jint top_k, jfloat min_p,
                       jfloat repeat_penalty, jint max_tokens, jint seed,
                       jobject token_cb) {
    auto * s = reinterpret_cast<pll_session *>(handle);
    if (s == nullptr || s->lctx == nullptr) {
        return env->NewStringUTF("\x01" "session not loaded");
    }
    s->cancel.store(false);

    const std::string user_text = jstr(env, juser);
    const std::string system_prompt = jstr(env, jsystem);

    // Collect media paths.
    std::vector<std::string> media;
    if (jmedia != nullptr) {
        const jsize n = env->GetArrayLength(jmedia);
        for (jsize i = 0; i < n; ++i) {
            auto item = static_cast<jstring>(env->GetObjectArrayElement(jmedia, i));
            media.push_back(jstr(env, item));
            env->DeleteLocalRef(item);
        }
    }
    if (!media.empty() && s->mctx == nullptr) {
        return env->NewStringUTF("\x01" "attachments need the projector; load mmproj first");
    }

    // Everything appended from here on can be undone by nativeRollbackTurn.
    s->undo_messages = s->messages.size();
    s->undo_render   = s->prev_render.size();
    s->undo_n_past   = s->n_past;
    s->undo_valid    = true;

    // The system prompt goes in as its own message at the head of the
    // conversation. Gemma 4 has a real system role; for other models the
    // template decides where it ends up, including folding it into the first
    // user turn the way Gemma 3 required.
    if (s->messages.empty() && !system_prompt.empty()) {
        s->messages.emplace_back("system", system_prompt);
    }

    std::string content;
    for (size_t i = 0; i < media.size(); ++i) {
        // mtmd swaps its own marker for the model's real media tokens
        // (<|image|> / <|audio|>) during mtmd_tokenize.
        content += mtmd_default_marker();
        content += "\n";
    }
    content += user_text;

    s->messages.emplace_back("user", content);

    std::string rendered;
    if (render_chat(s, /*add_assistant=*/true, rendered) < 0) {
        s->messages.pop_back();
        return env->NewStringUTF("\x01" "chat template failed");
    }
    // Only the part of the prompt that is new gets tokenized; the rest is
    // already in the KV cache. That holds exactly as long as each render extends
    // the last one, which the Gemma renderer guarantees and a foreign template
    // does not: several of llama.cpp's built-ins fold the system prompt into the
    // *last* user message, so the whole transcript shifts every turn. Detect it
    // by comparison rather than by trusting the template, and re-evaluate from
    // scratch when it happens - slow, but correct, and it beats feeding the
    // model a prompt spliced together from two different renderings.
    if (rendered.compare(0, s->prev_render.size(), s->prev_render) != 0) {
        LOGI("template rewrote history; re-evaluating %zu bytes", rendered.size());
        llama_memory_clear(llama_get_memory(s->lctx), true);
        s->n_past = 0;
        s->prev_render.clear();
        // The KV state the rollback would restore no longer exists.
        s->undo_valid = false;
    }

    const std::string delta = rendered.substr(s->prev_render.size());
    const bool add_bos = s->prev_render.empty();

    // ---- evaluate the new prompt segment ------------------------------------
    // Timings are split so a slow turn can be attributed: media preprocessing
    // (mel spectrogram / image resize), encoder + prefill, then decode.
    const int64_t t_prompt_start = now_ms();
    int64_t t_media_prep = 0;
    int64_t t_media_eval = 0;
    llama_pos n_media_tokens = 0;

    bool ok;
    if (media.empty()) {
        std::vector<llama_token> toks(delta.size() + 16);
        int32_t n = llama_tokenize(s->vocab, delta.c_str(), static_cast<int32_t>(delta.size()),
                                   toks.data(), static_cast<int32_t>(toks.size()),
                                   add_bos, /*parse_special=*/true);
        if (n < 0) {
            toks.resize(static_cast<size_t>(-n));
            n = llama_tokenize(s->vocab, delta.c_str(), static_cast<int32_t>(delta.size()),
                               toks.data(), static_cast<int32_t>(toks.size()), add_bos, true);
        }
        if (n < 0) {
            s->messages.pop_back();
            return env->NewStringUTF("\x01" "tokenization failed");
        }
        toks.resize(static_cast<size_t>(n));

        if (s->n_past + n >= static_cast<llama_pos>(llama_n_ctx(s->lctx))) {
            s->messages.pop_back();
            return env->NewStringUTF("\x01" "context full - start a new chat");
        }
        ok = eval_tokens(s, toks, /*logits_last=*/true);
    } else {
        std::vector<mtmd_bitmap *> bitmaps;
        bool load_ok = true;
        for (const auto & path : media) {
            auto wrapper = mtmd_helper_bitmap_init_from_file(s->mctx, path.c_str(), /*placeholder=*/false);
            if (wrapper.bitmap == nullptr) {
                LOGE("failed to read media: %s", path.c_str());
                load_ok = false;
                break;
            }
            bitmaps.push_back(wrapper.bitmap);
        }
        if (!load_ok) {
            for (auto * b : bitmaps) mtmd_bitmap_free(b);
            s->messages.pop_back();
            return env->NewStringUTF("\x01" "could not read the attachment");
        }

        mtmd_input_text text;
        text.text          = delta.c_str();
        text.text_len      = delta.size();
        text.add_special   = add_bos;
        text.parse_special = true;

        mtmd_input_chunks * chunks = mtmd_input_chunks_init();
        std::vector<const mtmd_bitmap *> bmp_c(bitmaps.begin(), bitmaps.end());
        const int64_t t_tok0 = now_ms();
        const int32_t trc = mtmd_tokenize(s->mctx, chunks, &text, bmp_c.data(), bmp_c.size());
        t_media_prep = now_ms() - t_tok0;

        for (auto * b : bitmaps) mtmd_bitmap_free(b);

        if (trc != 0) {
            mtmd_input_chunks_free(chunks);
            s->messages.pop_back();
            LOGE("mtmd_tokenize failed: %d", trc);
            return env->NewStringUTF("\x01" "could not encode the attachment");
        }

        llama_pos new_n_past = s->n_past;
        const int64_t t_eval0 = now_ms();
        const int32_t erc = mtmd_helper_eval_chunks(s->mctx, s->lctx, chunks,
                                                    s->n_past, /*seq_id=*/0, s->n_batch,
                                                    /*logits_last=*/true, &new_n_past);
        t_media_eval = now_ms() - t_eval0;
        n_media_tokens = new_n_past - s->n_past;
        mtmd_input_chunks_free(chunks);
        if (erc != 0) {
            s->messages.pop_back();
            LOGE("mtmd_helper_eval_chunks failed: %d (n_past=%d n_ctx=%u)",
                 erc, static_cast<int>(s->n_past), llama_n_ctx(s->lctx));
            // llama_decode's contract: 1 = no KV slot, 2 = aborted, -1 = bad
            // batch. Collapsing all three into "failed" sent people hunting for
            // a bug when they had simply hit Stop or run out of context.
            switch (erc) {
                case 1:
                    return env->NewStringUTF(
                        "\x01" "not enough context left for this attachment - start a new chat");
                case 2:
                    return env->NewStringUTF("\x01" "cancelled");
                default:
                    return env->NewStringUTF("\x01" "failed to process the attachment");
            }
        }
        s->n_past = new_n_past;
        ok = true;
    }

    if (!ok) {
        s->messages.pop_back();
        return env->NewStringUTF(s->cancel.load() ? "\x01" "cancelled" : "\x01" "prompt evaluation failed");
    }

    // ---- sampler chain -------------------------------------------------------
    llama_sampler_chain_params scp = llama_sampler_chain_default_params();
    scp.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(scp);

    if (repeat_penalty > 1.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, repeat_penalty, 0.0f, 0.0f));
    }
    if (temp <= 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        if (top_k > 0)   llama_sampler_chain_add(smpl, llama_sampler_init_top_k(top_k));
        if (top_p < 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_top_p(top_p, 1));
        if (min_p > 0.0f) llama_sampler_chain_add(smpl, llama_sampler_init_min_p(min_p, 1));
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(static_cast<uint32_t>(seed)));
    }

    jmethodID on_token = nullptr;
    if (token_cb != nullptr) {
        jclass cls = env->GetObjectClass(token_cb);
        on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)V");
    }

    // ---- decode loop ---------------------------------------------------------
    const int64_t t_prompt_ms = now_ms() - t_prompt_start;
    const int64_t t_decode_start = now_ms();
    int n_decoded = 0;

    std::string reply;
    std::string pending;   // bytes held back until they form valid UTF-8
    const auto n_ctx = static_cast<llama_pos>(llama_n_ctx(s->lctx));

    for (jint i = 0; i < max_tokens; ++i) {
        if (s->cancel.load()) break;

        const llama_token tok = llama_sampler_sample(smpl, s->lctx, -1);
        // NOTE: llama_sampler_sample() already calls llama_sampler_accept()
        // internally; accepting again would double-count repeat penalties.

        if (llama_vocab_is_eog(s->vocab, tok)) break;
        ++n_decoded;

        pending += token_to_piece(s->vocab, tok);
        const size_t hold = incomplete_utf8_tail(pending);
        if (pending.size() > hold) {
            std::string emit = pending.substr(0, pending.size() - hold);
            pending = pending.substr(pending.size() - hold);
            reply += emit;
            if (on_token != nullptr) {
                jstring js = env->NewStringUTF(emit.c_str());
                env->CallVoidMethod(token_cb, on_token, js);
                env->DeleteLocalRef(js);
                if (env->ExceptionCheck()) { env->ExceptionClear(); break; }
            }
        }

        if (s->n_past + 1 >= n_ctx) break;

        std::vector<llama_token> one{tok};
        if (!eval_tokens(s, one, /*logits_last=*/true)) break;
    }

    llama_sampler_free(smpl);

    const int64_t t_decode_ms = now_ms() - t_decode_start;

    // Kept for the UI as well as the log: the same numbers answer "is this
    // thing slow" and "am I about to run out of context", and the log is not
    // visible on a phone.
    s->last_prompt_ms  = t_prompt_ms;
    s->last_decode_ms  = t_decode_ms;
    s->last_decoded    = n_decoded;
    s->last_media_toks = static_cast<int>(n_media_tokens);

    // One line per turn, so "it feels slow" can be answered with the stage that
    // actually cost the time instead of a guess.
    LOGI("turn: prompt=%lldms (media prep=%lldms encode+prefill=%lldms, %d media tok) "
         "decode=%lldms %d tok (%.1f tok/s)",
         static_cast<long long>(t_prompt_ms),
         static_cast<long long>(t_media_prep),
         static_cast<long long>(t_media_eval),
         static_cast<int>(n_media_tokens),
         static_cast<long long>(t_decode_ms), n_decoded,
         t_decode_ms > 0 ? (n_decoded * 1000.0 / static_cast<double>(t_decode_ms)) : 0.0);

    s->messages.emplace_back("assistant", reply);
    std::string closed;
    if (render_chat(s, /*add_assistant=*/false, closed) >= 0) {
        s->prev_render = closed;
    }

    return env->NewStringUTF(reply.c_str());
}

/**
 * Undoes the last turn: drops the messages it added and truncates the KV cache
 * back to where it was.
 *
 * Needed to retry a question after the model has already answered "I don't
 * know". Simply asking again would leave the refusal in the history, and a
 * model that has just said it cannot know something tends to stay consistent
 * with itself on the second pass.
 */
JNIEXPORT jboolean JNICALL
JNI_FN(nativeRollbackTurn)(JNIEnv *, jobject, jlong handle) {
    auto * s = reinterpret_cast<pll_session *>(handle);
    if (s == nullptr || s->lctx == nullptr || !s->undo_valid) return JNI_FALSE;
    if (s->messages.size() < s->undo_messages) return JNI_FALSE;

    llama_memory_t mem = llama_get_memory(s->lctx);
    // [undo_n_past, inf) -- everything this turn wrote.
    if (mem != nullptr) llama_memory_seq_rm(mem, 0, s->undo_n_past, -1);

    s->messages.resize(s->undo_messages);
    s->prev_render.resize(s->undo_render);
    s->n_past    = s->undo_n_past;
    s->undo_valid = false;

    LOGI("rolled back to n_past=%d, %zu messages",
         static_cast<int>(s->n_past), s->messages.size());
    return JNI_TRUE;
}

} // extern "C"
