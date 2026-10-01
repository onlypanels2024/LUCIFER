package com.onlypanels.lucifer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Personality, model, web and advanced settings. */
public class SettingsActivity extends Activity implements Brain.Listener {
    private static final int REQ_PICK = 2;

    private Prefs prefs;
    private Brain brain;
    private LinearLayout content;
    private EditText personality;
    private TextView modelStatus;
    private TextView downloadLine;
    private LinearLayout modelList;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean copying;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            String done = ModelFiles.finishDownload(SettingsActivity.this);
            if (done != null) {
                Toast.makeText(SettingsActivity.this, done, Toast.LENGTH_LONG).show();
                brain.ensureLoaded();
                refreshModels();
            }
            String s = ModelFiles.downloadStatus(SettingsActivity.this);
            downloadLine.setText(s == null ? "" : s);
            downloadLine.setVisibility(s == null ? View.GONE : View.VISIBLE);
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        brain = Brain.get(this);
        Ui.darkBars(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        LinearLayout top = Ui.hbox(this);
        top.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        TextView back = Ui.iconButton(this, "←", "Back");
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView title = Ui.text(this, "Settings", 20, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        top.addView(title);
        root.addView(top);

        ScrollView scroll = new ScrollView(this);
        content = Ui.vbox(this, 16);
        content.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 40));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        buildPersonality();
        buildModel();
        buildWeb();
        buildAdvanced();
        buildChats();
        TextView about = Ui.text(this, "Lucifer " + versionName() + " · AI runs on this phone with llama.cpp",
                12, Ui.MUTED, false);
        about.setGravity(Gravity.CENTER);
        about.setPadding(0, Ui.dp(this, 24), 0, 0);
        content.addView(about);
    }

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception e) { return ""; }
    }

    private TextView heading(String s) {
        TextView t = Ui.text(this, s, 13, Ui.PURPLE_SOFT, true);
        t.setAllCaps(true);
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(this, 4), Ui.dp(this, 24), 0, 0);
        content.addView(t);
        return t;
    }

    private TextView explain(LinearLayout parent, String s) {
        TextView t = Ui.text(this, s, 14, Ui.MUTED, false);
        t.setPadding(0, Ui.dp(this, 6), 0, 0);
        parent.addView(t);
        return t;
    }

    // ---------- Personality ----------

    private void buildPersonality() {
        heading("Personality");
        LinearLayout c = Ui.card(this);
        explain(c, "Tell Lucifer who it is and how to talk to you. It reads this before every chat.");
        personality = new EditText(this);
        personality.setText(prefs.personality());
        personality.setTextColor(Ui.TEXT);
        personality.setHintTextColor(Ui.MUTED);
        personality.setHint("e.g. You are Lucifer. Talk to me like a close friend, keep answers short…");
        personality.setTextSize(15);
        personality.setMinLines(5);
        personality.setGravity(Gravity.TOP);
        personality.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        personality.setBackground(Ui.rounded(this, Ui.SURFACE_2, 12));
        personality.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        personality.setLayoutParams(Ui.matchWrap(this, 10));
        c.addView(personality);
        TextView reset = Ui.text(this, "Reset to default", 14, Ui.PURPLE_SOFT, false);
        reset.setPadding(0, Ui.dp(this, 10), 0, 0);
        reset.setOnClickListener(v -> personality.setText(Prefs.DEFAULT_PERSONALITY));
        c.addView(reset);
        content.addView(c);
    }

    // ---------- Model ----------

    private void buildModel() {
        heading("AI model");
        LinearLayout c = Ui.card(this);
        modelStatus = Ui.text(this, "", 15, Ui.TEXT, false);
        c.addView(modelStatus);
        downloadLine = Ui.text(this, "", 14, Ui.PURPLE_SOFT, false);
        downloadLine.setPadding(0, Ui.dp(this, 8), 0, 0);
        downloadLine.setVisibility(View.GONE);
        c.addView(downloadLine);
        modelList = Ui.vbox(this, 0);
        c.addView(modelList);

        Button dl = Ui.button(this, "Download a model", true);
        dl.setOnClickListener(v -> showDownloadDialog());
        c.addView(dl);
        Button pick = Ui.button(this, "Choose a .gguf file on this phone", false);
        pick.setOnClickListener(v -> pickFile());
        c.addView(pick);
        explain(c, "Bigger models are smarter but slower. On a Galaxy S25 Ultra, 7B–8B models in Q4_K_M size work best.");
        content.addView(c);
    }

    private void refreshModels() {
        File active = new File(prefs.modelPath());
        switch (brain.state()) {
            case READY: modelStatus.setText("✅  Ready: " + brain.detail()); modelStatus.setTextColor(Ui.TEXT); break;
            case LOADING: modelStatus.setText("⏳  Loading " + active.getName() + "…"); modelStatus.setTextColor(Ui.TEXT); break;
            case FAILED: modelStatus.setText("⚠️  " + brain.detail()); modelStatus.setTextColor(Ui.RED); break;
            default: modelStatus.setText("No model yet. Download one below, or choose a .gguf file you already have."); modelStatus.setTextColor(Ui.MUTED);
        }
        modelList.removeAllViews();
        for (File f : ModelFiles.list(this)) {
            if (f.length() == 0) continue;
            boolean isActive = f.getAbsolutePath().equals(active.getAbsolutePath());
            boolean downloading = ModelFiles.downloadStatus(this) != null && !isActive && !ModelFiles.looksLikeGguf(f);
            if (downloading) continue;
            LinearLayout row = Ui.hbox(this);
            row.setBackground(isActive ? Ui.outline(this, Ui.SURFACE_2, Ui.PURPLE, 12) : Ui.rounded(this, Ui.SURFACE_2, 12));
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            row.setLayoutParams(Ui.matchWrap(this, 10));
            LinearLayout txt = Ui.vbox(this, 0);
            TextView name = Ui.text(this, f.getName(), 14, Ui.TEXT, isActive);
            txt.addView(name);
            txt.addView(Ui.text(this, Ui.size(f.length()) + (isActive ? " · in use" : " · tap to use"), 12, Ui.MUTED, false));
            row.addView(txt, new LinearLayout.LayoutParams(0, -2, 1));
            TextView del = Ui.text(this, "🗑", 18, Ui.MUTED, false);
            del.setContentDescription("Delete " + f.getName());
            del.setPadding(Ui.dp(this, 12), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
            del.setOnClickListener(v -> confirmDelete(f, isActive));
            row.addView(del);
            row.setOnClickListener(v -> {
                if (!isActive) {
                    prefs.setModelPath(f.getAbsolutePath());
                    brain.ensureLoaded();
                    refreshModels();
                }
            });
            modelList.addView(row);
        }
    }

    private void confirmDelete(File f, boolean active) {
        new AlertDialog.Builder(this)
                .setTitle("Delete this model?")
                .setMessage(f.getName() + "\n\nThis frees " + Ui.size(f.length()) + ". Your chats are kept.")
                .setPositiveButton("Delete", (d, w) -> {
                    if (active) { brain.unload(); prefs.setModelPath(""); }
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    refreshModels();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showDownloadDialog() {
        LinearLayout box = Ui.vbox(this, 20);
        box.setBackgroundColor(Ui.SURFACE);
        final AlertDialog[] dlg = new AlertDialog[1];
        for (ModelFiles.Suggestion s : ModelFiles.SUGGESTIONS) {
            LinearLayout row = Ui.vbox(this, 12);
            row.setBackground(Ui.rounded(this, Ui.SURFACE_2, 12));
            row.setLayoutParams(Ui.matchWrap(this, 8));
            row.addView(Ui.text(this, s.name, 15, Ui.TEXT, true));
            row.addView(Ui.text(this, s.note, 13, Ui.MUTED, false));
            row.setOnClickListener(v -> { dlg[0].dismiss(); beginDownload(s.url); });
            box.addView(row);
        }
        TextView or = Ui.text(this, "Or paste a link to any .gguf file (e.g. from huggingface.co):", 14, Ui.MUTED, false);
        or.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 6));
        box.addView(or);
        EditText link = new EditText(this);
        link.setHint("https://huggingface.co/…/model-Q4_K_M.gguf");
        link.setHintTextColor(Ui.MUTED);
        link.setTextColor(Ui.TEXT);
        link.setTextSize(14);
        link.setSingleLine(true);
        link.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        link.setBackground(Ui.rounded(this, Ui.SURFACE_2, 12));
        link.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        box.addView(link);
        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        dlg[0] = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Download a model")
                .setView(sv)
                .setPositiveButton("Download link", (d, w) -> {
                    String url = link.getText().toString().trim();
                    if (!url.isEmpty()) beginDownload(url);
                })
                .setNegativeButton("Cancel", null)
                .create();
        dlg[0].show();
    }

    private void beginDownload(String url) {
        String err = ModelFiles.startDownload(this, url);
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Downloading — you can leave Lucifer, it carries on in the background. Use Wi-Fi for big models.",
                Toast.LENGTH_LONG).show();
        downloadLine.setText("Download starting…");
        downloadLine.setVisibility(View.VISIBLE);
    }

    private void pickFile() {
        if (copying) return;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker found on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_PICK && result == RESULT_OK && data != null && data.getData() != null) importFile(data.getData());
    }

    /** Copies the chosen model into Lucifer's folder so it can be loaded quickly. */
    private void importFile(Uri uri) {
        String name = "model.gguf";
        long size = -1;
        try (Cursor q = getContentResolver().query(uri, null, null, null, null)) {
            if (q != null && q.moveToFirst()) {
                int n = q.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int s = q.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0) name = q.getString(n);
                if (s >= 0 && !q.isNull(s)) size = q.getLong(s);
            }
        } catch (Exception ignored) {}
        if (!name.toLowerCase().endsWith(".gguf")) {
            Toast.makeText(this, "Please choose a .gguf model file.", Toast.LENGTH_LONG).show();
            return;
        }
        File dir = ModelFiles.dir(this);
        if (size > 0 && dir.getUsableSpace() < size + 200_000_000L) {
            Toast.makeText(this, "Not enough free space: this model needs " + Ui.size(size) + ".", Toast.LENGTH_LONG).show();
            return;
        }
        final File target = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
        final File temp = new File(dir, target.getName() + ".part");
        final long total = size;
        TextView msg = Ui.text(this, "Starting…", 15, Ui.TEXT, false);
        msg.setPadding(Ui.dp(this, 24), Ui.dp(this, 16), Ui.dp(this, 24), Ui.dp(this, 8));
        AlertDialog dlg = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Adding model").setView(msg).setCancelable(false).create();
        dlg.show();
        copying = true;
        new Thread(() -> {
            String error = null;
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(temp)) {
                byte[] buf = new byte[1 << 20];
                long done = 0, lastShown = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (done - lastShown > 50_000_000L) {
                        lastShown = done;
                        final long d = done;
                        handler.post(() -> msg.setText(total > 0
                                ? String.format(java.util.Locale.UK, "Copying… %d%%  (%s of %s)", d * 100 / total, Ui.size(d), Ui.size(total))
                                : "Copying… " + Ui.size(d)));
                    }
                }
            } catch (Exception e) {
                error = "Couldn't copy the file: " + e.getMessage();
            }
            if (error == null && !ModelFiles.looksLikeGguf(temp)) error = "That file isn't a valid .gguf model.";
            if (error == null) {
                if (target.exists()) target.delete();
                if (!temp.renameTo(target)) error = "Couldn't save the model.";
            }
            if (error != null) temp.delete();
            final String err = error;
            handler.post(() -> {
                copying = false;
                dlg.dismiss();
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                } else {
                    prefs.setModelPath(target.getAbsolutePath());
                    brain.ensureLoaded();
                    Toast.makeText(this, "Model added. You can delete the original from Downloads to save space.", Toast.LENGTH_LONG).show();
                }
                refreshModels();
            });
        }).start();
    }

    // ---------- Web ----------

    private TextView torLine;

    private void buildWeb() {
        heading("Web search & privacy");
        LinearLayout c = Ui.card(this);
        explain(c, "Turn web search on or off with the 🌐 button next to the message box. Searches use DuckDuckGo: "
                + "no account, no cookies, nothing saved to your Google history. Lucifer never asks for your location.");
        Switch tor = new Switch(this);
        tor.setText("Private mode (built-in Tor)");
        tor.setTextColor(Ui.TEXT);
        tor.setTextSize(16);
        tor.setChecked(prefs.tor());
        tor.setLayoutParams(Ui.matchWrap(this, 14));
        c.addView(tor);
        explain(c, "Sends every search through the Tor network so websites see a random server, often in another country — "
                + "never your real address. Searches are a bit slower. If Tor can't connect, Lucifer won't search rather than search unprotected.");

        LinearLayout torBox = Ui.vbox(this, 0);
        choiceInt(torBox, "Change location every", new String[]{"2 min", "5 min", "10 min"}, new int[]{2, 5, 10},
                prefs.torRotateMinutes(), v -> { prefs.setTorRotateMinutes(v); TorManager.get(this).restartRotation(); },
                "Lucifer asks Tor for a new route and IP address this often.");
        torLine = Ui.text(this, "", 14, Ui.PURPLE_SOFT, false);
        torLine.setPadding(0, Ui.dp(this, 14), 0, 0);
        torBox.addView(torLine);
        Button now = Ui.button(this, "New location now", false);
        now.setOnClickListener(v -> {
            if (TorManager.get(this).newIdentity()) {
                Toast.makeText(this, "Switched to a new route", Toast.LENGTH_SHORT).show();
                checkTorAddress();
            } else {
                Toast.makeText(this, "Tor is still connecting — try again in a moment.", Toast.LENGTH_SHORT).show();
            }
        });
        torBox.addView(now);
        torBox.setVisibility(prefs.tor() ? View.VISIBLE : View.GONE);
        c.addView(torBox);

        tor.setOnCheckedChangeListener((b, on) -> {
            prefs.setTor(on);
            TorManager.get(this).sync();
            torBox.setVisibility(on ? View.VISIBLE : View.GONE);
            if (on) checkTorAddress();
        });

        TextView whole = Ui.text(this, "Private mode protects Lucifer's searches only. To hide your address in every app, "
                + "use the free Orbot app's VPN mode.", 13, Ui.MUTED, false);
        whole.setPadding(0, Ui.dp(this, 14), 0, 0);
        c.addView(whole);
        content.addView(c);
        if (prefs.tor()) checkTorAddress();
    }

    /** Shows which address websites see right now. */
    private void checkTorAddress() {
        torLine.setText("Connecting to Tor…");
        TorManager tm = TorManager.get(this);
        tm.sync();
        new Thread(() -> {
            boolean ok = tm.waitUntilReady(90_000);
            String ip = ok ? tm.visibleAddress() : null;
            handler.post(() -> {
                if (isFinishing()) return;
                if (!ok) torLine.setText("⚠️  Tor couldn't connect yet. Check your internet connection.");
                else if (ip == null) torLine.setText("🟢  Tor connected");
                else torLine.setText("🟢  Websites currently see: " + ip + "  (not your real address)");
            });
        }).start();
    }

    // ---------- Advanced ----------

    private void buildAdvanced() {
        heading("Fine-tuning");
        LinearLayout c = Ui.card(this);
        choice(c, "Creativity", new String[]{"Precise", "Balanced", "Creative"}, new float[]{0.3f, 0.7f, 1.0f},
                prefs.temperature(), v -> prefs.setTemperature(v),
                "Precise sticks to facts; Creative is better for stories and ideas.");
        choiceInt(c, "Memory", new String[]{"Short (2k)", "Normal (4k)", "Long (8k)"}, new int[]{2048, 4096, 8192},
                prefs.contextSize(), v -> { prefs.setContextSize(v); brain.ensureLoaded(); },
                "How much of the chat Lucifer keeps in mind. Longer uses more memory and takes longer to load.");
        choiceInt(c, "Reply length", new String[]{"Short", "Normal", "Long"}, new int[]{512, 1024, 2048},
                prefs.maxTokens(), v -> prefs.setMaxTokens(v), "The most Lucifer will write in one reply.");
        choiceInt(c, "Speed (CPU cores)", new String[]{"4", "6", "8"}, new int[]{4, 6, 8},
                prefs.threads(), v -> { prefs.setThreads(v); brain.ensureLoaded(); },
                "6 is usually fastest on the S25 Ultra. Fewer cores keeps the phone cooler.");
        content.addView(c);
    }

    interface FloatSetter { void set(float v); }
    interface IntSetter { void set(int v); }

    private void choice(LinearLayout parent, String label, String[] names, float[] values, float current,
                        FloatSetter setter, String help) {
        int[] idx = new int[values.length];
        int sel = 0;
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - current) < Math.abs(values[sel] - current)) sel = i;
        segmented(parent, label, names, sel, i -> setter.set(values[i]), help);
    }

    private void choiceInt(LinearLayout parent, String label, String[] names, int[] values, int current,
                           IntSetter setter, String help) {
        int sel = 0;
        for (int i = 0; i < values.length; i++) if (Math.abs(values[i] - current) < Math.abs(values[sel] - current)) sel = i;
        segmented(parent, label, names, sel, i -> setter.set(values[i]), help);
    }

    private void segmented(LinearLayout parent, String label, String[] names, int selected, IntSetter onPick, String help) {
        TextView l = Ui.text(this, label, 15, Ui.TEXT, true);
        l.setPadding(0, Ui.dp(this, parent.getChildCount() == 0 ? 0 : 18), 0, Ui.dp(this, 8));
        parent.addView(l);
        LinearLayout row = Ui.hbox(this);
        TextView[] opts = new TextView[names.length];
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            TextView o = Ui.text(this, names[i], 14, Ui.TEXT, false);
            o.setGravity(Gravity.CENTER);
            o.setPadding(Ui.dp(this, 6), Ui.dp(this, 10), Ui.dp(this, 6), Ui.dp(this, 10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = Ui.dp(this, 6);
            row.addView(o, lp);
            opts[i] = o;
            o.setOnClickListener(v -> {
                onPick.set(k);
                for (int j = 0; j < opts.length; j++) styleOpt(opts[j], j == k);
            });
            styleOpt(o, i == selected);
        }
        parent.addView(row);
        TextView h = Ui.text(this, help, 13, Ui.MUTED, false);
        h.setPadding(0, Ui.dp(this, 6), 0, 0);
        parent.addView(h);
    }

    private void styleOpt(TextView o, boolean on) {
        o.setBackground(on ? Ui.rounded(this, Ui.PURPLE, 10) : Ui.rounded(this, Ui.SURFACE_2, 10));
        o.setTextColor(on ? 0xFFFFFFFF : Ui.MUTED);
    }

    // ---------- Chats ----------

    private void buildChats() {
        heading("Chats");
        LinearLayout c = Ui.card(this);
        explain(c, "Chats are saved only on this phone.");
        Button del = Ui.button(this, "Delete all chats", false);
        del.setTextColor(Ui.RED);
        del.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete all chats?")
                .setMessage("This can't be undone.")
                .setPositiveButton("Delete all", (d, w) -> {
                    Db.get(this).deleteAll();
                    prefs.setCurrentChat(-1);
                    Toast.makeText(this, "All chats deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null).show());
        c.addView(del);
        content.addView(c);
    }

    // ---------- Lifecycle ----------

    @Override
    protected void onResume() {
        super.onResume();
        brain.setListener(this);
        refreshModels();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs.setPersonality(personality.getText().toString());
        brain.clearListener(this);
        handler.removeCallbacks(poll);
    }

    @Override public void onState(Brain.State s, String detail) { refreshModels(); }
    @Override public void onStatus(long chatId, String status) {}
    @Override public void onReplyText(long chatId, String soFar) {}
    @Override public void onReplyDone(long chatId, String text, String sources, String note) {}
}
