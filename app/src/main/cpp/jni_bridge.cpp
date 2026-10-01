// Connects the Java app to the engine.
#include <jni.h>
#include <android/log.h>

#include "engine.h"
#include "llama.h"

#define TAG "Lucifer"

using lucifer::Engine;

static void log_cb(ggml_log_level level, const char * text, void *) {
    int prio = level == GGML_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR
             : level == GGML_LOG_LEVEL_WARN  ? ANDROID_LOG_WARN : ANDROID_LOG_DEBUG;
    __android_log_print(prio, TAG, "%s", text);
}

static std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return "";
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID getBytes = env->GetMethodID(strCls, "getBytes", "(Ljava/lang/String;)[B");
    jstring enc = env->NewStringUTF("UTF-8");
    auto arr = (jbyteArray) env->CallObjectMethod(s, getBytes, enc);
    jsize n = env->GetArrayLength(arr);
    std::string out(n, '\0');
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    env->DeleteLocalRef(arr);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(strCls);
    return out;
}

static jbyteArray jbytes(JNIEnv * env, const std::string & s) {
    jbyteArray a = env->NewByteArray((jsize) s.size());
    env->SetByteArrayRegion(a, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return a;
}

static std::string g_last_error;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_onlypanels_lucifer_Native_load(JNIEnv * env, jclass, jstring path, jint nCtx, jint nThreads) {
    llama_log_set(log_cb, nullptr);
    auto * e = new Engine();
    if (!e->load(jstr(env, path), nCtx, nThreads)) {
        g_last_error = e->error();
        delete e;
        return 0;
    }
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT void JNICALL
Java_com_onlypanels_lucifer_Native_free(JNIEnv *, jclass, jlong h) {
    delete reinterpret_cast<Engine *>(h);
}

JNIEXPORT jstring JNICALL
Java_com_onlypanels_lucifer_Native_lastError(JNIEnv * env, jclass, jlong h) {
    const std::string & e = h ? reinterpret_cast<Engine *>(h)->error() : g_last_error;
    return env->NewStringUTF(e.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_onlypanels_lucifer_Native_describe(JNIEnv * env, jclass, jlong h) {
    return env->NewStringUTF(reinterpret_cast<Engine *>(h)->describe().c_str());
}

JNIEXPORT void JNICALL
Java_com_onlypanels_lucifer_Native_stop(JNIEnv *, jclass, jlong h) {
    reinterpret_cast<Engine *>(h)->request_stop();
}

JNIEXPORT jint JNICALL
Java_com_onlypanels_lucifer_Native_droppedMessages(JNIEnv *, jclass, jlong h) {
    return reinterpret_cast<Engine *>(h)->dropped_messages();
}

JNIEXPORT jint JNICALL
Java_com_onlypanels_lucifer_Native_generate(JNIEnv * env, jclass, jlong h,
        jobjectArray roles, jobjectArray contents, jfloat temperature, jint maxTokens, jobject listener) {
    auto * e = reinterpret_cast<Engine *>(h);
    std::vector<lucifer::Message> msgs;
    jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; ++i) {
        auto r = (jstring) env->GetObjectArrayElement(roles, i);
        auto c = (jstring) env->GetObjectArrayElement(contents, i);
        msgs.push_back({jstr(env, r), jstr(env, c)});
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }
    lucifer::GenOptions opt;
    opt.temperature = temperature;
    opt.max_tokens = maxTokens;

    jclass cls = env->GetObjectClass(listener);
    jmethodID onText = env->GetMethodID(cls, "onText", "([B)Z");
    return e->generate(msgs, opt, [&](const std::string & piece) {
        jbyteArray a = jbytes(env, piece);
        jboolean keep = env->CallBooleanMethod(listener, onText, a);
        env->DeleteLocalRef(a);
        if (env->ExceptionCheck()) { env->ExceptionClear(); return false; }
        return keep == JNI_TRUE;
    });
}

} // extern "C"
