package com.vanipy.tengok;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A deliberately small SVG reader: shapes, paths, basic text, fills, strokes
 * and transforms. It produces a flat list of drawable items in pure Java so it
 * can be unit tested; SvgPanel paints them. Scripts, CSS, gradients, filters,
 * clips, masks, images and {@code <use>} are not interpreted, and the model
 * records which of those it skipped so the screen can say so.
 */
public final class SvgModel {
    public static final int MAX_ITEMS = 20_000;
    public static final int MAX_DEPTH = 64;
    public static final int MAX_PATH_OPS = 400_000;

    public static final int M = 0, L = 1, C = 2, Q = 3, Z = 4;

    public static final class Item {
        public boolean text;
        /** Path program: op codes with their coordinates in {@link #coords}. */
        public int[] ops = new int[0];
        public float[] coords = new float[0];
        public String content = "";
        public float x, y, fontSize = 16;
        public int anchor; // 0 start, 1 middle, 2 end
        public boolean bold, italic;
        public int fill = 0xFF000000; // ARGB, 0 alpha means none
        public int stroke = 0; // ARGB, 0 alpha means none
        public float strokeWidth = 1f;
        public boolean evenOdd;
        public int cap, join; // 0 butt/miter, 1 round, 2 square/bevel
        public float[] matrix = {1, 0, 0, 1, 0, 0}; // a b c d e f
    }

    public float width = 300, height = 150;
    public float vbX, vbY, vbW, vbH;
    public boolean hasViewBox;
    public final List<Item> items = new ArrayList<>();
    public final Set<String> skipped = new LinkedHashSet<>();
    public boolean truncated;

    // ------------------------------------------------------------ parsing

    public static SvgModel parse(byte[] data) throws IOException {
        final SvgModel m = new SvgModel();
        SafeXml.parse(data, new Handler(m));
        if (m.items.isEmpty() && !m.sawSvgRoot) throw new IOException("this is not an SVG document");
        return m;
    }

    private boolean sawSvgRoot;

    private static final class Style {
        int fill = 0xFF000000, stroke = 0;
        float strokeWidth = 1, fillOpacity = 1, strokeOpacity = 1, opacity = 1, fontSize = 16;
        boolean evenOdd, bold, italic;
        int cap, join, anchor;
        boolean fillIsPaintServer, strokeIsPaintServer;

        Style copy() {
            Style s = new Style();
            s.fill = fill; s.stroke = stroke; s.strokeWidth = strokeWidth;
            s.fillOpacity = fillOpacity; s.strokeOpacity = strokeOpacity; s.opacity = opacity;
            s.fontSize = fontSize; s.evenOdd = evenOdd; s.bold = bold; s.italic = italic;
            s.cap = cap; s.join = join; s.anchor = anchor;
            s.fillIsPaintServer = fillIsPaintServer; s.strokeIsPaintServer = strokeIsPaintServer;
            return s;
        }
    }

    private static final class Handler extends DefaultHandler {
        final SvgModel m;
        final List<Style> styles = new ArrayList<>();
        final List<float[]> matrices = new ArrayList<>();
        int depth;
        int skipDepth = -1; // depth at which a skipped subtree started
        Item textItem;
        final StringBuilder textBuf = new StringBuilder();
        int textDepth = -1;

        Handler(SvgModel m) {
            this.m = m;
            styles.add(new Style());
            matrices.add(new float[]{1, 0, 0, 1, 0, 0});
        }

