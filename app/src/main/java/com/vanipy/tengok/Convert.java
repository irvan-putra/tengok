package com.vanipy.tengok;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Conversion targets and pure helpers. Execution lives in ConvertPanel
 * (platform Bitmap for images, Media3 Transformer for audio/video); this
 * class touches no Android media classes so the JVM tests can cover it.
 *
 * The contract, same as the web viewer: the original file is never
 * modified, the result is a brand-new file the user picks a place for, and
 * every target says honestly what the conversion can lose.
 */
public final class Convert {
    private Convert() {
    }

    public static final class Target {
        public final String id;
        public final String label;
        public final String ext;
        public final String mime;
        public final String note;

        Target(String id, String label, String ext, String mime, String note) {
            this.id = id;
            this.label = label;
            this.ext = ext;
            this.mime = mime;
            this.note = note;
        }
    }

    public static List<Target> targetsFor(Sniffer.Kind kind) {
        List<Target> out = new ArrayList<>();
        switch (kind) {
            case IMAGE:
                out.add(new Target("png", "PNG image", "png", "image/png",
                        "Lossless output encoding. Transparency kept; usually a bigger file. "
                        + "Metadata (like EXIF) is not carried over."));
                out.add(new Target("jpeg", "JPEG image", "jpg", "image/jpeg",
                        "Lossy at 92% quality: slightly smaller, fine detail softens. "
                        + "Transparency becomes white. Metadata is not carried over."));
                out.add(new Target("webp", "WebP image", "webp", "image/webp",
                        "Lossy at 95% quality: small with good detail. Transparency kept. "
                        + "Metadata is not carried over."));
                break;
            case AUDIO:
                out.add(new Target("m4a", "AAC audio (.m4a)", "m4a", "audio/mp4",
                        "Lossy re-encode with the phone's own AAC encoder: a small file "
                        + "with some fine detail discarded. Anything the source codec "
                        + "already lost stays lost."));
                break;
            case VIDEO:
                out.add(new Target("mp4", "MP4 video (H.264 + AAC)", "mp4", "video/mp4",
                        "Re-encoded with the phone's own codecs. Lossy: some fine detail "
                        + "softens. Big files can take minutes."));
                break;
            case TEXT:
                out.add(new Target("utf8", "UTF-8 text", "txt", "text/plain",
                        "Re-saved as UTF-8. Characters survive when the source encoding "
                        + "is detected correctly."));
                out.add(new Target("latin1", "Latin-1 text", "txt", "text/plain",
                        "Old single-byte encoding. Characters outside Latin-1 become '?' - "
                        + "that loss cannot be undone in the result."));
                break;
            default:
                break; // MIDI and HEX offer nothing yet
        }
        return out;
    }

    /** "photo.jpeg" + "png" -> "photo.png"; a nameless source becomes "converted.<ext>". */
    public static String outName(String name, String ext) {
        String base = (name == null || name.isEmpty()) ? "converted" : name;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        return base + "." + ext;
    }

    /**
     * Re-encode already-decoded text. ISO-8859-1's encoder replaces
     * characters it cannot represent with '?', which the target note states.
     */
    public static byte[] reencodeText(String text, String targetId) {
        Charset cs = "latin1".equals(targetId)
                ? Charset.forName("ISO-8859-1") : StandardCharsets.UTF_8;
        return text.getBytes(cs);
    }
}
