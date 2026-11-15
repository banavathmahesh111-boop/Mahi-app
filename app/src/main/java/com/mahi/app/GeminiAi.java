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

// Sends a question to Google Gemini and returns the answer text.
// The key is typed into the app and saved on the phone only. It is never stored in this code.
public class GeminiAi {

    // Tried in order. If one model is not available, the next one is used.
    private static final String[] MODELS = {
            "gemini-2.5-flash",
            "gemini-3.5-flash",
            "gemini-3-flash-preview",
            "gemini-2.5-flash-lite",
            "gemini-flash-latest"
    };

    private static final String PREFS = "mahi";
    private static final String KEY_NAME = "gemini_key";

    interface Done {
        void onAnswer(String text);
    }

    // An error sent back by Google, with its status code (for example 404 or 503)
    static class HttpError extends Exception {
        final int code;

        HttpError(int code, String body) {
            super("HTTP " + code + " " + body);
            this.code = code;
        }
    }

    static String getKey(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, "");
    }

    static void saveKey(Context c, String key) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_NAME, key.trim()).apply();
    }

    // Runs in the background, then calls done with the answer
    static void ask(final Context c, final String question, final Done done) {
        new Thread(new Runnable() {
            public void run() {
                done.onAnswer(call(c, question));
            }
        }).start();
    }

    private static String call(Context c, String question) {
        String key = getKey(c);
        if (key.length() == 0) {
            return "Please add your Gemini key first. Tap the menu button at the top left.";
        }
        String lastProblem = "no model answered";
        for (String model : MODELS) {
            try {
                return clean(tryModel(model, key, question));
            } catch (HttpError e) {
                if (e.code == 400 || e.code == 401 || e.code == 403) {
                    return "Google rejected your key, error " + e.code
                            + ". Open the menu button and paste your key again.";
                }
                lastProblem = model + " gave error " + e.code;
            } catch (Exception e) {
                lastProblem = model + " failed: " + e.toString();
            }
        }
        return "Gemini did not answer. " + lastProblem;
    }

    private static String tryModel(String model, String key, String question) throws Exception {
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-goog-api-key", key);

            String prompt = "You are Mahi, a friendly voice assistant. Answer in one or two short plain sentences "
                    + "that are easy to hear. No markdown and no lists. Question: " + question;
            JSONObject part = new JSONObject().put("text", prompt);
            JSONObject content = new JSONObject().put("parts", new JSONArray().put(part));
            JSONObject body = new JSONObject().put("contents", new JSONArray().put(content));

            OutputStream os = conn.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String text = readAll(in);
            if (code != 200) {
                throw new HttpError(code, text);
            }
            JSONObject res = new JSONObject(text);
            JSONArray parts = res.getJSONArray("candidates").getJSONObject(0)
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

    private static String clean(String text) {
        String t = text.replace("*", "").replace("#", "").trim();
        if (t.length() > 500) {
            t = t.substring(0, 500);
        }
        return t;
    }
}
