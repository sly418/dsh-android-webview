package com.deepseek.dshwebview;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Lightweight persistent state used to survive process recreation (e.g. the
 * service being killed by the system while the app is in the background).
 * Stores only non-sensitive launch metadata — never API keys or user data.
 */
public final class AppState {

    private static final String PREFS = "dshwebview-state";
    private static final String KEY_LAST_URL = "last_url";
    private static final String KEY_LAUNCH_MODE = "launch_mode";
    private static final String KEY_LAST_STATE = "last_state";
    private static final String KEY_LAST_ERROR = "last_error";
    private static final String KEY_CRASH_COUNT = "crash_count";

    private AppState() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveUrl(Context c, String url) {
        if (url == null) return;
        prefs(c).edit().putString(KEY_LAST_URL, url).apply();
    }

    public static String getLastUrl(Context c) {
        return prefs(c).getString(KEY_LAST_URL, null);
    }

    public static void clearUrl(Context c) {
        prefs(c).edit().remove(KEY_LAST_URL).apply();
    }

    public static void saveMode(Context c, String mode) {
        prefs(c).edit().putString(KEY_LAUNCH_MODE, mode).apply();
    }

    public static String getMode(Context c) {
        return prefs(c).getString(KEY_LAUNCH_MODE, "");
    }

    public static void saveState(Context c, String state, String error) {
        prefs(c).edit()
                .putString(KEY_LAST_STATE, state)
                .putString(KEY_LAST_ERROR, error == null ? "" : error)
                .apply();
    }

    public static String getLastState(Context c) {
        return prefs(c).getString(KEY_LAST_STATE, "");
    }

    public static String getLastError(Context c) {
        return prefs(c).getString(KEY_LAST_ERROR, "");
    }

    /** Increment a counter each time the service starts; reset on clean READY. */
    public static void noteLaunchAttempt(Context c) {
        SharedPreferences p = prefs(c);
        p.edit().putInt(KEY_CRASH_COUNT, p.getInt(KEY_CRASH_COUNT, 0) + 1).apply();
    }

    public static void clearLaunchAttempts(Context c) {
        prefs(c).edit().putInt(KEY_CRASH_COUNT, 0).apply();
    }

    public static int getLaunchAttempts(Context c) {
        return prefs(c).getInt(KEY_CRASH_COUNT, 0);
    }
}
