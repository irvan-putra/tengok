package com.vanipy.tengok;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class DiagLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void appendAndReadRoundTrips() throws Exception {
        File f = new File(tmp.getRoot(), "diag.log");
        DiagLog.append(f, "11-22 33:44:55.000 VIEW kind=midi");
        DiagLog.append(f, "11-22 33:44:56.000 DETAILS-OK");
        String log = DiagLog.read(f);
        assertTrue(log.contains("VIEW kind=midi"));
        assertTrue(log.contains("DETAILS-OK"));
    }

    @Test
    public void trimKeepsNewestTailOnLineBoundary() throws Exception {
        File f = new File(tmp.getRoot(), "diag.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("line-").append(i).append(" padding-padding-padding\n");
        }
        java.nio.file.Files.write(f.toPath(), sb.toString().getBytes("UTF-8"));
        DiagLog.trim(f);
        String log = DiagLog.read(f);
        assertTrue(log.length() <= 48 * 1024);
        assertFalse(log.contains("line-0 "));
        assertTrue(log.contains("line-1999"));
        assertTrue(log.startsWith("line-"));  // boundary: no half line
    }

    @Test
    public void trimLeavesSmallFilesUntouched() throws Exception {
        File f = new File(tmp.getRoot(), "diag.log");
        DiagLog.append(f, "one");
        DiagLog.trim(f);
        assertEquals("one\n", DiagLog.read(f));
    }

    @Test
    public void scrubRedactsWatchedDisplayNameKeepsMime() {
        DiagLog.watch("cantina2.mid");
        String out = DiagLog.scrub("java.io.IOException: cantina2.mid: open failed, declared=audio/midi");
        assertFalse(out.contains("cantina2.mid"));
        assertTrue(out.contains("<file>"));
        assertTrue(out.contains("audio/midi"));
        DiagLog.watch(null);
    }

    @Test
    public void scrubWithoutWatchStillSanitizesPaths() {
        DiagLog.watch(null);
        String out = DiagLog.scrub("open failed: /storage/emulated/0/Music/cantina2.mid, audio/midi");
        assertFalse(out.contains("cantina2.mid"));
        assertTrue(out.contains("audio/midi"));
    }
}
