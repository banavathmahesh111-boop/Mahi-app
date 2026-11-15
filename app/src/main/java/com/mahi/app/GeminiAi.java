package com.mahi.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

// Sends a question to Google Gemini and returns the answer text.
// The key is typed into the app and saved on the phone only. It is never stored in this code.
public class GeminiAi {

    // If you get an error about the model, replace this with a model name from aistudio.google.com
    private static final String MODEL = "gemini-flash-latest";
    private static final String PREFS = "mahi";
    private static final String KEY_NAME = "gemini_key";

    interface Done {
        void onAnswer(String text);
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
            return "Please set your Gemini key first. Tap the menu button at the top left.";
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent");
            conn = (HttpURLConnection) url.openConnection();
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
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int n;
            while (in != null && (n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            if (code != 200) {
                return "The AI service gave error " + code + ". Check your key in the menu.";
            }
            JSONObject res = new JSONObject(sb.toString());
            String text = res.getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text");
            text = text.replace("*", "").trim();
            if (text.length() > 500) {
                text = text.substring(0, 500);
            }
            return text;
        } catch (Exception e) {
            return "I couldn't reach the AI service. Please check the internet connection.";
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
