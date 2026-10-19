package app.tikkamasalla.stremio.introskip;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Auto-picks the top ranked stream when an episode or movie is opened.
 * Fail soft: any problem leaves the full stream list on screen.
 */
public final class StreamAutoPick {
    private static final String TAG = "AutoPick";
    private static final long WAIT_MS = 4000L;
    private static final long WAIT_NOCACHE_MS = 8000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Object, String> FIRST_SEEN = Collections.synchronizedMap(
            new WeakHashMap<Object, String>());
    private static final Map<Object, Long> FIRST_AT = Collections.synchronizedMap(
            new WeakHashMap<Object, Long>());
    private static final Map<Object, Boolean> TIMER_4 = Collections.synchronizedMap(
            new WeakHashMap<Object, Boolean>());
    private static final Map<Object, Boolean> TIMER_8 = Collections.synchronizedMap(
            new WeakHashMap<Object, Boolean>());
    private static volatile String lastPickedVideoId = "";

    private StreamAutoPick() {
    }

    /**
     * Hook at the start of StreamsListFragment list observer. Takes the observer
     * lambda (p0) so no new registers are needed; fragment and state come from
     * its this$0 and L$0 fields.
     */
    public static void onStreams(Object lambda) {
        try {
            if (lambda == null) return;
            if (!IntroSkipPrefs.isAutoPickEnabled(appContext())) return;
            if (IntroSkipPrefs.isShowListEnabled(appContext())) return;
            Object fragment = field(lambda, "this$0");
            Object state = field(lambda, "L$0");
            if (fragment == null || state == null) return;
            String videoId = str(call(state, "getVideoId"));
            if (videoId.length() == 0) return;
            List<Object> streams = list(call(state, "getStreams"));
            if (streams == null || streams.isEmpty()) return;
            String seen = FIRST_SEEN.get(fragment);
            if ((videoId + "#picked").equals(seen) || videoId.equals(lastPickedVideoId)) return;
            long now = System.currentTimeMillis();
            Long firstAt = FIRST_AT.get(fragment);
            if (seen == null || !seen.equals(videoId)) {
                lastPickedVideoId = "";
                FIRST_SEEN.put(fragment, videoId);
                FIRST_AT.put(fragment, now);
                TIMER_4.put(fragment, false);
                TIMER_8.put(fragment, false);
                schedule(fragment, videoId, WAIT_MS, 4);
                schedule(fragment, videoId, WAIT_NOCACHE_MS, 8);
                return;
            }
            if (firstAt == null) firstAt = now;
            long elapsed = now - firstAt;
            List<StreamRanker.Stream> candidates = toRanker(streams, metaNameOf(state));
            if (candidates.isEmpty()) return;
            int[] req = StreamRanker.requestOf(videoId);
            List<StreamRanker.Ranked> ranked = StreamRanker.rank(candidates, isMovie(state), req[0], req[1]);
            if (ranked.isEmpty()) return;
            boolean anyCached = false;
            for (StreamRanker.Ranked r : ranked) {
                if (r.meta.cached) {
                    anyCached = true;
                    break;
                }
            }
            if (elapsed < WAIT_MS) return;
            if (!anyCached && elapsed < WAIT_NOCACHE_MS) return;
            Object best = streamFor(streams, ranked.get(0).stream);
            if (best == null) return;
            FIRST_SEEN.put(fragment, videoId + "#picked");
            lastPickedVideoId = videoId;
            Log.i(TAG, "pick " + videoId + " -> " + ranked.get(0).stream);
            logTop(ranked);
            navigate(fragment, best);
        } catch (Throwable ignored) {
            // Full list stays on screen.
        }
    }

