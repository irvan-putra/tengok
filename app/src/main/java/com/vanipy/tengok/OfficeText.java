package com.vanipy.tengok;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Plain-text preview of Office Open XML files (.docx, .xlsx, .pptx). It reads
 * the XML parts inside the ZIP in memory and keeps only the words and cell
 * values. Layout, fonts, images, charts, comments and macros are not read and
 * nothing is executed.
 */
public final class OfficeText {
    public static final int MAX_CHARS = 600_000;
    public static final int MAX_SHEET_ROWS = 2000;
    public static final int MAX_COLUMNS = 100;
    public static final int MAX_SECTIONS = 200;

    public static final class Section {
        public final String title;
        public final String text;

        Section(String title, String text) {
            this.title = title;
            this.text = text;
        }
    }

    public static final class Result {
        public String format = "";
        public final List<Section> sections = new ArrayList<>();
        public boolean truncated;
        public final List<String> notes = new ArrayList<>();
    }

    private final byte[] zip;
    private final List<ZipIndex.Entry> entries;
    private final Map<String, Integer> byName = new HashMap<>();
    private int budget = MAX_CHARS;
    private final Result result = new Result();

    private OfficeText(byte[] zip) throws IOException {
        this.zip = zip;
        this.entries = ZipIndex.parse(zip);
        for (int i = 0; i < entries.size(); i++) byName.put(entries.get(i).name, i);
    }

    public static Result read(byte[] zip) throws IOException {
        OfficeText t = new OfficeText(zip);
        if (t.byName.containsKey("word/document.xml")) {
            t.result.format = "Word document (.docx)";
            t.readDocx();
        } else if (t.byName.containsKey("xl/workbook.xml")) {
            t.result.format = "Excel workbook (.xlsx)";
            t.readXlsx();
        } else if (t.byName.containsKey("ppt/presentation.xml")) {
            t.result.format = "PowerPoint presentation (.pptx)";
            t.readPptx();
        } else {
            throw new IOException("this ZIP has no Word, Excel or PowerPoint main part");
        }
        return t.result;
    }

    private byte[] part(String name) throws IOException {
        Integer i = byName.get(name);
        if (i == null) return null;
        return ZipIndex.open(zip, i);
    }

    private boolean addSection(String title, String text) {
        if (result.sections.size() >= MAX_SECTIONS) {
            result.truncated = true;
            return false;
        }
        result.sections.add(new Section(title, text));
        return true;
    }

    // ---------------------------------------------------------------- docx

    private void readDocx() throws IOException {
        byte[] xml = part("word/document.xml");
        final StringBuilder sb = new StringBuilder();
        SafeXml.parse(xml, new DefaultHandler() {
            boolean inT;

            @Override
            public void startElement(String u, String l, String q, Attributes a) throws SAXException {
                String n = SafeXml.local(q);
                if (n.equals("t")) inT = true;
                else if (n.equals("tab")) sb.append('\t');
                else if (n.equals("br") || n.equals("cr")) sb.append('\n');
            }

            @Override
            public void endElement(String u, String l, String q) throws SAXException {
                String n = SafeXml.local(q);
                if (n.equals("t")) inT = false;
                else if (n.equals("p")) sb.append('\n');
                else if (n.equals("tc")) sb.append('\t');
                else if (n.equals("tr")) sb.append('\n');
                if (sb.length() > budget) {
                    result.truncated = true;
                    sb.setLength(budget);
                    throw new SafeXml.Stop();
                }
            }

            @Override
            public void characters(char[] ch, int s, int len) {
                if (inT) sb.append(ch, s, len);
            }
        });
        String text = collapseBlank(sb.toString());
        budget -= text.length();
        addSection("Document text", text);
        result.notes.add("Headers, footers, footnotes, comments, images and formatting are not shown.");
    }

    // ---------------------------------------------------------------- pptx

