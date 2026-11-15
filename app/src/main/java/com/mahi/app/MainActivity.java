package com.mahi.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;

public class MainActivity extends Activity {
    private WebView webView;
    private boolean pageReady = false;
    private String pendingAction = null;
    private static final int PERM = 201;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        webView.addJavascriptInterface(new Bridge(), "Android");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                display(MahiService.lastState, MahiService.lastText);
            }
        });
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
        if (allGranted()) {
            startMahi(null);
        } else {
            requestPerms();
        }
    }

    // Lets the page talk to the app
    private class Bridge {
        @JavascriptInterface
        public void onMic() {
            runOnUiThread(new Runnable() {
                public void run() {
                    startMahi(MahiService.ACTION_TALK);
                }
            });
        }

        @JavascriptInterface
        public void onAction(final String name) {
            runOnUiThread(new Runnable() {
                public void run() {
                    if ("settings".equals(name)) {
                        toggleService();
                    } else if ("menu".equals(name)) {
                        showKeyDialog();
                    }
                }
            });
        }
    }

    // Opens a box to paste the Gemini key. It is saved only on this phone.
    private void showKeyDialog() {
        final EditText box = new EditText(this);
        box.setHint("Paste your Gemini API key");
        new AlertDialog.Builder(this)
                .setTitle("Gemini key")
                .setMessage("Paste your key from aistudio.google.com. It is saved only on this phone.")
                .setView(box)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int which) {
                        GeminiAi.saveKey(MainActivity.this, box.getText().toString());
                        Toast.makeText(MainActivity.this, "Key saved", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private boolean hasMic() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    // Permissions Mahi asks for: microphone, phone calls, contacts, and notifications on newer phones
    private String[] neededPerms() {
        ArrayList<String> list = new ArrayList<>();
        list.add(Manifest.permission.RECORD_AUDIO);
        list.add(Manifest.permission.CALL_PHONE);
        list.add(Manifest.permission.READ_CONTACTS);
        if (Build.VERSION.SDK_INT >= 33) {
            list.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        return list.toArray(new String[0]);
    }

    // True when the three core permissions are already allowed
    private boolean allGranted() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestPerms() {
        requestPermissions(neededPerms(), PERM);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM) {
            if (hasMic()) {
                startMahi(pendingAction);
            } else if (pageReady) {
                display("idle", "Mic permission needed");
            }
            pendingAction = null;
        }
    }

    private void startMahi(String action) {
        if (!hasMic()) {
            pendingAction = action;
            requestPerms();
            return;
        }
        Intent i = new Intent(this, MahiService.class);
        if (action != null) {
            i.setAction(action);
        }
        startForegroundService(i);
    }

    private void toggleService() {
        if (MahiService.running) {
            Intent i = new Intent(this, MahiService.class);
            i.setAction(MahiService.ACTION_STOP);
            startService(i);
        } else {
            startMahi(null);
        }
    }

    private String label(String state) {
        if ("listening".equals(state)) {
            return "LISTENING";
        } else if ("thinking".equals(state)) {
            return "THINKING";
        } else if ("speaking".equals(state)) {
            return "SPEAKING";
        }
        return "READY";
    }

    // Shows the state (and the text, unless it is null) on the dashboard
    private void display(String state, String text) {
        if (text == null) {
            webView.evaluateJavascript("document.body.dataset.state='" + state + "';"
                    + "document.getElementById('sl').textContent='" + label(state) + "';", null);
        } else {
            webView.evaluateJavascript("setState('" + state + "');"
                    + "document.getElementById('sm').textContent=" + JSONObject.quote(text) + ";", null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        MahiService.ui = new MahiService.Ui() {
            public void show(String state, String text) {
                if (pageReady) {
                    display(state, text);
                }
            }
        };
        if (pageReady) {
            display(MahiService.lastState, MahiService.lastText);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        MahiService.ui = null;
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
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
