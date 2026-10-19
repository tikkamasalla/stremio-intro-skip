package app.tikkamasalla.stremio.introskip;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runtime bridge injected into Stremio's Media3 PlayerView. */
public final class IntroSkipBridge {
    private static final String ANILIST_URL = "https://graphql.anilist.co";
    private static final String ANISKIP_URL = "https://api.aniskip.com/v2/skip-times/";
    private static final String INTRODB_URL = "https://api.theintrodb.org/v4/media";
    private static final String CINEMETA_SERIES_URL = "https://v3-cinemeta.strem.io/catalog/series/top/search=";
    private static final String CINEMETA_MOVIE_URL = "https://v3-cinemeta.strem.io/catalog/movie/top/search=";
    private static final Pattern STREMIO_VIDEO_ID =
            Pattern.compile("^(tt\\d{7,10})(?::(\\d{1,3}):(\\d{1,4}))?$");
    private static final Object CINEMETA_LOCK = new Object();
    private static final Map<String, String> CINEMETA_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    private static final Pattern YEAR = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");
    private static final Pattern IMDB_ID = Pattern.compile("(?i)(tt\\d{7,})");
    private static final Pattern META_SEASON_EPISODE = Pattern.compile("(?i)(?:^|[^a-z0-9])S(\\d{1,2})[ ._-]*E(\\d{1,4})(?=$|[^a-z0-9])");
    private static final Pattern SEASON_EPISODE = Pattern.compile("(?i)(?:^|[^a-z0-9])S\\d{1,2}[ ._-]*E(\\d{1,4})(?=$|[^a-z0-9])");
    private static final Pattern SEASON = Pattern.compile("(?i)(?:^|[^a-z0-9])S(\\d{1,2})(?:[ ._-]*E\\d{1,4})?(?=$|[^a-z0-9])");
    private static final Pattern SEASON_WORD = Pattern.compile("(?i)(?:^|[^a-z0-9])(?:season|saison)[ ._-]*(\\d{1,2})(?=$|[^a-z0-9])");
    private static final Pattern EPISODE = Pattern.compile("(?i)(?:\\bepisode|\\bep|[ ._/-]e)[ ._/-]*(\\d{1,4})\\b");
    private static final Pattern STRUCTURED_TITLE = Pattern.compile(
            "(?i)(?:title|name|series|show|anime|mediaTitle|torrent_name|torrentName|filename|file_name|fileName)\\s*[:=]\\s*[\\\"']?([^,}\\]\\\"']+)");
    private static final String TAG = "IntroSkip";
    private static final long POLL_MS = 200L;
    private static final Map<Activity, Session> SESSIONS = new WeakHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService NETWORK = Executors.newCachedThreadPool();

    private IntroSkipBridge() {
    }

    public static void onPlayerSet(final View playerView, final Player player) {
        onPlayerSetInternal(playerView, player, false);
    }

    /** Same hook with the optional Intro Skip diagnostics enabled by the patch option. */
    public static void onPlayerSetDebug(final View playerView, final Player player) {
        onPlayerSetInternal(playerView, player, true);
    }