    private void readPptx() throws IOException {
        List<String> order = new ArrayList<>();
        Map<String, String> rels = relationships("ppt/_rels/presentation.xml.rels", "ppt/");
        byte[] pres = part("ppt/presentation.xml");
        SafeXml.parse(pres, new DefaultHandler() {
            @Override
            public void startElement(String u, String l, String q, Attributes a) {
                if (SafeXml.local(q).equals("sldId")) {
                    String rid = relId(a);
                    String target = rid == null ? null : rels.get(rid);
                    if (target != null) order.add(target);
                }
            }
        });
        if (order.isEmpty()) {
            // fall back to numeric order of ppt/slides/slideN.xml
            List<String> names = new ArrayList<>();
            for (ZipIndex.Entry e : entries) {
                if (e.name.startsWith("ppt/slides/slide") && e.name.endsWith(".xml")) names.add(e.name);
            }
            java.util.Collections.sort(names, (x, y) -> Integer.compare(num(x), num(y)));
            order.addAll(names);
        }
        int n = 0;
        for (String slide : order) {
            byte[] xml = part(slide);
            if (xml == null) continue;
            n++;
            final StringBuilder sb = new StringBuilder();
            SafeXml.parse(xml, new DefaultHandler() {
                boolean inT;

                @Override
                public void startElement(String u, String l, String q, Attributes a) {
                    String nm = SafeXml.local(q);
                    if (nm.equals("t")) inT = true;
                    else if (nm.equals("br")) sb.append('\n');
                }

                @Override
                public void endElement(String u, String l, String q) {
                    String nm = SafeXml.local(q);
                    if (nm.equals("t")) inT = false;
                    else if (nm.equals("p")) sb.append('\n');
                }

                @Override
                public void characters(char[] ch, int s, int len) {
                    if (inT) sb.append(ch, s, len);
                }
            });
            String text = collapseBlank(sb.toString());
            if (text.length() > budget) {
                text = text.substring(0, Math.max(0, budget));
                result.truncated = true;
            }
            budget -= text.length();
            if (!addSection("Slide " + n, text.isEmpty() ? "(no text on this slide)" : text)) break;
            if (budget <= 0) {
                result.truncated = true;
                break;
            }
        }
        result.notes.add("Slide text only: pictures, charts, speaker notes, animations and layout are not shown.");
    }

    // ---------------------------------------------------------------- xlsx

    private void readXlsx() throws IOException {
        final List<String> shared = new ArrayList<>();
        byte[] sst = part("xl/sharedStrings.xml");
        if (sst != null) {
            SafeXml.parse(sst, new DefaultHandler() {
                StringBuilder cur;
                boolean inT;
                int phonetic;

                @Override
                public void startElement(String u, String l, String q, Attributes a) {
                    String n = SafeXml.local(q);
                    if (n.equals("si")) cur = new StringBuilder();
                    else if (n.equals("rPh")) phonetic++;
                    else if (n.equals("t") && phonetic == 0) inT = true;
                }

                @Override
                public void endElement(String u, String l, String q) throws SAXException {
                    String n = SafeXml.local(q);
                    if (n.equals("t")) inT = false;
                    else if (n.equals("rPh")) phonetic--;
                    else if (n.equals("si")) {
                        shared.add(cur == null ? "" : cur.toString());
                        cur = null;
                        if (shared.size() > 200_000) throw new SafeXml.Stop();
                    }
                }

                @Override
                public void characters(char[] ch, int s, int len) {
                    if (inT && cur != null && cur.length() < 5000) cur.append(ch, s, len);
                }
            });
        }
        Map<String, String> rels = relationships("xl/_rels/workbook.xml.rels", "xl/");
        final List<String[]> sheets = new ArrayList<>(); // {name, path}
        SafeXml.parse(part("xl/workbook.xml"), new DefaultHandler() {
            @Override
            public void startElement(String u, String l, String q, Attributes a) {
                if (SafeXml.local(q).equals("sheet")) {
                    String rid = relId(a);
                    String path = rid == null ? null : rels.get(rid);
                    String name = a.getValue("name");
                    if (path != null) sheets.add(new String[]{name == null ? "Sheet" : name, path});
                }
            }
        });
        for (String[] sh : sheets) {
            byte[] xml = part(sh[1]);
            if (xml == null) continue;
            final StringBuilder sb = new StringBuilder();
            final int[] rows = {0};
            SafeXml.parse(xml, new DefaultHandler() {
                List<String> row;
                String cellType, cellRef;
                boolean inV, inT;
                StringBuilder val = new StringBuilder();
                boolean cut;

                @Override
                public void startElement(String u, String l, String q, Attributes a) {
                    String n = SafeXml.local(q);
                    if (n.equals("row")) row = new ArrayList<>();
                    else if (n.equals("c")) {
                        cellType = a.getValue("t");
                        cellRef = a.getValue("r");
                        val.setLength(0);
                    } else if (n.equals("v")) inV = true;
                    else if (n.equals("t")) inT = true;
                }

                @Override
                public void endElement(String u, String l, String q) throws SAXException {
                    String n = SafeXml.local(q);
                    if (n.equals("v")) inV = false;
                    else if (n.equals("t")) inT = false;
                    else if (n.equals("c") && row != null) {
                        int col = column(cellRef, row.size());
                        if (col < MAX_COLUMNS) {
                            while (row.size() < col) row.add("");
                            row.add(cellValue());
                        }
                    } else if (n.equals("row") && row != null) {
                        int end = row.size();
                        while (end > 0 && row.get(end - 1).isEmpty()) end--;
                        if (end > 0) {
                            rows[0]++;
                            for (int i = 0; i < end; i++) {
                                if (i > 0) sb.append('\t');
                                sb.append(row.get(i).replace('\t', ' ').replace('\n', ' '));
                            }
                            sb.append('\n');
                        }
                        row = null;
                        if (rows[0] >= MAX_SHEET_ROWS || sb.length() > budget) {
                            result.truncated = true;
                            throw new SafeXml.Stop();
                        }
                    }
                }

                @Override
                public void characters(char[] ch, int s, int len) {
                    if ((inV || inT) && val.length() < 5000) val.append(ch, s, len);
                }

                private String cellValue() {
                    String v = val.toString();
                    if (cellType == null || cellType.equals("n")) return v;
                    switch (cellType) {
                        case "s":
                            try {
                                int i = Integer.parseInt(v.trim());
                                return i >= 0 && i < shared.size() ? shared.get(i) : "";
                            } catch (NumberFormatException e) {
                                return "";
                            }
                        case "b":
                            return v.trim().equals("1") ? "TRUE" : "FALSE";
                        default:
                            return v;
                    }
                }
            });
            String text = sb.length() > budget ? sb.substring(0, Math.max(0, budget)) : sb.toString();
            budget -= text.length();
            if (!addSection(sh[0], text.isEmpty() ? "(empty sheet)" : text)) break;
            if (budget <= 0) {
                result.truncated = true;
                break;
            }
        }
        result.notes.add("Cell values only. Formulas show their last saved result; dates appear as serial numbers; "
                + "charts, images and formatting are not shown. Each sheet is limited to " + MAX_SHEET_ROWS
                + " rows and " + MAX_COLUMNS + " columns.");
    }