        @Override
        public void startElement(String u, String l, String q, Attributes a) throws SAXException {
            depth++;
            if (depth > MAX_DEPTH) {
                m.truncated = true;
                throw new SafeXml.Stop();
            }
            String n = SafeXml.local(q);
            if (skipDepth >= 0) {
                styles.add(styles.get(styles.size() - 1));
                matrices.add(matrices.get(matrices.size() - 1));
                return;
            }
            Style parent = styles.get(styles.size() - 1);
            Style st = parent.copy();
            applyStyle(st, a);
            float[] mat = matrices.get(matrices.size() - 1);
            String tr = a.getValue("transform");
            if (tr != null) mat = multiply(mat, parseTransform(tr));
            styles.add(st);
            matrices.add(mat);

            switch (n) {
                case "svg":
                    if (depth == 1) {
                        m.sawSvgRoot = true;
                        readRoot(a);
                    }
                    return;
                case "g":
                case "a":
                case "switch":
                case "tspan":
                    if (n.equals("tspan") && textItem != null) readTspan(a, st);
                    return;
                case "defs": case "symbol": case "clipPath": case "mask": case "marker": case "pattern":
                case "linearGradient": case "radialGradient": case "filter": case "title": case "desc":
                case "metadata": case "style": case "script": case "foreignObject": case "image":
                case "use": case "animate": case "set": case "animateTransform":
                    if (n.equals("style")) m.skipped.add("CSS styles");
                    else if (n.equals("use")) m.skipped.add("<use> references");
                    else if (n.equals("image")) m.skipped.add("embedded images");
                    else if (n.equals("script")) m.skipped.add("scripts (never run)");
                    else if (n.equals("filter")) m.skipped.add("filters");
                    else if (n.equals("clipPath") || n.equals("mask")) m.skipped.add("clips and masks");
                    else if (n.contains("Gradient") || n.equals("pattern")) m.skipped.add("gradients and patterns");
                    else if (n.startsWith("animate") || n.equals("set")) m.skipped.add("animation");
                    else if (n.equals("foreignObject")) m.skipped.add("embedded HTML");
                    skipDepth = depth;
                    return;
                case "text":
                    startText(a, st, mat);
                    return;
                case "path":
                    shape(a, st, mat, parsePath(a.getValue("d")));
                    return;
                case "rect":
                    shape(a, st, mat, rect(a));
                    return;
                case "circle": {
                    float r = num(a.getValue("r"), 0);
                    shape(a, st, mat, ellipse(num(a.getValue("cx"), 0), num(a.getValue("cy"), 0), r, r));
                    return;
                }
                case "ellipse":
                    shape(a, st, mat, ellipse(num(a.getValue("cx"), 0), num(a.getValue("cy"), 0),
                            num(a.getValue("rx"), 0), num(a.getValue("ry"), 0)));
                    return;
                case "line": {
                    PathBuilder b = new PathBuilder();
                    b.move(num(a.getValue("x1"), 0), num(a.getValue("y1"), 0));
                    b.line(num(a.getValue("x2"), 0), num(a.getValue("y2"), 0));
                    Style ls = st;
                    shape(a, ls, mat, b);
                    return;
                }
                case "polyline":
                case "polygon": {
                    float[] pts = numbers(a.getValue("points"));
                    PathBuilder b = new PathBuilder();
                    for (int i = 0; i + 1 < pts.length; i += 2) {
                        if (i == 0) b.move(pts[0], pts[1]);
                        else b.line(pts[i], pts[i + 1]);
                    }
                    if (n.equals("polygon")) b.close();
                    shape(a, st, mat, b);
                    return;
                }
                default:
                    m.skipped.add("<" + n + "> elements");
            }
        }

        @Override
        public void endElement(String u, String l, String q) {
            if (textItem != null && depth == textDepth) finishText();
            styles.remove(styles.size() - 1);
            matrices.remove(matrices.size() - 1);
            if (skipDepth == depth) skipDepth = -1;
            depth--;
        }

        @Override
        public void characters(char[] ch, int s, int len) {
            if (textItem != null && skipDepth < 0 && textBuf.length() < 2000) textBuf.append(ch, s, len);
        }

        void readRoot(Attributes a) {
            float w = length(a.getValue("width"), -1), h = length(a.getValue("height"), -1);
            float[] vb = numbers(a.getValue("viewBox"));
            if (vb.length == 4 && vb[2] > 0 && vb[3] > 0) {
                m.hasViewBox = true;
                m.vbX = vb[0]; m.vbY = vb[1]; m.vbW = vb[2]; m.vbH = vb[3];
            }
            if (w <= 0 && h <= 0 && m.hasViewBox) { w = m.vbW; h = m.vbH; }
            else if (w <= 0 && m.hasViewBox) w = h * m.vbW / m.vbH;
            else if (h <= 0 && m.hasViewBox) h = w * m.vbH / m.vbW;
            if (w > 0) m.width = w;
            if (h > 0) m.height = h;
        }

        void startText(Attributes a, Style st, float[] mat) {
            Item it = new Item();
            it.text = true;
            it.x = num(a.getValue("x"), 0);
            it.y = num(a.getValue("y"), 0);
            fillFrom(it, st);
            it.fontSize = st.fontSize;
            it.anchor = st.anchor;
            it.bold = st.bold;
            it.italic = st.italic;
            it.matrix = mat;
            textItem = it;
            textDepth = depth;
            textBuf.setLength(0);
        }

