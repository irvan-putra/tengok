package com.vanipy.tengok;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CrashLogTest {

    @Test
    public void sanitize_redactsUrisAndPaths_keepsStackFrames() {
        String trace = "java.lang.SecurityException: Permission Denial: reading "
                + "com.android.providers.media.MediaProvider uri "
                + "content://media/external_primary/file/4812 from pid=1234\n"
                + "\tat com.vanipy.tengok.HashUtil.md5(HashUtil.java:42)\n"
                + "Caused by: java.io.FileNotFoundException: /storage/emulated/0/Music/cantina2.mid: open failed\n"
                + "\tat android.content.ContentResolver.openInputStream(ContentResolver.java:1526)";
        String out = CrashLog.sanitize(trace);
        assertFalse(out.contains("content://media"));
        assertFalse(out.contains("cantina2.mid"));
        assertFalse(out.contains("/storage/emulated"));
        assertTrue(out.contains("SecurityException"));
        assertTrue(out.contains("com.vanipy.tengok.HashUtil.md5(HashUtil.java:42)"));
        assertTrue(out.contains("FileNotFoundException"));
        assertTrue(out.contains("ContentResolver.openInputStream(ContentResolver.java:1526)"));
    }

    @Test
    public void sanitize_leavesCleanTraceUntouched() {
        String trace = "java.lang.NullPointerException: Attempt to invoke virtual method "
                + "'int java.lang.String.length()' on a null object reference\n"
                + "\tat com.vanipy.tengok.ViewerActivity.showDetails(ViewerActivity.java:300)";
        String out = CrashLog.sanitize(trace);
        assertTrue(out.contains("NullPointerException"));
        assertTrue(out.contains("String.length()"));
        assertTrue(out.contains("com.vanipy.tengok.ViewerActivity.showDetails(ViewerActivity.java:300)"));
    }

    @Test
    public void sanitize_keepsMimeTypesAndComponentNames() {
        String out = CrashLog.sanitize("declared=audio/midi "
                + "ComponentInfo{com.vanipy.tengok/.MainActivity} "
                + "/storage/emulated/0/Music/cantina2.mid");
        assertTrue(out.contains("audio/midi"));
        assertTrue(out.contains("com.vanipy.tengok/.MainActivity"));
        assertFalse(out.contains("cantina2.mid"));
    }


    @Test
    public void shareable_dropsMessagesAndForeignFrames() {
        String crash = "tengok crash report\n"
                + "2026-11-19 18:00:00 +0700\n"
                + "version 0.1.4\n"
                + "thread main\n"
                + "android 14 sdk 34 device Pixel 8\n"
                + "java.lang.SecurityException: Permission Denial: reading "
                + "com.android.providers.media.MediaProvider uri content://media/external_primary/file/4812\n"
                + "\tat com.vanipy.tengok.Meta.sha256(Meta.java:57)\n"
                + "\tat com.vanipy.tengok.ViewerActivity.lambda$showDetails$2(ViewerActivity.java:230)\n"
                + "\tat android.content.ContentResolver.openInputStream(ContentResolver.java:1526)\n"
                + "\tat com.android.providers.media.MediaProvider.openFile(MediaProvider.java:999)\n"
                + "Caused by: java.io.FileNotFoundException: /storage/emulated/0/Music/cantina2.mid: open failed\n"
                + "\t... 3 more\n";
        String out = CrashLog.shareable(crash);
        assertTrue(out.contains("tengok crash report"));
        assertTrue(out.contains("version 0.1.4"));
        assertTrue(out.contains("java.lang.SecurityException"));
        assertTrue(out.contains("Caused by: java.io.FileNotFoundException"));
        assertTrue(out.contains("at com.vanipy.tengok.Meta.sha256(Meta.java:57)"));
        assertFalse(out.contains("content://media"));
        assertFalse(out.contains("cantina2.mid"));
        assertFalse(out.contains("/storage/emulated"));
        assertFalse(out.contains("MediaProvider"));
        assertFalse(out.contains("ContentResolver.openInputStream"));
        assertFalse(out.contains("Permission Denial"));
        assertFalse(out.contains("... 3 more"));
        assertTrue(out.contains("(full trace stays on this phone)"));
    }

    @Test
    public void shareable_handlesMessagelessException() {
        String crash = "tengok crash report\nversion 0.1.4\nthread main\n"
                + "java.lang.NullPointerException\n"
                + "\tat com.vanipy.tengok.ViewerActivity.onCreate(ViewerActivity.java:50)\n";
        String out = CrashLog.shareable(crash);
        assertTrue(out.contains("java.lang.NullPointerException"));
        assertTrue(out.contains("at com.vanipy.tengok.ViewerActivity.onCreate(ViewerActivity.java:50)"));
        assertFalse(out.contains("(exception class unreadable)"));
    }
}
