// Lucifer's on-device AI engine: a thin layer over llama.cpp.
// Kept free of JNI so it can be compiled and tested on a normal computer too.
#pragma once

#include <atomic>
#include <functional>
#include <string>
#include <vector>

struct llama_model;
struct llama_context;
struct llama_vocab;

namespace lucifer {

struct Message {
    std::string role;    // "system", "user" or "assistant"
    std::string content;
};

struct GenOptions {
    float temperature = 0.8f;
    float top_p = 0.95f;
    int   max_tokens = 1024;
};

class Engine {
public:
    ~Engine();

    // Loads a .gguf model file. Returns false and fills error() on failure.
    bool load(const std::string & path, int n_ctx, int n_threads);

    // Writes a reply to the conversation. on_text receives complete UTF-8 text
    // pieces as they are produced; return false from it to stop early.
    // Returns the number of tokens produced, or -1 on error (see error()).
    int generate(std::vector<Message> messages, const GenOptions & opt,
                 const std::function<bool(const std::string &)> & on_text);

    void request_stop() { stop_.store(true); }
    const std::string & error() const { return error_; }
    std::string describe() const;
    int dropped_messages() const { return dropped_; }

private:
    bool format(const std::vector<Message> & msgs, std::string & out);
    bool tokenize(const std::string & text, std::vector<int> & out);
    std::string piece(int token);
    static bool abort_cb(void * data);

    llama_model * model_ = nullptr;
    llama_context * ctx_ = nullptr;
    const llama_vocab * vocab_ = nullptr;
    std::string template_;
    std::vector<int> cached_;   // tokens currently held in the context memory
    int n_ctx_ = 0;
    int dropped_ = 0;
    std::atomic<bool> stop_{false};
    std::string error_;
};

// Length of the longest prefix of s that is complete UTF-8.
size_t utf8_complete_prefix(const std::string & s);

} // namespace lucifer