        void readTspan(Attributes a, Style st) {
            // A tspan with its own position is drawn as a continuation of the same text run.
            // Multi-position text is not laid out; only its words are kept.
            if (textBuf.length() > 0 && textBuf.charAt(textBuf.length() - 1) != ' ') textBuf.append(' ');
        }

        void finishText() {
            String t = textBuf.toString().replaceAll("\\s+", " ").trim();
            if (!t.isEmpty() && m.items.size() < MAX_ITEMS) {
                textItem.content = t;
                m.items.add(textItem);
            }
            textItem = null;
            textDepth = -1;
        }

        void shape(Attributes a, Style st, float[] mat, PathBuilder b) throws SAXException {
            if (m.items.size() >= MAX_ITEMS) {
                m.truncated = true;
                throw new SafeXml.Stop();
            }
            if (b.opCount() == 0) return;
            Item it = new Item();
            it.ops = b.ops();
            it.coords = b.coords();
            it.matrix = mat;
            fillFrom(it, st);
            if (st.fillIsPaintServer || st.strokeIsPaintServer) m.skipped.add("gradient and pattern paints (drawn flat gray)");
            m.items.add(it);
        }

        void fillFrom(Item it, Style st) {
            it.fill = withOpacity(st.fill, st.fillOpacity * st.opacity);
            it.stroke = withOpacity(st.stroke, st.strokeOpacity * st.opacity);
            it.strokeWidth = st.strokeWidth;
            it.evenOdd = st.evenOdd;
            it.cap = st.cap;
            it.join = st.join;
        }

        PathBuilder rect(Attributes a) {
            float x = num(a.getValue("x"), 0), y = num(a.getValue("y"), 0);
            float w = num(a.getValue("width"), 0), h = num(a.getValue("height"), 0);
            PathBuilder b = new PathBuilder();
            if (w <= 0 || h <= 0) return b;
            String rxs = a.getValue("rx"), rys = a.getValue("ry");
            float rx = num(rxs, -1), ry = num(rys, -1);
            if (rx < 0 && ry < 0) { rx = 0; ry = 0; }
            else if (rx < 0) rx = ry;
            else if (ry < 0) ry = rx;
            rx = Math.min(rx, w / 2); ry = Math.min(ry, h / 2);
            if (rx <= 0 || ry <= 0) {
                b.move(x, y); b.line(x + w, y); b.line(x + w, y + h); b.line(x, y + h); b.close();
                return b;
            }
            final float k = 0.5522847498f;
            b.move(x + rx, y);
            b.line(x + w - rx, y);
            b.cubic(x + w - rx + k * rx, y, x + w, y + ry - k * ry, x + w, y + ry);
            b.line(x + w, y + h - ry);
            b.cubic(x + w, y + h - ry + k * ry, x + w - rx + k * rx, y + h, x + w - rx, y + h);
            b.line(x + rx, y + h);
            b.cubic(x + rx - k * rx, y + h, x, y + h - ry + k * ry, x, y + h - ry);
            b.line(x, y + ry);
            b.cubic(x, y + ry - k * ry, x + rx - k * rx, y, x + rx, y);
            b.close();
            return b;
        }

        PathBuilder ellipse(float cx, float cy, float rx, float ry) {
            PathBuilder b = new PathBuilder();
            if (rx <= 0 || ry <= 0) return b;
            final float k = 0.5522847498f;
            b.move(cx + rx, cy);
            b.cubic(cx + rx, cy + k * ry, cx + k * rx, cy + ry, cx, cy + ry);
            b.cubic(cx - k * rx, cy + ry, cx - rx, cy + k * ry, cx - rx, cy);
            b.cubic(cx - rx, cy - k * ry, cx - k * rx, cy - ry, cx, cy - ry);
            b.cubic(cx + k * rx, cy - ry, cx + rx, cy - k * ry, cx + rx, cy);
            b.close();
            return b;
        }

        void applyStyle(Style st, Attributes a) {
            for (int i = 0; i < a.getLength(); i++) {
                String name = a.getQName(i);
                if (name.equals("style")) {
                    for (String decl : a.getValue(i).split(";")) {
                        int c = decl.indexOf(':');
                        if (c > 0) property(st, decl.substring(0, c).trim(), decl.substring(c + 1).trim());
                    }
                } else {
                    property(st, name, a.getValue(i).trim());
                }
            }
        }

