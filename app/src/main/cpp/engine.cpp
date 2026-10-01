#include "engine.h"

#include "llama.h"

#include <algorithm>
#include <cstring>
#include <mutex>

namespace lucifer {

static std::once_flag g_backend_once;

size_t utf8_complete_prefix(const std::string & s) {
    size_t n = s.size();
    // Look back at most 3 bytes for the start of an unfinished character.
    for (size_t back = 1; back <= 4 && back <= n; ++back) {
        unsigned char c = (unsigned char) s[n - back];
        if ((c & 0xC0) == 0x80) continue;          // continuation byte, keep looking
        size_t need = 1;
        if ((c & 0xE0) == 0xC0) need = 2;
        else if ((c & 0xF0) == 0xE0) need = 3;
        else if ((c & 0xF8) == 0xF0) need = 4;
        return back < need ? n - back : n;
    }
    return n;
}

Engine::~Engine() {
    if (ctx_) llama_free(ctx_);
    if (model_) llama_model_free(model_);
}

bool Engine::abort_cb(void * data) {
    return static_cast<Engine *>(data)->stop_.load();
}

bool Engine::load(const std::string & path, int n_ctx, int n_threads) {
    std::call_once(g_backend_once, [] { llama_backend_init(); });

    llama_model_params mp = llama_model_default_params();
    model_ = llama_model_load_from_file(path.c_str(), mp);
    if (!model_) {
        error_ = "This file couldn't be opened as an AI model. Make sure it's a complete .gguf file.";
        return false;
    }
    vocab_ = llama_model_get_vocab(model_);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = n_threads;
    cp.n_threads_batch = n_threads;
    cp.abort_callback = &Engine::abort_cb;
    cp.abort_callback_data = this;
    ctx_ = llama_init_from_model(model_, cp);
    if (!ctx_) {
        error_ = "Not enough memory to start this model. Try a smaller model or a shorter memory setting.";
        return false;
    }
    n_ctx_ = (int) llama_n_ctx(ctx_);

    const char * t = llama_model_chat_template(model_, nullptr);
    template_ = t ? t : "";
    return true;
}

std::string Engine::describe() const {
    if (!model_) return "";
    char buf[256];
    llama_model_desc(model_, buf, sizeof(buf));
    return buf;
}

bool Engine::format(const std::vector<Message> & msgs, std::string & out) {
    std::vector<llama_chat_message> chat;
    size_t total = 0;
    for (auto & m : msgs) {
        chat.push_back({m.role.c_str(), m.content.c_str()});
        total += m.content.size();
    }
    std::vector<char> buf(total * 2 + 1024);
    const char * tmpl = template_.empty() ? "chatml" : template_.c_str();
    for (int attempt = 0; attempt < 2; ++attempt) {
        int n = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), (int) buf.size());
        if (n > (int) buf.size()) {
            buf.resize(n + 1);
            n = llama_chat_apply_template(tmpl, chat.data(), chat.size(), true, buf.data(), (int) buf.size());
        }
        if (n >= 0) {
            out.assign(buf.data(), n);
            return true;
        }
        tmpl = "chatml";   // model's own template not recognised: fall back to a common one
    }
    error_ = "Couldn't format the conversation for this model.";
    return false;
}

bool Engine::tokenize(const std::string & text, std::vector<int> & out) {
    int n = -llama_tokenize(vocab_, text.c_str(), (int) text.size(), nullptr, 0, true, true);
    if (n <= 0) n = (int) text.size() + 16;
    out.resize(n);
    n = llama_tokenize(vocab_, text.c_str(), (int) text.size(), out.data(), (int) out.size(), true, true);
    if (n < 0) {
        error_ = "Couldn't read the message.";
        return false;
    }
    out.resize(n);
    return true;
}

std::string Engine::piece(int token) {
    char buf[256];
    int n = llama_token_to_piece(vocab_, token, buf, sizeof(buf), 0, false);
    if (n < 0) {
        std::string big(-n, '\0');
        n = llama_token_to_piece(vocab_, token, &big[0], (int) big.size(), 0, false);
        return n > 0 ? big.substr(0, n) : "";
    }
    return std::string(buf, n);
}

int Engine::generate(std::vector<Message> messages, const GenOptions & opt,
                     const std::function<bool(const std::string &)> & on_text) {
    if (!ctx_) { error_ = "No model loaded."; return -1; }
    stop_.store(false);
    dropped_ = 0;

    // Leave room for the reply; if the conversation is too long, forget the oldest messages.
    const int reply_room = std::min(opt.max_tokens, n_ctx_ / 4);
    std::vector<int> prompt;
    while (true) {
        std::string text;
        if (!format(messages, text) || !tokenize(text, prompt)) return -1;
        if ((int) prompt.size() + reply_room <= n_ctx_) break;
        size_t first = (!messages.empty() && messages[0].role == "system") ? 1 : 0;
        if (messages.size() - first <= 1) {
            error_ = "That message is too long for the memory setting. Try a shorter message or raise Memory in Settings.";
            return -1;
        }
        messages.erase(messages.begin() + first);
        // keep a user message first after the system prompt, as most templates expect
        if (messages.size() > first + 1 && messages[first].role == "assistant") {
            messages.erase(messages.begin() + first);
            dropped_++;
        }
        dropped_++;
    }

    // Reuse what the model already read last time; only read the new part.
    llama_memory_t mem = llama_get_memory(ctx_);
    size_t common = 0;
    while (common < cached_.size() && common < prompt.size() && cached_[common] == prompt[common]) common++;
    if (common == prompt.size()) common--;  // must read at least one token
    if (!llama_memory_seq_rm(mem, 0, (int) common, -1)) {
        llama_memory_clear(mem, true);
        common = 0;
    }
    cached_.resize(common);

    for (size_t i = common; i < prompt.size(); i += 512) {
        int n = (int) std::min<size_t>(512, prompt.size() - i);
        int rc = llama_decode(ctx_, llama_batch_get_one(prompt.data() + i, n));
        if (rc != 0) {
            llama_memory_clear(mem, true);
            cached_.clear();
            if (stop_.load()) return 0;
            error_ = "The model ran into a problem reading the conversation.";
            return -1;
        }
        cached_.insert(cached_.end(), prompt.begin() + i, prompt.begin() + i + n);
    }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab_), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(opt.top_p, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_min_p(0.05f, 1));
    if (opt.temperature <= 0.01f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(opt.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }

    std::string pending;
    int produced = 0;
    while (produced < opt.max_tokens && !stop_.load()) {
        if ((int) cached_.size() >= n_ctx_ - 1) break;
        int tok = llama_sampler_sample(smpl, ctx_, -1);
        if (llama_vocab_is_eog(vocab_, tok)) break;
        produced++;

        pending += piece(tok);
        size_t ok = utf8_complete_prefix(pending);
        if (ok > 0) {
            if (!on_text(pending.substr(0, ok))) stop_.store(true);
            pending.erase(0, ok);
        }

        int t = tok;
        if (llama_decode(ctx_, llama_batch_get_one(&t, 1)) != 0) {
            // stopped mid-step or failed: start fresh next time rather than trust the memory
            llama_memory_clear(mem, true);
            cached_.clear();
            break;
        }
        cached_.push_back(tok);
    }
    if (!pending.empty()) on_text(pending);
    llama_sampler_free(smpl);
    return produced;
}

} // namespace lucifer
