package com.mahi.app;

import android.Manifest;
import android.app.Activity;
import android.app.SearchManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private WebView webView;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean speaking = false;
    private boolean listening = false;
    private boolean autoListen = false;
    private boolean inForeground = false;
    private boolean musicWasPlaying = false;
    private String lastReply = "";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int MIC_REQUEST = 101;
    private static final int WAKE_REQUEST = 102;

    // Offline voice model (used by the wake word test)
    private static final String VOSK_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip";
    private static final String VOSK_DIR = "vosk-model-small-en-in-0.4";
    // The wake phrase. Change this one line (lowercase, real English words) to change it.
    private static final String WAKE_PHRASE = "hey buddy";
    // The model only listens for the wake phrase, and treats everything else as unknown
    private static final String WAKE_GRAMMAR = "[\"" + WAKE_PHRASE + "\", \"[unk]\"]";
    private Model voskModel;
    private SpeechService voskService;
    private boolean wakeTest = false;
    private boolean downloading = false;
    private int wakeHits = 0;

    // Starts listening again by itself (only while Mahi is on screen and no music is playing)
    private final Runnable restartTask = new Runnable() {
        public void run() {
            if (autoListen && inForeground && !listening && !musicPlaying()) {
                startMic(true);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
        initTts();
    }

    private void initTts() {
        tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            public void onInit(int status) {
                if (status != TextToSpeech.SUCCESS) {
                    return;
                }
                int r = tts.setLanguage(Locale.getDefault());
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts.setLanguage(Locale.US);
                }
                tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    public void onStart(String id) {
                    }

                    public void onDone(String id) {
                        finishSpeaking();
                    }

                    public void onError(String id) {
                        finishSpeaking();
                    }
                });
                ttsReady = true;
            }
        });
    }

    private void finishSpeaking() {
        runOnUiThread(new Runnable() {
            public void run() {
                speaking = false;
                if (listening || wakeTest) {
                    return;
                }
                showText("idle", lastReply);
                afterReply(500);
            }
        });
    }

    // After Mahi finishes talking: give the music back, or listen again
    private void afterReply(long restartMs) {
        if (musicWasPlaying) {
            resumeMusic();
        } else {
            scheduleRestart(restartMs);
        }
    }

    private void resumeMusic() {
        musicWasPlaying = false;
        handler.postDelayed(media(KeyEvent.KEYCODE_MEDIA_PLAY), 300);
    }

    private boolean musicPlaying() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        return !speaking && am.isMusicActive();
    }

    private void scheduleRestart(long ms) {
        handler.removeCallbacks(restartTask);
        if (autoListen) {
            handler.postDelayed(restartTask, ms);
        }
    }

    // Lets the page talk to the app
    private class Bridge {
        @JavascriptInterface
        public void onMic() {
            runOnUiThread(new Runnable() {
                public void run() {
                    toggleMic();
                }
            });
        }

        @JavascriptInterface
        public void onAction(final String name) {
            if ("settings".equals(name)) {
                runOnUiThread(new Runnable() {
                    public void run() {
                        toggleWakeTest();
                    }
                });
            }
        }
    }

    private void toggleMic() {
        if (wakeTest) {
            stopWakeTest();
        }
        if (autoListen) {
            autoListen = false;
            stopMic();
            return;
        }
        autoListen = true;
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            return;
        }
        startMic(false);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        boolean granted = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        if (code == MIC_REQUEST) {
            if (granted) {
                startMic(false);
            } else {
                autoListen = false;
                showText("idle", "Mic permission needed");
            }
        } else if (code == WAKE_REQUEST) {
            if (granted) {
                toggleWakeTest();
            } else {
                showText("idle", "Mic permission needed");
            }
        }
    }

    private void startMic(boolean keepText) {
        if (listening) {
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            autoListen = false;
            showText("idle", "Speech service not found on this phone");
            return;
        }
        if (musicPlaying()) {
            musicWasPlaying = true;
        }
        if (tts != null) {
            tts.stop();
        }
        speaking = false;
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new Listener());
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        listening = true;
        if (keepText) {
            showState("listening", "LISTENING");
        } else {
            showText("listening", "Listening\u2026");
        }
        recognizer.startListening(intent);
    }

    private void stopMic() {
        handler.removeCallbacks(restartTask);
        listening = false;
        speaking = false;
        lastReply = "Mic is off. Tap to start.";
        if (tts != null) {
            tts.stop();
        }
        if (recognizer != null) {
            recognizer.cancel();
        }
        showText("idle", lastReply);
        if (musicWasPlaying) {
            resumeMusic();
        }
    }

    private void showText(String state, String text) {
        String js = "setState('" + state + "');"
                + "document.getElementById('sm').textContent=" + JSONObject.quote(text) + ";";
        webView.evaluateJavascript(js, null);
    }

    private void postText(final String state, final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                showText(state, text);
            }
        });
    }

    // Changes only the status label and colour, and keeps the text on screen
    private void showState(String state, String label) {
        webView.evaluateJavascript("document.body.dataset.state='" + state + "';"
                + "document.getElementById('sl').textContent='" + label + "';", null);
    }

    private String first(Bundle b) {
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    // ---------- Wake word test (offline, tap the gear icon) ----------

    private void toggleWakeTest() {
        if (wakeTest) {
            stopWakeTest();
            return;
        }
        if (downloading) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, WAKE_REQUEST);
            return;
        }
        autoListen = false;
        if (listening) {
            stopMic();
        }
        wakeTest = true;
        wakeHits = 0;
        startWakeTest();
    }

    private void startWakeTest() {
        final File dir = new File(getFilesDir(), VOSK_DIR);
        if (!new File(dir, "am").isDirectory()) {
            downloadModel();
            return;
        }
        postText("thinking", "Loading voice model\u2026");
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (voskModel == null) {
                        voskModel = new Model(dir.getAbsolutePath());
                    }
                    Recognizer rec = new Recognizer(voskModel, 16000.0f, WAKE_GRAMMAR);
                    final SpeechService svc = new SpeechService(rec, 16000.0f);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (!wakeTest) {
                                svc.shutdown();
                                return;
                            }
                            voskService = svc;
                            svc.startListening(new VoskListener());
                            showText("listening", "Say: " + WAKE_PHRASE);
                        }
                    });
                } catch (Exception e) {
                    wakeTest = false;
                    postText("idle", "Voice test error: " + e.getMessage());
                }
            }
        }).start();
    }

    private void stopWakeTest() {
        wakeTest = false;
        if (voskService != null) {
            voskService.stop();
            voskService.shutdown();
            voskService = null;
        }
        showText("idle", "Voice test stopped. Matches: " + wakeHits);
    }

    private class VoskListener implements org.vosk.android.RecognitionListener {
        public void onPartialResult(String h) {
            heard(h, "partial");
        }

        public void onResult(String h) {
            heard(h, "text");
        }

        public void onFinalResult(String h) {
            heard(h, "text");
        }

        public void onError(Exception e) {
            postText("idle", "Voice test error: " + e.getMessage());
        }

        public void onTimeout() {
        }
    }

    private void heard(final String json, final String key) {
        runOnUiThread(new Runnable() {
            public void run() {
                if (!wakeTest) {
                    return;
                }
                String t;
                try {
                    t = new JSONObject(json).optString(key, "");
                } catch (Exception e) {
                    return;
                }
                if (t.length() == 0) {
                    return;
                }
                boolean wake = looksLikeWake(t);
                if (wake && "text".equals(key)) {
                    wakeHits++;
                }
                String shown = t.replace("[unk]", "(other)");
                showText("listening", "Heard: " + shown + (wake ? "  \u2705 " + wakeHits : ""));
            }
        });
    }

    private boolean looksLikeWake(String text) {
        String t = text.toLowerCase(Locale.ROOT).replace("[unk]", "").trim();
        return t.contains(WAKE_PHRASE);
    }

    private void downloadModel() {
        if (downloading) {
            return;
        }
        downloading = true;
        new Thread(new Runnable() {
            public void run() {
                try {
                    File zip = new File(getCacheDir(), "model.zip");
                    HttpURLConnection c = (HttpURLConnection) new URL(VOSK_URL).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    int total = c.getContentLength();
                    InputStream in = c.getInputStream();
                    FileOutputStream out = new FileOutputStream(zip);
                    byte[] buf = new byte[65536];
                    long got = 0;
                    int n;
                    int lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : 0;
                        if (pct != lastPct) {
                            lastPct = pct;
                            postText("thinking", "Downloading voice model " + pct + "%");
                        }
                    }
                    out.close();
                    in.close();
                    postText("thinking", "Unpacking\u2026");
                    File tmp = new File(getFilesDir(), "vosk_tmp");
                    deleteDir(tmp);
                    unzip(zip, tmp);
                    File made = new File(tmp, VOSK_DIR);
                    File dest = new File(getFilesDir(), VOSK_DIR);
                    deleteDir(dest);
                    if (!made.renameTo(dest)) {
                        throw new IOException("Could not save the model");
                    }
                    deleteDir(tmp);
                    zip.delete();
                    downloading = false;
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (wakeTest) {
                                startWakeTest();
                            }
                        }
                    });
                } catch (Exception e) {
                    downloading = false;
                    wakeTest = false;
                    postText("idle", "Download failed: " + e.getMessage());
                }
            }
        }).start();
    }

    private void unzip(File zip, File outDir) throws IOException {
        outDir.mkdirs();
        String base = outDir.getCanonicalPath() + File.separator;
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)));
        try {
            ZipEntry e;
            byte[] buf = new byte[65536];
            while ((e = zin.getNextEntry()) != null) {
                File f = new File(outDir, e.getName());
                if (!f.getCanonicalPath().startsWith(base)) {
                    throw new IOException("Bad zip file");
                }
                if (e.isDirectory()) {
                    f.mkdirs();
                    continue;
                }
                f.getParentFile().mkdirs();
                FileOutputStream o = new FileOutputStream(f);
                int n;
                while ((n = zin.read(buf)) > 0) {
                    o.write(buf, 0, n);
                }
                o.close();
            }
        } finally {
            zin.close();
        }
    }

    private void deleteDir(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteDir(k);
                }
            }
        }
        f.delete();
    }

    // ---------- Mahi's brain (simple commands) ----------

    private void handle(String said) {
        String t = said.toLowerCase(Locale.ROOT).trim();
        String w = t.split(" ")[0];
        if (t.contains("stop listening") || t.contains("go to sleep") || t.contains("mic off")
                || t.equals("sleep")) {
            autoListen = false;
            reply("Okay, I'll sleep. Tap the mic to wake me.", null, 0);
        } else if (t.startsWith("stop") || t.startsWith("pause") || t.contains("stop the song")
                || t.contains("stop music") || t.contains("stop playing")) {
            musicWasPlaying = false;
            reply("Stopped.", media(KeyEvent.KEYCODE_MEDIA_PAUSE), 300);
        } else if (t.equals("play") || t.equals("play music") || t.startsWith("resume")
                || t.startsWith("continue")) {
            musicWasPlaying = false;
            reply("Resuming.", media(KeyEvent.KEYCODE_MEDIA_PLAY), 300);
        } else if (t.contains("next song") || t.contains("another song") || t.equals("next")
                || t.startsWith("skip")) {
            musicWasPlaying = false;
            reply("Next song.", media(KeyEvent.KEYCODE_MEDIA_NEXT), 300);
        } else if (t.contains("previous") || t.contains("last song")) {
            musicWasPlaying = false;
            reply("Previous song.", media(KeyEvent.KEYCODE_MEDIA_PREVIOUS), 300);
        } else if (t.startsWith("install ") || t.startsWith("download ")) {
            String app = clean(t.substring(t.indexOf(' ') + 1),
                    " from play store", " on play store", " in play store", " app");
            reply("Opening Play Store for " + app + ". Tap Install.",
                    later("market://search?q=" + Uri.encode(app) + "&c=apps", "com.android.vending"), 1800);
        } else if (t.contains("play store")) {
            reply("Opening Play Store.", later("https://play.google.com/store", "com.android.vending"), 1800);
        } else if (t.startsWith("open ") || t.startsWith("launch ") || t.startsWith("start ")) {
            String name = clean(t.substring(t.indexOf(' ') + 1), " app");
            String pkg = findApp(name);
            if (pkg != null) {
                reply("Opening " + name + ".", launch(pkg), 1000);
            } else {
                reply("I couldn't find " + name + " on this phone.", null, 0);
            }
        } else if (t.startsWith("play ")) {
            String song = clean(t.substring(5), " on youtube music", " on youtube", " in youtube",
                    " on you tube", " on spotify");
            musicWasPlaying = false;
            reply("Playing " + song + ".", playSong(song, t.contains("spotify")), 1200);
        } else if (t.equals("time") || t.contains("what time") || t.contains("the time")) {
            reply("It's " + new SimpleDateFormat("h:mm a", Locale.getDefault()).format(new Date()) + ".", null, 0);
        } else if (t.contains("what date") || t.contains("today's date") || t.contains("what day")) {
            reply("Today is " + new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(new Date()) + ".",
                    null, 0);
        } else if (w.equals("hello") || w.equals("hi") || w.equals("hey")) {
            reply("Hello Mahesh. How can I help you?", null, 0);
        } else {
            reply("I heard: " + said + ". I'm still learning that one.", null, 0);
        }
    }

    private String clean(String s, String... junk) {
        for (String j : junk) {
            s = s.replace(j, "");
        }
        return s.trim();
    }

    private void reply(String text, Runnable action, long delayMs) {
        lastReply = text;
        showText("speaking", text);
        if (ttsReady) {
            speaking = true;
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mahi");
        } else {
            showText("idle", text);
            afterReply(1500);
        }
        if (action != null) {
            handler.postDelayed(action, delayMs);
        }
    }

    // ---------- Opening apps ----------

    private String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    // Finds an installed app by the name you said
    private String findApp(String spoken) {
        String n = norm(spoken);
        if (n.length() < 2) {
            return null;
        }
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN);
        main.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(main, 0);
        String best = null;
        int bestScore = 0;
        int bestLen = 0;
        for (ResolveInfo ri : apps) {
            String l = norm(ri.loadLabel(pm).toString());
            if (l.length() < 2) {
                continue;
            }
            int score = 0;
            if (l.equals(n)) {
                score = 3;
            } else if (l.startsWith(n) || (l.length() >= 3 && n.startsWith(l))) {
                score = 2;
            } else if (n.length() >= 3 && l.contains(n)) {
                score = 1;
            }
            if (score > bestScore || (score == bestScore && score > 0 && l.length() < bestLen)) {
                best = ri.activityInfo.packageName;
                bestScore = score;
                bestLen = l.length();
            }
        }
        return best;
    }

    private Runnable launch(final String pkg) {
        return new Runnable() {
            public void run() {
                try {
                    Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (i != null) {
                        startActivity(i);
                        return;
                    }
                } catch (Exception e) {
                    // fall through
                }
                showText("idle", "I couldn't open that");
            }
        };
    }

    // ---------- Music ----------

    // Sends a play / pause / next / previous button press to whatever music app is active
    private Runnable media(final int keyCode) {
        return new Runnable() {
            public void run() {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                long now = SystemClock.uptimeMillis();
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
                am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
            }
        };
    }

    // Asks YouTube Music or Spotify to search for the song and start playing it
    private Runnable playSong(final String song, final boolean spotifyFirst) {
        return new Runnable() {
            public void run() {
                String[] apps = spotifyFirst
                        ? new String[]{"com.spotify.music", "com.google.android.apps.youtube.music"}
                        : new String[]{"com.google.android.apps.youtube.music", "com.spotify.music"};
                for (String pkg : apps) {
                    Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
                    i.setPackage(pkg);
                    i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
                    i.putExtra(SearchManager.QUERY, song);
                    try {
                        startActivity(i);
                        return;
                    } catch (Exception e) {
                        // try the next music app
                    }
                }
                openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(song),
                        "com.google.android.youtube");
                showText("idle", "No music app found. Tap a song in YouTube.");
            }
        };
    }

    private Runnable later(final String url, final String pkg) {
        return new Runnable() {
            public void run() {
                openUrl(url, pkg);
            }
        };
    }

    private void openUrl(String url, String pkg) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.setPackage(pkg);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e2) {
                showText("idle", "I couldn't open that");
            }
        }
    }

    // ---------- Listening ----------

    private class Listener implements RecognitionListener {
        public void onReadyForSpeech(Bundle b) {
        }

        public void onBeginningOfSpeech() {
        }

        public void onRmsChanged(float v) {
        }

        public void onBufferReceived(byte[] b) {
        }

        public void onEndOfSpeech() {
        }

        public void onEvent(int t, Bundle b) {
        }

        public void onPartialResults(Bundle b) {
            String t = first(b);
            if (t != null) {
                webView.evaluateJavascript(
                        "document.getElementById('sm').textContent=" + JSONObject.quote(t) + ";", null);
            }
        }

        public void onResults(Bundle b) {
            listening = false;
            String t = first(b);
            if (t != null && t.trim().length() > 0) {
                showText("thinking", "You said: " + t);
                handle(t);
            } else if (musicWasPlaying) {
                showText("idle", "Didn't catch that. Tap and try again");
                resumeMusic();
            } else {
                scheduleRestart(500);
            }
        }

        public void onError(int error) {
            boolean was = listening;
            listening = false;
            if (!was) {
                return;
            }
            String msg;
            boolean retry = false;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    msg = "Didn't catch that. Tap and try again";
                    retry = true;
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    msg = "Mic is busy. Tap and try again";
                    retry = true;
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    msg = "Mic permission needed";
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    msg = "Voice needs internet. Tap the mic to try again";
                    break;
                default:
                    msg = "Mic error " + error + ". Tap the mic to try again";
            }
            if (musicWasPlaying) {
                // Mahi paused the music to listen, so give it back
                resumeMusic();
                if (!retry) {
                    autoListen = false;
                }
                showText("idle", msg);
                return;
            }
            if (retry && autoListen) {
                scheduleRestart(error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ? 1000 : 300);
                return;
            }
            autoListen = false;
            showText("idle", msg);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        inForeground = true;
        scheduleRestart(600);
    }

    @Override
    protected void onPause() {
        super.onPause();
        inForeground = false;
        handler.removeCallbacks(restartTask);
        listening = false;
        if (recognizer != null) {
            recognizer.cancel();
        }
        if (wakeTest) {
            stopWakeTest();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (voskService != null) {
            voskService.shutdown();
        }
        if (recognizer != null) {
            recognizer.destroy();
        }
        if (tts != null) {
            tts.shutdown();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
