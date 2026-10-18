package net.gtvbox.videoplayer;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Settings for the Vimu intro skip. The on/off switch lives in Vimu Settings
 * as a plain CheckBoxPreference row, so it is stored in the default
 * SharedPreferences file next to Vimu's own settings. The Cinemeta title
 * cache lives in a dedicated file. Everything here fails soft: any problem
 * leaves the skip enabled with an empty cache.
 */
public final class VimuIntroSkipPrefs {
    public static final String KEY_ENABLED = "introskip_enabled";

    private static final String CACHE_FILE = "vimu_introskip_cache";
    private static final long CACHE_TTL_MS = 60L * 24L * 60L * 60L * 1000L; // 60 days

    private VimuIntroSkipPrefs() {
    }

    /** True unless the user turned the switch off in Vimu Settings. */
    public static boolean isEnabled(PlayerFragment fragment) {
        try {
            if (fragment == null || fragment.getActivity() == null) return true;
            SharedPreferences prefs = android.preference.PreferenceManager
                    .getDefaultSharedPreferences(fragment.getActivity());
            return prefs.getBoolean(KEY_ENABLED, true);
        } catch (Throwable ignored) {
            return true;
        }
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
