package com.vanipy.tengok;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Read-only central-directory listing. Inflates one bounded entry in memory, never writes files. */
public final class ZipIndex {
    public static final int MAX_ENTRIES = 2000;
    public static final int MAX_ENTRY_BYTES = 16 * 1024 * 1024;
    public static final int MAX_RATIO = 100;
    public static final class Entry {
        public final String name;
        public final long size, packed;
        private final long crc;
        private final int local, method, dataEnd;
        public final boolean encrypted, directory;
        Entry(String name, long size, long packed, boolean encrypted, long crc, int local, int method, int dataEnd) {
            this.crc = crc; this.local = local; this.method = method; this.dataEnd = dataEnd;
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
            entries.add(new Entry(name, size, packed, (flags & 1) != 0, u32(b, p + 16), (int) local, u16(b, p + 10), (int) offset));
            p = (int) next;
        }
        require(p == end, "ZIP directory size does not match its entries.");
        return entries;
    }
    /** Metadata is re-read from these archive bytes; caller selects only the index. */
    public static byte[] open(byte[] archive, int index) throws IOException {
        List<Entry> entries = parse(archive);
        require(index >= 0 && index < entries.size(), "ZIP entry is no longer available.");
        Entry e = entries.get(index);
        require(!e.directory, "This entry is a folder.");
        require(!e.encrypted, "Encrypted entries cannot be opened.");
        require(e.method == 0 || e.method == 8, "Only stored and deflated ZIP entries can be opened.");
        require(e.size <= MAX_ENTRY_BYTES, "ZIP entry opening is limited to 16 MB per file.");
        require(e.size <= Math.max(1L, e.packed) * MAX_RATIO, "ZIP compression ratio exceeds the 100:1 safety limit.");
        int p = e.local;
        require((u16(archive, p + 6) & 1) == 0 && u16(archive, p + 8) == e.method,
                "ZIP local and central entry metadata disagree.");
        long start = (long) p + 30 + u16(archive, p + 26) + u16(archive, p + 28);
        require(start + e.packed <= e.dataEnd, "ZIP entry data is truncated.");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        if (e.method == 0) {
            require(e.packed == e.size, "Stored ZIP entry sizes disagree.");
            out.write(archive, (int) start, (int) e.packed);
        } else {
            java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
            try {
                inflater.setInput(archive, (int) start, (int) e.packed);
                byte[] buf = new byte[8192];
                while (!inflater.finished()) {
                    int n = inflater.inflate(buf);
                    require((long) out.size() + n <= e.size && (long) out.size() + n <= MAX_ENTRY_BYTES,
                            "ZIP entry expands beyond its declared size or safety limit.");
                    if (n > 0) out.write(buf, 0, n);
                    else require(inflater.finished(), "ZIP compressed data is truncated or invalid.");
                }
                require(inflater.getBytesRead() == e.packed, "ZIP compressed size does not match its data.");
            } catch (java.util.zip.DataFormatException ex) {
                throw new IOException("ZIP compressed data is invalid.", ex);
            } finally { inflater.end(); }
        }
        byte[] data = out.toByteArray();
        require(data.length == e.size, "ZIP entry size check failed.");
        java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(data);
        require(crc.getValue() == e.crc, "ZIP entry checksum check failed.");
        return data;
    }

}