    /**
     * All three patch hooks (PlayerView.setPlayer, ExoPlayerImpl.setVideoSurfaceView,
     * PlayerFragment.startStreamPlayback) land here. They describe the SAME playback, so they
     * are folded into ONE session per Activity; the Stremio wrapper player wins over the raw
     * ExoPlayerImpl. One session = one fetch, one overlay, one skip per segment.
     */
    private static void onPlayerSetInternal(final View playerView,
                                             final Player player,
                                             final boolean debugEnabled) {
        if (playerView == null || player == null) return;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Activity activity = activityOf(playerView.getContext());
                    if (activity == null || activity.isFinishing()) return;
                    Session session = SESSIONS.get(activity);
                    if (session == null || session.closed) {
                        session = new Session(activity);
                        SESSIONS.put(activity, session);
                    }
                    if (debugEnabled) session.debugEnabled = true;
                    session.offer(playerView, player, priorityOf(playerView, player));
                } catch (Throwable error) {
                    Log.w(TAG, "hook failed", error);
                }
            }
        });
    }

    private static int priorityOf(View view, Player player) {
        if (player.getClass().getName().startsWith("com.stremio.")) return 3;
        if (view.getClass().getName().endsWith("PlayerView")) return 2;
        return 1;
    }

    private static Activity activityOf(Context context) {
        Context current = context;
        for (int i = 0; i < 12 && current != null; i++) {
            if (current instanceof Activity) return (Activity) current;
            if (!(current instanceof ContextWrapper)) return null;
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private static boolean isOkKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A;
    }

    private static final class Session {
        private final Activity activity;
        private final StringBuilder debugLog = new StringBuilder();
        private final List<Segment> segments = new ArrayList<>();
        private final Set<String> autoSkipped = new HashSet<>();
        private final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (closed) return;
                try {
                    poll();
                } catch (Throwable error) {
                    log("ERROR poll: " + error);
                }
                if (!closed) MAIN.postDelayed(this, POLL_MS);
            }
        };
        private final Runnable precise = new Runnable() {
            @Override
            public void run() {
                if (closed) return;
                try {
                    poll();
                } catch (Throwable error) {
                    log("ERROR poll: " + error);
                }
            }
        };
        boolean closed;
        boolean debugEnabled;
        private boolean debugDialogShown;
        private Dialog debugDialog;
        private View anchor;
        private Player player;
        private int priority;
        private long attachedAt;
        private boolean seenAlive;
        private long deadSince;
        // media resolution
        private String identity;
        private String loadedKey;
        private String lastSnapshot;
        private MediaInfo pendingInfo;
        private long readySince;
        private long nextInfoAt;
        private int infoAttempts;
        private int fetchGeneration;
        private boolean aniDone;
        private boolean introDone;
        private List<Segment> introResult;
        // chapters of the file itself (exact ground truth) + online timings, merged in combine()
        private int mediaGeneration;
        private long skippedAt;
        private boolean chaptersRequested;
        private boolean movie;
        private boolean chaptersDone;
        private long chaptersStartedAt;
        private List<MkvChapters.Chapter> chapters;
        private List<Segment> online;
        private String onlineSource;
        private long onlineStartedAt;
        private String appliedSignature;
        // playback tracking
        private double lastPos = -1;
        private long lastPollAt;
        private Segment current;
        private boolean currentBySeek;
        private boolean currentByJump;
        private final Map<String, Integer> jumpSkips = new java.util.HashMap<>();
        private long lastSeekKeyAt;
        private long firstPlayingAt;
        private long lastTraceAt;
        private boolean inheritSeek;
        private long guardUntil;
        private double guardTarget;
        private long landingCheckAt;
        private boolean exoLookedUp;
        private Object exoInternal;
        private Method exoSetSeek;
        private Object exoExact;
        private Object exoOriginal;
        // overlay + keys
        private FrameLayout host;
        private TextView pill;
        private boolean pillShown;
        private boolean armed;
        private long lastOtherKeyAt;
        private Segment pillSegment;
        private boolean nextPill;
        private long noticeUntil;
        private Window.Callback originalCallback;
        private KeyCallback keyCallback;
        private Object fragment;
        private Method controlsVisibleMethod;

        /** Logs where a seek really landed (NEXT_SYNC reports SEEK_ADJUSTMENT to a keyframe). */
        private final Player.Listener listener = new Player.Listener() {
            @Override
            public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition,
                                                int reason) {
                if (reason != Player.DISCONTINUITY_REASON_SEEK
                        && reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) return;
                if (SystemClock.uptimeMillis() - skippedAt > 4000L) return;
                log("Landing " + (reason == Player.DISCONTINUITY_REASON_SEEK ? "seek" : "adjusted keyframe")
                        + ": " + fmt3(oldPosition.positionMs / 1000.0) + "s -> "
                        + fmt3(newPosition.positionMs / 1000.0) + "s");
            }
        };

        Session(Activity activity) {
            this.activity = activity;
        }

        void offer(View view, Player next, int nextPriority) {
            if (next == player) {
                if (nextPriority > priority) {
                    anchor = view;
                    priority = nextPriority;
                    fragment = null;
                }
                return;
            }
            if (player != null && nextPriority < priority && !playerDead()) {
                log("HOOK ignored (same playback): " + next.getClass().getName());
                return;
            }
            log("HOOK " + view.getClass().getName() + " player=" + next.getClass().getName()
                    + " prio=" + nextPriority);
            boolean wasRunning = player != null;
            try {
                if (player != null) player.removeListener(listener);
                next.addListener(listener);
            } catch (Throwable error) {
                log("ERROR listener: " + error);
            }
            player = next;
            priority = nextPriority;
            anchor = view;
            fragment = null;
            attachedAt = SystemClock.uptimeMillis();
            seenAlive = false;
            deadSince = 0;
            resetMedia();
            if (debugEnabled && !debugDialogShown) {
                debugDialogShown = true;
                showDebugDialog();
            }
            if (!wasRunning) {
                installKeys();
                MAIN.post(tick);
            }
        }

        private void resetMedia() {
            identity = null;
            loadedKey = null;
            pendingInfo = null;
            readySince = 0;
            nextInfoAt = 0;
            infoAttempts = 0;
            fetchGeneration++;
            mediaGeneration++;
            movie = false;
            segments.clear();
            autoSkipped.clear();
            jumpSkips.clear();
            firstPlayingAt = 0;
            inheritSeek = false;
            chaptersRequested = false;
            chaptersDone = false;
            chapters = null;
            online = null;
            onlineSource = null;
            appliedSignature = null;
            current = null;
            lastPos = -1;
            guardUntil = 0;
            landingCheckAt = 0;
            exoLookedUp = false;
            exoInternal = null;
            nextPill = false;
            hidePill(false);
        }

        private boolean playerDead() {
            if (player == null) return true;
            try {
                int state = player.getPlaybackState();
                if (state != Player.STATE_IDLE) return false;
            } catch (Throwable error) {
                return true;
            }
            return seenAlive || SystemClock.uptimeMillis() - attachedAt > 15000L;
        }

        private void close(String why) {
            if (closed) return;
            closed = true;
            log("Session closed: " + why);
            try {
                if (player != null) player.removeListener(listener);
            } catch (Throwable ignored) {
                // Player already released.
            }
            MAIN.removeCallbacks(tick);
            MAIN.removeCallbacks(precise);
            hidePill(false);
            try {
                if (debugDialog != null && debugDialog.isShowing()) debugDialog.dismiss();
            } catch (Throwable ignored) {
                // Window already gone.
            }
            debugDialog = null;
            if (host != null && pill != null) {
                try {
                    host.removeView(pill);
                } catch (Throwable ignored) {
                    // View already gone with the window.
                }
            }
            try {
                Window window = activity.getWindow();
                if (window != null && keyCallback != null && window.getCallback() == keyCallback) {
                    window.setCallback(originalCallback);
                }
            } catch (Throwable ignored) {
                // Activity window already torn down.
            }
            if (keyCallback != null) keyCallback.session = null;
            SESSIONS.remove(activity);
        }

        // ---------------------------------------------------------------- poll

        private void poll() {
            long now = SystemClock.uptimeMillis();
            if (activity.isFinishing() || (Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                close("activity finished");
                return;
            }
            int state;
            boolean playing;
            boolean playWhenReady;
            double pos;
            long durationMs;
            try {
                state = player.getPlaybackState();
                playing = player.isPlaying();
                playWhenReady = player.getPlayWhenReady();
                pos = player.getCurrentPosition() / 1000.0;
                durationMs = player.getDuration();
            } catch (Throwable error) {
                state = Player.STATE_IDLE;
                playing = false;
                playWhenReady = false;
                pos = 0;
                durationMs = -1;
            }
            if (state != Player.STATE_IDLE) {
                seenAlive = true;
                deadSince = 0;
            }
            boolean anchorGone = anchor != null && Build.VERSION.SDK_INT >= 19
                    && !anchor.isAttachedToWindow() && now - attachedAt > 3000L;
            if (playerDead() || anchorGone) {
                hidePill(false);
                if (deadSince == 0) deadSince = now;
                if (now - deadSince > 2500L) close(anchorGone ? "view detached" : "player released");
                return;
            }

            if (!IntroSkipPrefs.isEnabled(activity)) {
                // Master off: no lookups, no chapter reads, no pill. Keep the clock current so
                // switching it back on mid-playback does not look like a user seek.
                current = null;
                lastPos = pos;
                lastPollAt = now;
                if (pillShown) hidePill(true);
                return;
            }
            resolveMedia(now, durationMs);
            if (movie) {
                // Movies are never skipped: no segments, no pill, no lookups.
                current = null;
                lastPos = pos;
                lastPollAt = now;
                if (pillShown) hidePill(true);
                return;
            }
            combine(now);

            long elapsed = lastPollAt == 0 ? POLL_MS : now - lastPollAt;
            boolean jumped = lastPos >= 0
                    && (pos < lastPos - 0.75 || pos > lastPos + elapsed / 1000.0 * 2.5 + 1.0);
            lastPos = pos;
            lastPollAt = now;
            if (playing && firstPlayingAt == 0) firstPlayingAt = now;
            if (now - lastTraceAt >= 1000L && sysprop("debug.aniskip.trace")) {
                lastTraceAt = now;
                log("TRACE pos=" + fmt3(pos) + " state=" + state + (playing ? " playing" : " paused"));
            }

            if (landingCheckAt != 0 && now >= landingCheckAt && playing) {
                landingCheckAt = 0;
                log("Position 1 s after skip: " + fmt3(pos) + "s (target " + fmt3(guardTarget) + "s)");
            }
            if (guardUntil != 0) {
                // Right after our own seek: positions can lag (VLC) or still read the old
                // spot for a frame. Never re-evaluate the segment we just left.
                if (now < guardUntil && pos < guardTarget - 0.5) {
                    updatePillPosition();
                    if (noticeUntil != 0 && now > noticeUntil) hidePill(true);
                    return;
                }
                guardUntil = 0;
                jumped = false;
            }

            Segment segment = segments.isEmpty() ? null : segmentAt(pos);
            if (segment != current) {
                current = segment;
                // Only a jump the USER made (left/right/rewind keys just before it) counts as a
                // deliberate seek into the intro. Starting, resuming or the player's own
                // repositioning inside the intro still auto-skips.
                // A resume/start jump happens before ~3 s of real playback, whatever keys were pressed.
                currentByJump = segment != null && jumped;
                currentBySeek = (currentByJump && now - lastSeekKeyAt < 5000L && firstPlayingAt != 0
                        && now - firstPlayingAt > 3000L) || (segment != null && inheritSeek);
                if (segment != null) {
                    log("Segment entered " + segment.label + " pos=" + fmt(pos) + "s (start "
                            + fmt(segment.startSeconds) + "s, lag " + fmt(pos - segment.startSeconds)
                            + "s, " + (currentBySeek ? "user seek" : currentByJump
                            ? "player jump (resume/start)" : "playing") + ")");
                }
            }

            inheritSeek = false;
            Integer jumpCount = segment == null ? null : jumpSkips.get(segment.key());
            boolean autoWanted = segment != null && wantsAuto(segment);
            boolean autoPending = segment != null && !currentBySeek && autoWanted
                    && !wasSkipped(segment)
                    && (!currentByJump || jumpCount == null || jumpCount < 3);
            if (autoPending && playing) {
                if (currentByJump) jumpSkips.put(segment.key(), jumpCount == null ? 1 : jumpCount + 1);
                skip(segment, true, pos, durationMs);
                return;
            }

            if (segment == null && playing) {
                // 200 ms polling would show up to 0.2 s of the OP: re-check right at its start.
                boolean recaps = IntroSkipPrefs.isRecapsEnabled(activity);
                boolean creditsOff = IntroSkipPrefs.isCreditsOff(activity);
                boolean previews = IntroSkipPrefs.isPreviewsEnabled(activity);
                for (Segment next : segments) {
                    if (!recaps && next.isRecap()) continue;
                    if (creditsOff && next.isCredits()) continue;
                    if (!previews && next.isPreview()) continue;
                    double ahead = next.startSeconds - pos;
                    if (ahead > 0 && ahead <= POLL_MS / 1000.0 + 0.05) {
                        MAIN.removeCallbacks(precise);
                        MAIN.postDelayed(precise, (long) (ahead * 1000.0) + 10L);
                        break;
                    }
                }
                if (maybeShowNext(pos, durationMs, playWhenReady, now)) {
                    updatePillPosition();
                    return;
                }
            }

            if (segment != null && playWhenReady && !autoPending) {
                nextPill = false;
                if (pillSegment != segment || noticeUntil != 0) showButton(segment);
                refreshArmed(now);
            } else if (noticeUntil != 0) {
                if (now > noticeUntil) hidePill(true);
            } else if (pillShown && !nextPill) {
                hidePill(true);
            } else if (!pillShown && segment == null) {
                maybeShowNext(pos, durationMs, playWhenReady, now);
            }
            updatePillPosition();
        }

        private boolean maybeShowNext(double pos, long durationMs, boolean playWhenReady, long now) {
            try {
                if (movie || !playWhenReady || durationMs <= 0) return false;
                double remaining = durationMs / 1000.0 - pos;
                if (remaining < 0 || remaining > 90.0) {
                    if (nextPill) hidePill(true);
                    return false;
                }
                if (pillShown && nextPill) {
                    refreshArmed(now);
                    return true;
                }
                showNextEpisode();
                refreshArmed(now);
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }

        /** One automatic skip per segment and media: a rewind into it shows the button instead. */
        private boolean wasSkipped(Segment segment) {
            return autoSkipped.contains(segment.key()) || autoSkipped.contains(segment.label);
        }

        private Segment segmentAt(double pos) {
            boolean recaps = IntroSkipPrefs.isRecapsEnabled(activity);
            boolean creditsOff = IntroSkipPrefs.isCreditsOff(activity);
            boolean previews = IntroSkipPrefs.isPreviewsEnabled(activity);
            for (Segment segment : segments) {
                if (!recaps && segment.isRecap()) continue;
                if (creditsOff && segment.isCredits()) continue;
                if (!previews && segment.isPreview()) continue;
                if (pos >= segment.startSeconds && pos < segment.endSeconds - 0.4) return segment;
            }
            return null;
        }

        private boolean wantsAuto(Segment segment) {
            if (segment.isCredits()) return IntroSkipPrefs.isCreditsAuto(activity);
            return !IntroSkipPrefs.isManualMode(activity);
        }

        private void skip(Segment segment, boolean automatic, double pos, long durationMs) {
            double target = segment.endSeconds;
            if (durationMs > 0 && target * 1000.0 > durationMs - 1000.0) {
                target = Math.max(segment.startSeconds, (durationMs - 1000.0) / 1000.0);
            }
            autoSkipped.add(segment.key());
            autoSkipped.add(segment.label);
            String mode;
            skippedAt = SystemClock.uptimeMillis();
            try {
                mode = exactSeek(Math.round(target * 1000.0));
            } catch (Throwable error) {
                log("ERROR seek: " + error);
                return;
            }
            guardUntil = SystemClock.uptimeMillis() + 2500L;
            guardTarget = target;
            landingCheckAt = SystemClock.uptimeMillis() + 1000L;
            current = null;
            log((automatic ? "SKIP AUTO " : "SKIP MANUAL ") + segment.label + " " + fmt3(pos)
                    + "s -> " + fmt3(target) + "s (segment " + fmt3(segment.startSeconds) + "-"
                    + fmt3(segment.endSeconds) + ", source " + segment.source + ", seek " + mode + ")");
            showNotice(segment.skippedText());
        }

        /**
         * Stremio builds its ExoPlayer with SeekParameters.NEXT_SYNC: every seek lands on the
         * next keyframe, seconds past the target. Our skip asks the playback thread for an EXACT
         * seek and restores the original mode right after; the three messages run in order.
         */
        private String exactSeek(long targetMs) {
            if (!exoLookedUp) {
                exoLookedUp = true;
                try {
                    Player raw = player;
                    for (int i = 0; i < 4 && raw instanceof androidx.media3.common.ForwardingPlayer; i++) {
                        raw = ((androidx.media3.common.ForwardingPlayer) raw).getWrappedPlayer();
                    }
                    Field internal = findField(raw.getClass(), "internalPlayer");
                    Field params = findField(raw.getClass(), "seekParameters");
                    if (internal != null && params != null) {
                        Class<?> type = params.getType();
                        exoInternal = internal.get(raw);
                        exoOriginal = params.get(raw);
                        exoExact = type.getField("EXACT").get(null);
                        exoSetSeek = exoInternal.getClass().getMethod("setSeekParameters", type);
                    }
                } catch (Throwable error) {
                    log("Exact seek unavailable: " + error);
                    exoInternal = null;
                }
            }
            boolean exact = exoInternal != null && exoSetSeek != null && !legacySeek();
            if (exact) {
                try {
                    exoSetSeek.invoke(exoInternal, exoExact);
                } catch (Throwable error) {
                    exact = false;
                }
            }
            player.seekTo(targetMs);
            if (exact) {
                try {
                    exoSetSeek.invoke(exoInternal, exoOriginal);
                } catch (Throwable ignored) {
                    // Next user seek simply stays exact.
                }
            }
            return exact ? "EXACT" : "default";
        }

        // ------------------------------------------------------------- media

        private void resolveMedia(long now, long durationMs) {
            MediaItem item;
            try {
                item = player.getCurrentMediaItem();
            } catch (Throwable error) {
                return;
            }
            String id = item == null ? "" : safe(item.mediaId) + "|"
                    + (item.localConfiguration == null ? "" : String.valueOf(item.localConfiguration.uri));
            if (identity != null && !identity.equals(id)) {
                log("New media detected");
                resetMedia();
            }
            identity = id;
            if (movie) return;
            String[] stremioKind = stremioVideoId(anchor);
            if (stremioKind != null && parseInt(stremioKind[1]) == 0 && parseInt(stremioKind[2]) == 0) {
                markMovie();
                return;
            }
            if (!chaptersRequested) {
                chaptersRequested = true;
                startChapters(item == null || item.localConfiguration == null ? null
                        : item.localConfiguration.uri);
            }
            if (loadedKey != null) return;
            if (pendingInfo == null) {
                // The metadata scan uses reflection: retry for ~30 s, then stop (movies,
                // trailers) until the media item changes.
                if (now < nextInfoAt || infoAttempts >= 20) return;
                infoAttempts++;
                nextInfoAt = now + 1500L;
                MediaInfo info = mediaInfoFor(player, anchor);
                if (!info.snapshot.equals(lastSnapshot)) {
                    lastSnapshot = info.snapshot;
                    log("MEDIA\n" + info.debug);
                }
                if (!info.ready()) {
                    // Movies carry no season/episode: once the title is stable and no
                    // episode appeared after several scans, run the movie path
                    // (Cinemeta movie catalog + IntroDB by IMDb id). TV episodes
                    // reveal their number on the first scans, so this never fires
                    // for them; chapters and AniSkip are unaffected either way.
                    if (info.title.length() > 0 && info.episode <= 0 && infoAttempts >= 6) {
                        markMovie();
                    }
                    return;
                } else {
                    pendingInfo = info;
                    readySince = now;
                }
            }
            // Wait for the real duration: AniSkip filters submissions by length, and the
            // submission measured on our cut is what puts the skip on the right seconds.
            if (durationMs <= 0 && now - readySince < 8000L) return;
            startFetch(pendingInfo, durationMs > 0 ? Math.round(durationMs / 1000.0) : 0L);
        }

        /** A movie: drop everything that could skip and stop all lookups until the media changes. */
        private void markMovie() {
            movie = true;
            fetchGeneration++;
            mediaGeneration++;
            segments.clear();
            autoSkipped.clear();
            chapters = null;
            online = null;
            onlineSource = null;
            appliedSignature = null;
            current = null;
            hidePill(false);
            log("movie, skipping disabled");
        }

        private void startFetch(MediaInfo info, final long episodeLength) {
            loadedKey = info.key;
            pendingInfo = null;
            if (!IntroSkipPrefs.isEnabled(activity)) {
                aniDone = true;
                introDone = true;
                log("Lookup skipped: disabled in settings");
                return;
            }
            final int generation = ++fetchGeneration;
            final String title = info.title;
            final int season = info.season;
            final int episode = info.episode;
            final String imdbId = info.imdbId;
            aniDone = false;
            introDone = false;
            introResult = null;
            final long startedAt = SystemClock.uptimeMillis();
            log("Lookup: " + title + " S" + season + "E" + episode + " length=" + episodeLength + "s");
            log("Settings: enabled=" + IntroSkipPrefs.isEnabled(activity)
                    + " anime=" + IntroSkipPrefs.isAnimeEnabled(activity)
                    + " mode=" + IntroSkipPrefs.mode(activity)
                    + " recaps=" + IntroSkipPrefs.isRecapsEnabled(activity));
            final DebugLogger logger = new DebugLogger() {
                @Override
                public void log(String message) {
                    Session.this.log(message);
                }
            };
            // AniSkip and IntroDB run on separate threads with separate error handling:
            // IntroDB can never delay or break AniSkip, and is only used when AniSkip is empty.
            if (IntroSkipPrefs.isAnimeEnabled(activity)) {
                NETWORK.execute(new Runnable() {
                    @Override
                    public void run() {
                        final List<Segment> found = fetchAniSkip(title, season, episode, episodeLength, logger);
                        MAIN.post(new Runnable() {
                            @Override
                            public void run() {
                                onAniSkip(generation, found, startedAt);
                            }
                        });
                    }
                });
            } else {
                aniDone = true;
                onlineStartedAt = startedAt;
                log("AniSkip skipped: disabled in settings");
            }
            NETWORK.execute(new Runnable() {
                @Override
                public void run() {
                    List<Segment> result;
                    try {
                        result = fetchIntroDb(activity, imdbId, title, season, episode, episodeLength, logger);
                    } catch (Throwable error) {
                        logger.log("ERROR IntroDB: " + error);
                        result = new ArrayList<>();
                    }
                    final List<Segment> found = result;
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            onIntroDb(generation, found, startedAt);
                        }
                    });
                }
            });
        }

        private void onAniSkip(int generation, List<Segment> found, long startedAt) {
            if (closed || generation != fetchGeneration) return;
            aniDone = true;
            onlineStartedAt = startedAt;
            if (!found.isEmpty()) {
                online = found;
                onlineSource = "AniSkip";
            } else if (introDone) {
                online = introResult;
                onlineSource = "IntroDB";
            }
            combine(SystemClock.uptimeMillis());
        }

        private void onIntroDb(int generation, List<Segment> found, long startedAt) {
            if (closed || generation != fetchGeneration) return;
            introDone = true;
            introResult = found;
            if (aniDone && online == null) {
                online = found;
                onlineSource = "IntroDB";
                combine(SystemClock.uptimeMillis());
            }
        }

        private void startChapters(Uri uri) {
            String scheme = uri == null ? "" : safe(uri.getScheme()).toLowerCase(Locale.US);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                chaptersDone = true;
                log("Chapters: non-HTTP stream, skipped");
                return;
            }
            final String url = uri.toString();
            final int generation = mediaGeneration;
            chaptersStartedAt = SystemClock.uptimeMillis();
            NETWORK.execute(new Runnable() {
                @Override
                public void run() {
                    final List<MkvChapters.Chapter> found = MkvChapters.read(url, new MkvChapters.Log() {
                        @Override
                        public void log(String message) {
                            Session.this.log(message);
                        }
                    });
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            if (closed || generation != mediaGeneration) return;
                            chaptersDone = true;
                            chapters = found;
                            StringBuilder list = new StringBuilder();
                            for (MkvChapters.Chapter c : found) {
                                list.append("\n  ").append(fmt3(c.start)).append(" - ").append(fmt3(c.end))
                                        .append("  \"").append(c.name).append('"');
                            }
                            log("File chapters (" + (SystemClock.uptimeMillis() - chaptersStartedAt)
                                    + " ms): " + found.size() + list);
                            combine(SystemClock.uptimeMillis());
                        }
                    });
                }
            });
        }

        /**
         * Final intro/recap/credits/preview windows: a chapter NAMED opening/intro/recap/credits
         * is exact and wins; otherwise AniSkip/IntroDB is snapped onto chapter boundaries
         * within 15 s; otherwise the online timing is used as is. Online timings wait up to
         * 6 s for the chapters. Credits jump to the end of the credits segment so
         * after-credits scenes still play.
         */
        private void combine(long now) {
            boolean waitChapters = chaptersRequested && !chaptersDone && now - chaptersStartedAt < 6000L;
            List<Segment> named = chapterSegments(chapters, false);
            boolean strongIntro = false;
            for (Segment segment : named) strongIntro |= !segment.isRecap();
            if (!strongIntro && online != null && !waitChapters) {
                // An "Intro" chapter counts only if no OP/Opening chapter exists and AniSkip/IntroDB
                // has nothing, or has an intro starting and ending within 15 s of that chapter.
                for (Segment weak : chapterSegments(chapters, true)) {
                    boolean confirmed = online.isEmpty();
                    for (Segment o : online) {
                        if (!o.isRecap() && !o.isCredits() && !o.isPreview()
                                && Math.abs(o.startSeconds - weak.startSeconds) <= 15.0
                                && Math.abs(o.endSeconds - weak.endSeconds) <= 15.0) confirmed = true;
                    }
                    if (confirmed) named.add(weak);
                }
            }
            if (named.isEmpty() && (online == null || waitChapters)) return;
            boolean namedIntro = false;
            boolean namedRecap = false;
            boolean namedCredits = false;
            boolean namedPreview = false;
            for (Segment segment : named) {
                if (segment.isRecap()) namedRecap = true;
                else if (segment.isCredits()) namedCredits = true;
                else if (segment.isPreview()) namedPreview = true;
                else namedIntro = true;
            }
            List<Segment> out = new ArrayList<>(named);
            if (online != null && !waitChapters) {
                for (Segment segment : online) {
                    boolean covered;
                    if (segment.isRecap()) covered = namedRecap;
                    else if (segment.isCredits()) covered = namedCredits;
                    else if (segment.isPreview()) covered = namedPreview;
                    else covered = namedIntro;
                    if (covered) continue;
                    Segment snapped = snap(segment, chapters);
                    if (!overlaps(out, snapped.startSeconds, snapped.endSeconds)) out.add(snapped);
                }
            }
            StringBuilder signature = new StringBuilder();
            for (Segment segment : out) signature.append(segment.key()).append(segment.source).append(';');
            if (signature.toString().equals(appliedSignature)) return;
            appliedSignature = signature.toString();
            apply(out, onlineSource == null ? "chapters" : onlineSource, onlineStartedAt == 0
                    ? chaptersStartedAt : onlineStartedAt);
        }

        private void apply(List<Segment> found, String source, long startedAt) {
            // Refined windows must not turn a deliberate seek into the intro into an auto-skip.
            inheritSeek = current != null && currentBySeek;
            segments.clear();
            if (found != null) segments.addAll(found);
            Collections.sort(segments, new java.util.Comparator<Segment>() {
                @Override
                public int compare(Segment a, Segment b) {
                    return Double.compare(a.startSeconds, b.startSeconds);
                }
            });
            current = null;
            StringBuilder list = new StringBuilder();
            for (Segment segment : segments) {
                list.append(' ').append(segment.label).append(' ').append(fmt3(segment.startSeconds))
                        .append('-').append(fmt3(segment.endSeconds)).append(" [").append(segment.source)
                        .append(']');
            }
            log("Segments kept (" + source + ", " + (SystemClock.uptimeMillis() - startedAt)
                    + " ms): " + segments.size() + list);
        }

        // ------------------------------------------------------------ overlay

        private void ensurePill() {
            if (pill != null) return;
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) {
                View decor = activity.getWindow().getDecorView();
                content = decor instanceof FrameLayout ? decor : null;
            }
            if (content == null) return;
            host = (FrameLayout) content;
            pill = new TextView(activity);
            pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            pill.setGravity(Gravity.CENTER);
            pill.setSingleLine(true);
            pill.setPadding(dp(22), dp(11), dp(22), dp(11));
            pill.setFocusable(false);
            pill.setClickable(false);
            pill.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            pill.setVisibility(View.GONE);
            pill.setAlpha(0f);
            if (Build.VERSION.SDK_INT >= 21) pill.setElevation(dp(8));
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.END);
            params.setMargins(0, 0, dp(48), dp(48));
            host.addView(pill, params);
        }

        private void styleArmed(boolean isArmed) {
            if (pill == null) return;
            if (isArmed) {
                pill.setBackground(roundedBackground(0xF2FFFFFF, 0x00FFFFFF, dp(6)));
                pill.setTextColor(0xFF15131A);
            } else {
                pill.setBackground(roundedBackground(0xCC17151C, 0x59FFFFFF, dp(6)));
                pill.setTextColor(0xFFF2F0F5);
            }
        }

        private void showButton(Segment segment) {
            ensurePill();
            if (pill == null) return;
            pillSegment = segment;
            nextPill = false;
            noticeUntil = 0;
            armed = !controlsVisible() && SystemClock.uptimeMillis() - lastOtherKeyAt > 600L;
            pill.setText(segment.buttonText());
            styleArmed(armed);
            fadeIn();
            log("Button shown: " + segment.buttonText() + (armed ? " (OK = skip)" : " (OK to player)"));
        }

        private void showNextEpisode() {
            ensurePill();
            if (pill == null) return;
            pillSegment = null;
            nextPill = true;
            noticeUntil = 0;
            armed = !controlsVisible() && SystemClock.uptimeMillis() - lastOtherKeyAt > 600L;
            pill.setText("Next episode (OK)");
            styleArmed(armed);
            fadeIn();
            log("Button shown: Next episode (OK)" + (armed ? " (OK = next)" : " (OK to player)"));
        }

        private void playNextEpisode() {
            try {
                if (movie) {
                    log("Next episode ignored: movie");
                    return;
                }
                Object frag = resolveFragment();
                if (frag == null) {
                    log("Next episode failed: no fragment");
                    return;
                }
                try {
                    java.lang.reflect.Method direct = frag.getClass().getDeclaredMethod("navigateToNextVideo");
                    direct.setAccessible(true);
                    direct.invoke(frag);
                    log("Next episode: navigateToNextVideo");
                    return;
                } catch (Throwable ignored) {
                    // Fall through to binge/next lookup.
                }
                Object video = nextVideoOf(frag);
                if (video == null) {
                    log("Next episode failed: no next video");
                    return;
                }
                java.lang.reflect.Method nav = null;
                try {
                    nav = frag.getClass().getMethod("navigateToVideo", video.getClass());
                } catch (Throwable ignored) {
                    for (java.lang.reflect.Method m : frag.getClass().getMethods()) {
                        if ("navigateToVideo".equals(m.getName()) && m.getParameterTypes().length == 1) {
                            nav = m;
                            break;
                        }
                    }
                }
                if (nav == null) {
                    log("Next episode failed: no navigateToVideo");
                    return;
                }
                try {
                    Object vm = frag.getClass().getMethod("getViewModel").invoke(frag);
                    Object flow = vm.getClass().getMethod("getState").invoke(vm);
                    Object st = flow.getClass().getMethod("getValue").invoke(flow);
                    try {
                        st.getClass().getMethod("getNextVideo");
                        vm.getClass().getMethod("onStateChange", Class.forName(
                                "com.stremio.common.viewmodels.state.VideoPlaybackState$NextVideo"));
                    } catch (Throwable ignored2) {
                        // State event is optional; navigation still plays next.
                    }
                } catch (Throwable ignored) {
                    // Navigation without the state event still works.
                }
                nav.invoke(frag, video);
                log("Next episode: navigateToVideo");
            } catch (Throwable error) {
                log("ERROR next episode: " + error);
            }
        }

        private Object resolveFragment() {
            try {
                if (fragment != null && !(fragment instanceof Session)) return fragment;
                if (anchor == null) return null;
                Class<?> manager = Class.forName("androidx.fragment.app.FragmentManager",
                        false, activity.getClassLoader());
                return manager.getMethod("findFragment", View.class).invoke(null, anchor);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private Object nextVideoOf(Object frag) {
            try {
                Object vm = frag.getClass().getMethod("getViewModel").invoke(frag);
                Object flow = vm.getClass().getMethod("getState").invoke(vm);
                Object st = flow.getClass().getMethod("getValue").invoke(flow);
                try {
                    Object binge = st.getClass().getMethod("getBingeVideo").invoke(st);
                    if (binge != null) return binge;
                } catch (Throwable ignored) {
                    // Try manual next below.
                }
                return st.getClass().getMethod("getNextVideo").invoke(st);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private void refreshArmed(long now) {
            boolean next = (pillSegment != null || nextPill) && !controlsVisible() && now - lastOtherKeyAt > 600L;
            if (next != armed) {
                armed = next;
                styleArmed(armed);
                log("Button " + (armed ? "armed (OK = skip)" : "disarmed (OK to player)"));
            }
        }

        private void showNotice(String text) {
            ensurePill();
            if (pill == null) return;
            pillSegment = null;
            nextPill = false;
            armed = false;
            noticeUntil = SystemClock.uptimeMillis() + 2200L;
            pill.setText(text);
            styleArmed(false);
            fadeIn();
        }

        private void fadeIn() {
            updatePillPosition();
            if (!pillShown) {
                pillShown = true;
                pill.animate().cancel();
                pill.setVisibility(View.VISIBLE);
                pill.animate().alpha(1f).setDuration(160L).start();
            }
        }

        private void hidePill(boolean animate) {
            armed = false;
            pillSegment = null;
            nextPill = false;
            noticeUntil = 0;
            if (pill == null || !pillShown) return;
            pillShown = false;
            pill.animate().cancel();
            if (animate) {
                pill.animate().alpha(0f).setDuration(160L).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        if (!pillShown && pill != null) pill.setVisibility(View.GONE);
                    }
                }).start();
            } else {
                pill.setAlpha(0f);
                pill.setVisibility(View.GONE);
            }
        }

        private void updatePillPosition() {
            if (pill == null || !pillShown) return;
            // Lift above Leanback's transport row while it is on screen.
            float lift = controlsVisible() ? -dp(56) : 0f;
            if (pill.getTranslationY() != lift) {
                pill.animate().translationY(lift).setDuration(160L).start();
            }
        }

        private boolean controlsVisible() {
            try {
                if (fragment == null && anchor != null) {
                    Class<?> manager = Class.forName("androidx.fragment.app.FragmentManager",
                            false, activity.getClassLoader());
                    fragment = manager.getMethod("findFragment", View.class).invoke(null, anchor);
                    controlsVisibleMethod = fragment.getClass().getMethod("isControlsOverlayVisible");
                }
                if (fragment == null || controlsVisibleMethod == null) return false;
                return Boolean.TRUE.equals(controlsVisibleMethod.invoke(fragment));
            } catch (Throwable error) {
                fragment = this;
                controlsVisibleMethod = null;
                return false;
            }
        }

        private void installKeys() {
            try {
                Window window = activity.getWindow();
                Window.Callback existing = window.getCallback();
                if (existing instanceof KeyCallback) {
                    ((KeyCallback) existing).session = this;
                    keyCallback = (KeyCallback) existing;
                    originalCallback = keyCallback.base;
                    return;
                }
                originalCallback = existing;
                keyCallback = new KeyCallback(existing, this);
                window.setCallback(keyCallback);
            } catch (Throwable error) {
                log("ERROR key: " + error);
            }
        }

        /** True = key consumed. Only OK on an armed Skip button is ever consumed. */
        boolean onKey(KeyEvent event) {
            if (closed) return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN && isSeekKey(event.getKeyCode())) {
                lastSeekKeyAt = SystemClock.uptimeMillis();
            }
            if (!pillShown || (pillSegment == null && !nextPill)) return false;
            if (!isOkKey(event.getKeyCode())) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    // Any other key gives OK back to the player controls it just woke up.
                    lastOtherKeyAt = SystemClock.uptimeMillis();
                    if (armed) {
                        armed = false;
                        styleArmed(false);
                    }
                }
                return false;
            }
            if (!armed) return false;
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (nextPill) {
                    hidePill(false);
                    playNextEpisode();
                    return true;
                }
                Segment segment = pillSegment;
                double pos = 0;
                long duration = -1;
                try {
                    pos = player.getCurrentPosition() / 1000.0;
                    duration = player.getDuration();
                } catch (Throwable ignored) {
                    // Use defaults.
                }
                hidePill(false);
                skip(segment, false, pos, duration);
            }
            return true;
        }

        // -------------------------------------------------------------- debug

        void log(String message) {
            Log.i(TAG, message.length() > 1500 ? message.substring(0, 1500) : message);
            if (!debugEnabled) return;
            synchronized (debugLog) {
                debugLog.append(message).append('\n');
                if (debugLog.length() > 14000) {
                    debugLog.delete(0, debugLog.length() - 14000);
                }
            }
        }

        private Context ctx() {
            return activity;
        }

        private int dp(int value) {
            return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
        }

        private GradientDrawable roundedBackground(int fillColor, int strokeColor, int radius) {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setColor(fillColor);
            drawable.setCornerRadius(radius);
            drawable.setStroke(dp(1), strokeColor);
            return drawable;
        }

        private void showDebugDialog() {
            try {
                final Dialog dialog = new Dialog(ctx());
                debugDialog = dialog;
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

                LinearLayout root = new LinearLayout(ctx());
                root.setOrientation(LinearLayout.VERTICAL);
                root.setBackgroundColor(0xFF101216);

                LinearLayout header = new LinearLayout(ctx());
                header.setGravity(Gravity.CENTER_VERTICAL);
                header.setPadding(dp(16), dp(12), dp(10), dp(10));

                TextView heading = new TextView(ctx());
                heading.setText("Intro Skip DEBUG");
                heading.setTextColor(Color.WHITE);
                heading.setTextSize(18);
                heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                heading.setMaxLines(1);
                header.addView(heading, new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

                Button refresh = dialogButton("Refresh");
                Button copy = dialogButton("Copy");
                Button close = dialogButton("Close");
                header.addView(refresh);
                header.addView(copy);
                header.addView(close);
                root.addView(header);

                View divider = new View(ctx());
                divider.setBackgroundColor(0xFF343943);
                root.addView(divider, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

                final ScrollView scroll = new ScrollView(ctx());
                scroll.setFillViewport(true);
                final LinearLayout content = new LinearLayout(ctx());
                content.setOrientation(LinearLayout.VERTICAL);
                content.setPadding(dp(12), dp(12), dp(12), dp(18));
                scroll.addView(content, new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                root.addView(scroll, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

                refresh.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        renderDebugContent(content);
                    }
                });
                copy.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        ClipboardManager clipboard = (ClipboardManager) ctx()
                                .getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        if (clipboard != null) {
                            clipboard.setPrimaryClip(ClipData.newPlainText(
                                    "Intro Skip DEBUG", debugText()));
                            Toast.makeText(ctx(), "Debug copied", Toast.LENGTH_SHORT).show();
                        }
                    }
                });
                close.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                    }
                });

                renderDebugContent(content);
                dialog.setContentView(root);
                dialog.show();

                Window window = dialog.getWindow();
                if (window != null) {
                    window.setBackgroundDrawable(new ColorDrawable(0xFF101216));
                    window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
                    window.setDimAmount(0.35f);
                    if (Build.VERSION.SDK_INT >= 21) {
                        window.setStatusBarColor(0xFF0A0B0D);
                        window.setNavigationBarColor(0xFF0A0B0D);
                    }
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT);
                }
            } catch (Exception error) {
                log("ERROR showing debug: " + error);
            }
        }

        private String debugText() {
            synchronized (debugLog) {
                return debugLog.toString();
            }
        }

        private void renderDebugContent(LinearLayout content) {
            content.removeAllViews();
            String raw = debugText();
            if (raw.trim().length() == 0) {
                addDebugSection(content, "STATE", "No data received yet.");
                return;
            }

            String sectionTitle = "LOG";
            StringBuilder sectionBody = new StringBuilder();
            String[] lines = raw.split("\\n", -1);
            for (String line : lines) {
                String nextTitle = debugSectionTitle(line);
                if (nextTitle != null && sectionBody.length() > 0) {
                    addDebugSection(content, sectionTitle, sectionBody.toString().trim());
                    sectionBody.setLength(0);
                }
                if (nextTitle != null) sectionTitle = nextTitle;
                if (line.trim().length() > 0) sectionBody.append(line).append('\n');
            }
            if (sectionBody.length() > 0) {
                addDebugSection(content, sectionTitle, sectionBody.toString().trim());
            }
        }

        private void addDebugSection(LinearLayout content, String title, String body) {
            LinearLayout card = new LinearLayout(ctx());
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(10), dp(14), dp(12));
            card.setBackground(roundedBackground(0xFF1A1E25, 0xFF303743, dp(10)));

            TextView label = new TextView(ctx());
            label.setText(title);
            label.setTextColor(0xFF9FB7FF);
            label.setTextSize(11);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            card.addView(label);

            TextView text = new TextView(ctx());
            text.setText(body);
            text.setTextColor(0xFFE8EAF0);
            text.setTextSize(12);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextIsSelectable(true);
            text.setPadding(0, dp(6), 0, 0);
            card.addView(text, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, 0, 0, dp(10));
            content.addView(card, params);
        }

        private String debugSectionTitle(String line) {
            String value = line == null ? "" : line.trim();
            if (value.equals("MEDIA")) return "MEDIA FOUND";
            if (value.startsWith("REQUEST ")) return "NETWORK REQUEST";
            if (value.startsWith("RESPONSE ")) return "NETWORK RESPONSE";
            if (value.startsWith("AniList")) return "ANILIST";
                        if (value.startsWith("IntroDB")) return "INTRODB";
            if (value.startsWith("AniSkip")) return "ANISKIP";
            if (value.startsWith("Segment") || value.startsWith("Segments")) return "SEGMENTS";
            if (value.startsWith("ERROR")) return "ERROR";
            if (value.startsWith("HOOK")) return "HOOK STATE";
            return null;
        }

        private Button dialogButton(String label) {
            Button button = new Button(ctx());
            button.setText(label);
            button.setTextColor(0xFFE8EAF0);
            button.setTextSize(11);
            button.setAllCaps(false);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setMinWidth(0);
            button.setMinimumWidth(0);
            button.setPadding(dp(8), dp(2), dp(8), dp(2));
            button.setBackground(roundedBackground(0xFF252B35, 0xFF4B5667, dp(8)));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
            params.setMargins(dp(3), 0, 0, 0);
            button.setLayoutParams(params);
            return button;
        }

    }

    /** Window.Callback proxy: sees every key first, consumes only OK on an armed Skip button. */
    private static final class KeyCallback implements Window.Callback {
        final Window.Callback base;
        volatile Session session;

        KeyCallback(Window.Callback base, Session session) {
            this.base = base;
            this.session = session;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            Session current = session;
            if (current != null) {
                try {
                    if (current.onKey(event)) return true;
                } catch (Throwable error) {
                    Log.w(TAG, "key", error);
                }
            }
            return base != null && base.dispatchKeyEvent(event);
        }

        @Override
        public boolean dispatchKeyShortcutEvent(KeyEvent event) {
            return base != null && base.dispatchKeyShortcutEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            return base != null && base.dispatchTouchEvent(event);
        }

        @Override
        public boolean dispatchTrackballEvent(MotionEvent event) {
            return base != null && base.dispatchTrackballEvent(event);
        }

        @Override
        public boolean dispatchGenericMotionEvent(MotionEvent event) {
            return base != null && base.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
            return base != null && base.dispatchPopulateAccessibilityEvent(event);
        }

        @Override
        public View onCreatePanelView(int featureId) {
            return base == null ? null : base.onCreatePanelView(featureId);
        }

        @Override
        public boolean onCreatePanelMenu(int featureId, Menu menu) {
            return base != null && base.onCreatePanelMenu(featureId, menu);
        }

        @Override
        public boolean onPreparePanel(int featureId, View view, Menu menu) {
            return base != null && base.onPreparePanel(featureId, view, menu);
        }

        @Override
        public boolean onMenuOpened(int featureId, Menu menu) {
            return base != null && base.onMenuOpened(featureId, menu);
        }

        @Override
        public boolean onMenuItemSelected(int featureId, MenuItem item) {
            return base != null && base.onMenuItemSelected(featureId, item);
        }

        @Override
        public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
            if (base != null) base.onWindowAttributesChanged(attrs);
        }

        @Override
        public void onContentChanged() {
            if (base != null) base.onContentChanged();
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            if (base != null) base.onWindowFocusChanged(hasFocus);
        }

        @Override
        public void onAttachedToWindow() {
            if (base != null) base.onAttachedToWindow();
        }

        @Override
        public void onDetachedFromWindow() {
            if (base != null) base.onDetachedFromWindow();
        }

        @Override
        public void onPanelClosed(int featureId, Menu menu) {
            if (base != null) base.onPanelClosed(featureId, menu);
        }

        @Override
        public boolean onSearchRequested() {
            return base != null && base.onSearchRequested();
        }

        @Override
        public boolean onSearchRequested(SearchEvent searchEvent) {
            return base != null && base.onSearchRequested(searchEvent);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
            return base == null ? null : base.onWindowStartingActionMode(callback);
        }

        @Override
        public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
            return base == null ? null : base.onWindowStartingActionMode(callback, type);
        }

        @Override
        public void onActionModeStarted(ActionMode mode) {
            if (base != null) base.onActionModeStarted(mode);
        }

        @Override
        public void onActionModeFinished(ActionMode mode) {
            if (base != null) base.onActionModeFinished(mode);
        }

        @Override
        public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, Menu menu, int deviceId) {
            if (base != null) base.onProvideKeyboardShortcuts(data, menu, deviceId);
        }

        @Override
        public void onPointerCaptureChanged(boolean hasCapture) {
            if (base != null) base.onPointerCaptureChanged(hasCapture);
        }
    }

    private static String fmt(double seconds) {
        return String.format(Locale.US, "%.1f", seconds);
    }

    private static String fmt3(double seconds) {
        return String.format(Locale.US, "%.3f", seconds);
    }

    private static boolean isSeekKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD || keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD
                || keyCode == KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD || keyCode == KeyEvent.KEYCODE_MEDIA_STEP_FORWARD;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                // Try the superclass.
            }
        }
        return null;
    }

    /** A/B switch for measurements only: `setprop debug.aniskip.legacyseek 1` keeps NEXT_SYNC. */
    private static boolean legacySeek() {
        return sysprop("debug.aniskip.legacyseek");
    }

    /** Test-only switches set with `adb shell setprop <name> 1`; off by default. */
    private static boolean sysprop(String name) {
        try {
            Object value = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, name);
            return "1".equals(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final Pattern CHAPTER_INTRO = Pattern.compile("(?iu)(?:^|[^\\p{L}])(?:op\\d*|opening|"
            + "abertura|apertura|ouverture|vorspann|g[ée]n[ée]rique(?: de)? d[ée]but|main titles?|"
            + "title sequence|theme song)(?:$|[^\\p{L}])|オープニング");
    /** "Intro" is the cold open in many fansubs ("Intro" 0-114 s, then "OP"): weak evidence only. */
    private static final Pattern CHAPTER_WEAK_INTRO = Pattern.compile(
            "(?iu)(?:^|[^\\p{L}])(?:intro|introduction)(?:$|[^\\p{L}])");
    private static final Pattern CHAPTER_RECAP = Pattern.compile("(?iu)(?:^|[^\\p{L}])(?:recap|previously|"
            + "story so far|r[ée]sum[ée]|resumen|recapitula[çc][ãa]o|r[üu]ckblick|zusammenfassung)(?:$|[^\\p{L}])|あらすじ");
    private static final Pattern CHAPTER_CREDITS = Pattern.compile("(?iu)(?:^|[^\\p{L}])(?:ed\\d*|ending|"
            + "outro|credits|end credits|ending credits|closing credits|end theme|ending theme|"
            + "encerramento|generique de fin|abspann)(?:$|[^\\p{L}])|エンディング");
    private static final Pattern CHAPTER_PREVIEW = Pattern.compile("(?iu)(?:^|[^\\p{L}])(?:preview|"
            + "next episode|trailer|apercu|vorschau)(?:$|[^\\p{L}])|予告");
    private static final Pattern CHAPTER_NOT = Pattern.compile("(?iu)(?:^|[^\\p{L}])(?:eyecatch)(?:$|[^\\p{L}])");

    /** Chapters whose NAME says opening/intro/recap/credits/preview: their exact start/end. */
    private static List<Segment> chapterSegments(List<MkvChapters.Chapter> chapters, boolean weak) {
        List<Segment> out = new ArrayList<>();
        if (chapters == null) return out;
        for (MkvChapters.Chapter chapter : chapters) {
            double length = chapter.end - chapter.start;
            // A label, not a scene description ("Mike Takes His Leave of the Crew, Opening Credits").
            if (chapter.end <= 0 || CHAPTER_NOT.matcher(chapter.name).find()
                    || normTitle(chapter.name).split(" ").length > 4) continue;
            boolean intro = weak ? CHAPTER_WEAK_INTRO.matcher(chapter.name).find()
                    && !CHAPTER_INTRO.matcher(chapter.name).find() : CHAPTER_INTRO.matcher(chapter.name).find();
            if (intro && length >= 15 && length <= 240) {
                out.add(new Segment(chapter.start, chapter.end, "OP", "chapter \"" + chapter.name + "\""));
            } else if (!weak && CHAPTER_RECAP.matcher(chapter.name).find() && length >= 10 && length <= 360) {
                out.add(new Segment(chapter.start, chapter.end, "recap", "chapter \"" + chapter.name + "\""));
            } else if (!weak && CHAPTER_CREDITS.matcher(chapter.name).find() && length >= 15 && length <= 480) {
                out.add(new Segment(chapter.start, chapter.end, "credits", "chapter \"" + chapter.name + "\""));
            } else if (!weak && CHAPTER_PREVIEW.matcher(chapter.name).find() && length >= 10 && length <= 300) {
                out.add(new Segment(chapter.start, chapter.end, "preview", "chapter \"" + chapter.name + "\""));
            }
        }
        return out;
    }

    /** Moves each end of an online window onto the nearest chapter boundary within 15 s. */
    private static Segment snap(Segment segment, List<MkvChapters.Chapter> chapters) {
        if (chapters == null || chapters.isEmpty()) return segment;
        double start = nearestBoundary(segment.startSeconds, chapters);
        double end = nearestBoundary(segment.endSeconds, chapters);
        // Both ends on boundaries with the same length = the OP is a chapter: snap both. A lone
        // nearby boundary is trusted only within 3 s (generic chapters also cut scenes near an OP).
        boolean both = start >= 0 && end >= 0
                && Math.abs((end - start) - (segment.endSeconds - segment.startSeconds)) <= 5.0;
        if (!both) {
            if (start >= 0 && Math.abs(start - segment.startSeconds) > 3.0) start = -1;
            if (end >= 0 && Math.abs(end - segment.endSeconds) > 3.0) end = -1;
        }
        if (start < 0 && end < 0) return segment;
        double newStart = start >= 0 ? start : segment.startSeconds;
        double newEnd = end >= 0 ? end : segment.endSeconds;
        if (newEnd - newStart < 10) return segment;
        return new Segment(newStart, newEnd, segment.label, segment.source + " snapped to chapters ("
                + (start >= 0 ? "start " + fmt3(start - segment.startSeconds) + "s" : "start not snapped") + ", "
                + (end >= 0 ? "end " + fmt3(end - segment.endSeconds) + "s" : "end not snapped") + ")");
    }

    private static double nearestBoundary(double time, List<MkvChapters.Chapter> chapters) {
        double best = -1;
        for (MkvChapters.Chapter chapter : chapters) {
            for (double boundary : new double[]{chapter.start, chapter.end}) {
                if (boundary < 0 || Math.abs(boundary - time) > 15.0) continue;
                if (best < 0 || Math.abs(boundary - time) < Math.abs(best - time)) best = boundary;
            }
        }
        return best;
    }

    private static final class Segment {
        final double startSeconds;
        final double endSeconds;
        /** "OP", "recap", "credits" or "preview". */
        final String label;
        final String source;

        Segment(double startSeconds, double endSeconds, String label, String source) {
            this.startSeconds = startSeconds;
            this.endSeconds = endSeconds;
            this.label = label;
            this.source = source;
        }

        boolean isRecap() {
            return "recap".equals(label);
        }

        boolean isCredits() {
            return "credits".equals(label);
        }

        boolean isPreview() {
            return "preview".equals(label);
        }

        String key() {
            return label + "@" + startSeconds + "-" + endSeconds;
        }

        String buttonText() {
            if (isRecap()) return "Skip recap";
            if (isCredits()) return "Skip credits";
            if (isPreview()) return "Skip preview";
            return "Skip intro";
        }

        String skippedText() {
            if (isRecap()) return "Recap skipped";
            if (isCredits()) return "Credits skipped";
            if (isPreview()) return "Preview skipped";
            return "Intro skipped";
        }
    }

    private static final class MediaInfo {
        final String title;
        final int season;
        final int episode;
        final String imdbId;
        final String key;
        final String snapshot;
        final String debug;

        MediaInfo(String title, int season, int episode, String imdbId, String key, String snapshot, String debug) {
            this.title = title;
            this.season = season;
            this.episode = episode;
            this.imdbId = imdbId;
            this.key = key;
            this.snapshot = snapshot;
            this.debug = debug;
        }

        boolean ready() {
            return title.length() > 0 && episode > 0;
        }
    }

    private static MediaInfo mediaInfoFor(Player player, View playerView) {
        MediaItem item = player.getCurrentMediaItem();
        MediaMetadata playerMetadata = player.getMediaMetadata();
        MediaMetadata itemMetadata = item == null ? null : item.mediaMetadata;
        MediaItem.LocalConfiguration local = item == null ? null : item.localConfiguration;
        MediaItem.RequestMetadata request = item == null ? null : item.requestMetadata;

        List<String> titleCandidates = new ArrayList<>();
        List<String> episodeCandidates = new ArrayList<>();

        addMetadataCandidates(playerMetadata, titleCandidates, episodeCandidates);
        addMetadataCandidates(itemMetadata, titleCandidates, episodeCandidates);

        if (item != null) {
            addTitleCandidate(titleCandidates, item.mediaId);
            addEpisodeCandidate(episodeCandidates, item.mediaId);
            try {
                addUriCandidates(Uri.parse(item.mediaId), titleCandidates, episodeCandidates);
            } catch (Exception ignored) {
                // The media id is not necessarily a URI.
            }
        }
        if (local != null) {
            addUriCandidates(local.uri, titleCandidates, episodeCandidates);
            addObjectCandidates(local.tag, titleCandidates, episodeCandidates);
        }
        if (request != null) {
            addUriCandidates(request.mediaUri, titleCandidates, episodeCandidates);
            addTitleCandidate(titleCandidates, request.searchQuery);
            addEpisodeCandidate(episodeCandidates, request.searchQuery);
            addBundleCandidates(request.extras, titleCandidates, episodeCandidates);
        }

        if (playerView != null) {
            addTitleCandidate(titleCandidates, text(playerView.getContentDescription()));
            addObjectCandidates(playerView.getTag(), titleCandidates, episodeCandidates);
            View parent = playerView.getParent() instanceof View
                    ? (View) playerView.getParent() : null;
            if (parent != null) {
                addTitleCandidate(titleCandidates, text(parent.getContentDescription()));
                addObjectCandidates(parent.getTag(), titleCandidates, episodeCandidates);
            }
        }

        StringBuilder reflective = new StringBuilder();
        List<String> reflectedTitles = new ArrayList<>();
        List<String> reflectedEpisodes = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<Object, Boolean>());
        collectReflectiveCandidates(player, reflectedTitles, reflectedEpisodes, reflective, 0, visited);
        collectReflectiveCandidates(item, reflectedTitles, reflectedEpisodes, reflective, 0, visited);
        titleCandidates.addAll(reflectedTitles);
        episodeCandidates.addAll(reflectedEpisodes);

        String title = firstTitle(titleCandidates);
        // Stremio puts "<Show> - S01E07 - <Episode>" in the player metadata title: that is the
        // episode the user picked, so it wins over anything parsed from the stream URL (torrent
        // URLs carry the file index, e.g. .../6/<file>.mkv for episode 7).
        int[] metaSxE = metadataSeasonEpisode(playerMetadata, itemMetadata);
        // Stremio's own video id ("tt1844624:13:1") comes first: it does not depend on the addon.
        String[] stremioId = stremioVideoId(playerView);
        int vidEpisode = stremioId == null ? 0 : parseInt(stremioId[2]);
        int episode = vidEpisode > 0 ? vidEpisode
                : metaSxE[1] > 0 ? metaSxE[1] : firstEpisode(episodeCandidates);
        if (episode == 0) {
            episode = fallbackTrackNumber(playerMetadata, itemMetadata);
        }
        String id = item == null ? "" : safe(item.mediaId);
        String uri = local == null || local.uri == null ? "" : safe(local.uri.toString());
        int season = vidEpisode > 0 ? parseInt(stremioId[1]) : metaSxE[1] > 0 ? metaSxE[0] : 0;
        String seasonSource = vidEpisode > 0 ? "Stremio video id"
                : metaSxE[1] > 0 ? "Stremio title (SxxEyy)" : "";
        if (season == 0 && metaSxE[1] == 0 && vidEpisode == 0) {
            // Path only: a CDN host such as "s4.example.com" must not read as season 4.
            season = seasonFromText(local == null || local.uri == null ? "" : safe(local.uri.getPath()));
            if (season > 0) seasonSource = "decoded URI / file name";
        }
        if (season == 0 && metaSxE[1] == 0) {
            season = seasonFromText(id);
            if (season > 0) seasonSource = "mediaId";
        }
        String imdbId = stremioId != null ? stremioId[0]
                : firstImdbId(id, uri, titleCandidates, episodeCandidates);
        String identity = id.length() > 0 ? id : uri;
        String key = title + "|S" + season + "E" + episode + "|" + identity;
        String snapshot = key + "|" + describeMetadata(playerMetadata) + "|" +
                describeMetadata(itemMetadata) + "|" + uri + "|" + describeObject(local == null ? null : local.tag);

        StringBuilder debug = new StringBuilder();
        debug.append("Title: ").append(title.length() == 0 ? "<not found>" : title).append('\n');
        debug.append("Season: ").append(season > 0 ? season : "<not found>").append('\n');
        if (seasonSource.length() > 0) {
            debug.append("Season source: ").append(seasonSource).append('\n');
        }
        debug.append("Episode: ").append(episode > 0 ? episode : "<not found>").append('\n');
        debug.append("IMDb id: ").append(imdbId.length() == 0 ? "<not found>" : imdbId)
                .append(stremioId != null ? " (Stremio video id)" : "").append('\n');
        debug.append("Key: ").append(key).append('\n');
        appendMediaDebug(debug, item, playerMetadata, itemMetadata, local, request, playerView);
        if (reflective.length() > 0) {
            debug.append("Stremio reflection:\n").append(clip(reflective.toString()));
        }
        return new MediaInfo(title, season, episode, imdbId, key, snapshot, debug.toString());
    }

    private static String firstImdbId(String id, String uri, List<String> titles, List<String> episodes) {
        List<String> haystacks = new ArrayList<>();
        haystacks.add(safe(id));
        haystacks.add(safe(uri));
        haystacks.addAll(titles);
        haystacks.addAll(episodes);
        for (String haystack : haystacks) {
            Matcher matcher = IMDB_ID.matcher(haystack);
            if (matcher.find()) return matcher.group(1).toLowerCase(Locale.US);
        }
        return "";
    }

    private static int[] metadataSeasonEpisode(MediaMetadata first, MediaMetadata second) {
        for (MediaMetadata metadata : new MediaMetadata[]{first, second}) {
            if (metadata == null) continue;
            for (String value : new String[]{text(metadata.title), text(metadata.displayTitle)}) {
                Matcher matcher = META_SEASON_EPISODE.matcher(value);
                if (matcher.find()) {
                    return new int[]{parseInt(matcher.group(1)), parseInt(matcher.group(2))};
                }
            }
        }
        return new int[]{0, 0};
    }

    private static void addMetadataCandidates(MediaMetadata metadata,
                                              List<String> titles,
                                              List<String> episodes) {
        if (metadata == null) return;
        addTitleCandidate(titles, text(metadata.title));
        addTitleCandidate(titles, text(metadata.displayTitle));
        addTitleCandidate(titles, text(metadata.albumTitle));
        addTitleCandidate(titles, text(metadata.albumArtist));
        addTitleCandidate(titles, text(metadata.artist));
        addStructuredTitleCandidate(titles, text(metadata.description));
        addStructuredTitleCandidate(titles, text(metadata.subtitle));
        addBundleCandidates(metadata.extras, titles, episodes);
    }

    private static void addBundleCandidates(Bundle bundle,
                                             List<String> titles,
                                             List<String> episodes) {
        if (bundle == null) return;
        for (String key : bundle.keySet()) {
            Object value;
            try {
                value = bundle.get(key);
            } catch (Exception ignored) {
                continue;
            }
            String lower = key == null ? "" : key.toLowerCase(Locale.US);
            if (isTitleKey(lower)) addTitleCandidate(titles, describeObject(value));
            if (isEpisodeKey(lower)) addEpisodeCandidate(episodes, describeObject(value));
            if (value instanceof Bundle) addBundleCandidates((Bundle) value, titles, episodes);
        }
    }

    private static void addObjectCandidates(Object value,
                                             List<String> titles,
                                             List<String> episodes) {
        if (value == null) return;
        if (value instanceof Bundle) {
            addBundleCandidates((Bundle) value, titles, episodes);
        }
        String raw = describeObject(value);
        addStructuredTitleCandidate(titles, raw);
        addEpisodeCandidate(episodes, raw);
    }

    /**
     * IMDb id, season and episode of the playing item as Stremio itself knows them: the player
     * fragment's navigation arguments hold videoId ("tt1844624:13:1", or just "tt..." for a movie).
     * Null when unavailable (other catalogs such as kitsu, or the fragment is not reachable yet).
     */
    private static String[] stremioVideoId(View view) {
        try {
            if (view == null || !view.isAttachedToWindow()) return null;
            if (sysprop("debug.aniskip.noid")) return null; // test switch: force the Cinemeta fallback
            Class<?> manager = Class.forName("androidx.fragment.app.FragmentManager", false,
                    view.getContext().getClassLoader());
            Object fragment = manager.getMethod("findFragment", View.class).invoke(null, view);
            if (fragment == null) return null;
            Object args = fragment.getClass().getMethod("getArguments").invoke(fragment);
            if (!(args instanceof Bundle)) return null;
            String videoId = safe(((Bundle) args).getString("videoId"));
            if (videoId.length() == 0) videoId = safe(((Bundle) args).getString("id"));
            Matcher matcher = STREMIO_VIDEO_ID.matcher(videoId);
            if (!matcher.find()) return null;
            return new String[]{matcher.group(1),
                    matcher.group(2) == null ? "0" : matcher.group(2),
                    matcher.group(3) == null ? "0" : matcher.group(3)};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void addUriCandidates(Uri uri,
                                         List<String> titles,
                                         List<String> episodes) {
        if (uri == null) return;
        String[] queryKeys = new String[]{
                "title", "name", "series", "show", "anime", "mediaTitle",
                "torrent_name", "torrentName", "filename", "file_name", "fileName"
        };
        for (String key : queryKeys) addTitleCandidate(titles, uri.getQueryParameter(key));
        String[] episodeKeys = new String[]{
                "episode", "episodeNumber", "ep", "number", "episode_index", "episodeIndex",
                "torrent_name", "torrentName", "filename", "file_name", "fileName"
        };
        for (String key : episodeKeys) addEpisodeCandidate(episodes, uri.getQueryParameter(key));

        List<String> path = uri.getPathSegments();
        for (int i = 0; i < path.size(); i++) {
            String segment = decode(path.get(i));
            // A bare number is a torrent file index (/<hash>/<file>/6/...), never an episode.
            if (segment.matches("\\d{1,4}")) continue;
            int episode = episodeFromText(segment);
            if (episode > 0) {
                addEpisodeCandidate(episodes, segment);
                if (i > 0) addTitleCandidate(titles, path.get(i - 1));
            }
        }
    }

    private static void addTitleCandidate(List<String> candidates, String value) {
        if (value != null && value.trim().length() > 0) candidates.add(value.trim());
    }

    private static void addEpisodeCandidate(List<String> candidates, String value) {
        if (value != null && value.trim().length() > 0) candidates.add(value.trim());
    }

    private static void addStructuredTitleCandidate(List<String> candidates, String value) {
        String title = structuredTitle(value);
        if (title.length() > 0) candidates.add(title);
    }

    private static String firstTitle(List<String> candidates) {
        for (String candidate : candidates) {
            String structured = structuredTitle(candidate);
            String cleaned = cleanTitle(structured.length() > 0 ? structured : candidate);
            if (isUsableTitle(cleaned)) return cleaned;
        }
        return "";
    }

    private static String structuredTitle(String value) {
        String raw = safe(value);
        if (raw.length() == 0) return "";
        try {
            if (raw.startsWith("{") && raw.endsWith("}")) {
                JSONObject object = new JSONObject(raw);
                String[] keys = new String[]{
                        "title", "name", "series", "show", "anime", "mediaTitle",
                        "torrent_name", "torrentName", "filename", "file_name", "fileName"
                };
                for (String key : keys) {
                    String result = object.optString(key, "");
                    if (result.length() > 0) return result;
                }
                for (String key : new String[]{"meta", "media", "item", "metadata"}) {
                    JSONObject nested = object.optJSONObject(key);
                    String result = structuredTitle(nested == null ? "" : nested.toString());
                    if (result.length() > 0) return result;
                }
            }
        } catch (Exception ignored) {
            // Try the key=value/data-class form below.
        }
        Matcher matcher = STRUCTURED_TITLE.matcher(raw);
        return matcher.find() ? safe(matcher.group(1)) : "";
    }

    private static String cleanTitle(String value) {
        String result = decode(value);
        result = result.replaceAll("(?i)^(?:\\[[^\\]]+\\]\\s*)+", "");
        result = result.replaceAll("(?i)\\.(?:mkv|mp4|webm|m3u8)$", "");
        result = result.replaceAll("(?i)\\s*\\[[^\\]]*(?:\\d{3,4}p|webrip|web-dl|bluray|h264|x264|aac|sub|dub)[^\\]]*\\]\\s*$", "");
        result = result.replaceAll("(?i)(?:[ ._-]+)(?:S\\d{1,2}[ ._-]*E\\d{1,4}|episode[ ._-]*\\d{1,4}|ep[ ._-]*\\d{1,4}).*$", "");
        result = result.replaceAll("(?i)(?:[ ._-]+)(?:episode|ep)[ ._-]*\\d{1,4}.*$", "");
        result = result.replaceAll("[._-]+", " ").trim();
        return result.replaceAll("\\s{2,}", " ").trim();
    }

    private static boolean isUsableTitle(String value) {
        if (value == null || value.length() < 2 || value.length() > 160) return false;
        if (value.matches("(?i)^(episode|ep|e|stream|video|media|watch|play|source|cdn|hls|dash|file|download|unknown)[ ._-]*\\d*$")) return false;
        if (value.matches("\\d{1,4}") || value.matches("[0-9a-fA-F]{20,}")) return false;
        if (!value.matches(".*\\p{L}.*")) return false;
        if (value.contains("://") || value.contains("/") || value.contains("\\\\")) return false;
        return true;
    }

    private static int firstEpisode(List<String> candidates) {
        for (String candidate : candidates) {
            int episode = episodeFromText(candidate);
            if (episode > 0) return episode;
        }
        return 0;
    }

    private static int fallbackTrackNumber(MediaMetadata first, MediaMetadata second) {
        Integer firstTrack = first == null ? null : first.trackNumber;
        if (firstTrack != null && firstTrack > 0) return firstTrack;
        Integer secondTrack = second == null ? null : second.trackNumber;
        return secondTrack == null ? 0 : Math.max(secondTrack, 0);
    }

    private static int episodeFromText(String value) {
        String raw = decode(value);
        if (raw.length() == 0) return 0;
        try {
            if (raw.startsWith("{") && raw.endsWith("}")) {
                JSONObject object = new JSONObject(raw);
                for (String key : new String[]{
                        "episode", "episodeNumber", "ep", "number", "episode_index", "episodeIndex"
                }) {
                    int result = object.optInt(key, 0);
                    if (result > 0) return result;
                }
            }
        } catch (Exception ignored) {
            // Continue with textual patterns.
        }
        Matcher matcher = SEASON_EPISODE.matcher(raw);
        boolean found = matcher.find();
        if (!found) {
            matcher = EPISODE.matcher(raw);
            found = matcher.find();
        }
        if (found) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return raw.matches("\\d{1,4}") ? parseInt(raw) : 0;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static void collectReflectiveCandidates(Object value,
                                                    List<String> titles,
                                                    List<String> episodes,
                                                    StringBuilder debug,
                                                    int depth,
                                                    Set<Object> visited) {
        if (value == null || depth > 2 || visited.contains(value)) return;
        Class<?> type = value.getClass();
        if (type.isPrimitive() || type == String.class || type.isEnum()) return;
        visited.add(value);
        if (value instanceof Bundle) {
            addBundleCandidates((Bundle) value, titles, episodes);
            return;
        }
        Field[] fields;
        try {
            fields = type.getDeclaredFields();
        } catch (Throwable ignored) {
            return;
        }
        for (Field field : fields) {
            String name = field.getName();
            String lower = name.toLowerCase(Locale.US);
            if (!isInterestingField(lower)) continue;
            try {
                field.setAccessible(true);
                Object fieldValue = field.get(value);
                if (fieldValue == null) continue;
                String printable = describeObject(fieldValue);
                if (isTitleKey(lower)) addTitleCandidate(titles, printable);
                if (isEpisodeKey(lower)) addEpisodeCandidate(episodes, printable);
                if (debug.length() < 5000) {
                    debug.append(name).append('=').append(clip(printable)).append('\n');
                }
                if (depth < 2 && (lower.contains("media") || lower.contains("meta") ||
                        lower.contains("item") || lower.contains("stream") || lower.contains("tag") ||
                        lower.contains("request") || lower.contains("config") || lower.contains("player") ||
                        lower.contains("delegate") || lower.contains("backend") || lower.contains("engine") ||
                        lower.contains("state") || lower.contains("current") || lower.contains("impl"))) {
                    collectReflectiveCandidates(fieldValue, titles, episodes, debug,
                            depth + 1, visited);
                }
            } catch (Throwable ignored) {
                // Some framework fields cannot be accessed; continue with the others.
            }
        }
    }

    private static boolean isInterestingField(String name) {
        return isTitleKey(name) || isEpisodeKey(name) || name.contains("media") ||
                name.contains("meta") || name.contains("item") || name.contains("stream") ||
                name.contains("request") || name.contains("config") || name.contains("tag") ||
                name.contains("player") || name.contains("delegate") || name.contains("backend") ||
                name.contains("engine") || name.contains("state") || name.contains("current") ||
                name.contains("impl") || name.equals("url") || name.equals("uri");
    }

    private static boolean isTitleKey(String name) {
        return name.contains("title") || name.equals("name") || name.contains("series") ||
                name.contains("show") || name.contains("anime") || name.contains("torrentname") ||
                name.contains("filename");
    }

    private static boolean isEpisodeKey(String name) {
        return name.contains("episode") || name.equals("ep") || name.contains("episodenumber") ||
                name.contains("tracknumber") || name.equals("number") || name.contains("seasonepisode");
    }

    private static void appendMediaDebug(StringBuilder debug,
                                         MediaItem item,
                                         MediaMetadata playerMetadata,
                                         MediaMetadata itemMetadata,
                                         MediaItem.LocalConfiguration local,
                                         MediaItem.RequestMetadata request,
                                         View playerView) {
        debug.append("Player metadata: ").append(describeMetadata(playerMetadata)).append('\n');
        debug.append("Item metadata: ").append(describeMetadata(itemMetadata)).append('\n');
        if (item != null) {
            debug.append("mediaId: ").append(safe(item.mediaId)).append('\n');
        }
        if (local != null) {
            debug.append("URI: ").append(local.uri == null ? "" : local.uri).append('\n');
            debug.append("mimeType: ").append(safe(local.mimeType)).append('\n');
            debug.append("customCacheKey: ").append(safe(local.customCacheKey)).append('\n');
            debug.append("tag: ").append(clip(describeObject(local.tag))).append('\n');
        }
        if (request != null) {
            debug.append("request.mediaUri: ").append(request.mediaUri == null ? "" : request.mediaUri).append('\n');
            debug.append("request.searchQuery: ").append(safe(request.searchQuery)).append('\n');
            debug.append("request.extras: ").append(clip(bundleToText(request.extras))).append('\n');
        }
        if (playerView != null) {
            debug.append("view.contentDescription: ").append(text(playerView.getContentDescription())).append('\n');
            debug.append("view.tag: ").append(clip(describeObject(playerView.getTag()))).append('\n');
        }
    }

    private static String describeMetadata(MediaMetadata metadata) {
        if (metadata == null) return "<null>";
        return "title=" + clip(text(metadata.title)) +
                ", displayTitle=" + clip(text(metadata.displayTitle)) +
                ", albumTitle=" + clip(text(metadata.albumTitle)) +
                ", artist=" + clip(text(metadata.artist)) +
                ", subtitle=" + clip(text(metadata.subtitle)) +
                ", description=" + clip(text(metadata.description)) +
                ", trackNumber=" + metadata.trackNumber +
                ", extras=" + clip(bundleToText(metadata.extras));
    }

    private static String describeObject(Object value) {
        if (value == null) return "<null>";
        if (value instanceof Bundle) return bundleToText((Bundle) value);
        try {
            return String.valueOf(value);
        } catch (Throwable error) {
            return "<" + value.getClass().getName() + ">";
        }
    }

    private static String bundleToText(Bundle bundle) {
        if (bundle == null) return "<null>";
        StringBuilder result = new StringBuilder("{");
        for (String key : bundle.keySet()) {
            if (result.length() > 1) result.append(", ");
            Object value;
            try {
                value = bundle.get(key);
            } catch (Exception error) {
                value = "<error>";
            }
            result.append(key).append('=').append(describeObject(value));
            if (result.length() > 4000) {
                result.append("...");
                break;
            }
        }
        return result.append('}').toString();
    }

    private interface DebugLogger {
        void log(String message);
    }

    private static List<Segment> fetchAniSkip(String title, int season, int episode, long episodeLength,
                                              DebugLogger logger) {
        List<Segment> segments = new ArrayList<>();
        if (title.length() == 0 || episode < 1) {
            logger.log("AniSkip skipped: title or episode not found");
            return segments;
        }
        try {
            int[] mapped = resolveMalId(title, season, episode, logger);
            int malId = mapped[0];
            if (mapped[1] != episode) {
                logger.log("AniList: episode " + episode + " = episode " + mapped[1] + " of part 2");
                episode = mapped[1];
            }
            if (malId <= 0) {
                logger.log("AniList: no idMal found (not an anime?)");
                return segments;
            }
            logger.log("AniList: idMal = " + malId);
            // AniSkip only returns submissions whose length is close to ours (right timing);
            // length 0 = any cut, used only when nothing matches ours.
            JSONArray results = aniSkipResults(malId, episode, Math.max(0L, episodeLength), logger);
            if ((results == null || results.length() == 0) && episodeLength > 0) {
                logger.log("AniSkip: no submissions for length " + episodeLength + "s, trying any length");
                results = aniSkipResults(malId, episode, 0L, logger);
            }
            if (results == null) {
                logger.log("AniSkip: results field missing or unusable response");
                return segments;
            }
            for (int i = 0; i < results.length(); i++) {
                JSONObject result = results.optJSONObject(i);
                if (result == null) continue;
                JSONObject interval = result.optJSONObject("interval");
                if (interval == null) continue;
                double start = interval.optDouble("startTime", -1.0);
                double end = interval.optDouble("endTime", -1.0);
                if (start < 0.0 || end <= start) continue;
                String type = result.optString("skipType", "").toLowerCase(Locale.US);
                String label;
                if ("op".equals(type) || type.startsWith("op") || "mixed-op".equals(type)) label = "OP";
                else if ("recap".equals(type)) label = "recap";
                else if ("ed".equals(type) || type.startsWith("ed") || "mixed-ed".equals(type)) label = "credits";
                else continue;
                double theirLength = result.optDouble("episodeLength", 0.0);
                if (overlaps(segments, start, end)) continue;
                segments.add(new Segment(start, end, label, "AniSkip"));
                logger.log("Segment AniSkip: " + label + " " + fmt3(start) + "s -> " + fmt3(end)
                        + "s (measured on " + fmt3(theirLength) + "s, our length " + episodeLength + "s, diff "
                        + (episodeLength > 0 && theirLength > 0 ? fmt3(theirLength - episodeLength) + "s" : "?") + ")");
            }
        } catch (Exception error) {
            logger.log("ERROR AniSkip: " + error.getClass().getSimpleName() + ": " + safe(error.getMessage()));
        }
        return segments;
    }

    private static JSONArray aniSkipResults(int malId, int episode, long episodeLength,
                                            DebugLogger logger) throws Exception {
        String endpoint = ANISKIP_URL + malId + "/" + episode +
                "?types=op&types=ed&types=recap&episodeLength=" + episodeLength;
        return new JSONObject(request("GET", endpoint, null, logger)).optJSONArray("results");
    }

    private static final Pattern CANDIDATE_SEASON = Pattern.compile(
            "(?i)(?:(\\d+)(?:st|nd|rd|th)\\s+season|season\\s*(\\d+)|(second|third|fourth|fifth)(?:\\s+season)?$"
            + "|\\s(ii|iii|iv)$)");

    /**
     * AniList's single best match is not reliable ("Frieren: Beyond Journey's End" returns
     * the mini-anime ONA first), so score every candidate: title similarity, season marker,
     * format, and an episode count that must reach our episode.
     */
    private static int[] resolveMalId(String title, int season, int episode, DebugLogger logger) throws Exception {
        int wanted = season > 0 ? season : 1;
        String base = normTitle(title);
        List<String> searches = new ArrayList<>();
        String spaced = title.replaceAll(":(?=\\S)", ": ");
        searches.add(spaced);
        if (wanted > 1) {
            searches.add(spaced + " " + ordinalSeason(wanted) + " Season");
            searches.add(spaced + " Season " + wanted);
        }
        Set<Integer> seen = new HashSet<>();
        int bestId = 0;
        double bestScore = -9;
        boolean bestIsPart2 = false;
        int part1Episodes = 0;
        double part1Similarity = 0;
        StringBuilder report = new StringBuilder();
        for (String search : searches) {
            logger.log("AniList: search = " + search);
            JSONObject variables = new JSONObject().put("search", search);
            JSONObject body = new JSONObject()
                    .put("query", "query ($search: String) { Page(perPage: 10) { media(search: $search, type: ANIME) "
                            + "{ idMal format episodes title { romaji english native } synonyms } } }")
                    .put("variables", variables);
            JSONObject response = new JSONObject(request("POST", ANILIST_URL, body.toString(), logger));
            JSONObject data = response.optJSONObject("data");
            JSONObject page = data == null ? null : data.optJSONObject("Page");
            JSONArray media = page == null ? null : page.optJSONArray("media");
            if (media == null) continue;
            for (int i = 0; i < media.length(); i++) {
                JSONObject m = media.optJSONObject(i);
                int malId = m == null ? 0 : m.optInt("idMal", 0);
                if (malId <= 0 || !seen.add(malId)) continue;
                List<String> names = new ArrayList<>();
                JSONObject titles = m.optJSONObject("title");
                if (titles != null) {
                    for (String key : new String[]{"english", "romaji", "native"}) {
                        if (!titles.isNull(key)) names.add(titles.optString(key, ""));
                    }
                }
                JSONArray synonyms = m.optJSONArray("synonyms");
                for (int j = 0; synonyms != null && j < synonyms.length(); j++) names.add(synonyms.optString(j, ""));
                double similarity = 0;
                int candidateSeason = 1;
                for (String name : names) {
                    candidateSeason = Math.max(candidateSeason, seasonMarker(name));
                    String plain = normTitle(CANDIDATE_SEASON.matcher(name.trim()).replaceAll(" "));
                    similarity = Math.max(similarity, dice(base, plain));
                }
                String format = m.optString("format", "");
                double score = similarity + (candidateSeason == wanted ? 0.3 : -0.5)
                        + ("TV".equals(format) ? 0.1 : "TV_SHORT".equals(format) || "ONA".equals(format) ? -0.05 : -0.3);
                int episodes = m.isNull("episodes") ? 0 : m.optInt("episodes", 0);
                if (episodes > 0 && episode > episodes) score -= 0.4;
                report.append("\n  ").append(malId).append(' ').append(format).append(" season ").append(candidateSeason)
                        .append(" eps ").append(episodes).append(" sim ").append(fmt(similarity * 100)).append("% score ")
                        .append(fmt(score * 100)).append(' ').append(names.isEmpty() ? "" : names.get(0));
                boolean part2 = false;
                for (String name : names) part2 |= PART_TWO.matcher(name).find();
                // Split-cour shows: catalogs number cour 2 on from 13; AniSkip numbers it from 1.
                if (!part2 && candidateSeason == wanted && "TV".equals(format) && similarity > part1Similarity) {
                    part1Similarity = similarity;
                    part1Episodes = episodes;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestId = malId;
                    bestIsPart2 = part2;
                }
            }
            if (bestScore >= 1.3) break; // exact title, right season, TV: no need for more searches
        }
        logger.log("AniList: candidates" + report + "\n  -> " + bestId + " (score " + fmt(bestScore * 100) + ")");
        if (bestScore < 0.6) return new int[]{0, episode};
        if (bestIsPart2 && part1Episodes > 0 && episode > part1Episodes) {
            return new int[]{bestId, episode - part1Episodes};
        }
        return new int[]{bestId, episode};
    }

    private static final Pattern PART_TWO = Pattern.compile("(?i)(?:part|cour)\\s*(?:2|ii)\\b|2nd\\s+cour");

    private static int seasonMarker(String name) {
        Matcher matcher = CANDIDATE_SEASON.matcher(name.trim());
        if (!matcher.find()) return 1;
        if (matcher.group(1) != null) return parseInt(matcher.group(1));
        if (matcher.group(2) != null) return parseInt(matcher.group(2));
        String word = (matcher.group(3) != null ? matcher.group(3) : matcher.group(4)).toLowerCase(Locale.US);
        switch (word) {
            case "second": case "ii": return 2;
            case "third": case "iii": return 3;
            case "fourth": case "iv": return 4;
            default: return 5;
        }
    }

    private static String normTitle(String value) {
        String result = safe(value).toLowerCase(Locale.US).replace("’", "").replace("'", "");
        return result.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** Dice coefficient on word sets; 1.0 = same words. */
    private static double dice(String a, String b) {
        if (a.length() == 0 || b.length() == 0) return 0;
        if (a.equals(b)) return 1;
        Set<String> left = new HashSet<>(java.util.Arrays.asList(a.split(" ")));
        Set<String> right = new HashSet<>(java.util.Arrays.asList(b.split(" ")));
        int common = 0;
        for (String word : left) if (right.contains(word)) common++;
        return 2.0 * common / (left.size() + right.size());
    }

    /**
     * IntroDB for shows and movies. Lookup order for non-anime:
     * (a) an IMDb id from the stream/metadata, as before;
     * (b) else a keyless Cinemeta search by the cleaned show title (series catalog
     * for episodes, movie catalog for movies), exact normalized name match only;
     * (c) then IntroDB with that id + season/episode (+ duration_ms as before).
     * Runs on a NETWORK thread, never on the main thread, and never blocks AniSkip
     * (separate thread) or chapter skipping.
     */
    private static List<Segment> fetchIntroDb(android.content.Context ctx, String imdbId, String title, int season, int episode,
                                              long episodeLength, DebugLogger logger) {
        List<Segment> out = new ArrayList<>();
        String id = safe(imdbId);
        if (id.length() == 0 && safe(title).length() > 0) {
            boolean isMovie = season <= 0 && episode <= 0;
            id = cinemetaImdbId(ctx, title, isMovie, logger);
        }
        if (id.length() == 0) {
            logger.log("IntroDB skipped: no IMDb id found");
            return out;
        }
        try {
            logger.log("IntroDB: imdb = " + id);
            StringBuilder url = new StringBuilder(INTRODB_URL).append("?imdb_id=").append(id);
            if (season > 0 && episode > 0) {
                url.append("&season=").append(season).append("&episode=").append(episode);
            }
            if (episodeLength > 0) url.append("&duration_ms=").append(episodeLength * 1000L);
            JSONObject response = new JSONObject(request("GET", url.toString(), null, logger));
            String[] kinds = new String[]{"intro", "recap", "credits", "preview"};
            for (String kind : kinds) {
                JSONArray items = response.optJSONArray(kind);
                if (items == null) continue;
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    // start_ms null = from the very beginning of the episode.
                    double start = item.isNull("start_ms") ? 0.0 : item.optLong("start_ms", -1L) / 1000.0;
                    double end;
                    if (item.isNull("end_ms")) {
                        end = -1.0;
                    } else {
                        end = item.optLong("end_ms", -1L) / 1000.0;
                    }
                    // An intro/recap with no end is unusable (it would skip the episode).
                    if (start < 0.0 || end <= start || end - start > 600) continue;
                    String label;
                    if ("intro".equals(kind)) label = "OP";
                    else if ("recap".equals(kind)) label = "recap";
                    else if ("credits".equals(kind)) label = "credits";
                    else label = "preview";
                    Segment segment = new Segment(start, end, label, "IntroDB");
                    if (overlaps(out, start, end)) {
                        logger.log("IntroDB: duplicate skipped (" + kind + " " + start + "s)");
                        continue;
                    }
                    out.add(segment);
                    logger.log("Segment IntroDB: " + kind + " " + start + "s -> " + end + "s");
                }
            }
        } catch (Exception error) {
            logger.log("ERROR IntroDB: " + error.getClass().getSimpleName() + ": " + safe(error.getMessage()));
        }
        return out;
    }

    /**
     * Keyless Cinemeta lookup: Stremio's own public catalog. Exact normalized name
     * match only, so "Dexter" never resolves to "Dexter: Resurrection". When several
     * exact matches exist, the one whose release year matches a year in the title
     * wins, else the first. Results (including "no id") are cached per title.
     */
    private static String cinemetaImdbId(android.content.Context ctx, String title, boolean isMovie,
                                         DebugLogger logger) {
        String wanted = safe(title);
        if (wanted.length() == 0) return "";
        String key = (isMovie ? "movie:" : "series:") + normTitle(wanted);
        String cached = cachedCinemeta(ctx, key, wanted, logger);
        if (cached != null) return cached;
        // One request per title: a prefetch and the real lookup can arrive together.
        synchronized (CINEMETA_LOCK) {
            cached = cachedCinemeta(ctx, key, wanted, logger);
            if (cached != null) return cached;
            return cinemetaRequest(ctx, key, wanted, isMovie, logger);
        }
    }

    /** Session cache (hits and misses), then the stored 60-day hit cache; null = not cached. */
    private static String cachedCinemeta(android.content.Context ctx, String key, String wanted,
                                         DebugLogger logger) {
        String cached = CINEMETA_CACHE.get(key);
        if (cached != null) {
            logger.log("Cinemeta: cache hit for \"" + wanted + "\" -> "
                    + (cached.length() > 0 ? cached : "<none>"));
            return cached;
        }
        String stored = IntroSkipPrefs.cachedImdb(ctx, key);
        if (stored.length() > 0) {
            CINEMETA_CACHE.put(key, stored);
            logger.log("Cinemeta: cache hit (stored) for \"" + wanted + "\" -> " + stored);
            return stored;
        }
        return null;
    }

    private static String cinemetaRequest(android.content.Context ctx, String key, String wanted,
                                          boolean isMovie, DebugLogger logger) {
        String query;
        try {
            query = java.net.URLEncoder.encode(wanted, StandardCharsets.UTF_8.name()).replace("+", "%20");
        } catch (Exception error) {
            logger.log("Cinemeta: no exact name match for \"" + wanted + "\" (bad title)");
            return "";
        }
        String base = isMovie ? CINEMETA_MOVIE_URL : CINEMETA_SERIES_URL;
        try {
            // Cinemeta computes uncached searches slowly (5-10 s, sometimes a 504); the
            // server keeps the result, so one more 5 s attempt usually succeeds.
            JSONObject response;
            try {
                response = new JSONObject(request("GET", base + query + ".json", null, logger));
            } catch (Exception first) {
                logger.log("Cinemeta: retry after " + first.getClass().getSimpleName());
                response = new JSONObject(request("GET", base + query + ".json", null, logger));
            }
            JSONArray metas = response.optJSONArray("metas");
            if (metas == null || metas.length() == 0) {
                logger.log("Cinemeta: no results for \"" + wanted + "\"");
                CINEMETA_CACHE.put(key, "");
                return "";
            }
            String wantedNorm = normTitle(wanted);
            int wantedYear = extractYear(wanted);
            String wantedBase = wantedYear > 0
                    ? wantedNorm.replaceAll("\\b" + wantedYear + "\\b", " ").replaceAll("\\s+", " ").trim()
                    : wantedNorm;
            List<String> exactIds = new ArrayList<>();
            List<String> exactNames = new ArrayList<>();
            List<String> exactYears = new ArrayList<>();
            StringBuilder sample = new StringBuilder();
            for (int i = 0; i < metas.length(); i++) {
                JSONObject meta = metas.optJSONObject(i);
                if (meta == null) continue;
                String name = safe(meta.optString("name", ""));
                String imdb = safe(meta.optString("imdb_id", ""));
                if (imdb.length() == 0) imdb = safe(meta.optString("id", ""));
                Matcher matcher = IMDB_ID.matcher(imdb);
                if (!matcher.find()) continue;
                imdb = matcher.group(1).toLowerCase(Locale.US);
                if (sample.length() < 200 && name.length() > 0) {
                    if (sample.length() > 0) sample.append(", ");
                    sample.append("\"").append(name).append("\"");
                }
                String metaNorm = normTitle(name);
                boolean exact = metaNorm.equals(wantedNorm)
                        || (wantedYear > 0 && metaNorm.equals(wantedBase));
                if (exact) {
                    exactIds.add(imdb);
                    exactNames.add(name);
                    exactYears.add(safe(meta.optString("releaseInfo", "")));
                }
            }
            if (exactIds.isEmpty()) {
                logger.log("Cinemeta: no exact name match for \"" + wanted + "\" ("
                        + metas.length() + " candidates, e.g. " + sample + ")");
                CINEMETA_CACHE.put(key, "");
                return "";
            }
            String chosen;
            String chosenName;
            if (exactIds.size() == 1) {
                chosen = exactIds.get(0);
                chosenName = exactNames.get(0);
            } else if (wantedYear > 0) {
                chosen = exactIds.get(0);
                chosenName = exactNames.get(0);
                for (int i = 0; i < exactIds.size(); i++) {
                    if (exactYears.get(i).contains(String.valueOf(wantedYear))) {
                        chosen = exactIds.get(i);
                        chosenName = exactNames.get(i);
                        break;
                    }
                }
            } else {
                chosen = exactIds.get(0);
                chosenName = exactNames.get(0);
            }
            logger.log("Cinemeta: \"" + wanted + "\" -> " + chosen + " (\"" + chosenName + "\", "
                    + exactIds.size() + " exact match(es))");
            CINEMETA_CACHE.put(key, chosen);
            IntroSkipPrefs.cacheImdb(ctx, key, chosen);
            return chosen;
        } catch (Exception error) {
            logger.log("ERROR Cinemeta: " + error.getClass().getSimpleName() + ": " + safe(error.getMessage()));
            return "";
        }
    }

    private static int extractYear(String value) {
        Matcher matcher = YEAR.matcher(safe(value));
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private static boolean overlaps(List<Segment> segments, double candidateStart, double candidateEnd) {
        for (Segment segment : segments) {
            double start = Math.max(segment.startSeconds, candidateStart);
            double end = Math.min(segment.endSeconds, candidateEnd);
            if (end - start > 1.0) return true;
        }
        return false;
    }

    private static String ordinalSeason(int season) {
        if (season % 100 >= 11 && season % 100 <= 13) return season + "th";
        switch (season % 10) {
            case 1: return season + "st";
            case 2: return season + "nd";
            case 3: return season + "rd";
            default: return season + "th";
        }
    }

    private static int seasonFromText(String value) {
        // URI.toString() contains %20 around the filename. Decode before using
        // token boundaries, otherwise the final encoded space ends in a digit
        // and S04E04 is not recognized as a separate token.
        String raw = decode(value);
        Matcher matcher = SEASON.matcher(raw);
        if (matcher.find()) return parseInt(matcher.group(1));
        matcher = SEASON_WORD.matcher(raw);
        if (matcher.find()) return parseInt(matcher.group(1));
        return 0;
    }

    private static String request(String method, String endpoint, String body, DebugLogger logger) throws Exception {
        return request(method, endpoint, body, null, logger);
    }

    private static String request(String method, String endpoint, String body, String auth,
                                  DebugLogger logger) throws Exception {
        String shown = endpoint.replaceAll("api_key=[^&]*", "api_key=***");
        logger.log("REQUEST " + method + " " + shown +
                (body == null ? "" : "\nBody: " + clip(body)));
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "Stremio");
        if (auth != null && auth.length() > 0) {
            connection.setRequestProperty("Authorization", auth);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            OutputStream output = connection.getOutputStream();
            output.write(body.getBytes(StandardCharsets.UTF_8));
            output.close();
        }
        int responseCode = connection.getResponseCode();
        InputStream input = responseCode >= 400
                ? connection.getErrorStream() : connection.getInputStream();
        if (input == null) {
            logger.log("RESPONSE " + responseCode + ": <vide>");
            return "{}";
        }
        StringBuilder response = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) response.append(line);
            reader.close();
        } finally {
            connection.disconnect();
        }
        logger.log("RESPONSE " + responseCode + "\n" + clip(response.toString()));
        return response.toString();
    }

    private static String clip(String value) {
        if (value == null) return "";
        return value.length() <= 6000 ? value : value.substring(0, 6000) + "...[cut]";
    }

    private static String decode(String value) {
        String raw = safe(value);
        if (raw.length() == 0) return "";
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return raw;
        }
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
