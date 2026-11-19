package com.vanipy.tengok;

import java.util.Locale;

/**
 * Decides what a file probably is, from its leading bytes first, then the
 * declared MIME type, then its extension, then a text heuristic. Detection
 * is a hint for picking a viewer, never a security boundary: each viewer
 * still parses defensively and fails with a readable error.
 */
public final class Sniffer {

    public enum Kind {MIDI, IMAGE, AUDIO, TEXT, HEX}

    private Sniffer() {
    }

    public static Kind sniff(String name, String declaredMime, byte[] head) {
        Kind byMagic = byMagic(head);
        if (byMagic != null) return byMagic;
        Kind byMime = byMime(declaredMime);
        if (byMime != null) return byMime;
        Kind byExt = byExtension(name);
        if (byExt != null) return byExt;
        return looksLikeText(head) ? Kind.TEXT : Kind.HEX;
    }

    static Kind byMagic(byte[] h) {
        if (h == null || h.length < 4) return null;
        if (h[0] == 'M' && h[1] == 'T' && h[2] == 'h' && h[3] == 'd') return Kind.MIDI;
        if ((h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return Kind.IMAGE;
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8) return Kind.IMAGE; // jpeg
        if (h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8') return Kind.IMAGE;
        if (h[0] == 'B' && h[1] == 'M') return Kind.IMAGE;
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return Kind.IMAGE;
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E') return Kind.AUDIO;
        if (h.length >= 12 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') {
            String brand = new String(h, 8, 4);
            if (brand.startsWith("heic") || brand.startsWith("heix") || brand.startsWith("hevc")
                    || brand.startsWith("mif1") || brand.startsWith("msf1") || brand.startsWith("avif")
                    || brand.startsWith("avis")) return Kind.IMAGE;
            return null; // mp4/mov family: video is not a v0.1 viewer
        }
        if (h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return Kind.AUDIO;
        if (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return Kind.AUDIO;
        if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') return Kind.AUDIO; // mp3 with tag
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) return Kind.AUDIO; // mp3 frame sync
        return null;
    }

    static Kind byMime(String mime) {
        if (mime == null) return null;
        String m = mime.toLowerCase(Locale.ROOT);
        if (m.equals("audio/midi") || m.equals("audio/x-midi") || m.equals("audio/mid")
                || m.equals("application/x-midi")) return Kind.MIDI;
        if (m.startsWith("image/")) return Kind.IMAGE;
        if (m.equals("audio/mpeg") || m.equals("audio/mp4") || m.equals("audio/ogg")
                || m.equals("audio/flac") || m.equals("audio/x-wav") || m.equals("audio/wav")
                || m.equals("audio/aac") || m.equals("audio/opus") || m.equals("audio/3gpp")) return Kind.AUDIO;
        if (m.startsWith("text/")) return Kind.TEXT;
        if (m.equals("application/json") || m.equals("application/xml")) return Kind.TEXT;
        return null;
    }

    static Kind byExtension(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return null;
        String e = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        switch (e) {
            case "mid":
            case "midi":
            case "rmi":
            case "xmf":
            case "mxmf":
                return Kind.MIDI;
            case "png":
            case "jpg":
            case "jpeg":
            case "webp":
            case "gif":
            case "bmp":
            case "heic":
            case "heif":
            case "avif":
            case "wbmp":
                return Kind.IMAGE;
            case "mp3":
            case "ogg":
            case "oga":
            case "flac":
            case "wav":
            case "m4a":
            case "aac":
            case "opus":
            case "amr":
            case "3ga":
                return Kind.AUDIO;
            case "txt":
            case "log":
            case "md":
            case "markdown":
            case "json":
            case "xml":
            case "csv":
            case "tsv":
            case "yaml":
            case "yml":
            case "ini":
            case "cfg":
            case "conf":
            case "properties":
            case "sh":
            case "py":
            case "java":
            case "kt":
            case "js":
            case "css":
            case "html":
            case "htm":
            case "c":
            case "cpp":
            case "h":
            case "sql":
            case "srt":
                return Kind.TEXT;
            default:
                return null;
        }
    }

    /** Bytes with no NULs and almost all printable / whitespace are shown as text. */
    static boolean looksLikeText(byte[] h) {
        if (h == null) return false;
        if (h.length == 0) return false;
        int bad = 0;
        for (byte b : h) {
            int v = b & 0xFF;
            if (v == 0) return false;
            boolean printable = v >= 0x20 && v < 0x7F;
            boolean space = v == '\n' || v == '\r' || v == '\t' || v == '\f';
            boolean utf8Lead = v >= 0xC0; // crude but fine for a hint
            if (!printable && !space && !utf8Lead) bad++;
        }
        return bad * 25 < h.length; // under 4% odd bytes
    }
}
