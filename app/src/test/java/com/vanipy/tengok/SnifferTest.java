package com.vanipy.tengok;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SnifferTest {

    private static byte[] bytes(int... vals) {
        byte[] b = new byte[vals.length];
        for (int i = 0; i < vals.length; i++) b[i] = (byte) vals[i];
        return b;
    }

    @Test
    public void magicBytesWin() {
        assertEquals(Sniffer.Kind.MIDI, Sniffer.sniff("x.bin", null, new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6}));
        assertEquals(Sniffer.Kind.IMAGE, Sniffer.sniff("x.bin", null, bytes(0x89, 'P', 'N', 'G', 13, 10, 26, 10)));
        assertEquals(Sniffer.Kind.IMAGE, Sniffer.sniff("x.bin", null, bytes(0xFF, 0xD8, 0xFF, 0xE0)));
        assertEquals(Sniffer.Kind.IMAGE, Sniffer.sniff("x.bin", null, new byte[]{'G', 'I', 'F', '8', '9', 'a'}));
        assertEquals(Sniffer.Kind.AUDIO, Sniffer.sniff("x.bin", null, new byte[]{'O', 'g', 'g', 'S', 0, 2}));
        assertEquals(Sniffer.Kind.AUDIO, Sniffer.sniff("x.bin", null, new byte[]{'f', 'L', 'a', 'C', 0, 0}));
    }

    @Test
    public void magicBeatsWrongExtension() {
        // a .txt file that is really MIDI must open as MIDI
        assertEquals(Sniffer.Kind.MIDI, Sniffer.sniff("song.txt", null, new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6}));
    }

    @Test
    public void extensionsWorkWithoutMagic() {
        assertEquals(Sniffer.Kind.MIDI, Sniffer.sniff("tune.mid", null, new byte[0]));
        assertEquals(Sniffer.Kind.MIDI, Sniffer.sniff("tune.MIDI", null, new byte[0]));
        assertEquals(Sniffer.Kind.IMAGE, Sniffer.sniff("photo.webp", null, new byte[0]));
        assertEquals(Sniffer.Kind.AUDIO, Sniffer.sniff("voice.m4a", null, new byte[0]));
        assertEquals(Sniffer.Kind.TEXT, Sniffer.sniff("notes.md", null, new byte[0]));
    }

    @Test
    public void declaredMimeIsUsed() {
        assertEquals(Sniffer.Kind.MIDI, Sniffer.sniff("download", "audio/midi", new byte[0]));
        assertEquals(Sniffer.Kind.IMAGE, Sniffer.sniff("download", "image/png", new byte[0]));
        assertEquals(Sniffer.Kind.TEXT, Sniffer.sniff("download", "application/json", new byte[0]));
    }

    @Test
    public void textHeuristicAndFallback() {
        assertEquals(Sniffer.Kind.TEXT, Sniffer.sniff("noext", null, "hello world\nthis is text\n".getBytes()));
        assertEquals(Sniffer.Kind.HEX, Sniffer.sniff("noext", null, bytes(0, 1, 2, 3, 4, 5)));
        assertEquals(Sniffer.Kind.HEX, Sniffer.sniff("noext", null, new byte[0]));
        // mostly text with a couple of odd bytes still reads as text
        assertEquals(Sniffer.Kind.TEXT, Sniffer.sniff("noext", null, "nearly all of this is plain english prose with one \u0007 bell".getBytes()));
    }

    @Test
    public void heicFamilyIsImageButMp4IsNot() {
        byte[] heic = new byte[16];
        System.arraycopy(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c'}, 0, heic, 0, 12);
        assertEquals(Sniffer.Kind.IMAGE, Sniffer.sniff("a.bin", null, heic));
        byte[] mp4 = new byte[16];
        System.arraycopy(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'}, 0, mp4, 0, 12);
        // not an image; video is its own kind now (hex view + conversion)
        assertEquals(Sniffer.Kind.VIDEO, Sniffer.sniff("a.bin", null, mp4));
    }

    @Test
    public void videoMagicAndM4aStaysAudio() {
        byte[] ebml = new byte[] {0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0};
        assertEquals(Sniffer.Kind.VIDEO, Sniffer.sniff("a.bin", null, ebml));
        byte[] m4a = new byte[16];
        System.arraycopy(new byte[]{0, 0, 0, 24, 'f', 't', 'y', 'p', 'M', '4', 'A', ' '}, 0, m4a, 0, 12);
        assertEquals(Sniffer.Kind.AUDIO, Sniffer.sniff("a.bin", null, m4a));
        assertEquals(Sniffer.Kind.VIDEO, Sniffer.sniff("clip.mkv", null, new byte[4]));
        assertEquals(Sniffer.Kind.VIDEO, Sniffer.sniff("x", "video/mp4", new byte[4]));
    }

    @Test
    public void pdfSvgAndOffice() {
        assertEquals(Sniffer.Kind.PDF, Sniffer.sniff("x.bin", null, "%PDF-1.4\n".getBytes()));
        assertEquals(Sniffer.Kind.PDF, Sniffer.sniff("doc", "application/pdf", new byte[0]));
        assertEquals(Sniffer.Kind.PDF, Sniffer.sniff("a.pdf", null, new byte[0]));
        assertEquals(Sniffer.Kind.SVG, Sniffer.sniff("a.svg", "image/svg+xml", new byte[0]));
        assertEquals(Sniffer.Kind.SVG, Sniffer.sniff("noext", null, "<?xml version=\"1.0\"?>\n<svg xmlns=\"x\"></svg>".getBytes()));
        assertEquals(Sniffer.Kind.TEXT, Sniffer.sniff("page.html", null, "<html><body><svg></svg></body></html>".getBytes()));
        byte[] pk = {'P', 'K', 3, 4, 0, 0};
        assertEquals(Sniffer.Kind.OFFICE, Sniffer.sniff("a.docx", null, pk));
        assertEquals(Sniffer.Kind.OFFICE, Sniffer.sniff("A.XLSX", null, pk));
        assertEquals(Sniffer.Kind.OFFICE, Sniffer.sniff("a.pptx", null, pk));
        assertEquals(Sniffer.Kind.OFFICE, Sniffer.sniff("blob", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", pk));
        assertEquals(Sniffer.Kind.ZIP, Sniffer.sniff("a.zip", null, pk));
        // an old .doc (not a ZIP) stays honest: bytes
        assertEquals(Sniffer.Kind.HEX, Sniffer.sniff("old.doc", null, new byte[]{(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 0, 0, 0, 0}));
    }
}
