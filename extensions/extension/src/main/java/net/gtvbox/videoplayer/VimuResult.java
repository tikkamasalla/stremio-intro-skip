package net.gtvbox.videoplayer;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.ref.WeakReference;

/**
 * External player contract for Vimu: accepts the common start position keys and returns the
 * MX Player style result (position, duration in ms, end_by). Called from two smali hooks in
 * PlayerFragment (w1 and j1). j1 only runs on some exits (Back, Stop, Home), so track() also keeps
 * the latest position on the activity result once a second: any other finish() carries it too.
 */
public final class VimuResult {
    private static final String TAG = "VimuResult";
    private static final int RESULT_OK = -1;
    private static final long TRACK_MS = 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile WeakReference<PlayerFragment> tracked;
    private static boolean ticking;
    private static boolean logged;

    private VimuResult() {
    }

    /**
     * Hook in PlayerFragment.w1 (every launch). Keeps the activity result up to date with the
     * latest position and duration, so an exit that bypasses j1 still returns them.
     */
    public static void track(Object fragment) {
        try {
            tracked = new WeakReference<>((PlayerFragment) fragment);
            logged = false;
            if (!ticking) {
                ticking = true;
                MAIN.postDelayed(TICK, TRACK_MS);
            }
        } catch (Throwable error) {
            Log.w(TAG, "track", error);
        }
    }

    private static final Runnable TICK = new Runnable() {
        @Override
        public void run() {
            boolean again = false;
            try {
                PlayerFragment f = tracked == null ? null : tracked.get();
                Activity activity = f == null ? null : f.getActivity();
                if (activity != null && !activity.isFinishing()
                        && !(Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                    again = true;
                    e player = PlayerFragment.G0(f);
                    int duration = player == null ? 0 : player.getDuration();
                    if (duration > 0) {
                        Intent out = new Intent(activity.getPackageName() + ".result");
                        out.putExtra("position", Math.max(player.getCurrentPosition(), 0));
                        out.putExtra("duration", duration);
                        out.putExtra("end_by", "user");
                        activity.setResult(RESULT_OK, out);
                        if (!logged) {
                            logged = true;
                            Log.i(TAG, "tracking result position=" + out.getIntExtra("position", 0)
                                    + " duration=" + duration);
                        }
                    }
                }
            } catch (Throwable error) {
                Log.w(TAG, "tick", error);
            }
            if (again) {
                MAIN.postDelayed(this, TRACK_MS);
            } else {
                ticking = false;
            }
        }
    };

    /** Hook in PlayerFragment.w1: turns "position" / "extra_position" (int or long, ms) into "startfrom". */
    public static void normalize(Bundle extras) {
        try {
            if (extras == null || extras.containsKey("startfrom")) {
                return;
            }
            String[] keys = {"position", "extra_position"};
            for (String key : keys) {
                Object value = extras.get(key);
                if (value instanceof Number) {
                    long ms = ((Number) value).longValue();
                    if (ms > 0L && ms < Integer.MAX_VALUE) {
                        extras.putInt("startfrom", (int) ms);
                        Log.i(TAG, "start position " + ms + " ms from '" + key + "'");
                        return;
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "normalize", error);
        }
    }

    /**
     * Hook in PlayerFragment.j1, right before setResult. Puts position, duration and end_by on the
     * result and returns the result code to use: a plain user exit (0) becomes RESULT_OK so that
     * MX style callers read the extras; every other code is kept.
     */
    public static int fill(Object fragment, int code, Intent out) {
        try {
            int position = readInt(fragment, "J0");
            int duration = readInt(fragment, "K0");
            if (duration > 0) {
                if (position < 0) {
                    position = 0;
                }
                out.putExtra("position", position);
                out.putExtra("duration", duration);
            }
            out.putExtra("end_by", code == 1 ? "playback_completion" : "user");
            Log.i(TAG, "result code=" + code + " position=" + position + " duration=" + duration);
            return code == 0 ? RESULT_OK : code;
        } catch (Throwable error) {
            Log.w(TAG, "fill", error);
            return code;
        }
    }

    private static int readInt(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(owner);
    }
}
