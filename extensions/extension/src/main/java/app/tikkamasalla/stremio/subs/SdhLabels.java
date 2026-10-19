package app.tikkamasalla.stremio.subs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure Java subtitle helpers: label ranking, content scoring and cue cleaning.
 * No Android or Media3 imports, so plain javac unit tests can compile this
 * file on its own. Unicode escapes are used instead of literal symbols.
 */
public final class SdhLabels {
    private SdhLabels() {
    }

    /** Word boundaries keep "Chinese" and "Ethics" from matching HI. */
    private static final Pattern SDH_LABEL = Pattern.compile(
            "(?i)(?:\\b(SDH|CC|HI)\\b|hearing impaired|closed captions?|\\bdeaf\\b)");
    private static final Pattern FORCED_LABEL = Pattern.compile("(?i)\\bforced\\b");
    private static final Pattern SIGNS_LABEL = Pattern.compile("(?i)\\bsigns\\b");

    /** Bracketed or parenthesised sound tags, also used by the cleaner. */
    private static final Pattern BRACKET_TAG =
            Pattern.compile("\\[[^\\[\\]]{0,80}\\]|\\([^\\(\\)]{0,80}\\)");
    /** Music notes: stripped by the cleaner, lyric words between them are kept. */
    private static final Pattern MUSIC_NOTE = Pattern.compile("[\\u266A\\u266B\\u2669]");
    /** ALL-CAPS speaker label at the start of a line, e.g. JOHN: or [MARY]: is a tag. */
    private static final Pattern SPEAKER_LABEL =
            Pattern.compile("^[A-Z][A-Z0-9 .'\\-]{0,24}:\\s*");
    /** Styling tags that carry no meaning: <i>, <font ...>, {\\an8}. */
    private static final Pattern STYLE_TAG = Pattern.compile("<[^<>]{0,60}>|\\{\\\\[^\\{\\}]{0,40}\\}");
    private static final Pattern BLANKS = Pattern.compile("[ \\t\\u00A0]+");
    private static final Pattern TIMESTAMP_LINE =
            Pattern.compile("\\d{1,2}:\\d{2}(:\\d{2})?([,.]\\d{1,3})?\\s*-->");

    /** Track kinds, best first. Only FULL and SIGNS are dialogue complete. */
    public static final int KIND_FULL = 0;
    public static final int KIND_SIGNS = 1;
    public static final int KIND_FORCED = 2;
    public static final int KIND_SDH = 3;

    /** Role flag for sound descriptions (Media3 C value 0x400). */
    private static final int ROLE_DESCRIBES_SOUND = 0x400;
    /** Selection flag for forced tracks (Media3 C value 0x2). */
    private static final int SELECTION_FORCED = 0x2;

    /** Share of dialogue cues with sound descriptions at or above which a file counts as SDH. */
    public static final double SDH_THRESHOLD = 0.10;
    /** Minimum dialogue cues before a content verdict is trusted. */
    public static final int MIN_DIALOGUE_CUES = 10;

    /** True when the track name itself advertises hearing-impaired content. */
    public static boolean isSdhLabel(String label) {
        return label != null && SDH_LABEL.matcher(label).find();
    }

    public static boolean isForcedLabel(String label) {
        return label != null && FORCED_LABEL.matcher(label).find();
    }

    public static boolean isSignsLabel(String label) {
        return label != null && SIGNS_LABEL.matcher(label).find();
    }

    /** Rank a track from its label and Media3 flags. Lower is better. */
    public static int kindOf(String label, int roleFlags, int selectionFlags) {
        if ((roleFlags & ROLE_DESCRIBES_SOUND) != 0) return KIND_SDH;
        if (label != null) {
            if (SDH_LABEL.matcher(label).find()) return KIND_SDH;
            if (FORCED_LABEL.matcher(label).find()) return KIND_FORCED;
            if (SIGNS_LABEL.matcher(label).find()) return KIND_SIGNS;
        }
        if ((selectionFlags & SELECTION_FORCED) != 0) return KIND_FORCED;
        return KIND_FULL;
    }

    /** Normalize a subtitle language for comparison: eng and en both become en. */
    public static String normLang(String lang) {
        if (lang == null) return "";
        String l = lang.toLowerCase(Locale.US).replace('_', '-');
        int dash = l.indexOf('-');
        if (dash > 0) l = l.substring(0, dash);
        if (l.length() == 3) {
            if (l.equals("eng")) return "en";
            if (l.equals("spa")) return "es";
            if (l.equals("fra") || l.equals("fre")) return "fr";
            if (l.equals("deu") || l.equals("ger")) return "de";
            if (l.equals("ita")) return "it";
            if (l.equals("por")) return "pt";
            if (l.equals("rus")) return "ru";
            if (l.equals("jpn")) return "ja";
            if (l.equals("zho") || l.equals("chi")) return "zh";
            if (l.equals("kor")) return "ko";
            if (l.equals("hin")) return "hi";
            if (l.equals("ara")) return "ar";
            if (l.equals("nld") || l.equals("dut")) return "nl";
            if (l.equals("swe")) return "sv";
            if (l.equals("nor") || l.equals("nob")) return "nb";
            if (l.equals("dan")) return "da";
            if (l.equals("fin")) return "fi";
            if (l.equals("pol")) return "pl";
            if (l.equals("tur")) return "tr";
            if (l.equals("ukr")) return "uk";
            return l;
        }
        return l;
    }

    /** True when the cue carries a sound description: tag, music note or speaker label. */
    public static boolean isSoundCue(String cueText) {
        if (cueText == null) return false;
        String t = cueText.trim();
        if (t.isEmpty()) return false;
        if (MUSIC_NOTE.matcher(t).find()) return true;
        if (BRACKET_TAG.matcher(t).find()) return true;
        for (String line : t.split("\r?\n")) {
            if (SPEAKER_LABEL.matcher(line.trim()).find()) return true;
        }
        return false;
    }

