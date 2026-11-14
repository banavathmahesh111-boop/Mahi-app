package com.mahi.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.view.KeyEvent;

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
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MahiService extends Service {

    public interface Ui {
        void show(String state, String text);
    }

    // Shared with the screen (MainActivity)
    static Ui ui;
    static String lastState = "idle";
    static String lastText = "Mahi is off. Tap the gear to turn on.";
    static boolean running = false;
    static MahiService inst;

    static final String ACTION_TALK = "com.mahi.app.TALK";
    static final String ACTION_STOP = "com.mahi.app.STOP";

    // The wake phrase. Change this one line (lowercase, real English words) to change it.
    private static final String WAKE_PHRASE = "hey buddy";
    private static final String WAKE_GRAMMAR = "[\"" + WAKE_PHRASE + "\", \"[unk]\"]";
    private static final String VOSK_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-in-0.4.zip";
    private static final String VOSK_DIR = "vosk-model-small-en-in-0.4";
    private static final String CHANNEL = "mahi_listening";
    private static final int NOTE_ID = 7;

    // Popular apps: spoken name (lowercase, no spaces) -> Play Store package
    private static final String[][] KNOWN_APPS = {
            {"whatsapp", "com.whatsapp"},
            {"instagram", "com.instagram.android"},
            {"facebook", "com.facebook.katana"},
            {"messenger", "com.facebook.orca"},
            {"youtube", "com.google.android.youtube"},
            {"youtubemusic", "com.google.android.apps.youtube.music"},
            {"spotify", "com.spotify.music"},
            {"telegram", "org.telegram.messenger"},
            {"snapchat", "com.snapchat.android"},
            {"twitter", "com.twitter.android"},
            {"x", "com.twitter.android"},
            {"netflix", "com.netflix.mediaclient"},
            {"flipkart", "com.flipkart.android"},
            {"phonepe", "com.phonepe.app"},
            {"googlepay", "com.google.android.apps.nbu.paisa.user"},
            {"paytm", "net.one97.paytm"},
            {"zomato", "com.application.zomato"},
            {"swiggy", "in.swiggy.android"},
            {"chrome", "com.android.chrome"},
            {"gmail", "com.google.android.gm"},
            {"googlemaps", "com.google.android.apps.maps"},
            {"maps", "com.google.android.apps.maps"},
            {"hotstar", "in.startv.hotstar"},
            {"truecaller", "com.truecaller"},
            {"linkedin", "com.linkedin.android"},
            {"discord", "com.discord"},
            {"reddit", "com.reddit.frontpage"},
            {"pinterest", "com.pinterest"},
            {"zoom", "us.zoom.videomeetings"},
            {"googlemeet", "com.google.android.apps.tachyon"},
            {"uber", "com.ubercab"},
            {"ola", "com.olacabs.customer"},
            {"canva", "com.canva.editor"},
            {"duolingo", "com.duolingo"},
            {"vlc", "org.videolan.vlc"},
            {"chatgpt", "com.openai.chatgpt"},
            {"gemini", "com.google.android.apps.bard"}
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Model model;
    private SpeechService voskSvc;
    private SpeechRecognizer recognizer;
    private TextToSpeech tts;
    private boolean ttsReady = false;
    private boolean speaking = false;
    private boolean commandOn = false;
    private boolean loading = false;
    private boolean downloading = false;
    private boolean musicWasPlaying = false;
    private boolean sleepAfter = false;
    private String lastReply = "";

    private final Runnable wakeTask = new Runnable() {
        public void run() {
            startWake();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Mahi listening",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        initTts();
    }

    // Lets other parts of Mahi (like the Install tapper) speak
    static void say(final String text) {
        final MahiService s = inst;
        if (s == null) {
            return;
        }
        s.handler.post(new Runnable() {
            public void run() {
                s.reply(text, null, 0);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            startForegroundNow();
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        if (ACTION_TALK.equals(action)) {
            talkNow();
        } else {
            scheduleWake(200);
        }
        return START_STICKY;
    }

    private void startForegroundNow() {
        int pf = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, pf);
        Intent talk = new Intent(this, MahiService.class);
        talk.setAction(ACTION_TALK);
        PendingIntent talkPi = PendingIntent.getService(this, 1, talk, pf);
        Intent stop = new Intent(this, MahiService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop, pf);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Mahi is on")
                .setContentText("Say \"" + WAKE_PHRASE + "\"")
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(android.R.drawable.ic_btn_speak_now, "Talk", talkPi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Turn off", stopPi)
                .build();
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTE_ID, n);
        }
    }

    // ---------- Talking to the screen ----------

    private void push(final String state, final String text) {
        handler.post(new Runnable() {
            public void run() {
                lastState = state;
                if (text != null) {
                    lastText = text;
                }
                if (ui != null) {
                    ui.show(state, text);
                }
            }
        });
    }

    // ---------- Voice out ----------

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
        handler.post(new Runnable() {
            public void run() {
                speaking = false;
                if (commandOn) {
                    return;
                }
                push("idle", lastReply);
                if (sleepAfter) {
                    stopSelf();
                    return;
                }
                if (musicWasPlaying) {
                    resumeMusic();
                }
                scheduleWake(600);
            }
        });
    }

    private void reply(String text, Runnable action, long delayMs) {
        lastReply = text;
        push("speaking", text);
        if (ttsReady) {
            speaking = true;
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "mahi");
        } else {
            finishSpeaking();
        }
        if (action != null) {
            handler.postDelayed(action, delayMs);
        }
    }

    // ---------- Waiting for the wake phrase (offline) ----------

    private void scheduleWake(long ms) {
        handler.removeCallbacks(wakeTask);
        handler.postDelayed(wakeTask, ms);
    }

    private void startWake() {
        if (!running || voskSvc != null || loading || commandOn || speaking) {
            return;
        }
        final File dir = new File(getFilesDir(), VOSK_DIR);
        if (!new File(dir, "am").isDirectory()) {
            downloadModel();
            return;
        }
        loading = true;
        if (model == null) {
            push("thinking", "Loading voice model\u2026");
        }
        new Thread(new Runnable() {
            public void run() {
                try {
                    if (model == null) {
                        model = new Model(dir.getAbsolutePath());
                    }
                    Recognizer rec = new Recognizer(model, 16000.0f, WAKE_GRAMMAR);
                    final SpeechService svc = new SpeechService(rec, 16000.0f);
                    handler.post(new Runnable() {
                        public void run() {
                            loading = false;
                            if (!running || commandOn || speaking || voskSvc != null) {
                                svc.shutdown();
                                return;
                            }
                            voskSvc = svc;
                            svc.startListening(new VoskListener());
                            if (lastReply.length() == 0) {
                                push("idle", "Say \"" + WAKE_PHRASE + "\"");
                            } else {
                                push("idle", null);
                            }
                        }
                    });
                } catch (final Exception e) {
                    handler.post(new Runnable() {
                        public void run() {
                            loading = false;
                            push("idle", "Voice error: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void stopVosk() {
        handler.removeCallbacks(wakeTask);
        if (voskSvc != null) {
            voskSvc.stop();
            voskSvc.shutdown();
            voskSvc = null;
        }
    }

    private class VoskListener implements org.vosk.android.RecognitionListener {
        public void onPartialResult(String h) {
            check(h, "partial");
        }

        public void onResult(String h) {
            check(h, "text");
        }

        public void onFinalResult(String h) {
            check(h, "text");
        }

        public void onError(Exception e) {
            push("idle", "Voice error: " + e.getMessage());
        }

        public void onTimeout() {
        }
    }

    private void check(String json, String key) {
        if (commandOn || voskSvc == null) {
            return;
        }
        try {
            String t = new JSONObject(json).optString(key, "").replace("[unk]", "").trim();
            if (t.contains(WAKE_PHRASE)) {
                handler.post(new Runnable() {
                    public void run() {
                        onWake();
                    }
                });
            }
        } catch (Exception e) {
            // ignore
        }
    }

    private void onWake() {
        if (commandOn || voskSvc == null) {
            return;
        }
        stopVosk();
        if (musicPlaying()) {
            musicWasPlaying = true;
        }
        commandOn = true;
        handler.postDelayed(new Runnable() {
            public void run() {
                startCommand();
            }
        }, 250);
    }

    // Tap on the Talk button: skip the wake phrase
    private void talkNow() {
        if (commandOn) {
            return;
        }
        stopVosk();
        if (musicPlaying()) {
            musicWasPlaying = true;
        }
        commandOn = true;
        startCommand();
    }

    // ---------- Listening for the command (Google speech) ----------

    private void startCommand() {
        if (!commandOn) {
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            commandOn = false;
            push("idle", "Speech service not found on this phone");
            scheduleWake(1500);
            return;
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
        push("listening", "Listening\u2026");
        recognizer.startListening(intent);
    }

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
                push("listening", t);
            }
        }

        public void onResults(Bundle b) {
            if (!commandOn) {
                return;
            }
            commandOn = false;
            String t = first(b);
            if (t != null && t.trim().length() > 0) {
                push("thinking", "You said: " + t);
                handle(t);
            } else {
                noSpeech("Didn't catch that");
            }
        }

        public void onError(int error) {
            if (!commandOn) {
                return;
            }
            commandOn = false;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    noSpeech("Didn't catch that");
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    noSpeech("Mic is busy, try again");
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    noSpeech("Mic permission needed");
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    noSpeech("Voice needs internet");
                    break;
                default:
                    noSpeech("Mic error " + error);
            }
        }
    }

    // Nothing was understood: give the music back and go back to waiting
    private void noSpeech(String msg) {
        if (musicWasPlaying) {
            resumeMusic();
        }
        push("idle", msg);
        scheduleWake(1200);
    }

    private String first(Bundle b) {
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    // ---------- Mahi's brain (simple commands) ----------

    // Drops anything said before the command word, e.g. "ye badi open whatsapp" -> "open whatsapp"
    private String cutToCommand(String t) {
        String[] verbs = {"open ", "launch ", "start ", "play ", "install ", "download ", "stop", "pause",
                "resume", "continue", "next", "skip", "previous", "what time", "what date", "what day",
                "today's date", "go to sleep", "hello", "sleep", "mic off"};
        int best = -1;
        for (String v : verbs) {
            int i = t.indexOf(v);
            while (i >= 0 && i > 0 && t.charAt(i - 1) != ' ') {
                i = t.indexOf(v, i + 1);
            }
            if (i >= 0 && (best < 0 || i < best)) {
                best = i;
            }
        }
        return best > 0 ? t.substring(best).trim() : t;
    }

    private void handle(String said) {
        String t = cutToCommand(said.toLowerCase(Locale.ROOT).trim());
        String w = t.split(" ")[0];
        if (t.contains("stop listening") || t.contains("go to sleep") || t.contains("mic off")
                || t.equals("sleep")) {
            sleepAfter = true;
            reply("Okay, going to sleep. Open Mahi to wake me.", null, 0);
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
            if (blocked()) {
                return;
            }
            String name = clean(t.substring(t.indexOf(' ') + 1),
                    " from play store", " on play store", " in play store", " app");
            installApp(name);
        } else if (t.contains("play store")) {
            if (blocked()) {
                return;
            }
            reply("Opening Play Store.", later("https://play.google.com/store", "com.android.vending"), 1800);
        } else if (t.startsWith("open ") || t.startsWith("launch ") || t.startsWith("start ")) {
            if (blocked()) {
                return;
            }
            String name = clean(t.substring(t.indexOf(' ') + 1), " app");
            String pkg = findApp(name);
            if (pkg != null) {
                reply("Opening " + name + ".", launch(pkg), 1000);
            } else {
                reply("I couldn't find " + name + " on this phone.", null, 0);
            }
        } else if (t.startsWith("play ")) {
            if (blocked()) {
                return;
            }
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

    // Opening apps from the background needs the "Appear on top" permission
    private boolean blocked() {
        if (ui != null || Settings.canDrawOverlays(this)) {
            return false;
        }
        reply("I need the Appear on top permission to open apps from the background. "
                + "Please allow it for Mahi in Settings.", null, 0);
        return true;
    }

    // ---------- Installing apps ----------

    private String knownPackage(String spoken) {
        String n = norm(spoken);
        for (String[] a : KNOWN_APPS) {
            if (a[0].equals(n)) {
                return a[1];
            }
        }
        return null;
    }

    private void installApp(String name) {
        String pkg = knownPackage(name);
        if (pkg != null && getPackageManager().getLaunchIntentForPackage(pkg) != null) {
            reply(name + " is already installed.", null, 0);
            return;
        }
        String url = pkg != null
                ? "market://details?id=" + pkg
                : "market://search?q=" + Uri.encode(name) + "&c=apps";
        if (MahiAccess.instance == null) {
            reply("Opening Play Store for " + name + ". To let me tap Install for you, "
                    + "turn on Mahi in Accessibility settings.", later(url, "com.android.vending"), 2500);
            return;
        }
        MahiAccess.instance.startInstall(name, pkg == null);
        reply("Okay, installing " + name + ".", later(url, "com.android.vending"), 1500);
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
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                        return;
                    }
                } catch (Exception e) {
                    // fall through
                }
                push("idle", "I couldn't open that");
            }
        };
    }

    // ---------- Music ----------

    private boolean musicPlaying() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        return !speaking && am.isMusicActive();
    }

    private void resumeMusic() {
        musicWasPlaying = false;
        handler.postDelayed(media(KeyEvent.KEYCODE_MEDIA_PLAY), 300);
    }

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

    // Plays the song: first through YouTube Music / Spotify, otherwise the first YouTube result
    private Runnable playSong(final String song, final boolean spotifyFirst) {
        return new Runnable() {
            public void run() {
                String[] apps = spotifyFirst
                        ? new String[]{"com.spotify.music", "com.google.android.apps.youtube.music"}
                        : new String[]{"com.google.android.apps.youtube.music", "com.spotify.music"};
                for (String pkg : apps) {
                    Intent i = new Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
                    i.setPackage(pkg);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    i.putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*");
                    i.putExtra(SearchManager.QUERY, song);
                    try {
                        startActivity(i);
                        return;
                    } catch (Exception e) {
                        // try the next music app
                    }
                }
                new Thread(new Runnable() {
                    public void run() {
                        final String id = findVideoId(song);
                        handler.post(new Runnable() {
                            public void run() {
                                if (id != null) {
                                    playVideo(id);
                                } else {
                                    openUrl("https://www.youtube.com/results?search_query=" + Uri.encode(song),
                                            "com.google.android.youtube");
                                    push("idle", "I couldn't find that song. Tap a result in YouTube.");
                                }
                            }
                        });
                    }
                }).start();
            }
        };
    }

    // Reads YouTube's search page and returns the first video id (or null)
    private String findVideoId(String query) {
        HttpURLConnection c = null;
        try {
            String u = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
                    + "&sp=EgIQAQ%253D%253D";
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            InputStream in = c.getInputStream();
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0 && total < 2000000) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                total += n;
            }
            in.close();
            Matcher m = Pattern.compile("\"videoId\":\"([A-Za-z0-9_-]{11})\"").matcher(sb);
            if (m.find()) {
                return m.group(1);
            }
        } catch (Exception e) {
            // fall through
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
        return null;
    }

    private void playVideo(String id) {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:" + id));
        i.setPackage("com.google.android.youtube");
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            openUrl("https://www.youtube.com/watch?v=" + id, null);
        }
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
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            try {
                Intent j = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                j.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(j);
            } catch (Exception e2) {
                push("idle", "I couldn't open that");
            }
        }
    }

    // ---------- Offline voice model download ----------

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
                            push("thinking", "Downloading voice model " + pct + "%");
                        }
                    }
                    out.close();
                    in.close();
                    push("thinking", "Unpacking\u2026");
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
                    handler.post(new Runnable() {
                        public void run() {
                            scheduleWake(100);
                        }
                    });
                } catch (Exception e) {
                    downloading = false;
                    push("idle", "Download failed: " + e.getMessage());
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

    @Override
    public void onDestroy() {
        running = false;
        inst = null;
        handler.removeCallbacksAndMessages(null);
        stopVosk();
        if (recognizer != null) {
            recognizer.destroy();
        }
        if (tts != null) {
            tts.shutdown();
        }
        lastState = "idle";
        lastText = "Mahi is off. Tap the gear to turn on.";
        if (ui != null) {
            ui.show(lastState, lastText);
        }
        super.onDestroy();
    }
}
