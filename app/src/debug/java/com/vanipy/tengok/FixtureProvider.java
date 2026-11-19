package com.vanipy.tengok;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;

/** Debug builds only: serves files from the app's own files dir as content://
 *  URIs so the emulator smoke test can drive the real implicit VIEW route the
 *  way a file manager would. Never shipped in release APKs. */
public class FixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("..") || name.contains("/")) {
            throw new FileNotFoundException("bad name");
        }
        File f = new File(getContext().getFilesDir(), name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0) {
                String mime = MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
                if (mime != null) return mime;
            }
        }
        return "application/octet-stream";
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
