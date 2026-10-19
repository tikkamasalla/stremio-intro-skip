package app.tikkamasalla.stremio.introskip;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ranks Stremio video streams using pure Java 8 according to user preference rules.
 */
public final class StreamRanker {

    private StreamRanker() {
        // Utility class
    }

    /**
     * Input stream model representing a single Stremio stream candidate.
     */
    public static final class Stream {
        public final String name;
        public final String title;
        public final String url;
        public final String addonName;

        public Stream(String name, String title, String url, String addonName) {
            this.name = name != null ? name : "";
            this.title = title != null ? title : "";
            this.url = url;
            this.addonName = addonName != null ? addonName : "";
        }

        @Override
        public String toString() {
            String cleanName = name.replace("\n", " | ");
            String firstTitleLine = title.split("\\r?\\n", 2)[0];
            return "[" + cleanName + "] " + firstTitleLine;
        }
    }

    /**
     * Ranked stream result containing the original stream, numerical score, and human-readable reason.
     */
    public static final class Ranked {
        public final Stream stream;
        public final long score;
        public final String reason;
        public final ParsedMeta meta;

        public Ranked(Stream stream, long score, String reason, ParsedMeta meta) {
            this.stream = stream;
            this.score = score;
            this.reason = reason;
            this.meta = meta;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "Score: %13d | %s | Reason: %s", score, stream, reason);
        }
    }

    /**
     * Parsed metadata extracted from stream fields.
     */
    public static final class ParsedMeta {
        public final int resolution;
        public final boolean aiUpscale;
        public final long sizeBytes;
        public final String sizeDisplay;
        public final boolean hdr;
        public final boolean dolbyVision;
        public final String hdrLabel;
        public final boolean cached;
        public final boolean badSource;
        public final String badSourceLabel;
        public final boolean remux;
        public final String codec;
        public final int codecRank;
        public final int seeders;

        ParsedMeta(
                int resolution,
                boolean aiUpscale,
                long sizeBytes,
                String sizeDisplay,
                boolean hdr,
                boolean dolbyVision,
                String hdrLabel,
                boolean cached,
                boolean badSource,
                String badSourceLabel,
                boolean remux,
                String codec,
                int codecRank,
                int seeders) {
            this.resolution = resolution;
            this.aiUpscale = aiUpscale;
            this.sizeBytes = sizeBytes;
            this.sizeDisplay = sizeDisplay;
            this.hdr = hdr;
            this.dolbyVision = dolbyVision;
            this.hdrLabel = hdrLabel;
            this.cached = cached;
            this.badSource = badSource;
            this.badSourceLabel = badSourceLabel;
            this.remux = remux;
            this.codec = codec;
            this.codecRank = codecRank;
            this.seeders = seeders;
        }
    }

    // Token boundary helpers that work around dots, underscores, brackets, hyphens, spaces, and emojis
    private static final String B_START = "(?<![A-Za-z0-9])";
    private static final String B_END = "(?![A-Za-z0-9])";

    // AI Upscale patterns (includes common "upcale" typo seen in release titles)
    private static final Pattern AI_UPSCALE_PATTERN = Pattern.compile(
            B_START + "(?:ai[\\s._-]*upscal(?:e|ed|ing)?|upscal(?:e|ed|ing)?|ai[\\s._-]*upcale|upcale|ai[\\s._-]*4k)" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // Resolution patterns
    private static final Pattern RES_2160_PATTERN = Pattern.compile(
            B_START + "(?:2160p?|4k|uhd)" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern RES_1440_PATTERN = Pattern.compile(
            B_START + "(?:1440p?|2k|qhd)" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern RES_1080_PATTERN = Pattern.compile(
            B_START + "(?:1080[pi]?|fhd)" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern RES_720_PATTERN = Pattern.compile(
            B_START + "(?:720[pi]?)" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern RES_480_PATTERN = Pattern.compile(
            B_START + "(?:480[pi]?|576[pi]?|540p?|400p?|360p?|sd)" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // Size patterns (prioritize floppy disk emoji 💾 if present, otherwise general size token)
    private static final Pattern DISK_EMOJI_SIZE_PATTERN = Pattern.compile(
            "\uD83D\uDCBE\\s*(\\d+(?:\\.\\d+)?)\\s*(TiB|TB|GiB|GB|MiB|MB|KiB|KB)" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern GENERAL_SIZE_PATTERN = Pattern.compile(
            B_START + "(\\d+(?:\\.\\d+)?)\\s*(TiB|TB|GiB|GB|MiB|MB|KiB|KB)" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // HDR & Dolby Vision patterns
    private static final Pattern DV_PATTERN = Pattern.compile(
            B_START + "(?:dv|dovi|dolby[\\s._-]*vision)" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern HDR10_PLUS_PATTERN = Pattern.compile(
            B_START + "hdr10\\+" + "(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern HDR_PATTERN = Pattern.compile(
            B_START + "(?:hdr10|hdr)" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // Cached & Uncached Debrid markers
    private static final Pattern UNCACHED_MARKER_PATTERN = Pattern.compile(
            "(?:\\[\\s*(?:rd|tb|ad|pm|dl)\\s+download\\s*\\]|\u23F3|" + B_START + "(?:uncached|download)" + B_END + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern CACHED_MARKER_PATTERN = Pattern.compile(
            "(?:\\[\\s*(?:rd|tb|ad|pm|dl)\\s*\\+\\s*\\]|\u26A1|" + B_START + "(?:cached|instant)" + B_END + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern DEBRID_ADDON_PATTERN = Pattern.compile(
            B_START + "(?:torbox|real[\\s._-]*debrid|all[\\s._-]*debrid|premiumize|debrid|torrentio|comet|mediafusion|knightcrawler|jackettio|stremthru|rd|tb|ad|pm)" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // Bad source patterns (longer fused tokens first so CAMRip matches)
    private static final Pattern BAD_SOURCE_PATTERN = Pattern.compile(
            B_START + "(hdcams?|camrip|cams?|hdts|tsrip|telesync|ts|telecine|tcrip|tc|screeners?|dvdscr|bdscr|scr|r5|hc|korsub|3d|sample)" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // Remux and Codecs
    private static final Pattern REMUX_PATTERN = Pattern.compile(
            B_START + "remux" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern AV1_PATTERN = Pattern.compile(
            B_START + "av1" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern HEVC_PATTERN = Pattern.compile(
            B_START + "(?:hevc|h[._-]?265|x265)" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );
    private static final Pattern H264_PATTERN = Pattern.compile(
            B_START + "(?:avc|h[._-]?264|x264)" + B_END,
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    // Seeders pattern (👤 45 or Seeds: 45)
    private static final Pattern SEEDERS_PATTERN = Pattern.compile(
            "(?:\uD83D\uDC64\\s*(\\d+)|" + B_START + "seeds?[\\s:]*(\\d+)" + B_END + ")",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE
    );

    /**
     * Returns the highest-ranked stream, or null if the input list is null or empty.
     */
    public static Stream best(List<Stream> streams, boolean isMovie) {
        List<Ranked> ranked = rank(streams, isMovie);
        return ranked.isEmpty() ? null : ranked.get(0).stream;
    }

    /**
     * Ranks the given streams best-first according to the user's hierarchy of rules.
     * Bad sources are excluded unless every stream in the input list is a bad source.
     */
    public static List<Ranked> rank(List<Stream> streams, boolean isMovie) {
        if (streams == null || streams.isEmpty()) {
            return Collections.emptyList();
        }

        List<Candidate> allCandidates = new ArrayList<Candidate>(streams.size());
        List<Candidate> goodCandidates = new ArrayList<Candidate>(streams.size());

        for (Stream s : streams) {
            if (s == null) {
                continue;
            }
            ParsedMeta meta = parse(s);
            long score = computeScore(meta);
            Candidate c = new Candidate(s, meta, score);
            allCandidates.add(c);
            if (!meta.badSource) {
                goodCandidates.add(c);
            }
        }

        if (allCandidates.isEmpty()) {
            return Collections.emptyList();
        }

        // Rule 1: Exclude bad sources unless that is all there is
        final boolean onlyBadSources = goodCandidates.isEmpty();
        List<Candidate> activePool = onlyBadSources ? allCandidates : goodCandidates;

        boolean anyCached = false;
        for (Candidate c : activePool) {
            if (c.meta.cached) {
                anyCached = true;
                break;
            }
        }
        final boolean allUncached = !anyCached;

        Collections.sort(activePool, new Comparator<Candidate>() {
            @Override
            public int compare(Candidate a, Candidate b) {
                // 1. Non-bad before bad
                if (a.meta.badSource != b.meta.badSource) {
                    return a.meta.badSource ? 1 : -1;
                }
                // 2. Cached before uncached
                if (a.meta.cached != b.meta.cached) {
                    return a.meta.cached ? -1 : 1;
                }
                // 3. Higher resolution first (AI upscale counts as 2160)
                if (a.meta.resolution != b.meta.resolution) {
                    return Integer.compare(b.meta.resolution, a.meta.resolution);
                }
                // 4. HDR / Dolby Vision before SDR
                boolean aHdr = a.meta.hdr || a.meta.dolbyVision;
                boolean bHdr = b.meta.hdr || b.meta.dolbyVision;
                if (aHdr != bHdr) {
                    return aHdr ? -1 : 1;
                }
                // 5. Larger file size (in bytes) first
                if (a.meta.sizeBytes != b.meta.sizeBytes) {
                    return Long.compare(b.meta.sizeBytes, a.meta.sizeBytes);
                }
                // 6a. Tie-breaker: HEVC/AV1 over H264/other
                if (a.meta.codecRank != b.meta.codecRank) {
                    return Integer.compare(b.meta.codecRank, a.meta.codecRank);
                }
                // 6b. Tie-breaker: More seeders
                if (a.meta.seeders != b.meta.seeders) {
                    return Integer.compare(b.meta.seeders, a.meta.seeders);
                }
                return Long.compare(b.score, a.score);
            }
        });

        List<Ranked> result = new ArrayList<Ranked>(activePool.size());
        Candidate winner = activePool.get(0);
        Candidate runnerUp = activePool.size() > 1 ? activePool.get(1) : null;

        for (int i = 0; i < activePool.size(); i++) {
            Candidate current = activePool.get(i);
            String reason = buildReason(current, i == 0 ? runnerUp : winner, i == 0, allUncached, onlyBadSources, isMovie);
            result.add(new Ranked(current.stream, current.score, reason, current.meta));
        }

        return result;
    }

    /**
     * Parses a Stream's name, title, url, and addonName into structured metadata.
     */
    public static ParsedMeta parse(Stream stream) {
        String combined = (stream.name != null ? stream.name : "") + "\n" + (stream.title != null ? stream.title : "");

        // 1. AI Upscale check (counts as 4K / 2160p)
        boolean aiUpscale = AI_UPSCALE_PATTERN.matcher(combined).find();

        // 2. Resolution check
        int resolution = 0;
        if (RES_2160_PATTERN.matcher(combined).find()) {
            resolution = 2160;
        } else if (RES_1440_PATTERN.matcher(combined).find()) {
            resolution = 1440;
        } else if (RES_1080_PATTERN.matcher(combined).find()) {
            resolution = 1080;
        } else if (RES_720_PATTERN.matcher(combined).find()) {
            resolution = 720;
        } else if (RES_480_PATTERN.matcher(combined).find()) {
            resolution = 480;
        }
        if (aiUpscale && resolution < 2160) {
            resolution = 2160;
        }

        // 3. File size check
        long sizeBytes = 0L;
        String sizeDisplay = "unknown size";
        Matcher diskMatcher = DISK_EMOJI_SIZE_PATTERN.matcher(combined);
        if (diskMatcher.find()) {
            sizeBytes = parseSizeToBytes(diskMatcher.group(1), diskMatcher.group(2));
            sizeDisplay = diskMatcher.group(1) + " " + diskMatcher.group(2).toUpperCase(Locale.ROOT);
        } else {
            Matcher generalSizeMatcher = GENERAL_SIZE_PATTERN.matcher(combined);
            if (generalSizeMatcher.find()) {
                sizeBytes = parseSizeToBytes(generalSizeMatcher.group(1), generalSizeMatcher.group(2));
                sizeDisplay = generalSizeMatcher.group(1) + " " + generalSizeMatcher.group(2).toUpperCase(Locale.ROOT);
            }
        }

        // 4. HDR / Dolby Vision
        boolean dv = DV_PATTERN.matcher(combined).find();
        boolean hdr10Plus = HDR10_PLUS_PATTERN.matcher(combined).find();
        boolean hdr = hdr10Plus || HDR_PATTERN.matcher(combined).find();
        String hdrLabel;
        if (dv && hdr10Plus) {
            hdrLabel = "DV + HDR10+";
        } else if (dv && hdr) {
            hdrLabel = "DV + HDR";
        } else if (dv) {
            hdrLabel = "DV";
        } else if (hdr10Plus) {
            hdrLabel = "HDR10+";
        } else if (hdr) {
            hdrLabel = "HDR";
        } else {
            hdrLabel = "SDR";
        }

        // 5. Cached debrid check
        boolean hasUncachedMarker = UNCACHED_MARKER_PATTERN.matcher(combined).find();
        boolean hasCachedMarker = CACHED_MARKER_PATTERN.matcher(combined).find();
        boolean hasUrl = stream.url != null && !stream.url.trim().isEmpty();
        String addonContext = (stream.addonName != null ? stream.addonName : "") + " " + (stream.name != null ? stream.name : "");
        boolean fromDebridAddon = DEBRID_ADDON_PATTERN.matcher(addonContext).find();
        boolean cached = !hasUncachedMarker && (hasCachedMarker || (hasUrl && fromDebridAddon));

        // 6. Bad sources check
        Matcher badMatcher = BAD_SOURCE_PATTERN.matcher(combined);
        boolean badSource = false;
        String badSourceLabel = null;
        if (badMatcher.find()) {
            badSource = true;
            badSourceLabel = badMatcher.group(1).toUpperCase(Locale.ROOT);
        }

        // 7. Remux and Codec check
        boolean remux = REMUX_PATTERN.matcher(combined).find();
        String codec = "unknown";
        int codecRank = 0;
        if (AV1_PATTERN.matcher(combined).find()) {
            codec = "AV1";
            codecRank = 2;
        } else if (HEVC_PATTERN.matcher(combined).find()) {
            codec = "HEVC";
            codecRank = 2;
        } else if (H264_PATTERN.matcher(combined).find()) {
            codec = "H264";
            codecRank = 1;
        }

        // 8. Seeders check
        int seeders = 0;
        Matcher seedMatcher = SEEDERS_PATTERN.matcher(combined);
        if (seedMatcher.find()) {
            String numStr = seedMatcher.group(1) != null ? seedMatcher.group(1) : seedMatcher.group(2);
            try {
                seeders = Integer.parseInt(numStr);
            } catch (NumberFormatException ignored) {
                seeders = 0;
            }
        }

        return new ParsedMeta(
                resolution,
                aiUpscale,
                sizeBytes,
                sizeDisplay,
                hdr,
                dv,
                hdrLabel,
                cached,
                badSource,
                badSourceLabel,
                remux,
                codec,
                codecRank,
                seeders
        );
    }

    private static long parseSizeToBytes(String numberStr, String unitRaw) {
        double value;
        try {
            value = Double.parseDouble(numberStr);
        } catch (NumberFormatException e) {
            return 0L;
        }
        String unit = unitRaw.toUpperCase(Locale.ROOT);
        double multiplier;
        if ("TIB".equals(unit)) {
            multiplier = 1024.0 * 1024.0 * 1024.0 * 1024.0;
        } else if ("TB".equals(unit)) {
            multiplier = 1000.0 * 1000.0 * 1000.0 * 1000.0;
        } else if ("GIB".equals(unit)) {
            multiplier = 1024.0 * 1024.0 * 1024.0;
        } else if ("GB".equals(unit)) {
            multiplier = 1000.0 * 1000.0 * 1000.0;
        } else if ("MIB".equals(unit)) {
            multiplier = 1024.0 * 1024.0;
        } else if ("MB".equals(unit)) {
            multiplier = 1000.0 * 1000.0;
        } else if ("KIB".equals(unit)) {
            multiplier = 1024.0;
        } else if ("KB".equals(unit)) {
            multiplier = 1000.0;
        } else {
            multiplier = 1.0;
        }
        return Math.round(value * multiplier);
    }

    /**
     * Computes a composite base-10 long score where every tier strictly dominates lower tiers:
     * - Cached bonus:      +1,000,000,000,000 (10^12)
     * - Resolution:        resolution * 100,000,000 (up to 216,000,000,000; min step = 24,000,000,000)
     * - HDR/DV bonus:      +10,000,000,000 (10^10)
     * - File size (MB):    min(sizeMB, 999,999) * 10,000 (up to 9,999,990,000)
     * - Codec tie-breaker: codecRank * 1,000 (up to 2,000)
     * - Seeders tie-break: min(seeders, 999) (up to 999)
     * - Bad source penalty: -10,000,000,000,000 (-10^13) if fallback bad source
     */
    private static long computeScore(ParsedMeta m) {
        long score = 0L;
        if (m.badSource) {
            score -= 10_000_000_000_000L;
        }
        if (m.cached) {
            score += 1_000_000_000_000L;
        }
        score += (long) m.resolution * 100_000_000L;
        if (m.hdr || m.dolbyVision) {
            score += 10_000_000_000L;
        }
        long sizeMb = Math.min(m.sizeBytes / 1_000_000L, 999_999L);
        score += sizeMb * 10_000L;
        score += (long) m.codecRank * 1_000L;
        score += Math.min(m.seeders, 999);
        return score;
    }

    private static String buildReason(
            Candidate current,
            Candidate comparison,
            boolean isWinner,
            boolean allUncached,
            boolean onlyBadSources,
            boolean isMovie) {

        ParsedMeta m = current.meta;
        List<String> parts = new ArrayList<String>();

        if (onlyBadSources && m.badSource) {
            parts.add("fallback bad source (" + m.badSourceLabel + " - no clean sources available)");
        }

        if (m.cached) {
            parts.add("cached");
        } else if (allUncached) {
            parts.add("uncached (all streams uncached)");
        } else {
            parts.add("uncached");
        }

        if (m.aiUpscale) {
            parts.add("4K AI Upscale (counts as 2160p)");
        } else if (m.resolution == 2160) {
            parts.add("4K (2160p)");
        } else if (m.resolution > 0) {
            parts.add(m.resolution + "p");
        } else {
            parts.add("unknown res");
        }

        parts.add(m.hdrLabel);

        if (m.remux) {
            parts.add("REMUX");
        }

        parts.add(m.sizeDisplay);

        if (!"unknown".equals(m.codec)) {
            parts.add(m.codec);
        }

        if (m.seeders > 0) {
            parts.add(m.seeders + " seeds");
        }

        String summary = join(parts, ", ");

        if (comparison == null) {
            return summary + " - wins as only valid stream";
        }

        ParsedMeta other = comparison.meta;
        boolean curHdr = m.hdr || m.dolbyVision;
        boolean othHdr = other.hdr || other.dolbyVision;

        if (isWinner) {
            if (m.cached != other.cached) {
                return summary + " - wins: cached/instant-play beats uncached";
            }
            if (m.resolution != other.resolution) {
                if (m.aiUpscale && m.sizeBytes < other.sizeBytes) {
                    return summary + " - wins: 4K AI Upscale beats " + other.resolution + "p despite smaller file size";
                }
                return summary + " - wins: higher resolution (" + m.resolution + "p beats " + other.resolution + "p)";
            }
            if (curHdr != othHdr) {
                return summary + " - wins: " + m.hdrLabel + " beats SDR at " + m.resolution + "p despite smaller/different size";
            }
            if (m.sizeBytes != other.sizeBytes) {
                return summary + " - wins: largest file (" + m.sizeDisplay + " vs " + other.sizeDisplay + ")"
                        + (isMovie && m.remux ? " for highest-bitrate movie REMUX" : "");
            }
            if (m.codecRank != other.codecRank) {
                return summary + " - wins tie-breaker: preferred codec (" + m.codec + ")";
            }
            if (m.seeders != other.seeders) {
                return summary + " - wins tie-breaker: more seeders (" + m.seeders + " vs " + other.seeders + ")";
            }
            return summary + " - tied for best";
        } else {
            if (m.cached != other.cached) {
                return summary + " - ranked lower: uncached vs winner's cached stream";
            }
            if (m.resolution != other.resolution) {
                return summary + " - ranked lower: " + m.resolution + "p loses to winner's 2160p/4K";
            }
            if (curHdr != othHdr) {
                return summary + " - ranked lower: SDR loses to winner's " + other.hdrLabel + " at same resolution";
            }
            if (m.sizeBytes != other.sizeBytes) {
                return summary + " - ranked lower: smaller file (" + m.sizeDisplay + " vs winner's " + other.sizeDisplay + ")";
            }
            return summary;
        }
    }

    private static String join(List<String> items, String delimiter) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(delimiter);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    private static final class Candidate {
        final Stream stream;
        final ParsedMeta meta;
        final long score;

        Candidate(Stream stream, ParsedMeta meta, long score) {
            this.stream = stream;
            this.meta = meta;
            this.score = score;
        }
    }
}

