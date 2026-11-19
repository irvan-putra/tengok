package com.vanipy.tengok;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HexPanelTest {

    @Test
    public void formatsOffsetsHexAndAscii() {
        byte[] data = "HELLO".getBytes();
        String out = HexPanel.formatPage(data, data.length, 0);
        assertTrue(out.startsWith("00000000"));
        assertTrue(out.contains("48 45 4c 4c 4f"));
        assertTrue(out.contains("HELLO"));
    }

    @Test
    public void emptyFileSaysSo() {
        assertTrue(HexPanel.formatPage(new byte[0], 0, 0).contains("empty"));
    }

    @Test
    public void pageBaseOffsets() {
        String out = HexPanel.formatPage(new byte[32], 32, 4096);
        assertTrue(out.startsWith("00001000"));
    }
}
