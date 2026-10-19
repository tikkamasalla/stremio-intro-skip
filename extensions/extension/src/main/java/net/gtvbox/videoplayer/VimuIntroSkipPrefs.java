package net.gtvbox.videoplayer;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * Settings for the Vimu intro skip. The switches and mode selector live in Vimu
 * Settings as preference rows in res/xml/prefs.xml, so they are stored in the
 * default SharedPreferences file next to Vimu's own settings. The Cinemeta title
 * cache lives in a dedicated file. Everything here fails soft: any problem
 * leaves the skip enabled in Auto mode with an empty cache.
 */
public final class VimuIntroSkipPrefs {
    public static final String KEY_ENABLED = "introskip_enabled";
    public static final String KEY_ANIME = "introskip_anime";
    public static final String KEY_MODE = "introskip_mode";
    public static final String KEY_RECAPS = "introskip_recaps";
    public static final String MODE_AUTO = "auto";
    public static final String MODE_MANUAL = "manual";

    private static final String CACHE_FILE = "vimu_introskip_cache";
    private static final long CACHE_TTL_MS = 60L * 24L * 60L * 60L * 1000L; // 60 days

    private VimuIntroSkipPrefs() {
    }

    private static Context contextOf(PlayerFragment fragment) {
        try {
            if (fragment == null) return null;
            Activity activity = fragment.getActivity();
            return activity;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    /** True unless the user turned the master "Skip intros" switch off in Vimu Settings. */
    public static boolean isEnabled(PlayerFragment fragment) {
        return isEnabled(contextOf(fragment));
    }

    public static boolean isEnabled(Context context) {
        try {
            if (context == null) return true;
            return prefs(context).getBoolean(KEY_ENABLED, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** True unless the user turned "Skip anime openings (AniSkip)" off in Vimu Settings. */
    public static boolean isAnimeEnabled(PlayerFragment fragment) {
        return isAnimeEnabled(contextOf(fragment));
    }

    public static boolean isAnimeEnabled(Context context) {
        try {
            if (context == null) return true;
            return prefs(context).getBoolean(KEY_ANIME, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** True unless the user turned "Skip recaps" off in Vimu Settings. */
    public static boolean isRecapsEnabled(PlayerFragment fragment) {
        return isRecapsEnabled(contextOf(fragment));
    }

    public static boolean isRecapsEnabled(Context context) {
        try {
            if (context == null) return true;
            return prefs(context).getBoolean(KEY_RECAPS, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** Current skip mode: "auto" (default) or "manual". */
    public static String mode(PlayerFragment fragment) {
        return mode(contextOf(fragment));
    }

    public static String mode(Context context) {
        try {
            if (context == null) return MODE_AUTO;
            String raw = prefs(context).getString(KEY_MODE, MODE_AUTO);
            return raw != null && MODE_MANUAL.equalsIgnoreCase(raw.trim()) ? MODE_MANUAL : MODE_AUTO;
        } catch (Throwable ignored) {
            return MODE_AUTO;
        }
    }

    public static boolean isManualMode(PlayerFragment fragment) {
        return MODE_MANUAL.equals(mode(fragment));
    }

    public static boolean isManualMode(Context context) {
        return MODE_MANUAL.equals(mode(context));
    }

    /** Stored Cinemeta hit for a "series:title" key, or "" (missing or older than 60 days). */
    public static String cachedImdb(Context context, String key) {
        try {
            if (context == null || key == null) return "";
            String raw = context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE)
                    .getString(key, "");
            int bar = raw == null ? -1 : raw.indexOf('|');
            if (bar <= 0) return "";
            long stored = Long.parseLong(raw.substring(bar + 1));
            if (System.currentTimeMillis() - stored > CACHE_TTL_MS) return "";
            return raw.substring(0, bar);
        } catch (Throwable ignored) {
            return "";
        }
    }

    /** Hits only; a miss is never stored. */
    public static void cacheImdb(Context context, String key, String imdb) {
        try {
            if (context == null || key == null || imdb == null || imdb.length() == 0) return;
            context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE).edit()
                    .putString(key, imdb + "|" + System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
            // Cache is optional.
        }
    }
}
