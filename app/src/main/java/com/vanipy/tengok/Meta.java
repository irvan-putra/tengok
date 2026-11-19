package com.vanipy.tengok;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** File facts for the details sheet. Hash streams the file once, in chunks. */
public final class Meta {
    private Meta() {
    }

    public static String sha256(Source source) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            return "unavailable";
        }
        byte[] buf = new byte[8192];
        try (InputStream in = source.open()) {
            int r;
            while ((r = in.read(buf)) >= 0) md.update(buf, 0, r);
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format(Locale.ROOT, "%02x", b & 0xFF));
        return sb.toString();
    }

    public static String humanSize(long size) {
        if (size < 0) return "unknown size";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f MB", size / 1048576.0);
        return String.format(Locale.ROOT, "%.2f GB", size / 1073741824.0);
    }
}