    // ---------------------------------------------------------------- helpers

    /** Zero-based column from a cell reference like "AB12"; falls back to the next position. */
    static int column(String ref, int fallback) {
        if (ref == null) return fallback;
        int col = 0, i = 0;
        while (i < ref.length()) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') col = col * 26 + (c - 'A' + 1);
            else if (c >= 'a' && c <= 'z') col = col * 26 + (c - 'a' + 1);
            else break;
            if (col > 100000) return fallback;
            i++;
        }
        return i == 0 ? fallback : col - 1;
    }

    private static String relId(Attributes a) {
        for (int i = 0; i < a.getLength(); i++) {
            if (a.getQName(i).endsWith(":id")) return a.getValue(i); // r:id, not the plain numeric id
        }
        return null;
    }

    private Map<String, String> relationships(String relsPath, String baseDir) throws IOException {
        final Map<String, String> map = new HashMap<>();
        byte[] xml = part(relsPath);
        if (xml == null) return map;
        SafeXml.parse(xml, new DefaultHandler() {
            @Override
            public void startElement(String u, String l, String q, Attributes a) {
                if (SafeXml.local(q).equals("Relationship")) {
                    String id = a.getValue("Id"), target = a.getValue("Target");
                    if (id != null && target != null && !"External".equals(a.getValue("TargetMode"))) {
                        map.put(id, resolve(baseDir, target));
                    }
                }
            }
        });
        return map;
    }

    static String resolve(String baseDir, String target) {
        String full = target.startsWith("/") ? target.substring(1) : baseDir + target;
        List<String> out = new ArrayList<>();
        for (String seg : full.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!out.isEmpty()) out.remove(out.size() - 1);
            } else out.add(seg);
        }
        return String.join("/", out);
    }

    private static int num(String name) {
        int v = 0;
        boolean any = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c >= '0' && c <= '9') {
                v = Math.min(v * 10 + (c - '0'), 1_000_000);
                any = true;
            }
        }
        return any ? v : 0;
    }

    /** Trims trailing blanks and squeezes runs of 3+ newlines to a blank line. */
    static String collapseBlank(String s) {
        StringBuilder out = new StringBuilder();
        int newlines = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') {
                newlines++;
                if (newlines <= 2) out.append(c);
            } else {
                newlines = 0;
                out.append(c);
            }
        }
        return out.toString().trim();
    }
}
