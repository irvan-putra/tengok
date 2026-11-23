package com.vanipy.tengok;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Read-only central-directory listing. Never inflates or writes archive entries. */
public final class ZipIndex {
    public static final int MAX_ENTRIES = 2000;
    public static final class Entry {
        public final String name;
        public final long size, packed;
        public final boolean encrypted, directory;
        Entry(String name, long size, long packed, boolean encrypted) {
            this.name = name; this.size = size; this.packed = packed;
            this.encrypted = encrypted; this.directory = name.endsWith("/");
        }
    }
    private static int u16(byte[] b, int p) {
        return (b[p] & 255) | ((b[p + 1] & 255) << 8);
    }
    private static long u32(byte[] b, int p) {
        return (u16(b, p) & 65535L) | ((long) u16(b, p + 2) << 16);
    }
    private static void require(boolean ok, String message) throws IOException {
        if (!ok) throw new IOException(message);
    }
    public static List<Entry> parse(byte[] b) throws IOException {
        require(b.length <= 32 * 1024 * 1024, "ZIP listing is limited to 32 MB archives.");
        int end = -1;
        for (int p = b.length - 22; p >= Math.max(0, b.length - 65557); p--) {
            if (u32(b, p) == 0x06054b50L && p + 22 + u16(b, p + 20) == b.length) {
                end = p; break;
            }
        }
        require(end >= 0, "ZIP directory is missing or truncated.");
        require(u16(b, end + 4) == 0 && u16(b, end + 6) == 0
                && u16(b, end + 8) == u16(b, end + 10), "Split ZIP archives are not supported.");
        int count = u16(b, end + 10);
        long bytes = u32(b, end + 12), offset = u32(b, end + 16);
        require(count != 65535 && bytes != 0xffffffffL && offset != 0xffffffffL,
                "ZIP64 archives are not supported.");
        require(count <= MAX_ENTRIES, "ZIP listing is limited to 2,000 entries.");
        require(offset + bytes == end, "ZIP directory bounds are invalid or unsupported.");
        int p = (int) offset;
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            require(p >= 0 && (long) p + 46 <= end && u32(b, p) == 0x02014b50L,
                    "ZIP directory entry is truncated or invalid.");
            int flags = u16(b, p + 8), len = u16(b, p + 28);
            long next = (long) p + 46 + len + u16(b, p + 30) + u16(b, p + 32);
            require(next <= end && len > 0, "ZIP entry name or metadata is truncated.");
            long packed = u32(b, p + 20), size = u32(b, p + 24), local = u32(b, p + 42);
            require(size != 0xffffffffL && packed != 0xffffffffL && local != 0xffffffffL,
                    "ZIP64 entries are not supported.");
            require(u16(b, p + 34) == 0, "Split ZIP archives are not supported.");
            require(local + 30 <= offset && u32(b, (int) local) == 0x04034b50L,
                    "ZIP local entry header is invalid.");
            String name = new String(b, p + 46, len,
                    (flags & 2048) != 0 ? StandardCharsets.UTF_8 : Charset.forName("IBM437"));
            // Control characters must not hide lines or mimic UI controls.
            name = name.replaceAll("[\\p{Cntrl}\\p{Cf}]", "\uFFFD");
            entries.add(new Entry(name, size, packed, (flags & 1) != 0));
            p = (int) next;
        }
        require(p == end, "ZIP directory size does not match its entries.");
        return entries;
    }
}
