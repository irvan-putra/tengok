package com.vanipy.tengok;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A file tengok is looking at. The app never copies real files anywhere:
 * UriSource streams straight from the granting provider. BytesSource and
 * FileSource exist for debug fixtures and tests only.
 */
public abstract class Source {
    public String name = "file";
    public long size = -1;

    public abstract InputStream open() throws IOException;

    /** In-memory media source, used only by archive entries. No temporary files. */
    public android.media.MediaDataSource mediaDataSource() { return null; }

    /** A seekable descriptor when the provider gives one, else null. */
    public ParcelFileDescriptor openFd() throws IOException {
        return null;
    }

    /** A real file path when one exists (debug fixtures), else null. */
    public File localFile() {
        return null;
    }

    /** The content Uri when one exists (used for MediaPlayer), else null. */
    public Uri uri() {
        return null;
    }

    public byte[] head(int n) throws IOException {
        try (InputStream in = open()) {
            byte[] buf = new byte[n];
            int off = 0;
            while (off < n) {
                int r = in.read(buf, off, n - off);
                if (r < 0) break;
                off += r;
            }
            byte[] out = new byte[off];
            System.arraycopy(buf, 0, out, 0, off);
            return out;
        }
    }

    /** Hard cap so one hostile "file" cannot eat the phone's memory. */
    public static final long MAX_BUFFERED_BYTES = 32L * 1024 * 1024;

    public byte[] readAll(long cap) throws IOException {
        if (size >= 0 && size > cap) {
            throw new IOException("file is " + size + " bytes, over the " + cap + " byte limit");
        }
        try (InputStream in = open()) {
            byte[] buf = new byte[8192];
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            long total = 0;
            int r;
            while ((r = in.read(buf)) >= 0) {
                total += r;
                if (total > cap) {
                    throw new IOException("file is over the " + cap + " byte limit");
                }
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        }
    }

    public static Source forUri(Context ctx, Uri uri) {
        UriSource s = new UriSource(ctx, uri);
        return s;
    }

    static final class UriSource extends Source {
        private final Context ctx;
        private final Uri uri;

        UriSource(Context ctx, Uri uri) {
            this.ctx = ctx.getApplicationContext();
            this.uri = uri;
            this.name = queryName(ctx, uri);
            this.size = querySize(ctx, uri);
        }

        private static String queryName(Context ctx, Uri uri) {
            if ("file".equals(uri.getScheme())) {
                String p = uri.getPath();
                if (p != null) return new File(p).getName();
            }
            try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (i >= 0) {
                        String n = c.getString(i);
                        if (n != null && !n.isEmpty()) return n;
                    }
                }
            } catch (Exception ignored) {
            }
            String last = uri.getLastPathSegment();
            return last != null ? last : "file";
        }

        private static long querySize(Context ctx, Uri uri) {
            try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    int i = c.getColumnIndex(OpenableColumns.SIZE);
                    if (i >= 0 && !c.isNull(i)) return c.getLong(i);
                }
            } catch (Exception ignored) {
            }
            return -1;
        }

        @Override
        public InputStream open() throws IOException {
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) throw new IOException("the provider returned no stream");
            return in;
        }

        @Override
        public ParcelFileDescriptor openFd() throws IOException {
            return ctx.getContentResolver().openFileDescriptor(uri, "r");
        }

        @Override
        public Uri uri() {
            return uri;
        }
    }

    /** Debug/test only: an in-memory file. */
    public static final class BytesSource extends Source {
        private final byte[] data;

        public BytesSource(String name, byte[] data) {
            this.name = name;
            this.data = data;
            this.size = data.length;
        }

        @Override
        public InputStream open() { return new ByteArrayInputStream(data); }

        @Override
        public android.media.MediaDataSource mediaDataSource() {
            return new android.media.MediaDataSource() {
                @Override public int readAt(long pos, byte[] buffer, int offset, int count) {
                    if (pos < 0 || pos >= data.length) return -1;
                    int n = Math.min(count, data.length - (int) pos);
                    System.arraycopy(data, (int) pos, buffer, offset, n); return n;
                }
                @Override public long getSize() { return data.length; }
                @Override public void close() { }
            };
        }
    }

    /** Debug fixtures: a file inside the app's own directories. */
    public static final class FileSource extends Source {
        private final File file;

        public FileSource(File file) {
            this.file = file;
            this.name = file.getName();
            this.size = file.length();
        }

        @Override
        public InputStream open() throws IOException {
            return new FileInputStream(file);
        }

        @Override
        public ParcelFileDescriptor openFd() throws IOException {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        @Override
        public File localFile() {
            return file;
        }
    }
}
