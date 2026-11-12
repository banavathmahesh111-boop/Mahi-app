
package com.mahi.app;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private WebView webView;
    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private static final int MIC_REQUEST = 101;

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
        public void onAction(String name) {
        }
    }

    private void toggleMic() {
        if (listening) {
            stopMic();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC_REQUEST);
            return;
        }
        startMic();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == MIC_REQUEST) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                startMic();
            } else {
                showText("idle", "Mic permission needed");
            }
        }
    }

    private void startMic() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showText("idle", "Speech service not found on this phone");
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(new Listener());
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        listening = true;
        showText("listening", "Listening\u2026");
        recognizer.startListening(intent);
    }

    private void stopMic() {
        listening = false;
        if (recognizer != null) {
            recognizer.cancel();
        }
        showText("idle", "Waiting for you\u2026");
    }

    private void showText(String state, String text) {
        String js = "setState('" + state + "');"
                + "document.getElementById('sm').textContent=" + JSONObject.quote(text) + ";";
        webView.evaluateJavascript(js, null);
    }

    private String first(Bundle b) {
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
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
                webView.evaluateJavascript(
                        "document.getElementById('sm').textContent=" + JSONObject.quote(t) + ";", null);
            }
        }

        public void onResults(Bundle b) {
            listening = false;
            String t = first(b);
            if (t != null) {
                showText("idle", "You said: " + t);
            } else {
                showText("idle", "Didn't catch that");
            }
        }

        public void onError(int error) {
            boolean was = listening;
            listening = false;
            if (!was) {
                return;
            }
            String msg;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    msg = "Didn't catch that. Tap and try again";
                    break;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    msg = "Mic permission needed";
                    break;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    msg = "Voice needs internet";
                    break;
                case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                    msg = "Mic is busy, try again";
                    break;
                default:
                    msg = "Mic error " + error;
            }
            showText("idle", msg);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        listening = false;
        if (recognizer != null) {
            recognizer.cancel();
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
        if (recognizer != null) {
            recognizer.destroy();
        }
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
