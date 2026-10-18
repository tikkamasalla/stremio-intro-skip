package app.tikkamasalla.stremio.introskip;

import android.content.Context;
import android.content.SharedPreferences;

/** Intro Skip settings, stored in a dedicated file so Stremio keys are never touched. */
public final class IntroSkipPrefs {
    public static final String FILE = "introskip_prefs";
    public static final String KEY_ENABLED = "introskip_enabled";
    public static final String KEY_ANIME = "introskip_anime";
    public static final String KEY_MODE = "introskip_mode";
    public static final String KEY_RECAPS = "introskip_recaps";
    public static final String MODE_AUTO = "auto";
    public static final String MODE_MANUAL = "manual";

    private IntroSkipPrefs() {
    }

    private static final String CACHE_FILE = "introskip_cache";
    private static final long CACHE_TTL_MS = 60L * 24L * 60L * 60L * 1000L;

    /** Stored Cinemeta hit for a "series:title" / "movie:title" key, or "" (missing or older than 60 days). */
    public static String cachedImdb(Context context, String key) {
        try {
            String raw = context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE).getString(key, "");
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
            context.getSharedPreferences(CACHE_FILE, Context.MODE_PRIVATE).edit()
                    .putString(key, imdb + "|" + System.currentTimeMillis()).apply();
        } catch (Throwable ignored) {
            // Cache is optional.
        }
    }

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        try {
            return prefs(context).getBoolean(KEY_ENABLED, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean isAnimeEnabled(Context context) {
        try {
            return prefs(context).getBoolean(KEY_ANIME, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean isRecapsEnabled(Context context) {
        try {
            return prefs(context).getBoolean(KEY_RECAPS, true);
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static String mode(Context context) {
        try {
            return MODE_MANUAL.equals(prefs(context).getString(KEY_MODE, MODE_AUTO))
                    ? MODE_MANUAL : MODE_AUTO;
        } catch (Throwable ignored) {
            return MODE_AUTO;
        }
    }

    public static boolean isManualMode(Context context) {
        return MODE_MANUAL.equals(mode(context));
    }
}
