package com.onlypanels.lucifer;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** Saved chats, stored only on this phone. */
final class Db extends SQLiteOpenHelper {
    static final class Chat {
        long id;
        String title;
        long updated;
    }

    static final class Msg {
        long id;
        String role;
        String content;
        String sources = "";   // one "title\turl" per line, for replies that used the web
        Msg(long id, String role, String content) { this.id = id; this.role = role; this.content = content; }
    }

    private static Db instance;

    static synchronized Db get(Context c) {
        if (instance == null) instance = new Db(c.getApplicationContext());
        return instance;
    }

    private Db(Context c) {
        super(c, "lucifer.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE chats (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT NOT NULL, updated INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id INTEGER NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, sources TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX messages_chat ON messages(chat_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {}

    long newChat(String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        v.put("updated", System.currentTimeMillis());
        return getWritableDatabase().insert("chats", null, v);
    }

    boolean chatExists(long id) {
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM chats WHERE id=?", new String[]{"" + id})) {
            return c.moveToFirst();
        }
    }

    long addMessage(long chatId, String role, String content) {
        return addMessage(chatId, role, content, "");
    }

    long addMessage(long chatId, String role, String content, String sources) {
        ContentValues v = new ContentValues();
        v.put("sources", sources == null ? "" : sources);
        v.put("chat_id", chatId);
        v.put("role", role);
        v.put("content", content);
        v.put("created", System.currentTimeMillis());
        long id = getWritableDatabase().insert("messages", null, v);
        ContentValues u = new ContentValues();
        u.put("updated", System.currentTimeMillis());
        getWritableDatabase().update("chats", u, "id=?", new String[]{"" + chatId});
        return id;
    }

    void deleteMessage(long id) {
        getWritableDatabase().delete("messages", "id=?", new String[]{"" + id});
    }

    List<Msg> messages(long chatId) {
        List<Msg> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, role, content, sources FROM messages WHERE chat_id=? ORDER BY id", new String[]{"" + chatId})) {
            while (c.moveToNext()) {
                Msg m = new Msg(c.getLong(0), c.getString(1), c.getString(2));
                m.sources = c.getString(3);
                out.add(m);
            }
        }
        return out;
    }

    List<Chat> chats() {
        List<Chat> out = new ArrayList<>();
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT id, title, updated FROM chats ORDER BY updated DESC", null)) {
            while (c.moveToNext()) {
                Chat ch = new Chat();
                ch.id = c.getLong(0);
                ch.title = c.getString(1);
                ch.updated = c.getLong(2);
                out.add(ch);
            }
        }
        return out;
    }

    void renameChat(long id, String title) {
        ContentValues v = new ContentValues();
        v.put("title", title);
        getWritableDatabase().update("chats", v, "id=?", new String[]{"" + id});
    }

    void deleteChat(long id) {
        getWritableDatabase().delete("messages", "chat_id=?", new String[]{"" + id});
        getWritableDatabase().delete("chats", "id=?", new String[]{"" + id});
    }

    void deleteAll() {
        getWritableDatabase().delete("messages", null, null);
        getWritableDatabase().delete("chats", null, null);
    }
}
