package com.mahi.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.provider.ContactsContract;
import android.telecom.TelecomManager;
import android.view.KeyEvent;

import java.util.Locale;

// Phone features by voice: call a contact, answer a ringing call, and decline or end a call.
public class MahiCalls {

    // Makes the call and returns the sentence Mahi should say
    static String call(Context c, String spoken) {
        String who = spoken.trim();
        if (who.length() == 0) {
            return "Who should I call?";
        }
        if (c.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "I need permission to make calls. Please allow Phone for Mahi in Settings.";
        }
        String digits = who.replaceAll("[^0-9+]", "");
        if (digits.length() >= 6) {
            return place(c, digits, digits);
        }
        if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "I need Contacts permission to find " + who + ". Please allow it for Mahi in Settings.";
        }
        String[] found = findContact(c, who);
        if (found == null) {
            return "I couldn't find " + who + " in your contacts.";
        }
        return place(c, found[1], found[0]);
    }

    // Answers a ringing call. Checks after a moment and reports the result by voice.
    static String answer(final Context c) {
        if (c.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return "I need the Phone permission to answer calls. Please allow Phone for Mahi in Settings.";
        }
        final AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        try {
            TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            tm.acceptRingingCall();
        } catch (Exception e) {
            // the headset button below is the backup
        }
        new Thread(new Runnable() {
            public void run() {
                pause(1500);
                if (am.getMode() == AudioManager.MODE_RINGTONE) {
                    pressHeadsetButton(c);
                    pause(1500);
                }
                int mode = am.getMode();
                if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) {
                    MahiService.say("Call connected.");
                } else {
                    MahiService.say("I couldn't connect the call. Please tap answer on the phone.");
                }
            }
        }).start();
        return "Answering the call.";
    }

    // Declines a call that is ringing, or ends the call that is running
    static String endCall(Context c) {
        if (c.checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
            return "I need the Phone permission to end calls. Please allow Phone for Mahi in Settings.";
        }
        try {
            TelecomManager tm = (TelecomManager) c.getSystemService(Context.TELECOM_SERVICE);
            if (tm.endCall()) {
                return "Call ended.";
            }
            return "There is no call to end.";
        } catch (Exception e) {
            return "I couldn't end the call.";
        }
    }

    // Presses the headset button, which answers calls on many Android phones
    private static void pressHeadsetButton(Context c) {
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            long now = android.os.SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK, 0));
        } catch (Exception e) {
            // nothing else to try
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // carry on
        }
    }

    // Looks for an exact name first, then a name that contains the spoken name
    private static String[] findContact(Context c, String name) {
        String want = name.toLowerCase(Locale.ROOT);
        String[] proj = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };
        Cursor cur = null;
        String[] partial = null;
        try {
            cur = c.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null);
            while (cur != null && cur.moveToNext()) {
                String dn = cur.getString(0);
                String num = cur.getString(1);
                if (dn == null || num == null) {
                    continue;
                }
                String l = dn.toLowerCase(Locale.ROOT);
                if (l.equals(want)) {
                    return new String[]{dn, num};
                }
                if (partial == null && l.contains(want)) {
                    partial = new String[]{dn, num};
                }
            }
        } catch (Exception e) {
            // could not read contacts
        } finally {
            if (cur != null) {
                cur.close();
            }
        }
        return partial;
    }

    private static String place(Context c, String number, String who) {
        Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + number));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            return "Calling " + who + ".";
        } catch (Exception e) {
            return "I couldn't start the call.";
        }
    }
}
