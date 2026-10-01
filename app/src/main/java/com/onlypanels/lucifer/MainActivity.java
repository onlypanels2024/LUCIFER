package com.onlypanels.lucifer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** The chat screen. */
public class MainActivity extends Activity implements Brain.Listener {
    private static final int REQ_VOICE = 1;

    private Prefs prefs;
    private Brain brain;
    private Db db;

    private TextView subtitle;
    private ScrollView scroll;
    private LinearLayout list;
    private TextView statusLine;
    private EditText input;
    private TextView webChip;
    private TextView sendBtn;

    private long chatId = -1;
    private TextView liveReply;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        brain = Brain.get(this);
        db = Db.get(this);
        Ui.darkBars(this);
        buildScreen();
    }

    private void buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        // Top bar
        LinearLayout top = Ui.hbox(this);
        top.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        TextView chats = Ui.iconButton(this, "☰", "Chats");
        chats.setOnClickListener(v -> startActivity(new Intent(this, ChatsActivity.class)));
        top.addView(chats);
        LinearLayout titles = Ui.vbox(this, 0);
        titles.setPadding(Ui.dp(this, 12), 0, 0, 0);
        titles.addView(Ui.text(this, "Lucifer", 20, Ui.TEXT, true));
        subtitle = Ui.text(this, "", 12, Ui.MUTED, false);
        subtitle.setSingleLine(true);
        titles.addView(subtitle);
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextView newChat = Ui.iconButton(this, "＋", "New chat");
        newChat.setOnClickListener(v -> startNewChat());
        top.addView(newChat);
        top.addView(Ui.gap(this, 8));
        TextView settings = Ui.iconButton(this, "⚙", "Settings");
        settings.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        top.addView(settings);
        root.addView(top);

        View line = new View(this);
        line.setBackgroundColor(Ui.LINE);
        root.addView(line, new LinearLayout.LayoutParams(-1, 1));

        // Messages
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        list = Ui.vbox(this, 14);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        statusLine = Ui.text(this, "", 13, Ui.PURPLE_SOFT, false);
        statusLine.setPadding(Ui.dp(this, 18), Ui.dp(this, 4), Ui.dp(this, 18), Ui.dp(this, 4));
        statusLine.setVisibility(View.GONE);
        root.addView(statusLine);

        // Message bar
        LinearLayout bar = Ui.hbox(this);
        bar.setGravity(Gravity.BOTTOM);
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 10));
        webChip = Ui.iconButton(this, "🌐", "Web search");
        webChip.setOnClickListener(v -> {
            prefs.setWeb(!prefs.web());
            refreshWebChip();
            refreshSubtitle();
            Toast.makeText(this, prefs.web() ? "Web search on: Lucifer will look things up online"
                    : "Web search off: answers come only from Lucifer, fully offline", Toast.LENGTH_SHORT).show();
        });
        bar.addView(webChip);
        bar.addView(Ui.gap(this, 8));

        input = new EditText(this);
        input.setHint("Message Lucifer");
        input.setContentDescription("Message Lucifer");
        input.setHintTextColor(Ui.MUTED);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(16);
        input.setMaxLines(6);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_ENTER_ACTION);
        input.setBackground(Ui.rounded(this, Ui.SURFACE_2, 22));
        input.setPadding(Ui.dp(this, 16), Ui.dp(this, 11), Ui.dp(this, 16), Ui.dp(this, 11));
        input.setMinHeight(Ui.dp(this, 44));
        bar.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(Ui.gap(this, 8));

        TextView mic = Ui.iconButton(this, "🎤", "Speak");
        mic.setOnClickListener(v -> startVoice());
        bar.addView(mic);
        bar.addView(Ui.gap(this, 8));

        sendBtn = Ui.iconButton(this, "➤", "Send");
        sendBtn.setBackground(Ui.rounded(this, Ui.PURPLE, 22));
        sendBtn.setOnClickListener(v -> onSend());
        bar.addView(sendBtn);
        root.addView(bar);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        brain.setListener(this);
        brain.ensureLoaded();
        TorManager.get(this).sync();
        String done = ModelFiles.finishDownload(this);
        if (done != null) { Toast.makeText(this, done, Toast.LENGTH_LONG).show(); brain.ensureLoaded(); }
        long saved = prefs.currentChat();
        chatId = (saved > 0 && db.chatExists(saved)) ? saved : -1;
        refreshWebChip();
        refreshSubtitle();
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        brain.clearListener(this);
    }

    private void refreshWebChip() {
        boolean on = prefs.web();
        webChip.setBackground(on ? Ui.outline(this, Ui.PURPLE_DARK, Ui.PURPLE, 22) : Ui.rounded(this, Ui.SURFACE_2, 22));
        webChip.setAlpha(on ? 1f : 0.55f);
        webChip.setContentDescription(on ? "Web search on" : "Web search off");
    }

    private void refreshSubtitle() {
        switch (brain.state()) {
            case READY: subtitle.setText((!prefs.web() ? "Offline · " : prefs.tor() ? "Private web · " : "Web · ") + brain.detail()); break;
            case LOADING: subtitle.setText("Waking up…"); break;
            case FAILED: subtitle.setText("Model problem — see Settings"); break;
            default: subtitle.setText("No model yet");
        }
    }

    private void refreshSend() {
        boolean busy = brain.busy();
        sendBtn.setText(busy ? "■" : "➤");
        sendBtn.setContentDescription(busy ? "Stop" : "Send");
    }

    // ---------- Drawing the conversation ----------

    private void render() {
        list.removeAllViews();
        liveReply = null;
        refreshSend();
        Brain.State st = brain.state();
        if (st == Brain.State.NO_MODEL) { list.addView(noModelCard()); return; }
        if (st == Brain.State.FAILED) { list.addView(failedCard()); return; }

        List<Db.Msg> msgs = chatId > 0 ? db.messages(chatId) : new ArrayList<>();
        if (msgs.isEmpty() && !(brain.busy() && brain.busyChat() == chatId)) {
            list.addView(greeting(st == Brain.State.LOADING));
        }
        for (Db.Msg m : msgs) addBubble(m);
        if (brain.busy() && brain.busyChat() == chatId && chatId > 0) {
            liveReply = addAssistantBubble(brain.partial().isEmpty() ? "…" : brain.partial(), "");
            showStatus(brain.status());
        } else {
            showStatus("");
        }
        scrollToEnd();
    }

    private View greeting(boolean loading) {
        LinearLayout box = Ui.vbox(this, 24);
        box.setGravity(Gravity.CENTER);
        TextView mark = Ui.text(this, "♆", 56, Ui.PURPLE, false);
        mark.setGravity(Gravity.CENTER);
        box.addView(mark);
        TextView hi = Ui.text(this, loading ? "Waking up…" : "What's on your mind?", 22, Ui.TEXT, true);
        hi.setGravity(Gravity.CENTER);
        box.addView(hi);
        TextView sub = Ui.text(this, loading
                ? "Loading the model into memory. This takes a few seconds."
                : (prefs.web() ? "Web search is on — Lucifer will look things up online."
                               : "Web search is off — everything stays on this phone."), 14, Ui.MUTED, false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, Ui.dp(this, 8), 0, 0);
        box.addView(sub);
        box.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        box.setMinimumHeight(Ui.dp(this, 420));
        return box;
    }

    private View noModelCard() {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "Lucifer needs a brain", 20, Ui.TEXT, true));
        TextView t = Ui.text(this, "Lucifer runs an AI model right here on your phone — no account, no subscription. "
                + "Add a model once (a .gguf file, about 5 GB for the best ones) and you're set.", 15, Ui.MUTED, false);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        c.addView(t);
        android.widget.Button add = Ui.button(this, "Add a model", true);
        add.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        c.addView(add);
        return c;
    }

    private View failedCard() {
        LinearLayout c = Ui.card(this);
        c.addView(Ui.text(this, "Lucifer couldn't start", 20, Ui.RED, true));
        TextView t = Ui.text(this, brain.detail(), 15, Ui.MUTED, false);
        t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 4));
        c.addView(t);
        android.widget.Button s = Ui.button(this, "Open settings", true);
        s.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        c.addView(s);
        return c;
    }

    private void addBubble(Db.Msg m) {
        if ("user".equals(m.role)) addUserBubble(m.content);
        else addAssistantBubble(m.content, m.sources);
    }

    private void addUserBubble(String text) {
        TextView t = Ui.text(this, text, 16, 0xFFFFFFFF, false);
        t.setTextIsSelectable(true);
        t.setBackground(Ui.rounded(this, Ui.PURPLE_DARK, 18));
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.END;
        lp.topMargin = Ui.dp(this, 12);
        lp.leftMargin = Ui.dp(this, 48);
        list.addView(t, lp);
        t.setOnLongClickListener(v -> { copy(text); return true; });
    }

    private TextView addAssistantBubble(String text, String sources) {
        LinearLayout box = Ui.vbox(this, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = Ui.dp(this, 12);
        lp.rightMargin = Ui.dp(this, 24);
        box.setLayoutParams(lp);

        TextView t = Ui.text(this, "", 16, Ui.TEXT, false);
        t.setText(Ui.markdown(text));
        t.setLineSpacing(0, 1.15f);
        t.setBackground(Ui.rounded(this, Ui.SURFACE, 18));
        t.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        t.setOnLongClickListener(v -> { copy(text); return true; });
        box.addView(t, new LinearLayout.LayoutParams(-2, -2));

        if (sources != null && !sources.isEmpty()) {
            LinearLayout src = Ui.vbox(this, 0);
            src.setPadding(Ui.dp(this, 6), Ui.dp(this, 6), 0, 0);
            src.addView(Ui.text(this, "Sources", 12, Ui.MUTED, true));
            String[] rows = sources.split("\n");
            for (int i = 0; i < rows.length; i++) {
                String[] parts = rows[i].split("\t", 2);
                if (parts.length < 2) continue;
                final String url = parts[1];
                String host = Uri.parse(url).getHost();
                TextView s = Ui.text(this, "[" + (i + 1) + "] " + parts[0] + (host != null ? "  ·  " + host.replaceFirst("^www\\.", "") : ""),
                        13, Ui.PURPLE_SOFT, false);
                s.setSingleLine(true);
                s.setEllipsize(android.text.TextUtils.TruncateAt.END);
                s.setPadding(0, Ui.dp(this, 3), 0, Ui.dp(this, 3));
                s.setOnClickListener(v -> openLink(url));
                src.addView(s);
            }
            box.addView(src);
        }
        list.addView(box);
        return t;
    }

    private void showStatus(String s) {
        statusLine.setText(s);
        statusLine.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.scrollTo(0, list.getBottom()));
    }

    // ---------- Actions ----------

    private void startNewChat() {
        if (brain.busy()) brain.stop();
        chatId = -1;
        prefs.setCurrentChat(-1);
        input.setText("");
        render();
    }

    private void onSend() {
        if (brain.busy()) { brain.stop(); return; }
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if (brain.state() != Brain.State.READY) {
            Toast.makeText(this, brain.state() == Brain.State.LOADING ? "Lucifer is still waking up — one moment."
                    : "Add a model in Settings first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (chatId <= 0) {
            String title = text.replaceAll("\\s+", " ");
            chatId = db.newChat(title.length() > 48 ? title.substring(0, 48) + "…" : title);
            prefs.setCurrentChat(chatId);
        }
        db.addMessage(chatId, "user", text);
        input.setText("");
        brain.reply(chatId, db.messages(chatId), prefs.web());
        render();
    }

    private void startVoice() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Talk to Lucifer");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Voice typing isn't available on this phone.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req == REQ_VOICE && result == RESULT_OK && data != null) {
            ArrayList<String> said = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (said != null && !said.isEmpty()) {
                String cur = input.getText().toString().trim();
                input.setText(cur.isEmpty() ? said.get(0) : cur + " " + said.get(0));
                input.setSelection(input.getText().length());
            }
        }
    }

    private void copy(String text) {
        new AlertDialog.Builder(this)
                .setItems(new String[]{"Copy text", "Share"}, (d, which) -> {
                    if (which == 0) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(ClipData.newPlainText("Lucifer", text));
                        Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                    } else {
                        Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                        startActivity(Intent.createChooser(s, "Share"));
                    }
                }).show();
    }

    private void openLink(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show();
        }
    }

    // ---------- Brain updates ----------

    @Override
    public void onState(Brain.State s, String detail) {
        refreshSubtitle();
        render();
    }

    @Override
    public void onStatus(long id, String status) {
        if (id == chatId) showStatus(status);
    }

    @Override
    public void onReplyText(long id, String soFar) {
        if (id != chatId || liveReply == null) return;
        boolean atEnd = scroll.getScrollY() + scroll.getHeight() >= list.getBottom() - Ui.dp(this, 120);
        liveReply.setText(Ui.markdown(soFar));
        showStatus("");
        if (atEnd) scrollToEnd();
    }

    @Override
    public void onReplyDone(long id, String text, String sources, String note) {
        if (id != chatId) { refreshSend(); return; }
        render();
        if (note != null && !note.isEmpty()) {
            TextView n = Ui.text(this, note, 13, text.isEmpty() ? Ui.RED : Ui.MUTED, false);
            n.setPadding(Ui.dp(this, 6), Ui.dp(this, 8), 0, 0);
            list.addView(n);
            scrollToEnd();
        }
    }
}
