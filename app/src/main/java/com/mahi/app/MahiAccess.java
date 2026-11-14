package com.mahi.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;

// Taps "Install" in the Play Store for Mahi (only the Play Store is allowed in the settings file)
public class MahiAccess extends AccessibilityService {

    static MahiAccess instance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private String label = null;
    private boolean searchMode = false;
    private boolean pressedResult = false;
    private int tries = 0;

    private final Runnable task = new Runnable() {
        public void run() {
            step();
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    // appName: what to say back. search: true when the app page was not opened directly
    void startInstall(String appName, boolean search) {
        label = appName;
        searchMode = search;
        pressedResult = false;
        tries = 0;
        h.removeCallbacks(task);
        h.postDelayed(task, 3000);
    }

    private void step() {
        if (label == null) {
            return;
        }
        tries++;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null
                && "com.android.vending".contentEquals(root.getPackageName())) {
            if (searchMode && !pressedResult) {
                if (clickResult(root)) {
                    pressedResult = true;
                }
            } else {
                int r = clickInstall(root);
                if (r == 1) {
                    finish("Tapped Install.");
                    return;
                }
                if (r == 2) {
                    finish(label + " is already installed.");
                    return;
                }
            }
        }
        if (tries < 30) {
            h.postDelayed(task, 800);
        } else {
            finish("I couldn't find the Install button. Please tap it yourself.");
        }
    }

    private void finish(String message) {
        label = null;
        MahiService.say(message);
    }

    private int clickInstall(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByText("Install");
        for (AccessibilityNodeInfo n : list) {
            if (isExact(n, "install") && clickUp(n)) {
                return 1;
            }
        }
        List<AccessibilityNodeInfo> open = root.findAccessibilityNodeInfosByText("Open");
        for (AccessibilityNodeInfo n : open) {
            if (isExact(n, "open")) {
                return 2;
            }
        }
        return 0;
    }

    // Search mode: tap the first app in the results that matches the name
    private boolean clickResult(AccessibilityNodeInfo root) {
        String want = norm(label);
        int minTop = (int) (130 * getResources().getDisplayMetrics().density);
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        Rect r = new Rect();
        while (!q.isEmpty() && seen < 600) {
            AccessibilityNodeInfo n = q.poll();
            seen++;
            CharSequence t = n.getText();
            if (t != null && !n.isEditable() && norm(t.toString()).contains(want)) {
                String cls = n.getClassName() != null ? n.getClassName().toString() : "";
                String id = n.getViewIdResourceName();
                n.getBoundsInScreen(r);
                boolean isSearchBar = cls.contains("EditText")
                        || (id != null && id.toLowerCase(Locale.ROOT).contains("search"))
                        || r.top < minTop;
                if (!isSearchBar && clickUp(n)) {
                    return true;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch != null) {
                    q.add(ch);
                }
            }
        }
        return false;
    }

    private boolean isExact(AccessibilityNodeInfo n, String word) {
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        return (t != null && word.equalsIgnoreCase(t.toString().trim()))
                || (d != null && word.equalsIgnoreCase(d.toString().trim()));
    }

    private boolean clickUp(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; i < 6 && c != null; i++) {
            if (c.isClickable()) {
                return c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            c = c.getParent();
        }
        return false;
    }

    private String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
