package com.onlypanels.lucifer;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** List of saved chats. Tap to open, long-press to rename or delete. */
public class ChatsActivity extends Activity {
    private LinearLayout list;
    private Prefs prefs;

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = new Prefs(this);
        Ui.darkBars(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);

        LinearLayout top = Ui.hbox(this);
        top.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        TextView back = Ui.iconButton(this, "←", "Back");
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView title = Ui.text(this, "Chats", 20, Ui.TEXT, true);
        title.setPadding(Ui.dp(this, 12), 0, 0, 0);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView add = Ui.iconButton(this, "＋", "New chat");
        add.setOnClickListener(v -> { prefs.setCurrentChat(-1); finish(); });
        top.addView(add);
        root.addView(top);

        ScrollView sv = new ScrollView(this);
        list = Ui.vbox(this, 16);
        list.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 24));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        list.removeAllViews();
        List<Db.Chat> chats = Db.get(this).chats();
        if (chats.isEmpty()) {
            TextView t = Ui.text(this, "No chats yet. Start one and it'll be saved here.", 15, Ui.MUTED, false);
            t.setPadding(0, Ui.dp(this, 24), 0, 0);
            list.addView(t);
            return;
        }
        long current = prefs.currentChat();
        for (Db.Chat ch : chats) {
            LinearLayout row = Ui.vbox(this, 14);
            row.setBackground(ch.id == current ? Ui.outline(this, Ui.SURFACE, Ui.PURPLE, 14) : Ui.rounded(this, Ui.SURFACE, 14));
            row.setLayoutParams(Ui.matchWrap(this, 10));
            TextView name = Ui.text(this, ch.title, 16, Ui.TEXT, false);
            name.setMaxLines(2);
            row.addView(name);
            row.addView(Ui.text(this, DateUtils.getRelativeTimeSpanString(ch.updated).toString(), 12, Ui.MUTED, false));
            row.setOnClickListener(v -> { prefs.setCurrentChat(ch.id); finish(); });
            row.setOnLongClickListener(v -> { options(ch); return true; });
            list.addView(row);
        }
        TextView hint = Ui.text(this, "Press and hold a chat to rename or delete it.", 13, Ui.MUTED, false);
        hint.setPadding(0, Ui.dp(this, 16), 0, 0);
        list.addView(hint);
    }

    private void options(Db.Chat ch) {
        new AlertDialog.Builder(this)
                .setTitle(ch.title)
                .setItems(new String[]{"Rename", "Delete"}, (d, which) -> {
                    if (which == 0) rename(ch);
                    else new AlertDialog.Builder(this)
                            .setTitle("Delete this chat?")
                            .setPositiveButton("Delete", (d2, w) -> {
                                if (Brain.get(this).busyChat() == ch.id) Brain.get(this).stop();
                                Db.get(this).deleteChat(ch.id);
                                if (prefs.currentChat() == ch.id) prefs.setCurrentChat(-1);
                                render();
                            })
                            .setNegativeButton("Cancel", null).show();
                }).show();
    }

    private void rename(Db.Chat ch) {
        EditText e = new EditText(this);
        e.setText(ch.title);
        e.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Rename chat")
                .setView(e)
                .setPositiveButton("Save", (d, w) -> {
                    String t = e.getText().toString().trim();
                    if (!t.isEmpty()) { Db.get(this).renameChat(ch.id, t); render(); }
                })
                .setNegativeButton("Cancel", null).show();
    }
}
