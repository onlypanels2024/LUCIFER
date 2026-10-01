package com.onlypanels.lucifer;

import android.content.Context;
import android.content.SharedPreferences;

/** Settings saved on the phone. */
final class Prefs {
    static final String DEFAULT_PERSONALITY =
            "You are Lucifer, my personal assistant. Be direct, clear and helpful.";

    private final SharedPreferences p;

    Prefs(Context c) {
        p = c.getApplicationContext().getSharedPreferences("lucifer", Context.MODE_PRIVATE);
    }

    String personality() { return p.getString("personality", DEFAULT_PERSONALITY); }
    void setPersonality(String s) { p.edit().putString("personality", s).apply(); }

    String modelPath() { return p.getString("modelPath", ""); }
    void setModelPath(String s) { p.edit().putString("modelPath", s).apply(); }

    int contextSize() { return p.getInt("nCtx", 4096); }
    void setContextSize(int v) { p.edit().putInt("nCtx", v).apply(); }

    float temperature() { return p.getFloat("temperature", 0.8f); }
    void setTemperature(float v) { p.edit().putFloat("temperature", v).apply(); }

    int maxTokens() { return p.getInt("maxTokens", 1024); }
    void setMaxTokens(int v) { p.edit().putInt("maxTokens", v).apply(); }

    int threads() {
        int cores = Runtime.getRuntime().availableProcessors();
        return p.getInt("threads", Math.max(2, Math.min(6, cores - 2)));
    }
    void setThreads(int v) { p.edit().putInt("threads", v).apply(); }

    long downloadId() { return p.getLong("downloadId", -1); }
    void setDownloadId(long v) { p.edit().putLong("downloadId", v).apply(); }

    boolean web() { return p.getBoolean("web", true); }
    void setWeb(boolean v) { p.edit().putBoolean("web", v).apply(); }

    boolean tor() { return p.getBoolean("tor", false); }
    void setTor(boolean v) { p.edit().putBoolean("tor", v).apply(); }

    int torRotateMinutes() { return p.getInt("torRotate", 5); }
    void setTorRotateMinutes(int v) { p.edit().putInt("torRotate", v).apply(); }

    long currentChat() { return p.getLong("currentChat", -1); }
    void setCurrentChat(long v) { p.edit().putLong("currentChat", v).apply(); }
}
