package com.onlypanels.lucifer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.torproject.jni.TorService;

/**
 * Lucifer's built-in Tor. When Private mode is on, web searches go through Tor
 * and Lucifer asks Tor for a new route (new IP address) every few minutes.
 * Only Lucifer's own traffic goes through it, not other apps.
 */
final class TorManager {
    private static final String TAG = "LuciferTor";
    private static TorManager instance;

    static synchronized TorManager get(Context c) {
        if (instance == null) instance = new TorManager(c.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile TorService service;
    private boolean bound;
    private volatile long lastRotation;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((TorService.LocalBinder) binder).getService();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final Runnable rotator = new Runnable() {
        @Override
        public void run() {
            Prefs p = new Prefs(app);
            if (!p.tor() || !bound) return;
            if (ready()) newIdentity();
            main.postDelayed(this, p.torRotateMinutes() * 60_000L);
        }
    };

    private TorManager(Context app) {
        this.app = app;
    }

    /** Starts Tor (if Private mode is on) and the regular location change. Safe to call often. */
    void sync() {
        if (new Prefs(app).tor()) start(); else stop();
    }

    void start() {
        if (bound) return;
        try {
            bound = app.bindService(new Intent(app, TorService.class), conn, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e(TAG, "couldn't start Tor", e);
            bound = false;
        }
        main.removeCallbacks(rotator);
        main.postDelayed(rotator, new Prefs(app).torRotateMinutes() * 60_000L);
    }

    void stop() {
        main.removeCallbacks(rotator);
        if (!bound) return;
        try { app.unbindService(conn); } catch (Exception ignored) {}
        bound = false;
        service = null;
    }

    void restartRotation() {
        main.removeCallbacks(rotator);
        if (bound) main.postDelayed(rotator, new Prefs(app).torRotateMinutes() * 60_000L);
    }

    boolean running() { return bound; }

    /** True once Tor has built its first route and is ready for searches. */
    boolean ready() {
        TorService s = service;
        if (s == null) return false;
        try {
            String v = s.getInfo("status/circuit-established");
            if (v != null && v.trim().equals("1")) return true;
            String phase = s.getInfo("status/bootstrap-phase");
            return phase != null && phase.contains("PROGRESS=100");
        } catch (Exception e) {
            return false;
        }
    }

    int socksPort() {
        TorService s = service;
        try { return s == null ? 9050 : s.getSocksPort(); } catch (Exception e) { return 9050; }
    }

    /** Waits (on a background thread) for Tor to be ready. */
    boolean waitUntilReady(long timeoutMs) {
        if (!bound) main.post(this::start);
        long end = System.currentTimeMillis() + timeoutMs;
        long lastLog = 0;
        while (System.currentTimeMillis() < end) {
            if (ready()) return true;
            if (System.currentTimeMillis() - lastLog > 10_000) {
                lastLog = System.currentTimeMillis();
                Log.i(TAG, "waiting: " + bootstrapPhase());
            }
            try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
        }
        return ready();
    }

    /** Asks Tor for a fresh route, so sites see a different IP address from now on. */
    boolean newIdentity() {
        TorService s = service;
        if (s == null) return false;
        try {
            s.getTorControlConnection().signal("NEWNYM");
            lastRotation = System.currentTimeMillis();
            Log.i(TAG, "new Tor identity");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "NEWNYM failed", e);
            return false;
        }
    }

    long lastRotation() { return lastRotation; }

    /** Tor's own description of how far it has got connecting, e.g. "PROGRESS=45 TAG=loading_descriptors". */
    String bootstrapPhase() {
        TorService s = service;
        if (s == null) return "service not bound yet";
        try {
            String p = s.getInfo("status/bootstrap-phase");
            return p == null ? "no answer from Tor" : p;
        } catch (Exception e) {
            return "error " + e;
        }
    }

    /** The address websites currently see, checked through Tor. Run on a background thread. */
    String visibleAddress() {
        try {
            String json = WebSearch.fetchViaTor("https://check.torproject.org/api/ip", socksPort());
            org.json.JSONObject o = new org.json.JSONObject(json);
            return (o.optBoolean("IsTor") ? "" : "NOT TOR: ") + o.optString("IP");
        } catch (Exception e) {
            return null;
        }
    }
}
