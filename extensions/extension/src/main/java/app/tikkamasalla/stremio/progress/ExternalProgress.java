package app.tikkamasalla.stremio.progress;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Constructor;

/**
 * Makes Stremio keep watch progress when an external player is used. Called from small hooks in
 * ExternalPlayerContract (launch and result) and StremioApi.playerTimeChanged (log only).
 * Every entry point fails soft: on any problem the original Stremio code path runs unchanged.
 */
public final class ExternalProgress {
    private static final String TAG = "ExtProgress";
    private static final String RESULT_CLASS =
            "com.stremio.common.players.ExternalPlayerContract$ExternalPlayerResult";

    private static volatile Context appContext;

    private ExternalProgress() {
    }

    /** Hook at the start of createIntent: keeps a Context to resolve the target player. */
    public static void remember(Context context) {
        try {
            if (context != null) {
                appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Hook at the end of createIntent. Stremio already sets "return_result", "startfrom" (Vimu) and an
     * int "position" (MX Player, mpv, Just Player). VLC reads "position" only as a long, and an int and a
     * long cannot share one key, so for VLC the key is rewritten as a long and "from_start" is added.
     */
    public static void decorate(Intent intent) {
        try {
            if (intent == null || !intent.hasExtra("return_result")) {
                return; // the YouTube branch, not a player launch
            }
            int position = intent.getIntExtra("startfrom", 0);
            intent.putExtra("return_result", true);
            intent.putExtra("startfrom", position);
            String target = resolveTarget(intent);
            if (target != null && target.startsWith("org.videolan.vlc")) {
                intent.putExtra("position", (long) position);
                intent.putExtra("from_start", position <= 0);
            } else {
                intent.putExtra("position", position);
            }
            Log.i(TAG, "launch position=" + position + " target=" + target);
            keepAlive(true);
        } catch (Throwable error) {
            Log.w(TAG, "decorate", error);
        }
    }

    /**
     * Hook at the start of parseResult. Reads position and duration from any known key and type, and
     * returns an ExternalPlayerResult, or null to let Stremio's own parser run.
     */
    public static Object parse(int resultCode, Intent data) {
        keepAlive(false);
        try {
            if (data == null) {
                Log.i(TAG, "result code=" + resultCode + " without data");
                return null;
            }
            String action = data.getAction();
            Bundle extras = data.getExtras();
            long position = readLong(extras, "extra_position", "position");
            long duration = readLong(extras, "extra_duration", "duration");
            String endBy = extras != null ? extras.getString("end_by") : null;
            boolean completed = "playback_completion".equals(endBy);
            if ("net.gtvbox.videoplayer.result".equals(action)) {
                completed |= resultCode == 1;
            } else if ("org.videolan.vlc.player.result".equals(action)) {
                completed |= duration == 0L;
            } else if ("is.xyz.mpv.MPVActivity.result".equals(action)) {
                completed |= resultCode == Activity_RESULT_OK && position == 0L && duration == 0L;
            }
            Log.i(TAG, "result code=" + resultCode + " action=" + action + " position=" + position
                    + " duration=" + duration + " end_by=" + endBy + " completed=" + completed);
            if (position <= 0L && duration <= 0L && !completed) {
                return null;
            }
            Constructor<?> constructor = Class.forName(RESULT_CLASS)
                    .getConstructor(long.class, long.class, boolean.class);
            return constructor.newInstance(Math.max(position, 0L), Math.max(duration, 0L), completed);
        } catch (Throwable error) {
            Log.w(TAG, "parse", error);
            return null;
        }
    }

    /** Keeps the process alive while the external player is open (see KeepAliveService). */
    private static void keepAlive(boolean start) {
        try {
            Context context = appContext;
            if (context == null) {
                return;
            }
            Intent service = new Intent(context, KeepAliveService.class);
            if (!start) {
                context.stopService(service);
            } else if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
            Log.i(TAG, "keep-alive " + (start ? "start" : "stop"));
        } catch (Throwable error) {
            Log.w(TAG, "keep-alive", error);
        }
    }

    /** Hook in StremioApi.playerTimeChanged: log only, shows what reaches the core. */
    public static void logTime(long position, long duration) {
        Log.i(TAG, "playerTimeChanged position=" + position + " duration=" + duration);
    }

    private static final int Activity_RESULT_OK = -1;

    private static long readLong(Bundle extras, String... keys) {
        if (extras == null) {
            return 0L;
        }
        for (String key : keys) {
            if (!extras.containsKey(key)) {
                continue;
            }
            Object value = extras.get(key);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            if (value instanceof String) {
                try {
                    return Long.parseLong((String) value);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0L;
    }

    private static String resolveTarget(Intent intent) {
        try {
            if (intent.getPackage() != null) {
                return intent.getPackage();
            }
            if (intent.getComponent() != null) {
                return intent.getComponent().getPackageName();
            }
            Context context = appContext;
            if (context == null) {
                return null;
            }
            ResolveInfo info = context.getPackageManager().resolveActivity(intent, 0);
            return info != null && info.activityInfo != null ? info.activityInfo.packageName : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
