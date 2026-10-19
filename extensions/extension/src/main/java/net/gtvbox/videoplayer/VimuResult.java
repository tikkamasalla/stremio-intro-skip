package net.gtvbox.videoplayer;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Field;

/**
 * External player contract for Vimu: accepts the common start position keys and returns the
 * MX Player style result (position, duration in ms, end_by). Called from two smali hooks in
 * PlayerFragment (w1 and j1).
 */
public final class VimuResult {
    private static final String TAG = "VimuResult";
    private static final int RESULT_OK = -1;

    private VimuResult() {
    }

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