    private static void schedule(final Object fragment, final String videoId,
                                 long delayMs, final int timer) {
        try {
            Map<Object, Boolean> map = timer == 4 ? TIMER_4 : TIMER_8;
            if (Boolean.TRUE.equals(map.get(fragment))) return;
            map.put(fragment, true);
            MAIN.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (!isActive(fragment)) return;
                        if (!IntroSkipPrefs.isAutoPickEnabled(appContext())) return;
                        if (IntroSkipPrefs.isShowListEnabled(appContext())) return;
                        String seen = FIRST_SEEN.get(fragment);
                        if (seen == null || !seen.equals(videoId) || videoId.equals(lastPickedVideoId)) return;
                        List<Object> streams = currentStreams(fragment);
                        if (streams == null || streams.isEmpty()) return;
                        List<StreamRanker.Stream> candidates = toRanker(streams, currentMetaName(fragment));
                        if (candidates.isEmpty()) return;
                        String vid = currentVideoId(fragment);
                        int[] req = StreamRanker.requestOf(vid);
                        List<StreamRanker.Ranked> ranked =
                                StreamRanker.rank(candidates, isMovieId(vid), req[0], req[1]);
                        if (ranked.isEmpty()) return;
                        if (timer == 4) {
                            boolean anyCached = false;
                            for (StreamRanker.Ranked r : ranked) {
                                if (r.meta.cached) {
                                    anyCached = true;
                                    break;
                                }
                            }
                            if (!anyCached) return;
                        }
                        Object best = streamFor(streams, ranked.get(0).stream);
                        if (best == null) return;
                        FIRST_SEEN.put(fragment, videoId + "#picked");
                        lastPickedVideoId = videoId;
                        Log.i(TAG, "pick(timer" + timer + ") " + videoId + " -> " + ranked.get(0).stream);
                        logTop(ranked);
                        navigate(fragment, best);
                    } catch (Throwable ignored) {
                        // Full list stays on screen.
                    }
                }
            }, delayMs);
        } catch (Throwable ignored) {
            // No timer; later observer calls still pick.
        }
    }

    private static void logTop(List<StreamRanker.Ranked> ranked) {
        try {
            for (int i = 0; i < ranked.size() && i < 3; i++) {
                StreamRanker.Ranked r = ranked.get(i);
                Log.i(TAG, "rank " + (i + 1) + ": " + r.stream + " | " + r.reason);
            }
        } catch (Throwable ignored) {
            // Logging only.
        }
    }

    private static android.content.Context appContext() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Method current = thread.getMethod("currentApplication");
            return (android.app.Application) current.invoke(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object field(Object holder, String name) {
        try {
            Field field = null;
            for (Class<?> c = holder.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    field = c.getDeclaredField(name);
                    break;
                } catch (NoSuchFieldException ignored) {
                    // Superclass next.
                }
            }
            if (field == null) return null;
            field.setAccessible(true);
            return field.get(holder);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object call(Object target, String method) {
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        if (value instanceof List) return (List<Object>) value;
        return null;
    }

    private static boolean isMovie(Object state) {
        try {
            return isMovieId(str(call(state, "getVideoId")));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isMovieId(String videoId) {
        if (videoId == null || videoId.length() == 0) return false;
        return !videoId.contains(":");
    }

    private static List<Object> currentStreams(Object fragment) {
        try {
            Object viewModel = null;
            try {
                viewModel = fragment.getClass().getMethod("getViewModel").invoke(fragment);
            } catch (Throwable ignored) {
                Object delegate = field(fragment, "viewModel$delegate");
                if (delegate != null) {
                    viewModel = delegate.getClass().getMethod("getValue").invoke(delegate);
                }
            }
            if (viewModel == null) return null;
            Object flow = viewModel.getClass().getMethod("getState").invoke(viewModel);
            Object state = flow.getClass().getMethod("getValue").invoke(flow);
            if (state == null) return null;
            return list(call(state, "getStreams"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String metaNameOf(Object state) {
        try {
            Object meta = call(state, "getMeta");
            return meta == null ? "" : str(call(meta, "getName"));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String currentMetaName(Object fragment) {
        try {
            Object viewModel = null;
            try {
                viewModel = fragment.getClass().getMethod("getViewModel").invoke(fragment);
            } catch (Throwable ignored) {
                Object delegate = field(fragment, "viewModel$delegate");
                if (delegate != null) {
                    viewModel = delegate.getClass().getMethod("getValue").invoke(delegate);
                }
            }
            if (viewModel == null) return "";
            Object flow = viewModel.getClass().getMethod("getState").invoke(viewModel);
            return metaNameOf(flow.getClass().getMethod("getValue").invoke(flow));
        } catch (Throwable ignored) {
            return "";
        }
    }

    /**
     * Addons sometimes return streams of other titles (a search for a short name). A stream is
     * eligible only when it shares a significant word with the title; no usable title means no filter.
     */
    private static boolean matchesMeta(String streamText, String metaName) {
        try {
            String name = metaName == null ? "" : metaName.toLowerCase(java.util.Locale.US);
            String text = streamText == null ? "" : streamText.toLowerCase(java.util.Locale.US);
            java.util.Set<String> tokens = new java.util.HashSet<String>();
            for (String t : text.split("[^a-z0-9]+")) {
                if (t.length() > 0) tokens.add(t);
            }
            String[] words = name.split("[^a-z0-9]+");
            // A trailing roman numeral or number ("Planet Earth II") must match exactly.
            if (words.length > 1) {
                String last = words[words.length - 1];
                if (last.matches("ii|iii|iv|v|vi|vii|viii|ix|[2-9]") && !tokens.contains(last)) return false;
            }
            int significant = 0;
            for (String word : words) {
                if (word.length() < 3 || word.equals("the") || word.equals("and") || word.equals("der")
                        || word.equals("die") || word.equals("das") || word.equals("des")) {
                    continue;
                }
                significant++;
                if (tokens.contains(word)) return true;
            }
            return significant == 0;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String currentVideoId(Object fragment) {
        try {
            Object viewModel = null;
            try {
                viewModel = fragment.getClass().getMethod("getViewModel").invoke(fragment);
            } catch (Throwable ignored) {
                Object delegate = field(fragment, "viewModel$delegate");
                if (delegate != null) {
                    viewModel = delegate.getClass().getMethod("getValue").invoke(delegate);
                }
            }
            if (viewModel == null) return "";
            Object flow = viewModel.getClass().getMethod("getState").invoke(viewModel);
            Object state = flow.getClass().getMethod("getValue").invoke(flow);
            if (state == null) return "";
            return str(call(state, "getVideoId"));
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static List<StreamRanker.Stream> toRanker(List<Object> streams, String metaName) {
        List<StreamRanker.Stream> out = new ArrayList<StreamRanker.Stream>();
        for (Object s : streams) {
            if (s == null) continue;
            if (!isPlayable(s)) continue;
            try {
                String name = str(call(s, "getName"));
                String title = descriptionOf(s);
                String url = "";
                try {
                    Object u = call(s, "getUrl");
                    if (u != null) {
                        try {
                            url = str(u.getClass().getMethod("getUrl").invoke(u));
                        } catch (Throwable ignored) {
                            url = str(u);
                        }
                    } else {
                        Object torrent = null;
                        try {
                            torrent = s.getClass().getMethod("getTorrent").invoke(s);
                        } catch (Throwable ignored) {
                            torrent = null;
                        }
                        if (torrent != null) {
                            try {
                                url = str(torrent.getClass().getMethod("getInfoHash").invoke(torrent));
                            } catch (Throwable ignored) {
                                url = "";
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    url = "";
                }
                if (!matchesMeta(name + " " + title, metaName)) continue;
                out.add(new StreamRanker.Stream(name, title, url, ""));
            } catch (Throwable ignored) {
                // Skip unusable entries.
            }
        }
        return out;
    }

    /** Only streams the player can open directly: a URL or a torrent. Web links, YouTube and frames open other apps. */
    private static boolean isPlayable(Object stream) {
        try {
            return call(stream, "getUrl") != null || call(stream, "getTorrent") != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isActive(Object fragment) {
        try {
            return fragment != null
                    && Boolean.TRUE.equals(call(fragment, "isAdded"))
                    && Boolean.TRUE.equals(call(fragment, "isResumed"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String descriptionOf(Object stream) {
        String desc = str(call(stream, "getDescription"));
        if (desc.length() == 0) desc = str(call(stream, "getTitle"));
        return desc;
    }

    private static Object streamFor(List<Object> streams, StreamRanker.Stream best) {
        if (best == null) return null;
        for (Object s : streams) {
            try {
                String name = str(call(s, "getName"));
                String title = descriptionOf(s);
                if (name.equals(best.name) && title.equals(best.title)) return s;
            } catch (Throwable ignored) {
                // Continue.
            }
        }
        return null;
    }

    private static void navigate(Object fragment, Object stream) {
        try {
            Method navigate = null;
            try {
                navigate = fragment.getClass().getDeclaredMethod("navigateToStream", stream.getClass());
            } catch (Throwable ignored) {
                for (Method m : fragment.getClass().getDeclaredMethods()) {
                    if ("navigateToStream".equals(m.getName()) && m.getParameterTypes().length == 1) {
                        navigate = m;
                        break;
                    }
                }
            }
            if (navigate == null) return;
            navigate.setAccessible(true);
            navigate.invoke(fragment, stream);
        } catch (Throwable ignored) {
            // User picks manually from the list.
        }
    }
}
