package com.vanipy.tengok;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MetaTest {

    @Test
    public void sha256BufferedMatchesKnownDigest() throws Exception {
        // sha256("hello") - pinning the digest catches digest wiring mistakes
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                Meta.sha256("hello".getBytes("UTF-8")));
    }

    @Test
    public void sha256BufferedMatchesStreamed() throws Exception {
        byte[] data = new byte[100000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31 + 7);
        Source s = new Source.BytesSource("fixture.bin", data);
        assertEquals(Meta.sha256(s), Meta.sha256(data));
    }
}
