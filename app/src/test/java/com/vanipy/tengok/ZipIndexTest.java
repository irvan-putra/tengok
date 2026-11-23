package com.vanipy.tengok;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class ZipIndexTest {
    private byte[] zip(String... names) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (String name : names) {
                z.putNextEntry(new ZipEntry(name));
                if (!name.endsWith("/")) z.write("Synthetic content".getBytes("UTF-8"));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }
    @Test public void pathsSizesAndUnicode() throws Exception {
        List<ZipIndex.Entry> e = ZipIndex.parse(zip("folder/", "folder/你好.txt", "../outside.txt"));
        assertEquals(3, e.size()); assertTrue(e.get(0).directory);
        assertEquals("folder/你好.txt", e.get(1).name);
        assertEquals(17, e.get(1).size); assertFalse(e.get(1).encrypted);
        assertEquals("../outside.txt", e.get(2).name); // displayed, never extracted
    }
    @Test public void emptyZip() throws Exception { assertTrue(ZipIndex.parse(zip()).isEmpty()); }
    @Test public void sniffing() throws Exception {
        assertEquals(Sniffer.Kind.ZIP, Sniffer.sniff("x.txt", "text/plain", zip("a")));
        assertEquals(Sniffer.Kind.ZIP, Sniffer.sniff("X.ZIP", null, new byte[0]));
        assertEquals(Sniffer.Kind.ZIP, Sniffer.sniff("file", "application/zip", new byte[0]));
    }
    private void bad(byte[] b) throws Exception {
        try { ZipIndex.parse(b); fail("Expected readable parse error"); }
        catch (IOException expected) { assertNotNull(expected.getMessage()); }
    }
    @Test public void rejectsTruncatedCorruptAndOversized() throws Exception {
        byte[] b = zip("a");
        for (int n = 0; n < b.length; n++) bad(Arrays.copyOf(b, n));
        byte[] c = b.clone(); c[b.length - 6] = (byte) 255; bad(c);
        bad(new byte[32 * 1024 * 1024 + 1]);
    }
    @Test public void rejectsSplitZip64AndEntryFlood() throws Exception {
        byte[] b = zip(); b[b.length - 18] = 1; bad(b);
        b = zip(); b[b.length - 12] = -1; b[b.length - 11] = -1; bad(b);
        String[] names = new String[2001];
        for (int i = 0; i < names.length; i++) names[i] = "a" + i;
        bad(zip(names));
    }
    @Test public void randomBytesNeverThrowUnchecked() throws Exception {
        Random random = new Random(1);
        for (int n=0;n<300;n++) { byte[] b=new byte[n]; random.nextBytes(b); bad(b); }
    }

    @Test public void opensDeflateAndChecksCrc() throws Exception {
        byte[] b = zip("file.txt");
        assertEquals("Synthetic content", new String(ZipIndex.open(b, 0), "UTF-8"));
        for (int i=0; i<b.length-4; i++) if (b[i]==80 && b[i+1]==75 && b[i+2]==1 && b[i+3]==2) {
            b[i+16] ^= 1; break;
        }
        try { ZipIndex.open(b,0); fail("CRC should fail"); } catch(IOException expected) { }
    }
    @Test public void rejectsBombAndEncryption() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(out)) {
            z.putNextEntry(new ZipEntry("bomb.txt"));z.write(new byte[1024*1024]);z.closeEntry();
        }
        try { ZipIndex.open(out.toByteArray(),0); fail("ratio should fail"); } catch(IOException expected) { }
        byte[] b=zip("a");
        for (int i=0; i<b.length-4; i++) if (b[i]==80 && b[i+1]==75 && b[i+2]==1 && b[i+3]==2) {b[i+8]|=1;break;}
        try { ZipIndex.open(b,0); fail("encryption should fail"); } catch(IOException expected) { }
    }
}
