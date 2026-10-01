package com.onlypanels.lucifer;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owns the loaded model. All model work runs on one background thread;
 * results come back on the main thread. Replies are saved to the chat
 * even if you leave the screen while Lucifer is still writing.
 */
final class Brain {
    enum State { NO_MODEL, LOADING, READY, FAILED }

    interface Listener {
        void onState(State s, String detail);
        void onStatus(long chatId, String status);
        void onReplyText(long chatId, String fullTextSoFar);
        void onReplyDone(long chatId, String finalText, String sources, String note);
    }

    private static Brain instance;

    static synchronized Brain get(Context c) {
        if (instance == null) instance = new Brain(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private long handle;
    private String loadedKey = "";
    private State state = State.NO_MODEL;
    private String detail = "";
    private Listener listener;

    private volatile boolean busy;
    private volatile long busyChat = -1;
    private volatile String partial = "";
    private volatile String status = "";

    private Brain(Context app) {
        this.app = app;
    }

    void setListener(Listener l) { listener = l; }
    void clearListener(Listener l) { if (listener == l) listener = null; }

    State state() { return state; }
    String detail() { return detail; }
    boolean busy() { return busy; }
    long busyChat() { return busyChat; }
    String partial() { return partial; }
    String status() { return status; }

    private void postStatus(long chatId, String s) {
        status = s;
        main.post(() -> { if (listener != null) listener.onStatus(chatId, s); });
    }

    private void setState(State s, String d) {
        main.post(() -> {
            state = s;
            detail = d;
            if (listener != null) listener.onState(s, d);
        });
    }

    /** Loads (or reloads) the model chosen in settings, if it isn't already loaded. */
    void ensureLoaded() {
        Prefs prefs = new Prefs(app);
        String path = prefs.modelPath();
        String key = path + "|" + prefs.contextSize() + "|" + prefs.threads();
        if (path.isEmpty() || !new File(path).exists()) {
            if (handle != 0) unload();
            loadedKey = "";
            setState(State.NO_MODEL, "");
            return;
        }
        if (key.equals(loadedKey) && (state == State.READY || state == State.LOADING)) return;
        loadedKey = key;
        state = State.LOADING;
        setState(State.LOADING, new File(path).getName());
        final int nCtx = prefs.contextSize();
        final int threads = prefs.threads();
        worker.execute(() -> {
            if (handle != 0) { Native.free(handle); handle = 0; }
            long h = Native.load(path, nCtx, threads);
            if (h == 0) {
                setState(State.FAILED, Native.lastError(0));
            } else {
                handle = h;
                setState(State.READY, Native.describe(h));
            }
        });
    }

    void unload() {
        final long h = handle;
        handle = 0;
        loadedKey = "";
        if (h != 0) {
            Native.stop(h);
            worker.execute(() -> Native.free(h));
        }
        setState(State.NO_MODEL, "");
    }

    void stop() {
        long h = handle;
        if (h != 0 && busy) Native.stop(h);
    }

    /** Writes a reply to the conversation (searching the web first if asked) and saves it to the chat. */
    void reply(long chatId, List<Db.Msg> history, boolean useWeb) {
        if (busy || history.isEmpty()) return;
        Prefs prefs = new Prefs(app);
        String today = new java.text.SimpleDateFormat("EEEE d MMMM yyyy", java.util.Locale.UK).format(new java.util.Date());
        String personality = prefs.personality().trim();
        String system = (personality.isEmpty() ? "" : personality + "\n\n") + "Today's date is " + today + ".";
        final String[] roles = new String[history.size() + 1];
        final String[] contents = new String[history.size() + 1];
        roles[0] = "system";
        contents[0] = system;
        for (int i = 0; i < history.size(); i++) {
            roles[i + 1] = history.get(i).role;
            contents[i + 1] = history.get(i).content;
        }
        final int last = history.size();
        final String question = contents[last];
        final float temp = prefs.temperature();
        final int maxTokens = prefs.maxTokens();
        final int nCtx = prefs.contextSize();

        busy = true;
        busyChat = chatId;
        partial = "";
        status = "";
        worker.execute(() -> {
            String sources = "";
            if (useWeb) {
                boolean tor = new Prefs(app).tor();
                List<WebSearch.Result> results = new java.util.ArrayList<>();
                boolean torMissing = false;
                if (tor) {
                    TorManager tm = TorManager.get(app);
                    if (!tm.ready()) postStatus(chatId, "Connecting to Tor for a private search…");
                    torMissing = !tm.waitUntilReady(60_000);
                    WebSearch.torPort = tm.socksPort();
                }
                WebSearch.viaTor = tor;
                if (torMissing) {
                    postStatus(chatId, "Couldn't connect to Tor, so Lucifer didn't search (to keep you private). Answering from what it knows…");
                } else {
                    postStatus(chatId, tor ? "Searching the web privately through Tor…" : "Searching the web…");
                    // keep the web notes to roughly a third of Lucifer's memory
                    int budget = Math.max(1500, nCtx * 4 / 3);
                    results = WebSearch.search(searchQuery(question), 5, 2, budget / 3);
                }
                if (torMissing) {
                    // already explained above
                } else if (results.isEmpty()) {
                    postStatus(chatId, "Couldn't reach the web, answering from what Lucifer knows…");
                } else {
                    contents[last] = WebSearch.asContext(searchQuery(question), results) + "\n\nQuestion: " + question;
                    StringBuilder src = new StringBuilder();
                    for (WebSearch.Result r : results) src.append(r.title.replace('\t', ' ').replace('\n', ' ')).append('\t').append(r.url).append('\n');
                    sources = src.toString().trim();
                    postStatus(chatId, "Read " + results.size() + " results. Thinking…");
                }
            } else {
                postStatus(chatId, "Thinking…");
            }

            final StringBuilder sb = new StringBuilder();
            final long[] lastPost = {0};
            String note = null;
            int n;
            if (handle == 0) {
                note = "No model is loaded.";
            } else {
                n = Native.generate(handle, roles, contents, temp, maxTokens, utf8 -> {
                    sb.append(new String(utf8, StandardCharsets.UTF_8));
                    long now = System.currentTimeMillis();
                    if (now - lastPost[0] > 80) {
                        lastPost[0] = now;
                        final String soFar = sb.toString();
                        partial = soFar;
                        main.post(() -> { if (listener != null) listener.onReplyText(chatId, soFar); });
                    }
                    return true;
                });
                if (n < 0) note = Native.lastError(handle);
                else if (Native.droppedMessages(handle) > 0)
                    note = "This chat is long, so Lucifer has forgotten the oldest messages.";
            }
            final String text = sb.toString().trim();
            if (!text.isEmpty() && Db.get(app).chatExists(chatId)) {
                Db.get(app).addMessage(chatId, "assistant", text, sources);
            }
            final String finalNote = note;
            final String finalSources = text.isEmpty() ? "" : sources;
            main.post(() -> {
                busy = false;
                busyChat = -1;
                partial = "";
                status = "";
                if (listener != null) listener.onReplyDone(chatId, text, finalSources, finalNote);
            });
        });
    }

    /** Shortens a long message into something a search engine can use. */
    static String searchQuery(String q) {
        String s = q.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
