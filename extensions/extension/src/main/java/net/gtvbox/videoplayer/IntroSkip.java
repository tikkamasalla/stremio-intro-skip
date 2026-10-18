package net.gtvbox.videoplayer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Auto-skips the intro (and recap) chapter of the file being played, using:
 * (a) the chapter list Vimu already parses for its seek-bar ticks;
 * (b) AniSkip (via AniList GraphQL -> MAL id -> AniSkip v2 API) for anime or confident matches;
 * (c) IntroDB (via a keyless Cinemeta title lookup -> IntroDB v4 API) as a fallback for western TV episodes.
 * Online segments from AniSkip and IntroDB snap to file chapter boundaries when chapters exist.
 *
 * Hooked from PlayerFragment.onResume/onPause.
 * Rules: one skip per chapter, 2.5 s guard after our seek, a chapter named OP/Opening wins over one
 * named "Intro". Starting or resuming inside the intro skips; only a deliberate user seek (a jump
 * within 5 s of a left/right/rewind/fast-forward key, after 3 s of playback) is left alone.
 * Key presses are observed through a pass-through proxy of the activity's Window.Callback.
 */
public final class IntroSkip implements Runnable {
    private static final String TAG = "VimuIntroSkip";
    private static final long POLL_MS = 200L;
    private static final int HTTP_TIMEOUT_MS = 5000;
    private static final String PREFS_NAME = "vimu_introskip_cache";
    private static final long CACHE_TTL_MS = 60L * 24L * 60L * 60L * 1000L; // 60 days

    private static final String ANILIST_URL = "https://graphql.anilist.co";
    private static final String ANISKIP_URL = "https://api.aniskip.com/v2/skip-times/";
    private static final String CINEMETA_SERIES_URL = "https://v3-cinemeta.strem.io/catalog/series/top/search=";
    private static final Pattern IMDB_ID = Pattern.compile("(?i)(tt\\d{7,})");
    private static final Pattern YEAR = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b");

    private static final WeakHashMap<PlayerFragment, IntroSkip> ACTIVE = new WeakHashMap<>();
    private static final Map<String, String> MAL_CACHE = new ConcurrentHashMap<>();
    private static final Object CINEMETA_LOCK = new Object();
    private static final Map<String, String> CINEMETA_CACHE = new ConcurrentHashMap<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** OP / opening / title-sequence names: anime ("OP", "Opening 2") and western ("Opening Credits", "Main Titles"). */
    private static final String OPENING_WORDS = "op\\d{0,2}|opening(?:\\s+(?:song|theme|credits|titles?|sequence))?"
            + "|(?:main|title)\\s+(?:titles?|credits?|sequence|song|theme|card)|titles?|theme(?:\\s+(?:song|music))?";
    /** a) chapter names that mean intro / recap. */
    private static final Pattern INTRO_NAME = Pattern.compile(
            "(?i)(?:^|[^a-z])(?:" + OPENING_WORDS + "|intro(?:duction)?|recap|previously(?:\\s+on)?)(?:[^a-z]|$)|オープニング|あらすじ");
    /** "Intro" alone: counts only when the file has no OP/Opening chapter (cold opens are often "Intro"). */
    private static final Pattern WEAK_INTRO_NAME = Pattern.compile(
            "(?i)(?:^|[^a-z])intro(?:duction)?(?:[^a-z]|$)");
    private static final Pattern OPENING_NAME = Pattern.compile(
            "(?i)(?:^|[^a-z])(?:" + OPENING_WORDS + ")(?:[^a-z]|$)|オープニング");
    /** Names that must never be auto-skipped even if they also match the intro words. */
    private static final Pattern NEVER_NAME = Pattern.compile(
            "(?i)(?:^|[^a-z])(?:ed\\d{0,2}|ending|outro|(?:end|ending|closing)\\s+(?:credits?|titles?)|preview|next\\s+episode|eyecatch)(?:[^a-z]|$)");
    /** A bare "Credits" chapter: the opening credits near the start, the end credits near the end. */
    private static final Pattern CREDITS_NAME = Pattern.compile("(?i)(?:^|[^a-z])credits?(?:[^a-z]|$)");
    /** b) names that carry no meaning: "", "Chapter 1", "Chapter 01", "01", "00:01:25.000". */
    private static final Pattern GENERIC_NAME = Pattern.compile(
            "(?i)^\\s*(?:(?:chapter|chapitre|kapitel|cap[ií]tulo|capitolo|hoofdstuk|rozdział|глава|ch\\.?)\\s*)?\\d{0,3}\\s*$|^\\s*\\d{1,2}:\\d{2}(?::\\d{2})?(?:[.,]\\d+)?\\s*$");

