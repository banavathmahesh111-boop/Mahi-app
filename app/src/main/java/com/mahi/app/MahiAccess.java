package com.mahi.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.Locale;

// Taps "Install" in the Play Store for Mahi (the settings file only allows the Play Store)
public class MahiAccess extends AccessibilityService {

    static MahiAccess instance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private String label = null;
    private boolean searchMode = false;
    private boolean pressedResult = false;
    private int tries = 0;
    private String lastPkg = "";

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
        lastPkg = "";
        h.removeCallbacks(task);
        h.postDelayed(task, 4000);
    }

    private void step() {
        if (label == null) {
            return;
        }
        tries++;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String pkg = (root != null && root.getPackageName() != null) ? root.getPackageName().toString() : "";
        lastPkg = pkg;
        if ("com.android.vending".equals(pkg)) {
            if (searchMode && !pressedResult) {
                if (clickResult(root)) {
                    pressedResult = true;
                }
            } else {
                int r = findInstall(root);
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
        if (tries < 40) {
            h.postDelayed(task, 1000);
        } else {
            if ("com.android.vending".equals(lastPkg)) {
                finish("I found the Play Store but not the Install button. Please tap it yourself.");
            } else {
                finish("The Play Store did not open. Please open it and tap Install yourself.");
            }
        }
    }

    private void finish(String message) {
        label = null;
        MahiService.say(message);
    }

    // Looks through the whole page. Returns 1 if Install was tapped, 2 if the app is already installed, 0 if neither.
    private int findInstall(AccessibilityNodeInfo root) {
        boolean openFound = false;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen < 800) {
            AccessibilityNodeInfo n = q.poll();
            seen++;
            String t = textOf(n);
            if ("install".equals(t)) {
                if (tap(n)) {
                    return 1;
                }
            } else if ("open".equals(t) || "uninstall".equals(t)) {
                openFound = true;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) {
                    q.add(c);
                }
            }
        }
        return openFound ? 2 : 0;
    }

    // Search mode: tap the first result that matches the app name (skips the search box at the top)
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
                if (!isSearchBar && tap(n)) {
                    return true;
                }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) {
                    q.add(c);
                }
            }
        }
        return false;
    }

    private String textOf(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) {
            t = n.getContentDescription();
        }
        return (t == null) ? "" : t.toString().trim().toLowerCase(Locale.ROOT);
    }

    // Taps a button: clicks it, or the nearest clickable parent, or taps its position on the screen
    private boolean tap(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; i < 6 && c != null; i++) {
            if (c.isClickable() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true;
            }
            c = c.getParent();
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (r.width() <= 0 || r.height() <= 0) {
            return false;
        }
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 100))
                .build();
        return dispatchGesture(g, null, null);
    }

    private String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
