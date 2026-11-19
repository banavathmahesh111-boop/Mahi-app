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

// Mahi's AI brain, powered by Google Gemini only.
// The key is typed into the app and saved on the phone only. It is never stored in this code.
public class GeminiAi {

    // Tried in order. The lite model comes first because it usually has a larger free daily limit.
    private static final String[] MODELS = {
            "gemini-2.5-flash-lite",
            "gemini-2.5-flash",
            "gemini-flash-latest"
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
    private static final String HISTORY_NAME = "gemini_history";
    private static final int MAX_MESSAGES = 12;

    interface Done {
        void onAnswer(String text);
    }

    // An error sent back by Google, with its status code and message
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
            return "Please add your Gemini key first. Tap the menu button at the top left.";
        }

        JSONArray history = loadHistory(c);
        String lastProblem = "no model answered";
        for (String model : MODELS) {
            try {
                String answer = clean(askModel(model, key, history, question));
                saveHistory(c, history, question, answer);
                return answer;
            } catch (HttpError e) {
                if (isKeyError(e)) {
                    return "Google rejected your key, error " + e.code
                            + ". Open the menu button and paste your key again.";
                }
                if (e.code == 429) {
                    return "Gemini is busy right now, the free limit was reached. "
                            + "Wait a minute, or turn on billing for your project.";
                }
                lastProblem = model + " gave error " + e.code + ". " + shortText(e.body);
            } catch (Exception e) {
                lastProblem = model + " failed: " + e.toString();
            }
        }
        return "Gemini did not answer. " + lastProblem;
    }

    private static boolean isKeyError(HttpError e) {
        return e.code == 401 || e.code == 403 || (e.code == 400 && e.body.contains("API key"));
    }

    // Asks one model with web search. If the search is refused or over its limit, asks again without it.
    private static String askModel(String model, String key, JSONArray history, String question) throws Exception {
        try {
            return send(model, key, history, question, true);
        } catch (HttpError e) {
            if (!isKeyError(e) && (e.code == 400 || e.code == 429)) {
                return send(model, key, history, question, false);
            }
            throw e;
        }
    }

    private static String send(String model, String key, JSONArray history, String question, boolean useSearch)
            throws Exception {
        // The conversation so far, then this question
        JSONArray contents = new JSONArray();
        for (int i = 0; i < history.length(); i++) {
            contents.put(history.get(i));
        }
        contents.put(turn("user", question));

        JSONObject body = new JSONObject()
                .put("systemInstruction", new JSONObject()
                        .put("parts", new JSONArray().put(new JSONObject().put("text", PERSONALITY))))
                .put("contents", contents)
                .put("generationConfig", new JSONObject().put("maxOutputTokens", 300).put("temperature", 0.7));
        if (useSearch) {
            body.put("tools", new JSONArray().put(new JSONObject().put("google_search", new JSONObject())));
        }

        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-goog-api-key", key);

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);
            if (code != 200) {
                throw new HttpError(code, text);
            }

            JSONArray parts = new JSONObject(text).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts");
            StringBuilder answer = new StringBuilder();
            for (int i = 0; i < parts.length(); i++) {
                answer.append(parts.getJSONObject(i).optString("text", ""));
            }
            if (answer.length() == 0) {
                throw new Exception("empty answer");
            }
            return answer.toString();
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
        all.put(turn("model", answer));
        JSONArray kept = new JSONArray();
        int start = Math.max(0, all.length() - MAX_MESSAGES);
        for (int i = start; i < all.length(); i++) {
            kept.put(all.get(i));
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(HISTORY_NAME, kept.toString()).apply();
    }

    private static JSONObject turn(String role, String text) throws Exception {
        return new JSONObject()
                .put("role", role)
                .put("parts", new JSONArray().put(new JSONObject().put("text", text)));
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
