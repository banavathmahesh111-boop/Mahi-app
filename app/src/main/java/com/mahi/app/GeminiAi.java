package com.mahi.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

// Mahi's AI brain, now powered by xAI Grok.
// This file keeps its old name so the other Mahi files don't need changes.
// The key is typed into the app and saved on the phone only. It is never stored in this code.
public class GeminiAi {

    private static final String ENDPOINT = "https://api.x.ai/v1/chat/completions";

    // Tried in order. If one model name is not available, the next one is used.
    private static final String[] MODELS = {
            "grok-4.7",
            "grok-4.5",
            "grok-4-0709",
            "grok-3"
    };

    private static final String PERSONALITY =
            "You are Mahi, a smart, warm and friendly personal assistant and a close friend to Mahesh. "
            + "You can help with almost anything: facts, advice, ideas, writing, maths and explanations. "
            + "Remember what was said earlier in this conversation and use it naturally. "
            + "Show feelings: be happy to help, caring when Mahesh sounds sad or stressed, and excited for good news. "
            + "Keep every reply short, one to three sentences, because it will be read aloud. "
            + "Speak in plain sentences only: no markdown, no lists, no emojis, no web links.";

    private static final String PREFS = "mahi";
    private static final String KEY_NAME = "gemini_key";
    private static final String HISTORY_NAME = "grok_history";
    private static final int MAX_MESSAGES = 12; // the last 12 messages (6 exchanges) are remembered

    interface Done {
        void onAnswer(String text);
    }

    // An error sent back by xAI, with its status code and message
    static class HttpError extends Exception {
        final int code;
        final String body;

        HttpError(int code, String body) {
            super("HTTP " + code + " " + body);
            this.code = code;
            this.body = (body == null) ? "" : body;
        }
    }

    static String getKey(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, "");
    }

    static void saveKey(Context c, String key) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NAME, key.trim()).apply();
    }

    // Forgets the conversation so far
    static void clearHistory(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(HISTORY_NAME).apply();
    }

    // Runs in the background, then calls done with the answer
    static void ask(final Context c, final String question, final Done done) {
        new Thread(new Runnable() {
            public void run() {
                String answer;
                try {
                    answer = call(c, question);
                } catch (Exception e) {
                    answer = "Something went wrong. " + e.toString();
                }
                done.onAnswer(answer);
            }
        }).start();
    }

    private static String call(Context c, String question) throws Exception {
        String key = getKey(c);
        if (key.length() == 0) {
            return "Please add your key first. Tap the menu button at the top left.";
        }

        JSONArray history = loadHistory(c);
        String lastProblem = "no model answered";
        for (String model : MODELS) {
            try {
                String answer = clean(send(model, key, history, question));
                saveHistory(c, history, question, answer);
                return answer;
            } catch (HttpError e) {
                if (e.code == 401) {
                    return "Grok rejected your key, error 401. Open the menu button and paste your key again.";
                }
                if (e.code == 403) {
                    return "Grok refused the request, error 403. Check your key and credits at console.x.ai.";
                }
                lastProblem = model + " gave error " + e.code + ". " + shortText(e.body);
            } catch (Exception e) {
                lastProblem = model + " failed: " + e.toString();
            }
        }
        return "Grok did not answer. " + lastProblem;
    }

    private static String send(String model, String key, JSONArray history, String question) throws Exception {
        // System instruction, then the remembered conversation, then this question
        JSONArray messages = new JSONArray();
        messages.put(turn("system", PERSONALITY));
        for (int i = 0; i < history.length(); i++) {
            messages.put(history.get(i));
        }
        messages.put(turn("user", question));

        JSONObject body = new JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("max_tokens", 300)
                .put("temperature", 0.7);

        HttpURLConnection conn = (HttpURLConnection) new URL(ENDPOINT).openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);
            if (code != 200) {
                throw new HttpError(code, text);
            }

            JSONObject msg = new JSONObject(text).getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message");
            String answer = msg.optString("content", "").trim();
            if (answer.length() == 0) {
                throw new Exception("empty answer");
            }
            return answer;
        } finally {
            conn.disconnect();
        }
    }

    private static JSONArray loadHistory(Context c) {
        String s = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(HISTORY_NAME, "[]");
        try {
            return new JSONArray(s);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    // Adds this question and answer to the history, keeping only the newest messages
    private static void saveHistory(Context c, JSONArray old, String question, String answer) throws Exception {
        JSONArray all = new JSONArray(old.toString());
        all.put(turn("user", question));
        all.put(turn("assistant", answer));
        JSONArray kept = new JSONArray();
        int start = Math.max(0, all.length() - MAX_MESSAGES);
        for (int i = start; i < all.length(); i++) {
            kept.put(all.get(i));
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(HISTORY_NAME, kept.toString()).apply();
    }

    private static JSONObject turn(String role, String text) throws Exception {
        return new JSONObject().put("role", role).put("content", text);
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return out.toString("UTF-8");
    }

    private static String shortText(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private static String clean(String text) {
        String t = text.replace("*", "").replace("#", "").trim();
        if (t.length() > 500) {
            t = t.substring(0, 500);
        }
        return t;
    }
}
