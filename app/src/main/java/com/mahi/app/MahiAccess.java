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

// Taps the app in the Play Store search results, then taps "Install" on its page.
// Only the Play Store is allowed (see mahi_access.xml).
public class MahiAccess extends AccessibilityService {

    static MahiAccess instance;

    private final Handler h = new Handler(Looper.getMainLooper());
    private String label = null;   // what Mahi says back, e.g. "x.com"
    private String want = null;    // the name to look for, e.g. "x"
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

    // appName: what you said. search: true when we opened a search instead of the app's page
    void startInstall(String appName, boolean search) {
        label = appName;
        want = cleanName(appName);
        searchMode = search;
        pressedResult = false;
        tries = 0;
        lastPkg = "";
        h.removeCallbacks(task);
        h.postDelayed(task, 3000);
    }

    // "x.com" -> "x", "Instagram app" -> "instagram"
    private String cleanName(String s) {
        String t = s.toLowerCase(Locale.ROOT).trim();
        t = t.replace(".com", "").replace(".in", "").replace(".app", "").replace(" app", "");
        return t.replaceAll("[^a-z0-9]", "");
    }

    private void step() {
        if (label == null) {
            return;
        }
        tries++;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        lastPkg = (root != null && root.getPackageName() != null) ? root.getPackageName().toString() : "";
        if ("com.android.vending".equals(lastPkg)) {
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
        if (tries < 60) {
            h.postDelayed(task, 1000);
        } else if ("com.android.vending".equals(lastPkg)) {
            finish("I found the Play Store but couldn't tap Install. Please tap it yourself.");
        } else {
            finish("The Play Store did not open. Please open it and tap Install yourself.");
        }
    }

    private void finish(String message) {
        label = null;
        MahiService.say(message);
    }

    // Looks through the app's page. 1 = Install tapped, 2 = already installed, 0 = not found yet
    private int findInstall(AccessibilityNodeInfo root) {
        if (root == null) {
            return 0;
        }
        boolean installed = false;
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
        q.add(root);
        int seen = 0;
        while (!q.isEmpty() && seen < 1000) {
            AccessibilityNodeInfo n = q.poll();
            seen++;
            String t = textOf(n);
            if (t.equals("install") && n.isVisibleToUser()) {
                if (tap(n)) {
                    return 1;
                }
            } else if (t.equals("open") || t.equals("uninstall")) {
                installed = true;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) {
                    q.add(c);
                }
            }
        }
        return installed ? 2 : 0;
    }

    // Search results: first looks for an exact title (like "X"), then a short title that contains the name.
    // The search box and the top of the screen are skipped.
    private boolean clickResult(AccessibilityNodeInfo root) {
        if (root == null || want == null || want.length() == 0) {
            return false;
        }
        int minTop = (int) (130 * getResources().getDisplayMetrics().density);
        for (int pass = 1; pass <= 2; pass++) {
            if (pass == 2 && want.length() < 4) {
                break;
            }
            ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<>();
            q.add(root);
            int seen = 0;
            Rect r = new Rect();
            while (!q.isEmpty() && seen < 800) {
                AccessibilityNodeInfo n = q.poll();
                seen++;
                CharSequence t = n.getText();
                if (t != null && !n.isEditable()) {
                    String key = t.toString().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
                    boolean match = (pass == 1)
                            ? key.equals(want)
                            : (key.contains(want) && key.length() <= want.length() + 15);
                    if (match) {
                        n.getBoundsInScreen(r);
                        String id = n.getViewIdResourceName();
                        boolean searchBar = (id != null && id.toLowerCase(Locale.ROOT).contains("search"))
                                || r.top < minTop;
                        if (!searchBar && tap(n)) {
                            return true;
                        }
                    }
                }
                for (int i = 0; i < n.getChildCount(); i++) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) {
                        q.add(c);
                    }
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

    // Taps a button: clicks it, or its nearest clickable parent, or taps its position on the screen
    private boolean tap(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; i < 8 && c != null; i++) {
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
}
