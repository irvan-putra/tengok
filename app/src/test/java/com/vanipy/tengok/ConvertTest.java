package com.vanipy.tengok;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.Test;

public class ConvertTest {

    @Test
    public void targetsPerKind() {
        assertEquals(3, Convert.targetsFor(Sniffer.Kind.IMAGE).size());
        assertEquals(1, Convert.targetsFor(Sniffer.Kind.AUDIO).size());
        assertEquals(1, Convert.targetsFor(Sniffer.Kind.VIDEO).size());
        assertEquals(2, Convert.targetsFor(Sniffer.Kind.TEXT).size());
        assertTrue(Convert.targetsFor(Sniffer.Kind.MIDI).isEmpty());
        assertTrue(Convert.targetsFor(Sniffer.Kind.HEX).isEmpty());
    }

    @Test
    public void everyTargetStatesItsLoss() {
        for (Sniffer.Kind k : Sniffer.Kind.values()) {
            for (Convert.Target t : Convert.targetsFor(k)) {
                assertTrue(t.id + " has no label", !t.label.isEmpty());
                assertTrue(t.id + " has no extension", !t.ext.isEmpty());
                // the honesty contract: every target says what the conversion
                // can lose, in plain words
                assertTrue(t.id + " has an empty note", t.note.length() > 20);
            }
        }
    }

    @Test
    public void outNameSwapsExtension() {
        assertEquals("photo.png", Convert.outName("photo.jpeg", "png"));
        assertEquals("song.m4a", Convert.outName("song", "m4a"));
        assertEquals("converted.txt", Convert.outName("", "txt"));
        assertEquals("converted.wav", Convert.outName(null, "wav"));
        assertEquals(".hidden.png", Convert.outName(".hidden", "png"));
    }

    @Test
    public void utf8RoundTripKeepsText() {
        String s = "héllo wörld ðŸŽµ";
        assertArrayEquals(s.getBytes(StandardCharsets.UTF_8), Convert.reencodeText(s, "utf8"));
    }

    @Test
    public void latin1ReplacesUnmappableWithQuestionMark() {
        byte[] out = Convert.reencodeText("a€b", "latin1");
        assertArrayEquals(new byte[] {'a', '?', 'b'}, out);
        assertArrayEquals(new byte[] {'h', (byte) 0xE9, 'l'}, Convert.reencodeText("hél", "latin1"));
    }
}
