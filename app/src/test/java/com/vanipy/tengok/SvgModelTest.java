package com.vanipy.tengok;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

public class SvgModelTest {
    private static SvgModel parse(String svg) throws IOException {
        return SvgModel.parse(svg.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void sizeFromViewBoxAndShapes() throws Exception {
        SvgModel m = parse("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 200 100'>"
                + "<rect x='1' y='2' width='10' height='20' fill='#f00'/>"
                + "<circle cx='5' cy='5' r='3' fill='blue'/></svg>");
        assertEquals(200f, m.width, 0.01f);
        assertEquals(100f, m.height, 0.01f);
        assertTrue(m.hasViewBox);
        assertEquals(2, m.items.size());
        assertEquals(0xFFFF0000, m.items.get(0).fill);
        assertEquals(0xFF0000FF, m.items.get(1).fill);
    }

    @Test
    public void groupTransformAndInheritedStyle() throws Exception {
        SvgModel m = parse("<svg width='10' height='10'><g transform='translate(5,5) scale(2)' fill='rgb(0,255,0)' "
                + "style='stroke:blue;stroke-width:3'><rect width='1' height='1'/></g></svg>");
        SvgModel.Item it = m.items.get(0);
        assertEquals(0xFF00FF00, it.fill);
        assertEquals(0xFF0000FF, it.stroke);
        assertEquals(3f, it.strokeWidth, 0.001f);
        assertArrayEquals(new float[]{2, 0, 0, 2, 5, 5}, it.matrix, 0.001f);
    }

    @Test
    public void pathCommandsRelativeAndArcs() throws Exception {
        SvgModel.PathBuilder b = SvgModel.parsePath("M0 0 L10 0 h-5 v5 z m1,1 l2 2 a5 5 0 1 1 -10 0 C1 1 2 2 3 3 s1 1 2 2 Q1 1 2 2 t1 1");
        assertTrue(b.opCount() > 8);
        // bad data keeps what was read before it
        assertTrue(SvgModel.parsePath("M0 0 L1 1 L").opCount() >= 2);
        assertEquals(0, SvgModel.parsePath("L1 1").opCount());
        assertEquals(0, SvgModel.parsePath(null).opCount());
    }

    @Test
    public void compactNumbersAndFlags() {
        float[] n = SvgModel.numbers("1.5.5-2e1,3");
        assertArrayEquals(new float[]{1.5f, .5f, -20f, 3f}, n, 0.001f);
        SvgModel.PathBuilder b = SvgModel.parsePath("M0 0a1 1 0 01 2 0");
        assertTrue(b.opCount() >= 2);
    }

    @Test
    public void unsupportedFeaturesAreReportedNotDrawn() throws Exception {
        SvgModel m = parse("<svg width='10' height='10'><style>.a{fill:red}</style><defs><linearGradient id='g'/></defs>"
                + "<use href='#a'/><script>alert(1)</script><rect width='5' height='5' fill='url(#g)'/></svg>");
        assertEquals(1, m.items.size());
        assertEquals(0xFF808080, m.items.get(0).fill);
        assertTrue(m.skipped.contains("CSS styles"));
        assertTrue(m.skipped.contains("scripts (never run)"));
        assertTrue(m.skipped.contains("<use> references"));
    }

    @Test
    public void textIsKept() throws Exception {
        SvgModel m = parse("<svg width='50' height='20'><text x='3' y='4' font-size='12' text-anchor='middle'>Hi <tspan>there</tspan></text></svg>");
        assertEquals(1, m.items.size());
        assertTrue(m.items.get(0).text);
        assertEquals("Hi there", m.items.get(0).content);
        assertEquals(1, m.items.get(0).anchor);
    }

    @Test
    public void entitiesAndNonSvgAreRefused() {
        try {
            parse("<!DOCTYPE svg [<!ENTITY a \"b\">]><svg/>");
            fail();
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            parse("<html/>");
            fail();
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            parse("<svg><rect");
            fail();
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void deepNestingAndHugeCountsStopSafely() throws Exception {
        StringBuilder sb = new StringBuilder("<svg width='1' height='1'>");
        for (int i = 0; i < 200; i++) sb.append("<g>");
        sb.append("<rect width='1' height='1'/>");
        for (int i = 0; i < 200; i++) sb.append("</g>");
        sb.append("</svg>");
        SvgModel m = parse(sb.toString());
        assertTrue(m.truncated);
        StringBuilder many = new StringBuilder("<svg width='1' height='1'>");
        for (int i = 0; i < SvgModel.MAX_ITEMS + 10; i++) many.append("<rect width='1' height='1'/>");
        many.append("</svg>");
        SvgModel big = parse(many.toString());
        assertEquals(SvgModel.MAX_ITEMS, big.items.size());
        assertTrue(big.truncated);
    }
}