    /** True when the cue holds dialogue: any letter, or a music-only cue. */
    public static boolean isDialogueCue(String cueText) {
        if (cueText == null) return false;
        String t = cueText.trim();
        if (t.isEmpty()) return false;
        for (int i = 0; i < t.length(); i++) {
            if (Character.isLetter(t.charAt(i))) return true;
        }
        return MUSIC_NOTE.matcher(t).find();
    }

    /**
     * Share of dialogue cues that carry sound descriptions.
     * Returns -1 when fewer than MIN_DIALOGUE_CUES dialogue cues are present.
     */
    public static double scoreCues(List<String> cues) {
        int dialogue = 0;
        int sound = 0;
        for (String cue : cues) {
            boolean sdh = isSoundCue(cue);
            if (!isDialogueCue(cue)) {
                if (!sdh) continue;
                dialogue++;
                sound++;
                continue;
            }
            dialogue++;
            if (sdh) sound++;
        }
        if (dialogue < MIN_DIALOGUE_CUES) return -1.0;
        return (double) sound / (double) dialogue;
    }

    /** True when the cues score at or above SDH_THRESHOLD. Weak samples keep the track. */
    public static boolean isSdhContent(List<String> cues) {
        return scoreCues(cues) >= SDH_THRESHOLD;
    }

    /** Parse an .srt body into per-cue text (lines joined with line feeds). */
    public static List<String> parseSrt(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        String body = text.replace("\r\n", "\n").replace('\r', '\n');
        if (body.length() > 0 && body.charAt(0) == '﻿') body = body.substring(1);
        String[] blocks = body.split("\n[ \\t\\u00A0]*\n");
        for (String block : blocks) {
            String[] lines = block.split("\n");
            StringBuilder cue = new StringBuilder();
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (cue.length() == 0 && line.matches("\\d{1,6}")) continue;
                if (TIMESTAMP_LINE.matcher(line).find()) continue;
                line = STYLE_TAG.matcher(line).replaceAll("");
                line = BLANKS.matcher(line).replaceAll(" ").trim();
                if (line.isEmpty()) continue;
                if (cue.length() > 0) cue.append('\n');
                cue.append(line);
            }
            if (cue.length() > 0) out.add(cue.toString());
        }
        return out;
    }

    /** Parse a WebVTT body into per-cue text. */
    public static List<String> parseVtt(String text) {
        List<String> out = new ArrayList<String>();
        if (text == null) return out;
        String body = text.replace("\r\n", "\n").replace('\r', '\n');
        if (body.length() > 0 && body.charAt(0) == '﻿') body = body.substring(1);
        String[] blocks = body.split("\n[ \\t\\u00A0]*\n");
        boolean first = true;
        for (String block : blocks) {
            if (first) {
                first = false;
                if (block.trim().startsWith("WEBVTT")) continue;
            }
            String trimmed = block.trim();
            if (trimmed.startsWith("NOTE") || trimmed.startsWith("STYLE")
                    || trimmed.startsWith("REGION")) continue;
            String[] lines = block.split("\n");
            StringBuilder cue = new StringBuilder();
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty()) continue;
                if (TIMESTAMP_LINE.matcher(line).find()) continue;
                if (!line.contains(" ") && cue.length() == 0 && !line.contains("<")) {
                    // Possible numeric cue identifier on its own line; keep only when
                    // more lines follow, decided below by cue length.
                }
                line = STYLE_TAG.matcher(line).replaceAll("");
                line = BLANKS.matcher(line).replaceAll(" ").trim();
                if (line.isEmpty()) continue;
                if (cue.length() > 0) cue.append('\n');
                cue.append(line);
            }
            String built = cue.toString();
            if (built.matches("\\d{1,6}")) continue;
            if (!built.isEmpty()) out.add(built);
        }
        return out;
    }

    /** Pick the parser by url suffix, falling back to content sniffing. */
    public static List<String> parseSubtitleFile(String url, String text) {
        if (url != null && url.toLowerCase(Locale.US).contains(".vtt")) return parseVtt(text);
        if (text != null) {
            String head = text.length() > 200 ? text.substring(0, 200) : text;
            if (head.contains("WEBVTT")) return parseVtt(text);
        }
        return parseSrt(text);
    }

    /**
     * Strip the SDH parts from one cue text: sound tags in []/() (even split
     * across lines), lone music notes, leading speaker labels, styling tags.
     * Lyric words between notes are kept, only the notes go. Returns null when
     * nothing speakable remains and the cue should be dropped.
     */
    public static String cleanCueText(String text) {
        if (text == null) return null;
        String whole = STYLE_TAG.matcher(text).replaceAll("");
        whole = BRACKET_TAG.matcher(whole).replaceAll(" ");
        whole = MUSIC_NOTE.matcher(whole).replaceAll(" ");
        String[] lines = whole.split("\r?\n");
        StringBuilder out = new StringBuilder();
        for (String raw : lines) {
            String line = raw;
            Matcher speaker = SPEAKER_LABEL.matcher(line.trim());
            if (speaker.find()) line = line.trim().substring(speaker.end());
            line = BLANKS.matcher(line).replaceAll(" ").trim();
            line = line.replaceAll("\\s+([,.!?;:])", "$1").trim();
            if (line.isEmpty() || line.equals("-")) continue;
            if (out.length() > 0) out.append('\n');
            out.append(line);
        }
        if (out.length() == 0) return null;
        return out.toString();
    }
}
