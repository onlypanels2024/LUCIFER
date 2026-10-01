package com.onlypanels.lucifer;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Finding, downloading and checking model files. Models live in Lucifer's own folder on the phone. */
final class ModelFiles {
    static final class Suggestion {
        final String name, note, url;
        Suggestion(String name, String note, String url) { this.name = name; this.note = note; this.url = url; }
    }

    static final Suggestion[] SUGGESTIONS = {
            new Suggestion("Llama 3.1 8B — abliterated", "Best all-rounder with refusals removed · 4.9 GB",
                    "https://huggingface.co/mlabonne/Meta-Llama-3.1-8B-Instruct-abliterated-GGUF/resolve/main/meta-llama-3.1-8b-instruct-abliterated.Q4_K_M.gguf"),
            new Suggestion("Qwen 2.5 7B Instruct", "Strong at facts, maths and summaries · 4.7 GB",
                    "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf"),
            new Suggestion("Llama 3.2 3B Instruct", "Smaller and about twice as fast · 2.0 GB",
                    "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf"),
    };

    private ModelFiles() {}

    static File dir(Context c) {
        File d = new File(c.getExternalFilesDir(null), "models");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static List<File> list(Context c) {
        File[] fs = dir(c).listFiles((d, n) -> n.toLowerCase().endsWith(".gguf"));
        List<File> out = new ArrayList<>(fs == null ? new ArrayList<>() : Arrays.asList(fs));
        out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    /** True if the file starts with the GGUF signature. */
    static boolean looksLikeGguf(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[4];
            return in.read(b) == 4 && b[0] == 'G' && b[1] == 'G' && b[2] == 'U' && b[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    /** Hugging Face "blob" page links are turned into direct download links. */
    static String directLink(String url) {
        url = url.trim();
        if (url.contains("huggingface.co/") && url.contains("/blob/")) url = url.replace("/blob/", "/resolve/");
        return url;
    }

    static String fileNameFor(String url) {
        String path = Uri.parse(url).getLastPathSegment();
        if (path == null || path.isEmpty()) path = "model.gguf";
        path = path.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!path.toLowerCase().endsWith(".gguf")) path += ".gguf";
        return path;
    }

    /** Starts a background download. Android shows its progress in the notifications. */
    static String startDownload(Context c, String url) {
        Prefs prefs = new Prefs(c);
        url = directLink(url);
        if (!url.startsWith("https://") && !url.startsWith("http://")) return "That doesn't look like a web link.";
        String name = fileNameFor(url);
        File target = new File(dir(c), name);
        if (target.exists()) target.delete();
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request r = new DownloadManager.Request(Uri.parse(url))
                .setTitle("Lucifer: " + name)
                .setDescription("Downloading an AI model")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(c, null, "models/" + name)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false);
        long old = prefs.downloadId();
        if (old > 0) dm.remove(old);
        prefs.setDownloadId(dm.enqueue(r));
        return null;
    }

    /** Download status for the settings screen, or null if nothing is downloading. */
    static String downloadStatus(Context c) {
        long id = new Prefs(c).downloadId();
        if (id <= 0) return null;
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        try (Cursor q = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (q == null || !q.moveToFirst()) return null;
            int status = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            long done = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
            long total = q.getLong(q.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            switch (status) {
                case DownloadManager.STATUS_PENDING: return "Download waiting to start…";
                case DownloadManager.STATUS_PAUSED: return "Download paused (waiting for a connection) · " + Ui.size(done);
                case DownloadManager.STATUS_RUNNING:
                    return total > 0 ? String.format(java.util.Locale.UK, "Downloading… %d%% · %s of %s",
                            done * 100 / total, Ui.size(done), Ui.size(total)) : "Downloading… " + Ui.size(done);
                default: return null;
            }
        }
    }

    /** If a download has finished, makes it the active model. Returns a message for the user, or null. */
    static String finishDownload(Context c) {
        Prefs prefs = new Prefs(c);
        long id = prefs.downloadId();
        if (id <= 0) return null;
        DownloadManager dm = (DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE);
        try (Cursor q = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (q == null || !q.moveToFirst()) { prefs.setDownloadId(-1); return null; }
            int status = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                String local = q.getString(q.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI));
                prefs.setDownloadId(-1);
                File f = local == null ? null : new File(Uri.parse(local).getPath());
                if (f == null || !f.exists()) return "The download finished but the file is missing.";
                if (!looksLikeGguf(f)) {
                    f.delete();
                    return "That link didn't give a .gguf model file (it may be a web page). Try the direct download link.";
                }
                prefs.setModelPath(f.getAbsolutePath());
                return "Model downloaded: " + f.getName();
            }
            if (status == DownloadManager.STATUS_FAILED) {
                int reason = q.getInt(q.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                prefs.setDownloadId(-1);
                dm.remove(id);
                if (reason == DownloadManager.ERROR_INSUFFICIENT_SPACE) return "The download stopped: not enough free space on the phone.";
                return "The download didn't work (code " + reason + "). Check the link and your connection.";
            }
        }
        return null;
    }

    /** Called by Android when a download finishes, even if Lucifer is closed. */
    public static class DoneReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id > 0 && id == new Prefs(c).downloadId()) {
                finishDownload(c);
                Brain.get(c).ensureLoaded();
            }
        }
    }
}