        void property(Style st, String name, String v) {
            switch (name) {
                case "fill": {
                    st.fillIsPaintServer = v.startsWith("url(");
                    st.fill = color(v, st.fill);
                    break;
                }
                case "stroke": {
                    st.strokeIsPaintServer = v.startsWith("url(");
                    st.stroke = color(v, st.stroke);
                    break;
                }
                case "stroke-width": st.strokeWidth = num(v, st.strokeWidth); break;
                case "fill-opacity": st.fillOpacity = clamp01(num(v, 1)); break;
                case "stroke-opacity": st.strokeOpacity = clamp01(num(v, 1)); break;
                case "opacity": st.opacity = st.opacity * clamp01(num(v, 1)); break;
                case "fill-rule": st.evenOdd = v.equals("evenodd"); break;
                case "stroke-linecap": st.cap = v.equals("round") ? 1 : v.equals("square") ? 2 : 0; break;
                case "stroke-linejoin": st.join = v.equals("round") ? 1 : v.equals("bevel") ? 2 : 0; break;
                case "font-size": st.fontSize = Math.max(1, Math.min(500, num(v, st.fontSize))); break;
                case "font-weight": st.bold = v.equals("bold") || v.equals("bolder") || num(v, 400) >= 600; break;
                case "font-style": st.italic = v.equals("italic") || v.equals("oblique"); break;
                case "text-anchor": st.anchor = v.equals("middle") ? 1 : v.equals("end") ? 2 : 0; break;
                default:
            }
        }
    }

    // ------------------------------------------------------------ values

    static float clamp01(float v) {
        return v < 0 ? 0 : v > 1 ? 1 : v;
    }

