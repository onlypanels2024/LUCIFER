package com.onlypanels.lucifer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Only in test builds: lets the automated virtual-phone test set things up. Not in the real app. */
public class TestHooks extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String task = i.getStringExtra("task");
        Prefs p = new Prefs(c);
        String result = "ok";
        if ("download".equals(task)) {
            String err = ModelFiles.startDownload(c, i.getStringExtra("url"));
            if (err != null) result = err;
        } else if ("usemodel".equals(task)) {
            p.setModelPath(i.getStringExtra("path"));
            Brain.get(c).ensureLoaded();
        } else if ("web".equals(task)) {
            p.setWeb(i.getBooleanExtra("on", true));
        } else if ("tor".equals(task)) {
            p.setTor(i.getBooleanExtra("on", false));
            TorManager.get(c).sync();
        } else if ("temp".equals(task)) {
            p.setTemperature(i.getFloatExtra("value", 0.8f));
        } else if ("torip".equals(task)) {
            final PendingResult pr = goAsync();
            new Thread(() -> {
                TorManager tm = TorManager.get(c);
                String ip = tm.waitUntilReady(40_000) ? tm.visibleAddress() : "not ready";
                Log.i("UITEST", "torip: " + ip);
                pr.setResultData(ip);
                pr.finish();
            }).start();
            return;
        } else if ("newid".equals(task)) {
            result = "newid=" + TorManager.get(c).newIdentity();
        } else if ("canceldl".equals(task)) {
            long id = p.downloadId();
            if (id > 0) ((android.app.DownloadManager) c.getSystemService(Context.DOWNLOAD_SERVICE)).remove(id);
            p.setDownloadId(-1);
        } else if ("torphase".equals(task)) {
            result = TorManager.get(c).bootstrapPhase();
        } else if ("state".equals(task)) {
            result = Brain.get(c).state() + " | " + Brain.get(c).detail() + " | busy=" + Brain.get(c).busy()
                    + " | download=" + ModelFiles.downloadStatus(c) + " | tor=" + TorManager.get(c).ready();
        }
        Log.i("UITEST", task + ": " + result);
        setResultData(result);
    }
}