    // ---- Media filename / title parsing & AniList scoring patterns ----
    private static final Pattern FILE_EXT = Pattern.compile(
            "(?i)\\.(?:mkv|mp4|avi|webm|ts|m2ts|m4v|mov|flv|wmv|ogm|mpg|mpeg|iso|strm)$");
    private static final Pattern LEADING_GROUP = Pattern.compile("^\\s*\\[[^\\]]+\\]");
    private static final Pattern CRC_HASH = Pattern.compile("(?i)\\[[0-9a-f]{8}\\]");
    private static final Pattern YEAR_PAREN = Pattern.compile("\\(((?:19|20)\\d{2})\\)");
    private static final Pattern YEAR_TOKEN = Pattern.compile("\\b((?:19|20)\\d{2})\\b");
    private static final Pattern BRACKET_TAG = Pattern.compile("\\[[^\\]]*\\]");
    private static final Pattern PAREN_META = Pattern.compile(
            "(?i)\\([^)]*(?:\\d{3,4}p|\\d{3,4}x\\d{3,4}|bluray|bdrip|bd|web-?dl|webrip|web|hdtv|dvd|hevc|x264|x265|h\\.?264|h\\.?265|av1|10\\s*bit|8\\s*bit|aac|flac|eac3|ac3|opus|dual|multi|sub|dub|raw|uncensored|remux|cr|b-global)[^)]*\\)");
    private static final Pattern TRAILING_TECH = Pattern.compile(
            "(?i)(?:^|[\\s._-]+)(?:\\d{3,4}p|2160p|1080p|720p|480p|4k|web-?dl|webrip|bluray|bdrip|bdremux|remux|hdtv|dvdrip|hevc|x264|x265|h\\.?264|h\\.?265|av1|10bit|8bit|aac\\d*(?:\\.\\d+)?|flac|ddp?\\d*(?:\\.\\d+)?|eac3|ac3|dual[ ._-]*audio|multi[ ._-]*subs?)\\b.*$");
    private static final Pattern SXXEYY = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])S(\\d{1,2})[ ._-]*E(\\d{1,4})(?:v\\d+)?(?=$|[^a-z0-9])");
    private static final Pattern SXX_DASH_EP = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:S|Season\\s*)(\\d{1,2})\\s*[-–—]+\\s*(?:Ep(?:isode)?\\.?\\s*)?(\\d{1,4})(?:v\\d+)?(?=$|[^a-z0-9])");
    private static final Pattern NXNN = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(\\d{1,2})x(\\d{2,3})(?=$|[^a-z0-9])");
    private static final Pattern ANIME_DASH_EP = Pattern.compile(
            "(?i)^(.+?)\\s+[-–—]+\\s*(?:Ep(?:isode)?\\.?\\s*)?(\\d{1,4})(?:v\\d+)?(?:\\s*[-–—]+\\s*[^\\d].*)?$");
    private static final Pattern EXPLICIT_EP = Pattern.compile(
            "(?i)^(.+?)[\\s._-]+(?:Episode|Ep\\.?|E)\\s*(\\d{1,4})(?:v\\d+)?(?:\\b.*)?$");
    private static final Pattern TRAILING_EP = Pattern.compile(
            "^(.+?\\p{L}[^\\d]*?)\\s+(\\d{2,3})(?:v\\d+)?$");
    private static final Pattern TITLE_SEASON = Pattern.compile(
            "(?i)(?:\\bSeason\\s*(\\d{1,2})|\\b(\\d{1,2})(?:st|nd|rd|th)\\s+Season|\\bS(\\d{1,2}))\\b");
    private static final Pattern CANDIDATE_SEASON = Pattern.compile(
            "(?i)(?:(\\d+)(?:st|nd|rd|th)\\s+season|season\\s*(\\d+)|(second|third|fourth|fifth)(?:\\s+season)?$"
                    + "|\\s(ii|iii|iv)$)");
    private static final Pattern PART_TWO = Pattern.compile(
            "(?i)(?:part|cour)\\s*(?:2|ii)\\b|2nd\\s+(?:part|cour)\\b");

    private final WeakReference<PlayerFragment> fragment;
    private boolean running;
    private e player;
    private int durationMs = Integer.MIN_VALUE;
    private List<Seg> segments;
    private boolean chaptersHaveIntro;
    /** Online timestamps (AniSkip or IntroDB) for the episode; used only when chapters give none. */
    private volatile List<Seg> remote;
    private boolean remoteStarted;
    private boolean remoteApplied;
    private volatile int mediaGen;
    private long nextChapterRead;
    private int chapterReads;
    private String chapterSignature;
    private final Set<String> skipped = new HashSet<>();
    private int lastPos = -1;
    private long lastAt;
    private long guardUntil;
    private int guardTarget;
    private Seg current;
    private boolean currentBySeek;
    private boolean currentByJump;
    private long firstPlayingAt;
    private int flowPos = -1;
    private int flowTicks;
    private volatile long lastSeekKeyAt;
    private Window hookedWindow;
    private Window.Callback hookedOriginal;
    private Window.Callback hookedProxy;
    private final Runnable precise = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            try {
                tick();
            } catch (Throwable error) {
                Log.w(TAG, "precise", error);
            }
        }
    };

    private static final class Seg {
        final int start;
        final int end;
        final String name;
        final String rule;

        Seg(int start, int end, String name, String rule) {
            this.start = start;
            this.end = end;
            this.name = name;
            this.rule = rule;
        }

        String key() {
            return start + "-" + end;
        }

        boolean recap() {
            return name != null && name.toLowerCase(Locale.US).matches(".*(recap|previously|あらすじ).*");
        }
    }

    private static final class ParsedMedia {
        final String title;
        final int season;
        final int episode;
        final String year;
        final boolean looksLikeAnime;
        final String raw;
        String imdbId;

        ParsedMedia(String title, int season, int episode, String year, boolean looksLikeAnime, String raw) {
            this.title = title;
            this.season = season;
            this.episode = episode;
            this.year = year;
            this.looksLikeAnime = looksLikeAnime;
            this.raw = raw;
        }
    }

    private static final class AniSkipCandidate {
        final int startMs;
        final int endMs;
        final String name;
        final double theirLengthSec;
        final double lengthDiffSec;

        AniSkipCandidate(int startMs, int endMs, String name, double theirLengthSec, double lengthDiffSec) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.name = name;
            this.theirLengthSec = theirLengthSec;
            this.lengthDiffSec = lengthDiffSec;
        }
    }

    private IntroSkip(PlayerFragment fragment) {
        this.fragment = new WeakReference<>(fragment);
    }

    public static void start(PlayerFragment f) {
        try {
            if (!VimuIntroSkipPrefs.isEnabled(f)) return;
            IntroSkip skip = ACTIVE.get(f);
            if (skip == null) {
                skip = new IntroSkip(f);
                ACTIVE.put(f, skip);
            }
            if (!skip.running) {
                skip.running = true;
                skip.lastPos = -1;
                skip.firstPlayingAt = 0;
                skip.hookKeys(f);
                MAIN.removeCallbacks(skip);
                MAIN.post(skip);
            }
        } catch (Throwable error) {
            Log.w(TAG, "start", error);
        }
    }

    public static void stop(PlayerFragment f) {
        try {
            IntroSkip skip = ACTIVE.get(f);
            if (skip != null) {
                skip.running = false;
                skip.unhookKeys();
                MAIN.removeCallbacks(skip);
                MAIN.removeCallbacks(skip.precise);
            }
        } catch (Throwable error) {
            Log.w(TAG, "stop", error);
        }
    }

    private static boolean isSeekKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD || keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD
                || keyCode == KeyEvent.KEYCODE_MEDIA_STEP_BACKWARD || keyCode == KeyEvent.KEYCODE_MEDIA_STEP_FORWARD;
    }

    /** Wraps the activity's Window.Callback in a pass-through proxy that only timestamps seek keys. */
    private void hookKeys(PlayerFragment f) {
        try {
            unhookKeys();
            Activity activity = f.getActivity();
            if (activity == null) return;
            final Window window = activity.getWindow();
            if (window == null) return;
            final Window.Callback original = window.getCallback();
            if (original == null) return;
            Window.Callback proxy = (Window.Callback) Proxy.newProxyInstance(Window.Callback.class.getClassLoader(),
                    new Class<?>[]{Window.Callback.class}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object o, Method method, Object[] args) throws Throwable {
                            if ("dispatchKeyEvent".equals(method.getName()) && args != null && args.length == 1
                                    && args[0] instanceof KeyEvent && isSeekKey(((KeyEvent) args[0]).getKeyCode())) {
                                lastSeekKeyAt = SystemClock.uptimeMillis();
                            }
                            try {
                                return method.invoke(original, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause() != null ? e.getCause() : e;
                            }
                        }
                    });
            window.setCallback(proxy);
            hookedWindow = window;
            hookedOriginal = original;
            hookedProxy = proxy;
            Log.i(TAG, "seek-key hook installed");
        } catch (Throwable error) {
            Log.w(TAG, "hookKeys", error);
        }
    }

    private void unhookKeys() {
        try {
            if (hookedWindow != null && hookedWindow.getCallback() == hookedProxy) {
                hookedWindow.setCallback(hookedOriginal);
            }
        } catch (Throwable error) {
            Log.w(TAG, "unhookKeys", error);
        }
        hookedWindow = null;
        hookedOriginal = null;
        hookedProxy = null;
    }

    @Override
    public void run() {
        if (!running) return;
        try {
            tick();
        } catch (Throwable error) {
            Log.w(TAG, "tick", error);
        }
        if (running) MAIN.postDelayed(this, POLL_MS);
    }

    private void tick() {
        PlayerFragment f = fragment.get();
        if (f == null) {
            running = false;
            return;
        }
        e p = PlayerFragment.G0(f);
        if (p == null) return;
        long now = SystemClock.uptimeMillis();
        int duration = p.getDuration();
        if (p != player) {
            player = p;
            resetMedia();
        }
        durationMs = duration;
        boolean playingNow = p.isPlaying();
        int posNow = p.getCurrentPosition();
        if (firstPlayingAt == 0 && playingNow) firstPlayingAt = now;
        int moved = posNow - flowPos;
        flowPos = posNow;
        if (!playingNow || moved < 0 || moved >= 1500) flowTicks = 0;
        else if (moved > 0) flowTicks++;
        if (now >= nextChapterRead && (segments == null ? chapterReads < 40 : true)) {
            chapterReads++;
            // Until found: every 3 s. Afterwards every 10 s, only to notice a new file in the same player.
            nextChapterRead = now + (segments == null ? 3000L : 10000L);
            String signature = chapterSignature(p);
            if (signature != null && !signature.equals(chapterSignature)) {
                if (chapterSignature != null) {
                    Log.i(TAG, "chapter list changed: new media");
                    resetMedia();
                }
                chapterSignature = signature;
                readChapters(p, duration);
                // If online segments were applied before chapters loaded and chapters have no intro, re-snap now.
                if (!chaptersHaveIntro) remoteApplied = false;
            }
        }
        if (!remoteStarted && duration > 0 && playingNow) {
            remoteStarted = true;
            startRemoteLookup(f, duration);
        }
        List<Seg> fromRemote = remote;
        if (!chaptersHaveIntro && !remoteApplied && fromRemote != null && !fromRemote.isEmpty()
                && chapterReads >= 1) {
            // Chapters had their chance (read at least once) and yielded no intro chapter: use online segments.
            remoteApplied = true;
            List<Seg> snapped = snapSegments(fromRemote, p.E(), duration);
            segments = snapped;
            for (Seg s : snapped) {
                Log.i(TAG, "final segment '" + s.name + "' " + s.start + "-" + s.end + "ms (source " + s.rule + ")");
            }
        }
        if (segments == null || segments.isEmpty()) return;

        int pos = p.getCurrentPosition();
        boolean playing = p.isPlaying();
        long elapsed = lastPos < 0 ? POLL_MS : now - lastAt;
        boolean jumped = lastPos >= 0 && (pos < lastPos - 750 || pos > lastPos + elapsed * 2.5 + 1000);
        lastPos = pos;
        lastAt = now;

        if (guardUntil != 0) {
            if (now < guardUntil && pos < guardTarget - 500) return;
            guardUntil = 0;
            jumped = false;
            // Our own seek just landed: the decoder is running, so a chained skip (recap, then intro) need not wait.
            if (playing) flowTicks = 4;
        }

        Seg seg = null;
        for (Seg s : segments) {
            if (pos >= s.start && pos < s.end - 400) {
                seg = s;
                break;
            }
        }
        if (seg != current) {
            current = seg;
            // Only a jump the USER made (seek key just before it, after 3 s of playback) is a
            // deliberate seek. Start/resume and the player's own repositioning still skip.
            currentByJump = seg != null && jumped;
            currentBySeek = currentByJump && now - lastSeekKeyAt < 5000L && firstPlayingAt != 0
                    && now - firstPlayingAt > 3000L;
            if (seg != null) {
                Log.i(TAG, "enter '" + seg.name + "' pos=" + pos + "ms (start " + seg.start + ", late "
                        + (pos - seg.start) + "ms, " + (currentBySeek ? "user seek" : currentByJump
                        ? "player jump (start/resume)" : "playback") + ")");
            }
        }
        // Seeking before the decoder is really producing frames (the position still sits on the
        // resume point) crashed ExoPlayer natively on the TV, so wait for 4 polls of steady advance.
        boolean flowing = flowTicks >= 4;
        if (seg != null && playing && flowing && !currentBySeek && !skipped.contains(seg.key())) {
            skip(f, p, seg, pos);
        }
        if (seg == null && playing) {
            // 200 ms polling would show up to 0.2 s of the intro: re-check right at its start.
            for (Seg s : segments) {
                int ahead = s.start - pos;
                if (ahead > 0 && ahead <= POLL_MS + 50 && !skipped.contains(s.key())) {
                    MAIN.removeCallbacks(precise);
                    MAIN.postDelayed(precise, Math.max(ahead + 10L, 15L));
                    break;
                }
            }
        }
    }

    // ---- Remote lookup: (1) AniSkip (AniList -> MAL id -> AniSkip), (2) IntroDB (TMDB -> IntroDB) ----

    private void startRemoteLookup(PlayerFragment f, final int durationMs) {
        Context appCtx = null;
        ParsedMedia parsed = null;
        try {
            Activity activity = f.getActivity();
            if (activity != null) {
                appCtx = activity.getApplicationContext();
                parsed = extractMediaFromIntent(activity.getIntent());
            }
        } catch (Throwable error) {
            Log.w(TAG, "remote intent parse", error);
        }
        if (parsed == null || parsed.title.length() == 0 || parsed.episode <= 0) {
            Log.i(TAG, "Remote lookup: no usable title/episode in the playing intent");
            return;
        }
        final ParsedMedia info = parsed;
        final Context ctx = appCtx;
        final int gen = mediaGen;
        Log.i(TAG, "Media detected: title='" + info.title + "' S" + info.season + "E" + info.episode
                + (info.year != null ? " year=" + info.year : "") + " animeHint=" + info.looksLikeAnime
                + " (from '" + info.raw + "')");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    List<Seg> out = fetchAniSkip(ctx, info, durationMs);
                    if ((out == null || out.isEmpty()) && gen == mediaGen) {
                        out = fetchIntroDb(ctx, info, durationMs);
                    }
                    if (gen == mediaGen && out != null && !out.isEmpty()) {
                        remote = out;
                    }
                } catch (Throwable error) {
                    Log.w(TAG, "Remote lookup failed: " + error);
                }
            }
        }, "IntroSkipRemote").start();
    }

    private static ParsedMedia extractMediaFromIntent(Intent intent) {
        if (intent == null) return null;
        List<String> candidates = new ArrayList<>();
        try {
            for (String key : new String[]{"title", "forcename", "name", "filename", "file_name", "video_title"}) {
                String val = intent.getStringExtra(key);
                if (val != null && val.trim().length() > 0) candidates.add(val.trim());
            }
            Uri uri = intent.getData();
            if (uri != null) {
                for (String key : new String[]{"title", "name", "filename", "file", "torrentName"}) {
                    try {
                        String q = uri.getQueryParameter(key);
                        if (q != null && q.trim().length() > 0) candidates.add(q.trim());
                    } catch (Throwable ignored) {
                    }
                }
                List<String> segments = uri.getPathSegments();
                if (segments != null) {
                    for (int i = segments.size() - 1; i >= 0; i--) {
                        String seg = segments.get(i);
                        if (seg != null && seg.trim().length() > 0) candidates.add(seg.trim());
                    }
                }
            }
            Bundle extras = intent.getExtras();
            if (extras != null) {
                for (String key : extras.keySet()) {
                    Object v = extras.get(key);
                    if (v instanceof String) {
                        String s = ((String) v).trim();
                        if (s.length() > 0 && !candidates.contains(s)) candidates.add(s);
                    }
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "extractMediaFromIntent", error);
        }
        ParsedMedia fallback = null;
        for (String raw : candidates) {
            ParsedMedia m = parseCandidate(raw);
            if (m != null && m.title.length() > 0 && m.episode > 0) {
                if (m.looksLikeAnime) {
                    m.imdbId = findImdbId(candidates);
                    return m;
                }
                if (fallback == null) fallback = m;
            }
        }
        if (fallback != null) fallback.imdbId = findImdbId(candidates);
        return fallback;
    }

    /** First IMDb id (tt1234567 style) found in the playing intent, or null. */
    private static String findImdbId(List<String> candidates) {
        if (candidates == null) return null;
        for (String raw : candidates) {
            if (raw == null) continue;
            Matcher matcher = IMDB_ID.matcher(raw);
            if (matcher.find()) return matcher.group(1).toLowerCase(Locale.US);
        }
        return null;
    }

    /**
     * Parses release names like:
     * - "[sam] Ginga Eiyuu Densetsu - Die Neue These - 08 [BD 1080p HEVC].mkv"
     * - "[SubsPlease] Frieren - 05 (1080p).mkv"
     * - "Show S01E05" / "Show.2023.S01E05.1080p.WEB-DL.mkv"
     */
    private static ParsedMedia parseCandidate(String input) {
        if (input == null) return null;
        String decoded = urlDecode(input).trim();
        if (decoded.length() == 0 || decoded.startsWith("http://") || decoded.startsWith("https://")) {
            int slash = decoded.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < decoded.length()) decoded = decoded.substring(slash + 1);
        }
        String work = FILE_EXT.matcher(decoded).replaceAll("").trim();
        if (work.length() == 0) return null;

        boolean animeHint = LEADING_GROUP.matcher(work).find() || CRC_HASH.matcher(work).find();
        String year = null;
        Matcher yp = YEAR_PAREN.matcher(work);
        if (yp.find()) year = yp.group(1);

        // Strip [group], [BD 1080p HEVC], [ABCD1234], and (1080p) tags
        work = BRACKET_TAG.matcher(work).replaceAll(" ");
        work = PAREN_META.matcher(work).replaceAll(" ");
        work = YEAR_PAREN.matcher(work).replaceAll(" ");
        work = work.replaceAll("\\(\\s*\\)", " ").replaceAll("\\s+", " ").trim();

        int season = 1;
        int episode = -1;
        String rawTitle = null;

        // 1) S01E05 / s1e5
        Matcher sxe = SXXEYY.matcher(work);
        if (sxe.find()) {
            season = safeParseInt(sxe.group(1), 1);
            episode = safeParseInt(sxe.group(2), -1);
            rawTitle = work.substring(0, sxe.start());
        }

        // 2) S2 - 05 / Season 2 - 05
        if (episode <= 0) {
            Matcher sdash = SXX_DASH_EP.matcher(work);
            if (sdash.find()) {
                season = safeParseInt(sdash.group(1), 1);
                episode = safeParseInt(sdash.group(2), -1);
                rawTitle = work.substring(0, sdash.start());
                animeHint = true;
            }
        }

        // 3) 1x05
        if (episode <= 0) {
            Matcher nxn = NXNN.matcher(work);
            if (nxn.find()) {
                season = safeParseInt(nxn.group(1), 1);
                episode = safeParseInt(nxn.group(2), -1);
                rawTitle = work.substring(0, nxn.start());
            }
        }

        // Strip unbracketed trailing tech tokens before anime dash / trailing episode matching
        String strippedTech = TRAILING_TECH.matcher(work).replaceAll("").trim();
        if (strippedTech.length() == 0) strippedTech = work;

        // 4) Anime dash pattern: "Ginga Eiyuu Densetsu - Die Neue These - 08" or "Frieren - 05"
        if (episode <= 0) {
            String spaced = strippedTech.replace('_', ' ');
            // Match the LAST " - <ep>" by using greedy (.+) on the left
            Matcher animeDash = Pattern.compile(
                    "(?i)^(.+)\\s+[-–—]+\\s*(?:Ep(?:isode)?\\.?\\s*)?(\\d{1,4})(?:v\\d+)?(?:\\s*[-–—]+\\s*[^\\d].*)?$")
                    .matcher(spaced);
            if (!animeDash.matches()) {
                animeDash = ANIME_DASH_EP.matcher(spaced);
            }
            if (animeDash.matches()) {
                int ep = safeParseInt(animeDash.group(2), -1);
                if (ep > 0 && ep < 1900) {
                    rawTitle = animeDash.group(1);
                    episode = ep;
                    animeHint = true;
                }
            }
        }

        // 5) Explicit "Episode 05" / "Ep 05"
        if (episode <= 0) {
            String spaced = strippedTech.replace('.', ' ').replace('_', ' ');
            Matcher exp = EXPLICIT_EP.matcher(spaced);
            if (exp.matches()) {
                int ep = safeParseInt(exp.group(2), -1);
                if (ep > 0 && ep < 1900) {
                    rawTitle = exp.group(1);
                    episode = ep;
                }
            }
        }

        // 6) Trailing episode number when anime group/hash hint is present: "[Group] Show Name 05"
        if (episode <= 0 && animeHint) {
            String spaced = strippedTech.replace('_', ' ').replace('.', ' ').replaceAll("\\s+", " ").trim();
            Matcher trail = TRAILING_EP.matcher(spaced);
            if (trail.matches()) {
                int ep = safeParseInt(trail.group(2), -1);
                if (ep > 0 && ep < 1900) {
                    rawTitle = trail.group(1);
                    episode = ep;
                }
            }
        }

        if (episode <= 0 || rawTitle == null) return null;

        // Clean up the extracted title
        String clean = rawTitle.replace('_', ' ').replace('.', ' ');
        if (year == null) {
            Matcher yt = YEAR_TOKEN.matcher(clean);
            if (yt.find()) year = yt.group(1);
        }
        clean = YEAR_TOKEN.matcher(clean).replaceAll(" ");

        // Extract inline season indicator from title if season is still 1 (e.g., "Show Season 2")
        Matcher ts = TITLE_SEASON.matcher(clean);
        if (ts.find()) {
            int foundSeason = safeParseInt(
                    ts.group(1) != null ? ts.group(1) : ts.group(2) != null ? ts.group(2) : ts.group(3), 1);
            if (season == 1 && foundSeason > 0) season = foundSeason;
            clean = clean.substring(0, ts.start()) + " " + clean.substring(ts.end());
        }
        if (season == 1) {
            int marker = seasonMarker(clean);
            if (marker > 1) {
                season = marker;
                clean = CANDIDATE_SEASON.matcher(clean.trim()).replaceAll(" ");
            }
        }

        clean = clean.replaceAll("^\\s*[-–—:]+\\s*", "")
                .replaceAll("\\s*[-–—:]+\\s*$", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (clean.length() < 2 || !clean.matches(".*\\p{L}.*")) return null;
        return new ParsedMedia(clean, season, episode, year, animeHint, decoded);
    }

    private static List<Seg> fetchAniSkip(Context ctx, ParsedMedia info, int durationMs) {
        List<Seg> out = new ArrayList<>();
        try {
            int[] mapped = resolveMalId(ctx, info.title, info.season, info.episode, info.looksLikeAnime);
            int malId = mapped[0];
            int episode = mapped[1];
            if (malId <= 0) {
                Log.i(TAG, "AniSkip: no confident MAL id for '" + info.title + "'");
                return out;
            }
            if (episode != info.episode) {
                Log.i(TAG, "AniList: episode " + info.episode + " mapped to episode " + episode + " of part 2");
            }
            Log.i(TAG, "AniList: chosen idMal=" + malId + " episode=" + episode);

            long episodeLengthSec = durationMs > 0 ? Math.round(durationMs / 1000.0) : 0L;
            JSONArray results = queryAniSkip(malId, episode, Math.max(0L, episodeLengthSec));
            if ((results == null || results.length() == 0) && episodeLengthSec > 0) {
                Log.i(TAG, "AniSkip: no submissions for length " + episodeLengthSec + "s, retrying with episodeLength=0");
                results = queryAniSkip(malId, episode, 0L);
            }
            if (results == null || results.length() == 0) {
                Log.i(TAG, "AniSkip: 0 submissions for malId=" + malId + " ep=" + episode);
                return out;
            }

            List<AniSkipCandidate> candidates = new ArrayList<>();
            for (int i = 0; i < results.length(); i++) {
                JSONObject item = results.optJSONObject(i);
                if (item == null) continue;
                JSONObject interval = item.optJSONObject("interval");
                if (interval == null) continue;
                double startSec = interval.optDouble("startTime", -1.0);
                double endSec = interval.optDouble("endTime", -1.0);
                if (startSec < 0.0 || endSec <= startSec || endSec - startSec > 600.0) continue;
                String type = item.optString("skipType", "").toLowerCase(Locale.US);
                if (!"op".equals(type) && !"recap".equals(type)) continue;
                double theirLen = item.optDouble("episodeLength", 0.0);
                double diff = (episodeLengthSec > 0 && theirLen > 0)
                        ? Math.abs(theirLen - episodeLengthSec) : Double.MAX_VALUE;
                int startMs = (int) Math.round(startSec * 1000.0);
                int endMs = (int) Math.round(endSec * 1000.0);
                candidates.add(new AniSkipCandidate(
                        startMs, endMs, "recap".equals(type) ? "Recap" : "OP", theirLen, diff));
            }

            // Choose the submission whose episodeLength best matches the real duration
            Collections.sort(candidates, new Comparator<AniSkipCandidate>() {
                @Override
                public int compare(AniSkipCandidate a, AniSkipCandidate b) {
                    return Double.compare(a.lengthDiffSec, b.lengthDiffSec);
                }
            });

            StringBuilder summary = new StringBuilder();
            for (AniSkipCandidate c : candidates) {
                if (overlapsMs(out, c.startMs, c.endMs)) continue;
                out.add(new Seg(c.startMs, c.endMs, c.name, "aniskip"));
                summary.append(" [").append(c.name).append(' ').append(c.startMs).append('-').append(c.endMs)
                        .append("ms len=").append(fmt1(c.theirLengthSec)).append("s diff=")
                        .append(c.lengthDiffSec < Double.MAX_VALUE ? fmt1(c.lengthDiffSec) + "s" : "?").append(']');
            }
            Collections.sort(out, new Comparator<Seg>() {
                @Override
                public int compare(Seg a, Seg b) {
                    return Integer.compare(a.start, b.start);
                }
            });
            Log.i(TAG, "AniSkip response: raw=" + results.length() + " kept=" + out.size()
                    + " (ourDuration=" + episodeLengthSec + "s)" + summary);
        } catch (Throwable error) {
            Log.w(TAG, "AniSkip lookup failed: " + error);
        }
        return out;
    }

    private static JSONArray queryAniSkip(int malId, int episode, long episodeLengthSec) throws Exception {
        String url = ANISKIP_URL + malId + "/" + episode
                + "?types=op&types=recap&episodeLength=" + episodeLengthSec;
        String resp = http("GET", url, null, null);
        if (resp == null || resp.length() == 0) return null;
        return new JSONObject(resp).optJSONArray("results");
    }

    /**
     * Resolves title + season + episode to {malId, mappedEpisode} via AniList GraphQL with
     * candidate scoring, caching the title -> MAL id mapping in memory and SharedPreferences for 60 days.
     */
    private static int[] resolveMalId(Context ctx, String title, int season, int episode, boolean looksLikeAnime)
            throws Exception {
        int wanted = season > 0 ? season : 1;
        boolean wantedPart2 = PART_TWO.matcher(title).find();
        String cleanTitle = PART_TWO.matcher(title).replaceAll(" ").replaceAll("\\s+", " ").trim();
        if (cleanTitle.length() == 0) cleanTitle = title;
        String base = normTitle(cleanTitle);
        String cacheKey = base + "|s" + wanted + "|a" + (looksLikeAnime ? 1 : 0) + (wantedPart2 ? "|p2" : "");

        int[] cached = getCachedMal(ctx, cacheKey, episode);
        if (cached != null) {
            return cached;
        }

        List<String> searches = new ArrayList<>();
        String spaced = cleanTitle.replaceAll(":(?=\\S)", ": ");
        if (wantedPart2) {
            searches.add(title.replaceAll(":(?=\\S)", ": "));
        }
        searches.add(spaced);
        if (wanted > 1) {
            searches.add(spaced + " " + ordinalSeason(wanted) + " Season");
            searches.add(spaced + " Season " + wanted);
        }

        Set<Integer> seen = new HashSet<>();
        int bestId = 0;
        double bestScore = -9.0;
        double bestSimilarity = 0.0;
        int bestEpisodes = 0;
        boolean bestIsPart2 = false;
        int part1Episodes = 0;
        double part1Similarity = 0.0;
        StringBuilder report = new StringBuilder();

        for (String search : searches) {
            Log.i(TAG, "AniList: search = '" + search + "'");
            JSONObject variables = new JSONObject().put("search", search);
            JSONObject body = new JSONObject()
                    .put("query", "query ($search: String) { Page(perPage: 10) { media(search: $search, type: ANIME) "
                            + "{ idMal format episodes title { romaji english native } synonyms } } }")
                    .put("variables", variables);
            String rawResp = http("POST", ANILIST_URL, body.toString(), null);
            JSONObject response = new JSONObject(rawResp);
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
                        if (!titles.isNull(key)) {
                            String t = titles.optString(key, "");
                            if (t != null && t.length() > 0) names.add(t);
                        }
                    }
                }
                JSONArray synonyms = m.optJSONArray("synonyms");
                for (int j = 0; synonyms != null && j < synonyms.length(); j++) {
                    String syn = synonyms.optString(j, "");
                    if (syn != null && syn.length() > 0) names.add(syn);
                }

                double similarity = 0.0;
                int candidateSeason = 1;
                boolean part2 = false;
                for (String name : names) {
                    candidateSeason = Math.max(candidateSeason, seasonMarker(name));
                    part2 |= PART_TWO.matcher(name).find();
                    String stripped = CANDIDATE_SEASON.matcher(name.trim()).replaceAll(" ");
                    String plain = normTitle(stripped);
                    similarity = Math.max(similarity, dice(base, plain));
                    String plainNoPart = normTitle(PART_TWO.matcher(stripped).replaceAll(" "));
                    similarity = Math.max(similarity, dice(base, plainNoPart) - (part2 == wantedPart2 ? 0.0 : 0.12));
                }

                String format = m.optString("format", "");
                double score = similarity + (candidateSeason == wanted ? 0.3 : -0.5)
                        + ("TV".equals(format) ? 0.1 : "TV_SHORT".equals(format) || "ONA".equals(format) ? -0.05 : -0.3);
                if (wantedPart2) {
                    score += part2 ? 0.15 : -0.15;
                }
                int episodes = m.isNull("episodes") ? 0 : m.optInt("episodes", 0);
                if (episodes > 0 && episode > episodes) score -= 0.4;

                report.append("\n  idMal=").append(malId).append(' ').append(format)
                        .append(" season=").append(candidateSeason).append(" eps=").append(episodes)
                        .append(" sim=").append(fmt1(similarity * 100.0)).append("% score=")
                        .append(fmt1(score * 100.0)).append(" '")
                        .append(names.isEmpty() ? "" : names.get(0)).append('\'');

                // Split-cour shows: catalogs number cour 2 on from 13; AniSkip numbers it from 1.
                if (!part2 && candidateSeason == wanted && "TV".equals(format) && similarity > part1Similarity) {
                    part1Similarity = similarity;
                    part1Episodes = episodes;
                }
                if (score > bestScore) {
                    bestScore = score;
                    bestSimilarity = similarity;
                    bestId = malId;
                    bestEpisodes = episodes;
                    bestIsPart2 = part2;
                }
            }
            if (bestScore >= 1.3) break;
        }

        Log.i(TAG, "AniList candidates:" + (report.length() == 0 ? " <none>" : report)
                + "\n  -> bestId=" + bestId + " (score=" + fmt1(bestScore * 100.0)
                + ", sim=" + fmt1(bestSimilarity * 100.0) + "%, animeHint=" + looksLikeAnime + ")");

        // Confidence check: anime-styled filenames accept score >= 0.60; generic SxxEyy filenames
        // require a high-confidence title & format match so western shows fall through to IntroDB.
        double minScore = looksLikeAnime ? 0.60 : 0.95;
        double minSim = looksLikeAnime ? 0.50 : 0.85;
        if (bestId <= 0 || bestScore < minScore || bestSimilarity < minSim) {
            return new int[]{0, episode};
        }

        int offset = (bestIsPart2 && part1Episodes > 0 && episode > part1Episodes) ? part1Episodes : 0;
        String cacheKeyToStore = offset > 0 ? (cacheKey + "|ep>" + part1Episodes) : cacheKey;
        putCachedMal(ctx, cacheKeyToStore, bestId, bestEpisodes, offset);
        if (offset > 0) {
            // Also record the part1 boundary on the base key so future episodes know where part 2 starts
            putCachedMal(ctx, cacheKey, bestId, part1Episodes, -1);
        }
        return new int[]{bestId, episode - offset};
    }

    private static int[] getCachedMal(Context ctx, String key, int episode) {
        String raw = getRawCacheEntry(ctx, key);
        if (raw == null) return null;
        int[] parsed = parseCacheEntry(raw);
        if (parsed == null) return null;
        int malId = parsed[0];
        int maxEps = parsed[1];
        int offset = parsed[2];
        if (offset < 0 || (maxEps > 0 && episode > maxEps)) {
            String part2Raw = getRawCacheEntry(ctx, key + "|ep>" + maxEps);
            if (part2Raw == null) return null;
            int[] p2 = parseCacheEntry(part2Raw);
            if (p2 == null) return null;
            Log.i(TAG, "AniList cache hit (part 2) for '" + key + "' -> idMal=" + p2[0]);
            return new int[]{p2[0], Math.max(1, episode - Math.max(0, p2[2]))};
        }
        Log.i(TAG, "AniList cache hit for '" + key + "' -> idMal=" + malId);
        return new int[]{malId, Math.max(1, episode - Math.max(0, offset))};
    }

    private static String getRawCacheEntry(Context ctx, String key) {
        long now = System.currentTimeMillis();
        String mem = MAL_CACHE.get(key);
        if (mem != null) {
            int[] p = parseCacheEntryWithTtl(mem, now);
            if (p != null) return mem;
            MAL_CACHE.remove(key);
        }
        if (ctx == null) return null;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String stored = prefs.getString("mal:" + key, null);
            if (stored != null) {
                if (parseCacheEntryWithTtl(stored, now) != null) {
                    MAL_CACHE.put(key, stored);
                    return stored;
                }
                prefs.edit().remove("mal:" + key).apply();
            }
        } catch (Throwable error) {
            Log.w(TAG, "cache read", error);
        }
        return null;
    }

    private static int[] parseCacheEntry(String entry) {
        return parseCacheEntryWithTtl(entry, System.currentTimeMillis());
    }

    private static int[] parseCacheEntryWithTtl(String entry, long nowMs) {
        try {
            String[] parts = entry.split(",");
            if (parts.length < 4) return null;
            int malId = Integer.parseInt(parts[0]);
            int maxEps = Integer.parseInt(parts[1]);
            int offset = Integer.parseInt(parts[2]);
            long savedAt = Long.parseLong(parts[3]);
            if (malId <= 0 || nowMs - savedAt > CACHE_TTL_MS) return null;
            return new int[]{malId, maxEps, offset};
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void putCachedMal(Context ctx, String key, int malId, int maxEpisodes, int offset) {
        if (malId <= 0) return;
        String value = malId + "," + maxEpisodes + "," + offset + "," + System.currentTimeMillis();
        MAL_CACHE.put(key, value);
        if (ctx == null) return;
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putString("mal:" + key, value).apply();
        } catch (Throwable error) {
            Log.w(TAG, "cache write", error);
        }
    }

    /**
     * Keyless IntroDB lookup: an IMDb id from the playing intent when one is present,
     * else a Cinemeta title search (exact normalized match, remembered for 60 days),
     * then IntroDB by imdb_id + season/episode. No API keys are used.
     */
    private static List<Seg> fetchIntroDb(Context ctx, ParsedMedia info, int durationMs) {
        List<Seg> out = new ArrayList<>();
        try {
            String id = info.imdbId;
            if (id == null || id.length() == 0) {
                id = cinemetaImdbId(ctx, info.title);
            }
            if (id == null || id.length() == 0) {
                Log.i(TAG, "IntroDB skipped: no IMDb id found for '" + info.title + "'");
                return out;
            }
            Log.i(TAG, "IntroDB: looking up imdb=" + id + " S" + info.season + "E" + info.episode);
            JSONObject body = new JSONObject(http("GET", "https://api.theintrodb.org/v4/media?imdb_id=" + id
                    + "&season=" + info.season + "&episode=" + info.episode + "&duration_ms=" + durationMs,
                    null, null));
            String[] kinds = {"recap", "intro"};
            for (String kind : kinds) {
                JSONArray items = body.optJSONArray(kind);
                for (int i = 0; items != null && i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    int start = item.isNull("start_ms") ? 0 : item.optInt("start_ms", -1);
                    int end = item.isNull("end_ms") ? -1 : item.optInt("end_ms", -1);
                    // An entry with no end, or longer than 10 min, would skip the episode itself.
                    if (start < 0 || end <= start || end - start > 600000) continue;
                    if (overlapsMs(out, start, end)) continue;
                    out.add(new Seg(start, end, "recap".equals(kind) ? "Recap" : "Intro", "introdb"));
                }
            }
            Log.i(TAG, "IntroDB: imdb=" + id + " S" + info.season + "E" + info.episode
                    + " -> " + out.size() + " segment(s)");
        } catch (Throwable error) {
            Log.w(TAG, "IntroDB lookup failed: " + error);
        }
        return out;
    }

    /**
     * Keyless Cinemeta lookup using the public catalog. Exact normalized name match only,
     * so "Dexter" never resolves to "Dexter: Resurrection". When several exact matches
     * exist, the one whose release year matches a year in the title wins, else the first.
     * Hits are remembered in memory and in SharedPreferences for 60 days.
     */
    private static String cinemetaImdbId(Context ctx, String title) {
        if (title == null || title.trim().length() == 0) return "";
        String wanted = title.trim();
        String key = "series:" + normTitle(wanted);
        String cached;
        synchronized (CINEMETA_LOCK) {
            cached = CINEMETA_CACHE.get(key);
            if (cached == null) {
                cached = VimuIntroSkipPrefs.cachedImdb(ctx, key);
                if (cached.length() > 0) CINEMETA_CACHE.put(key, cached);
                else cached = null;
            }
        }
        if (cached != null) {
            Log.i(TAG, "Cinemeta: cache hit for \"" + wanted + "\" -> "
                    + (cached.length() > 0 ? cached : "<none>"));
            return cached;
        }
        String query;
        try {
            query = URLEncoder.encode(wanted, "UTF-8").replace("+", "%20");
        } catch (Throwable error) {
            Log.w(TAG, "Cinemeta: bad title \"" + wanted + "\"");
            return "";
        }
        try {
            String response;
            try {
                response = http("GET", CINEMETA_SERIES_URL + query + ".json", null, null);
            } catch (Throwable first) {
                Log.i(TAG, "Cinemeta: retry after " + first.getClass().getSimpleName());
                response = http("GET", CINEMETA_SERIES_URL + query + ".json", null, null);
            }
            JSONObject json = new JSONObject(response);
            JSONArray metas = json.optJSONArray("metas");
            if (metas == null || metas.length() == 0) {
                Log.i(TAG, "Cinemeta: no results for \"" + wanted + "\"");
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
                String name = meta.optString("name", "");
                if (name == null) name = "";
                String imdb = meta.optString("imdb_id", "");
                if (imdb == null || imdb.length() == 0) imdb = meta.optString("id", "");
                if (imdb == null) imdb = "";
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
                    String release = meta.optString("releaseInfo", "");
                    exactYears.add(release == null ? "" : release);
                }
            }
            if (exactIds.isEmpty()) {
                Log.i(TAG, "Cinemeta: no exact name match for \"" + wanted + "\" ("
                        + metas.length() + " candidates, e.g. " + sample + ")");
                return "";
            }
            String chosen = exactIds.get(0);
            String chosenName = exactNames.get(0);
            if (exactIds.size() > 1 && wantedYear > 0) {
                for (int i = 0; i < exactIds.size(); i++) {
                    if (exactYears.get(i).contains(String.valueOf(wantedYear))) {
                        chosen = exactIds.get(i);
                        chosenName = exactNames.get(i);
                        break;
                    }
                }
            }
            Log.i(TAG, "Cinemeta: \"" + wanted + "\" -> " + chosen + " (\"" + chosenName + "\", "
                    + exactIds.size() + " exact match(es))");
            synchronized (CINEMETA_LOCK) {
                CINEMETA_CACHE.put(key, chosen);
            }
            VimuIntroSkipPrefs.cacheImdb(ctx, key, chosen);
            return chosen;
        } catch (Throwable error) {
            Log.w(TAG, "Cinemeta lookup failed: " + error);
            return "";
        }
    }

    private static int extractYear(String value) {
        if (value == null) return 0;
        Matcher matcher = YEAR.matcher(value);
        if (!matcher.find()) return 0;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Snaps online segments (AniSkip / IntroDB) to chapter boundaries within 15 s:
     * both ends snap when the snapped duration still matches within 5 s; a single end
     * snaps only when it moves by 3 s or less.
     */
    private static List<Seg> snapSegments(List<Seg> list, long[] starts, int duration) {
        if (list == null || list.isEmpty() || starts == null || starts.length == 0) {
            return list;
        }
        List<Seg> out = new ArrayList<>(list.size());
        for (Seg seg : list) {
            out.add(snapOne(seg, starts, duration));
        }
        return out;
    }

    private static Seg snapOne(Seg seg, long[] starts, int duration) {
        int start = nearestBoundaryMs(seg.start, starts, duration);
        int end = nearestBoundaryMs(seg.end, starts, duration);
        boolean both = start >= 0 && end >= 0
                && Math.abs((end - start) - (seg.end - seg.start)) <= 5000;
        if (!both) {
            if (start >= 0 && Math.abs(start - seg.start) > 3000) start = -1;
            if (end >= 0 && Math.abs(end - seg.end) > 3000) end = -1;
        }
        if (start < 0 && end < 0) return seg;
        int newStart = start >= 0 ? start : seg.start;
        int newEnd = end >= 0 ? end : seg.end;
        if (newEnd - newStart < 10000) return seg;
        Log.i(TAG, "Snapped '" + seg.name + "' (" + seg.rule + ") " + seg.start + "-" + seg.end
                + "ms -> " + newStart + "-" + newEnd + "ms");
        return new Seg(newStart, newEnd, seg.name, seg.rule + "+snap");
    }

    private static int nearestBoundaryMs(int timeMs, long[] starts, int duration) {
        int best = -1;
        for (int i = 0; i < starts.length; i++) {
            int s = (int) starts[i];
            int e = endOf(starts, i, duration);
            int[] bounds = new int[]{s, e};
            for (int b : bounds) {
                if (b < 0 || Math.abs(b - timeMs) > 15000) continue;
                if (best < 0 || Math.abs(b - timeMs) < Math.abs(best - timeMs)) {
                    best = b;
                }
            }
        }
        return best;
    }

    private static boolean overlapsMs(List<Seg> segments, int startMs, int endMs) {
        for (Seg s : segments) {
            int start = Math.max(s.start, startMs);
            int end = Math.min(s.end, endMs);
            if (end - start > 1000) return true;
        }
        return false;
    }

    private static int seasonMarker(String name) {
        if (name == null) return 1;
        Matcher matcher = CANDIDATE_SEASON.matcher(name.trim());
        if (!matcher.find()) return 1;
        if (matcher.group(1) != null) return safeParseInt(matcher.group(1), 1);
        if (matcher.group(2) != null) return safeParseInt(matcher.group(2), 1);
        String word = (matcher.group(3) != null ? matcher.group(3) : matcher.group(4)).toLowerCase(Locale.US);
        if ("second".equals(word) || "ii".equals(word)) return 2;
        if ("third".equals(word) || "iii".equals(word)) return 3;
        if ("fourth".equals(word) || "iv".equals(word)) return 4;
        return 5;
    }

    private static String ordinalSeason(int season) {
        if (season % 100 >= 11 && season % 100 <= 13) return season + "th";
        switch (season % 10) {
            case 1:
                return season + "st";
            case 2:
                return season + "nd";
            case 3:
                return season + "rd";
            default:
                return season + "th";
        }
    }

    private static String normTitle(String value) {
        if (value == null) return "";
        String result = value.toLowerCase(Locale.US).replace("\u2019", "").replace("'", "");
        return result.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    /** Dice coefficient on word sets; 1.0 = same words. */
    private static double dice(String a, String b) {
        if (a == null || b == null || a.length() == 0 || b.length() == 0) return 0.0;
        if (a.equals(b)) return 1.0;
        Set<String> left = new HashSet<>(Arrays.asList(a.split(" ")));
        Set<String> right = new HashSet<>(Arrays.asList(b.split(" ")));
        int common = 0;
        for (String word : left) {
            if (right.contains(word)) common++;
        }
        return 2.0 * common / (left.size() + right.size());
    }

    private static String urlDecode(String s) {
        if (s == null || s.length() == 0) return "";
        try {
            String safePercent = s.replaceAll("%(?![0-9a-fA-F]{2})", "%25").replace("+", "%2B");
            return URLDecoder.decode(safePercent, "UTF-8");
        } catch (Throwable ignored) {
            return s;
        }
    }

    private static int safeParseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    /** HTTP helper with 5 s connect/read timeouts and 1 automatic retry. */
    private static String http(String method, String url, String jsonBody, String bearer) throws Exception {
        Exception lastError = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = null;
            InputStream in = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setRequestMethod(method);
                c.setConnectTimeout(HTTP_TIMEOUT_MS);
                c.setReadTimeout(HTTP_TIMEOUT_MS);
                c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("User-Agent", "VimuIntroSkip/1.0");
                if (bearer != null) {
                    c.setRequestProperty("Authorization", bearer);
                }
                if (jsonBody != null) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    byte[] payload = jsonBody.getBytes("UTF-8");
                    OutputStream os = c.getOutputStream();
                    os.write(payload);
                    os.close();
                }
                int code = c.getResponseCode();
                if (code == 404) {
                    // AniSkip returns 404 when no skip times exist for an episode; no need to retry.
                    return "{}";
                }
                if (code >= 500 || code == 429) {
                    throw new java.io.IOException("HTTP " + code);
                }
                in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                if (in == null) return "{}";
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                return out.toString("UTF-8");
            } catch (Exception e) {
                lastError = e;
                if (attempt == 0) {
                    Log.i(TAG, "HTTP retry after " + e.getClass().getSimpleName() + " on " + url);
                }
            } finally {
                if (in != null) {
                    try {
                        in.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (c != null) {
                    try {
                        c.disconnect();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        throw lastError != null ? lastError : new java.io.IOException("HTTP request failed");
    }

    private void resetMedia() {
        skipped.clear();
        segments = null;
        chaptersHaveIntro = false;
        remote = null;
        remoteStarted = false;
        remoteApplied = false;
        mediaGen++;
        chapterSignature = null;
        chapterReads = 0;
        nextChapterRead = 0;
        current = null;
        currentByJump = false;
        firstPlayingAt = 0;
        flowTicks = 0;
        guardUntil = 0;
        lastPos = -1;
    }

    private static String chapterSignature(e p) {
        long[] starts = p.E();
        if (starts == null || starts.length == 0) return null;
        StringBuilder sig = new StringBuilder();
        for (long start : starts) sig.append(start).append(',');
        return sig.toString();
    }

    private void skip(PlayerFragment f, e p, Seg seg, int pos) {
        skipped.add(seg.key());
        int target = seg.end;
        if (durationMs > 0 && target > durationMs - 1000) target = Math.max(seg.start, durationMs - 1000);
        // Same call Vimu's own chapter-skip buttons use (q.D/E/G -> e.seekTo). The adapter's
        // k(long) is the scrub-preview path (e1(pos, true)) and leaves the seek bar stuck.
        p.seekTo(target);
        guardUntil = SystemClock.uptimeMillis() + 2500L;
        guardTarget = target;
        current = null;
        Log.i(TAG, "SKIP '" + seg.name + "' " + pos + "ms -> " + target + "ms (chapter " + seg.start + "-"
                + seg.end + ", rule " + seg.rule + ")");
        final int landTarget = target;
        MAIN.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    PlayerFragment pf = fragment.get();
                    e pl = pf == null ? null : PlayerFragment.G0(pf);
                    if (pl != null) Log.i(TAG, "landed 1s after skip: pos=" + pl.getCurrentPosition() + "ms (target " + landTarget + "ms)");
                } catch (Throwable ignored) {
                }
            }
        }, 1000L);
        try {
            f.P1(seg.recap() ? "Recap skipped" : "Intro skipped");
        } catch (Throwable error) {
            Log.w(TAG, "notice", error);
        }
    }

    private void readChapters(e p, int duration) {
        long[] starts = p.E();
        if (starts == null || starts.length == 0) return;
        int n = starts.length;
        String[] names = new String[n];
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String name;
            try {
                name = p.y0(i);
            } catch (Throwable error) {
                name = "";
            }
            names[i] = name == null ? "" : name.trim();
            all.append(" [").append(i).append("] ").append(starts[i]).append("ms '").append(names[i]).append('\'');
        }
        Log.i(TAG, "chapters (" + n + ", duration " + duration + "ms):" + all);
        List<Seg> found = new ArrayList<>();
        // a) named intro / recap chapters
        boolean hasOpening = false;
        for (int i = 0; i < n; i++) {
            if (!excluded(names[i], starts[i], duration) && OPENING_NAME.matcher(names[i]).find()) hasOpening = true;
        }
        for (int i = 0; i < n; i++) {
            if (excluded(names[i], starts[i], duration)) continue;
            if (duration > 0 && starts[i] > duration / 2) continue; // an intro never sits in the second half
            if (!INTRO_NAME.matcher(names[i]).find() && !isIntroCredits(names[i], starts[i], duration)) continue;
            // "Intro" alone counts only when there is no OP/Opening chapter.
            if (hasOpening && !OPENING_NAME.matcher(names[i]).find() && WEAK_INTRO_NAME.matcher(names[i]).find()
                    && !names[i].toLowerCase(Locale.US).matches(".*(recap|previously|あらすじ).*")) {
                Log.i(TAG, "ignoring '" + names[i] + "': an OP/Opening chapter exists");
                continue;
            }
            int end = endOf(starts, i, duration);
            if (end > starts[i] + 1000) found.add(new Seg((int) starts[i], end, names[i], "name"));
        }
        // b) unnamed chapters: a 60-110 s chapter starting in the first 6 minutes. If several
        //    qualify (e.g. an 85 s cold open before a 90 s OP), take the one closest to 90 s,
        //    the usual TV opening length; ties go to the earlier one.
        if (found.isEmpty()) {
            int best = -1;
            long bestDelta = Long.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                if (!GENERIC_NAME.matcher(names[i]).matches()) continue;
                int end = endOf(starts, i, duration);
                if (end <= 0) continue;
                long length = end - starts[i];
                if (starts[i] <= 360000L && length >= 60000L && length <= 110000L) {
                    long delta = Math.abs(length - 90000L);
                    if (delta < bestDelta) {
                        best = i;
                        bestDelta = delta;
                    }
                }
            }
            if (best >= 0) {
                found.add(new Seg((int) starts[best], endOf(starts, best, duration),
                        names[best].length() == 0 ? "chapter " + (best + 1) : names[best], "length"));
            }
        }
        // c) nothing usable: allow online fallback (AniSkip / IntroDB)
        chaptersHaveIntro = !found.isEmpty();
        segments = found;
        if (found.isEmpty()) {
            Log.i(TAG, "no intro chapter");
        } else {
            for (Seg s : found) {
                Log.i(TAG, "final segment '" + s.name + "' " + s.start + "-" + s.end + "ms (source chapter:" + s.rule + ")");
            }
        }
    }

    /**
     * Never skipped: ED/ending/end credits/preview, a bare "Credits" outside the first 35% of the
     * runtime, and Amazon-style scene titles that merely end in the intro words
     * ("Marcy Gives Full Disclosure, Opening Credits").
     */
    private static boolean excluded(String name, long start, int duration) {
        if (NEVER_NAME.matcher(name).find()) return true;
        int comma = name.indexOf(',');
        if (comma > 0 && name.substring(0, comma).trim().split("\\s+").length > 2) return true;
        return CREDITS_NAME.matcher(name).find() && !OPENING_NAME.matcher(name).find() && !isIntroCredits(name, start, duration);
    }

    /** A bare "Credits" chapter early in the episode is the title sequence (late ones are end credits). */
    private static boolean isIntroCredits(String name, long start, int duration) {
        if (!CREDITS_NAME.matcher(name).find() || OPENING_NAME.matcher(name).find()) return false;
        return duration > 0 ? start < duration * 0.35 : start < 600000L;
    }

    private static int endOf(long[] starts, int i, int duration) {
        if (i + 1 < starts.length) return (int) starts[i + 1];
        return duration > 0 ? duration : -1;
    }
}

