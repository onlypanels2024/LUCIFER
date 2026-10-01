package com.onlypanels.lucifer;

import android.text.Html;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/** Free web search (DuckDuckGo, with Wikipedia as a backup) and page reading. No account needed. */
final class WebSearch {
    static final class Result {
        final String title, url, snippet;
        String pageText = "";
        Result(String title, String url, String snippet) { this.title = title; this.url = url; this.snippet = snippet; }
    }

    private static final String UA =
            "Mozilla/5.0 (Linux; Android 15; SM-S938B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36";

    /** When true, every request goes through Lucifer's built-in Tor. Never falls back to a direct connection. */
    static volatile boolean viaTor = false;
    static volatile int torPort = 9050;

    private WebSearch() {}

    /** Downloads a page through Tor (used to show which address websites see). */
    static String fetchViaTor(String url, int port) throws Exception {
        return read(open(url, true, port));
    }

    /** Searches and reads the top pages. Never throws; returns what it could find. */
    static List<Result> search(String query, int maxResults, int pagesToRead, int charsPerPage) {
        List<Result> results = new ArrayList<>();
        try { results = duckDuckGo(query, maxResults); } catch (Exception ignored) {}
        if (results.isEmpty()) {
            try { results = wikipedia(query, maxResults); } catch (Exception ignored) {}
        }
        int read = 0;
        for (Result r : results) {
            if (read >= pagesToRead) break;
            try {
                String text = pageText(r.url);
                if (text.length() > 200) {
                    r.pageText = trimTo(text, charsPerPage);
                    read++;
                }
            } catch (Exception ignored) {}
        }
        return results;
    }

    static List<Result> duckDuckGo(String query, int max) throws Exception {
        String html = post("https://html.duckduckgo.com/html/", "q=" + URLEncoder.encode(query, "UTF-8") + "&kl=wt-wt");
        List<Result> out = new ArrayList<>();
        Pattern link = Pattern.compile("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
        Pattern snip = Pattern.compile("<a[^>]*class=\"result__snippet\"[^>]*>(.*?)</a>", Pattern.DOTALL);
        Matcher lm = link.matcher(html);
        Matcher sm = snip.matcher(html);
        Set<String> seen = new LinkedHashSet<>();
        while (lm.find() && out.size() < max) {
            String url = realUrl(lm.group(1));
            String title = clean(lm.group(2));
            String snippet = sm.find(lm.end()) ? clean(sm.group(1)) : "";
            if (url.isEmpty() || url.contains("duckduckgo.com/y.js") || !seen.add(url)) continue;
            out.add(new Result(title, url, snippet));
        }
        return out;
    }

    private static String realUrl(String href) throws Exception {
        href = href.replace("&amp;", "&");
        int i = href.indexOf("uddg=");
        if (i >= 0) {
            String enc = href.substring(i + 5);
            int amp = enc.indexOf('&');
            if (amp >= 0) enc = enc.substring(0, amp);
            return URLDecoder.decode(enc, "UTF-8");
        }
        if (href.startsWith("//")) return "https:" + href;
        return href.startsWith("http") ? href : "";
    }

    static List<Result> wikipedia(String query, int max) throws Exception {
        String url = "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json&srlimit=" + max
                + "&srsearch=" + URLEncoder.encode(query, "UTF-8");
        JSONArray arr = new JSONObject(get(url)).getJSONObject("query").getJSONArray("search");
        List<Result> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String title = o.getString("title");
            out.add(new Result(title,
                    "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"),
                    clean(o.optString("snippet"))));
        }
        return out;
    }

    /** Downloads a page and keeps only its readable text. */
    static String pageText(String url) throws Exception {
        String html = get(url);
        html = html.replaceAll("(?is)<(script|style|noscript|svg|nav|footer|header|form|aside)[^>]*>.*?</\\1>", " ");
        html = html.replaceAll("(?is)<!--.*?-->", " ");
        html = html.replaceAll("(?i)<br\\s*/?>|</p>|</h[1-6]>|</li>|</tr>|</div>", "\n");
        String text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString();
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n")) {
            String l = line.replaceAll("[\\s\\u00A0\\uFFFC]+", " ").trim();
            if (l.length() >= 40) sb.append(l).append('\n');   // skip menus, buttons and other short bits
        }
        return sb.toString().trim();
    }

    private static String trimTo(String s, int n) {
        if (s.length() <= n) return s;
        int cut = s.lastIndexOf(' ', n);
        return s.substring(0, cut > n / 2 ? cut : n) + "…";
    }

    private static String clean(String html) {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().replaceAll("\\s+", " ").trim();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = open(url);
        return read(c);
    }

    private static String post(String url, String form) throws Exception {
        HttpURLConnection c = open(url);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream os = c.getOutputStream()) { os.write(form.getBytes(StandardCharsets.UTF_8)); }
        return read(c);
    }

    private static HttpURLConnection open(String url) throws Exception {
        return open(url, viaTor, torPort);
    }

    private static HttpURLConnection open(String url, boolean tor, int port) throws Exception {
        HttpURLConnection c = (HttpURLConnection) (tor
                ? new URL(url).openConnection(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port)))
                : new URL(url).openConnection());
        c.setUseCaches(false);
        // new connection each time, so a location change applies straight away
        if (tor) c.setRequestProperty("Connection", "close");
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", UA);
        c.setRequestProperty("Accept-Language", "en-GB,en;q=0.9");
        return c;
    }

    private static String read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        if (code >= 400) throw new Exception("HTTP " + code);
        String type = c.getContentType();
        if (type != null && !type.contains("text") && !type.contains("json") && !type.contains("xml"))
            throw new Exception("not a web page");
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n, total = 0;
            while ((n = in.read(buf)) > 0 && total < 1_500_000) { bos.write(buf, 0, n); total += n; }
            return bos.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Turns the results into notes the model reads before answering. */
    static String asContext(String query, List<Result> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("Web search results for: ").append(query).append("\n\n");
        for (int i = 0; i < results.size(); i++) {
            Result r = results.get(i);
            sb.append('[').append(i + 1).append("] ").append(r.title).append('\n');
            if (!r.snippet.isEmpty()) sb.append(r.snippet).append('\n');
            if (!r.pageText.isEmpty()) sb.append(r.pageText).append('\n');
            sb.append('\n');
        }
        sb.append("Answer the question using these results where they help, and mention the source numbers like [1]. "
                + "If the results don't answer it, say so and answer from what you know.");
        return sb.toString();
    }
}