    static int withOpacity(int argb, float opacity) {
        int a = argb >>> 24;
        a = Math.round(a * clamp01(opacity));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    /** A number with an optional CSS unit; percentages and unknown text give the fallback. */
    static float num(String s, float fallback) {
        if (s == null) return fallback;
        s = s.trim();
        if (s.isEmpty() || s.endsWith("%")) return fallback;
        return length(s, fallback);
    }

    static float length(String s, float fallback) {
        if (s == null) return fallback;
        s = s.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.endsWith("%")) return fallback;
        float mult = 1;
        if (s.endsWith("px")) s = s.substring(0, s.length() - 2);
        else if (s.endsWith("pt")) { s = s.substring(0, s.length() - 2); mult = 96f / 72f; }
        else if (s.endsWith("pc")) { s = s.substring(0, s.length() - 2); mult = 16f; }
        else if (s.endsWith("mm")) { s = s.substring(0, s.length() - 2); mult = 96f / 25.4f; }
        else if (s.endsWith("cm")) { s = s.substring(0, s.length() - 2); mult = 96f / 2.54f; }
        else if (s.endsWith("in")) { s = s.substring(0, s.length() - 2); mult = 96f; }
        else if (s.endsWith("em")) { s = s.substring(0, s.length() - 2); mult = 16f; }
        try {
            float v = Float.parseFloat(s.trim()) * mult;
            return Float.isNaN(v) || Float.isInfinite(v) ? fallback : v;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** All numbers in a list separated by commas and/or spaces. */
    static float[] numbers(String s) {
        if (s == null) return new float[0];
        List<Float> out = new ArrayList<>();
        NumberScanner sc = new NumberScanner(s);
        while (true) {
            Float f = sc.next();
            if (f == null) break;
            out.add(f);
            if (out.size() > 200_000) break;
        }
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    static final class NumberScanner {
        final String s;
        int i;

        NumberScanner(String s) {
            this.s = s;
        }

        void skipSep() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == ',' || s.charAt(i) == '\t'
                    || s.charAt(i) == '\n' || s.charAt(i) == '\r')) i++;
        }

        boolean atNumber() {
            skipSep();
            if (i >= s.length()) return false;
            char c = s.charAt(i);
            return c == '-' || c == '+' || c == '.' || (c >= '0' && c <= '9');
        }

        /** Next number, or null when the next token is not a number. */
        Float next() {
            if (!atNumber()) return null;
            int start = i;
            if (s.charAt(i) == '-' || s.charAt(i) == '+') i++;
            boolean dot = false, digits = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') { digits = true; i++; }
                else if (c == '.' && !dot) { dot = true; i++; }
                else break;
            }
            if (!digits) { i = start + 1; return 0f; }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                int save = i;
                i++;
                if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) i++;
                int expDigits = 0;
                while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') { i++; expDigits++; }
                if (expDigits == 0) i = save;
            }
            try {
                float v = Float.parseFloat(s.substring(start, i));
                return Float.isNaN(v) || Float.isInfinite(v) ? 0f : v;
            } catch (NumberFormatException e) {
                return 0f;
            }
        }

        /** Arc flags are single characters that may touch the next number. */
        Float flag() {
            skipSep();
            if (i < s.length() && (s.charAt(i) == '0' || s.charAt(i) == '1')) {
                return (float) (s.charAt(i++) - '0');
            }
            return null;
        }
    }

    private static final Map<String, Integer> NAMED = new HashMap<>();

    static {
        String[][] t = {
                {"black", "000000"}, {"white", "ffffff"}, {"red", "ff0000"}, {"green", "008000"},
                {"blue", "0000ff"}, {"yellow", "ffff00"}, {"orange", "ffa500"}, {"purple", "800080"},
                {"gray", "808080"}, {"grey", "808080"}, {"silver", "c0c0c0"}, {"maroon", "800000"},
                {"navy", "000080"}, {"teal", "008080"}, {"lime", "00ff00"}, {"aqua", "00ffff"},
                {"cyan", "00ffff"}, {"fuchsia", "ff00ff"}, {"magenta", "ff00ff"}, {"olive", "808000"},
                {"pink", "ffc0cb"}, {"brown", "a52a2a"}, {"gold", "ffd700"}, {"indigo", "4b0082"},
                {"violet", "ee82ee"}, {"crimson", "dc143c"}, {"coral", "ff7f50"}, {"salmon", "fa8072"},
                {"tomato", "ff6347"}, {"khaki", "f0e68c"}, {"beige", "f5f5dc"}, {"ivory", "fffff0"},
                {"lightgray", "d3d3d3"}, {"lightgrey", "d3d3d3"}, {"darkgray", "a9a9a9"}, {"darkgrey", "a9a9a9"},
                {"lightblue", "add8e6"}, {"darkblue", "00008b"}, {"skyblue", "87ceeb"}, {"steelblue", "4682b4"},
                {"lightgreen", "90ee90"}, {"darkgreen", "006400"}, {"darkred", "8b0000"}, {"orangered", "ff4500"},
                {"turquoise", "40e0d0"}, {"tan", "d2b48c"}, {"chocolate", "d2691e"}, {"slategray", "708090"},
                {"dimgray", "696969"}, {"whitesmoke", "f5f5f5"}, {"gainsboro", "dcdcdc"}, {"lavender", "e6e6fa"},
        };
        for (String[] e : t) NAMED.put(e[0], 0xFF000000 | Integer.parseInt(e[1], 16));
    }

    /** ARGB colour; 0 (transparent) means none. Paint servers fall back to flat gray. */
    static int color(String v, int fallback) {
        if (v == null) return fallback;
        String s = v.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("inherit")) return fallback;
        if (s.equals("none") || s.equals("transparent")) return 0;
        if (s.equals("currentcolor")) return 0xFF000000;
        if (s.startsWith("url(")) return 0xFF808080;
        if (s.startsWith("#")) {
            try {
                String h = s.substring(1);
                if (h.length() == 3 || h.length() == 4) {
                    StringBuilder x = new StringBuilder();
                    for (int i = 0; i < h.length(); i++) x.append(h.charAt(i)).append(h.charAt(i));
                    h = x.toString();
                }
                if (h.length() == 6) return 0xFF000000 | Integer.parseInt(h, 16);
                if (h.length() == 8) {
                    long rgba = Long.parseLong(h, 16);
                    int r = (int) (rgba >> 24) & 255, g = (int) (rgba >> 16) & 255,
                            b = (int) (rgba >> 8) & 255, a = (int) rgba & 255;
                    return (a << 24) | (r << 16) | (g << 8) | b;
                }
            } catch (NumberFormatException e) {
                return fallback;
            }
            return fallback;
        }
        if (s.startsWith("rgb")) {
            int open = s.indexOf('('), close = s.indexOf(')');
            if (open < 0 || close < open) return fallback;
            String[] p = s.substring(open + 1, close).split("[,\\s/]+");
            int[] c = new int[3];
            float alpha = 1f;
            int n = 0;
            for (String part : p) {
                if (part.isEmpty()) continue;
                if (n < 3) {
                    float f;
                    try {
                        f = part.endsWith("%") ? Float.parseFloat(part.substring(0, part.length() - 1)) * 2.55f
                                : Float.parseFloat(part);
                    } catch (NumberFormatException e) {
                        return fallback;
                    }
                    c[n++] = Math.max(0, Math.min(255, Math.round(f)));
                } else {
                    try {
                        alpha = part.endsWith("%") ? Float.parseFloat(part.substring(0, part.length() - 1)) / 100f
                                : Float.parseFloat(part);
                    } catch (NumberFormatException e) {
                        alpha = 1f;
                    }
                }
            }
            if (n < 3) return fallback;
            return (Math.round(clamp01(alpha) * 255) << 24) | (c[0] << 16) | (c[1] << 8) | c[2];
        }
        Integer named = NAMED.get(s);
        return named != null ? named : fallback;
    }

    // ------------------------------------------------------------ transforms

    static float[] multiply(float[] p, float[] c) {
        // result = p * c, matrices as [a b c d e f] meaning [[a c e][b d f][0 0 1]]
        return new float[]{
                p[0] * c[0] + p[2] * c[1],
                p[1] * c[0] + p[3] * c[1],
                p[0] * c[2] + p[2] * c[3],
                p[1] * c[2] + p[3] * c[3],
                p[0] * c[4] + p[2] * c[5] + p[4],
                p[1] * c[4] + p[3] * c[5] + p[5]};
    }

    static float[] parseTransform(String s) {
        float[] m = {1, 0, 0, 1, 0, 0};
        int i = 0;
        int guard = 0;
        while (i < s.length() && guard++ < 64) {
            int open = s.indexOf('(', i);
            if (open < 0) break;
            int close = s.indexOf(')', open);
            if (close < 0) break;
            String name = s.substring(i, open).replace(",", " ").trim().toLowerCase(Locale.ROOT);
            float[] a = numbers(s.substring(open + 1, close));
            float[] t = null;
            switch (name) {
                case "translate":
                    if (a.length >= 1) t = new float[]{1, 0, 0, 1, a[0], a.length > 1 ? a[1] : 0};
                    break;
                case "scale":
                    if (a.length >= 1) t = new float[]{a[0], 0, 0, a.length > 1 ? a[1] : a[0], 0, 0};
                    break;
                case "rotate":
                    if (a.length >= 1) {
                        double r = Math.toRadians(a[0]);
                        float cos = (float) Math.cos(r), sin = (float) Math.sin(r);
                        float[] rot = {cos, sin, -sin, cos, 0, 0};
                        if (a.length >= 3) {
                            float cx = a[1], cy = a[2];
                            t = multiply(multiply(new float[]{1, 0, 0, 1, cx, cy}, rot), new float[]{1, 0, 0, 1, -cx, -cy});
                        } else t = rot;
                    }
                    break;
                case "matrix":
                    if (a.length >= 6) t = new float[]{a[0], a[1], a[2], a[3], a[4], a[5]};
                    break;
                case "skewx":
                    if (a.length >= 1) t = new float[]{1, 0, (float) Math.tan(Math.toRadians(a[0])), 1, 0, 0};
                    break;
                case "skewy":
                    if (a.length >= 1) t = new float[]{1, (float) Math.tan(Math.toRadians(a[0])), 0, 1, 0, 0};
                    break;
                default:
            }
            if (t != null) m = multiply(m, t);
            i = close + 1;
        }
        return m;
    }

    // ------------------------------------------------------------ paths

    static final class PathBuilder {
        private int[] ops = new int[16];
        private float[] coords = new float[64];
        private int nOps, nCoords;
        float cx, cy, sx, sy; // current point and subpath start

        int opCount() {
            return nOps;
        }

        int[] ops() {
            return java.util.Arrays.copyOf(ops, nOps);
        }

        float[] coords() {
            return java.util.Arrays.copyOf(coords, nCoords);
        }

        private void op(int o, float... c) {
            if (nOps >= MAX_PATH_OPS) return;
            if (nOps == ops.length) ops = java.util.Arrays.copyOf(ops, nOps * 2);
            if (nCoords + c.length > coords.length) coords = java.util.Arrays.copyOf(coords, Math.max(coords.length * 2, nCoords + c.length));
            ops[nOps++] = o;
            for (float v : c) coords[nCoords++] = v;
        }

        void move(float x, float y) { op(M, x, y); cx = sx = x; cy = sy = y; }
        void line(float x, float y) { op(L, x, y); cx = x; cy = y; }
        void cubic(float x1, float y1, float x2, float y2, float x, float y) { op(C, x1, y1, x2, y2, x, y); cx = x; cy = y; }
        void quad(float x1, float y1, float x, float y) { op(Q, x1, y1, x, y); cx = x; cy = y; }
        void close() { op(Z); cx = sx; cy = sy; }

        /** SVG elliptical arc converted to cubic curves (SVG 1.1 implementation notes, F.6). */
        void arc(float rx, float ry, float rotDeg, boolean large, boolean sweep, float x, float y) {
            float x0 = cx, y0 = cy;
            if (x0 == x && y0 == y) return;
            rx = Math.abs(rx); ry = Math.abs(ry);
            if (rx == 0 || ry == 0) { line(x, y); return; }
            double phi = Math.toRadians(rotDeg % 360);
            double cosPhi = Math.cos(phi), sinPhi = Math.sin(phi);
            double dx2 = (x0 - x) / 2.0, dy2 = (y0 - y) / 2.0;
            double x1p = cosPhi * dx2 + sinPhi * dy2;
            double y1p = -sinPhi * dx2 + cosPhi * dy2;
            double lambda = (x1p * x1p) / ((double) rx * rx) + (y1p * y1p) / ((double) ry * ry);
            if (lambda > 1) {
                double s = Math.sqrt(lambda);
                rx *= s; ry *= s;
            }
            double rx2 = (double) rx * rx, ry2 = (double) ry * ry;
            double num = rx2 * ry2 - rx2 * y1p * y1p - ry2 * x1p * x1p;
            double den = rx2 * y1p * y1p + ry2 * x1p * x1p;
            double coef = den == 0 ? 0 : Math.sqrt(Math.max(0, num / den));
            if (large == sweep) coef = -coef;
            double cxp = coef * (rx * y1p / ry);
            double cyp = coef * -(ry * x1p / rx);
            double ccx = cosPhi * cxp - sinPhi * cyp + (x0 + x) / 2.0;
            double ccy = sinPhi * cxp + cosPhi * cyp + (y0 + y) / 2.0;
            double th1 = Math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx);
            double th2 = Math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx);
            double dth = th2 - th1;
            if (sweep && dth < 0) dth += 2 * Math.PI;
            else if (!sweep && dth > 0) dth -= 2 * Math.PI;
            int segs = (int) Math.ceil(Math.abs(dth) / (Math.PI / 2) - 1e-9);
            if (segs < 1) segs = 1;
            double delta = dth / segs;
            double t = 4.0 / 3.0 * Math.tan(delta / 4);
            double a1 = th1;
            for (int i = 0; i < segs; i++) {
                double a2 = a1 + delta;
                double c1 = Math.cos(a1), s1 = Math.sin(a1), c2 = Math.cos(a2), s2 = Math.sin(a2);
                double e1x = c1 - t * s1, e1y = s1 + t * c1;
                double e2x = c2 + t * s2, e2y = s2 - t * c2;
                float[] p1 = ell(ccx, ccy, rx, ry, cosPhi, sinPhi, e1x, e1y);
                float[] p2 = ell(ccx, ccy, rx, ry, cosPhi, sinPhi, e2x, e2y);
                float[] p3 = i == segs - 1 ? new float[]{x, y} : ell(ccx, ccy, rx, ry, cosPhi, sinPhi, c2, s2);
                cubic(p1[0], p1[1], p2[0], p2[1], p3[0], p3[1]);
                a1 = a2;
            }
        }

        private static float[] ell(double ccx, double ccy, double rx, double ry, double cosPhi, double sinPhi, double ux, double uy) {
            double x = rx * ux, y = ry * uy;
            return new float[]{(float) (cosPhi * x - sinPhi * y + ccx), (float) (sinPhi * x + cosPhi * y + ccy)};
        }
    }

    /** Parses SVG path data (M L H V C S Q T A Z, absolute and relative). Bad data keeps what was read so far. */
    static PathBuilder parsePath(String d) {
        PathBuilder b = new PathBuilder();
        if (d == null) return b;
        NumberScanner sc = new NumberScanner(d);
        char cmd = 0;
        float lastCx = 0, lastCy = 0; // last control point for S/T
        char prev = 0;
        boolean started = false;
        int guard = 0;
        while (true) {
            sc.skipSep();
            if (sc.i >= d.length()) break;
            if (guard++ > 1_000_000) break;
            char c = d.charAt(sc.i);
            if (Character.isLetter(c)) {
                cmd = c;
                sc.i++;
                if (cmd == 'Z' || cmd == 'z') {
                    if (started) b.close();
                    prev = 'Z';
                    continue;
                }
            } else if (cmd == 0 || cmd == 'Z' || cmd == 'z') {
                break;
            } else if (!sc.atNumber()) {
                break;
            }
            boolean rel = Character.isLowerCase(cmd);
            char up = Character.toUpperCase(cmd);
            float ox = rel ? b.cx : 0, oy = rel ? b.cy : 0;
            Float a1, a2, a3, a4, a5, a6, a7;
            switch (up) {
                case 'M':
                    a1 = sc.next(); a2 = sc.next();
                    if (a1 == null || a2 == null) return b;
                    b.move(ox + a1, oy + a2);
                    started = true;
                    cmd = rel ? 'l' : 'L';
                    prev = 'M';
                    break;
                case 'L':
                    a1 = sc.next(); a2 = sc.next();
                    if (a1 == null || a2 == null || !started) return b;
                    b.line(ox + a1, oy + a2);
                    prev = 'L';
                    break;
                case 'H':
                    a1 = sc.next();
                    if (a1 == null || !started) return b;
                    b.line(ox + a1, b.cy);
                    prev = 'L';
                    break;
                case 'V':
                    a1 = sc.next();
                    if (a1 == null || !started) return b;
                    b.line(b.cx, oy + a1);
                    prev = 'L';
                    break;
                case 'C':
                    a1 = sc.next(); a2 = sc.next(); a3 = sc.next(); a4 = sc.next(); a5 = sc.next(); a6 = sc.next();
                    if (a6 == null || !started) return b;
                    b.cubic(ox + a1, oy + a2, ox + a3, oy + a4, ox + a5, oy + a6);
                    lastCx = ox + a3; lastCy = oy + a4;
                    prev = 'C';
                    break;
                case 'S': {
                    a1 = sc.next(); a2 = sc.next(); a3 = sc.next(); a4 = sc.next();
                    if (a4 == null || !started) return b;
                    float x1 = prev == 'C' ? 2 * b.cx - lastCx : b.cx, y1 = prev == 'C' ? 2 * b.cy - lastCy : b.cy;
                    b.cubic(x1, y1, ox + a1, oy + a2, ox + a3, oy + a4);
                    lastCx = ox + a1; lastCy = oy + a2;
                    prev = 'C';
                    break;
                }
                case 'Q':
                    a1 = sc.next(); a2 = sc.next(); a3 = sc.next(); a4 = sc.next();
                    if (a4 == null || !started) return b;
                    b.quad(ox + a1, oy + a2, ox + a3, oy + a4);
                    lastCx = ox + a1; lastCy = oy + a2;
                    prev = 'Q';
                    break;
                case 'T': {
                    a1 = sc.next(); a2 = sc.next();
                    if (a2 == null || !started) return b;
                    float x1 = prev == 'Q' ? 2 * b.cx - lastCx : b.cx, y1 = prev == 'Q' ? 2 * b.cy - lastCy : b.cy;
                    b.quad(x1, y1, ox + a1, oy + a2);
                    lastCx = x1; lastCy = y1;
                    prev = 'Q';
                    break;
                }
                case 'A':
                    a1 = sc.next(); a2 = sc.next(); a3 = sc.next();
                    a4 = sc.flag(); a5 = sc.flag();
                    a6 = sc.next(); a7 = sc.next();
                    if (a7 == null || a4 == null || a5 == null || !started) return b;
                    b.arc(a1, a2, a3, a4 != 0, a5 != 0, ox + a6, oy + a7);
                    prev = 'A';
                    break;
                default:
                    return b;
            }
        }
        return b;
    }
}
