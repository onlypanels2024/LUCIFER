package com.onlypanels.lucifer;

/** The on-device AI engine (llama.cpp), compiled into libLucifer.so. */
final class Native {
    static {
        System.loadLibrary("lucifer");
    }

    interface TextListener {
        /** Receives each new piece of the reply as UTF-8. Return false to stop. */
        boolean onText(byte[] utf8);
    }

    private Native() {}

    static native long load(String path, int nCtx, int nThreads);
    static native void free(long handle);
    static native String lastError(long handle);
    static native String describe(long handle);
    static native void stop(long handle);
    static native int droppedMessages(long handle);
    static native int generate(long handle, String[] roles, String[] contents,
                               float temperature, int maxTokens, TextListener listener);
}
