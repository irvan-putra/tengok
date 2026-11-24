package com.vanipy.tengok;

import static org.junit.Assert.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

public class OfficeTextTest {
    private static byte[] zip(String... nameAndContent) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            for (int i = 0; i < nameAndContent.length; i += 2) {
                z.putNextEntry(new ZipEntry(nameAndContent[i]));
                z.write(nameAndContent[i + 1].getBytes("UTF-8"));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    public void docxParagraphsTabsAndTables() throws Exception {
        String doc = "<w:document xmlns:w='w'><w:body><w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:tab/><w:t> world</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>Second</w:t><w:br/><w:t>line</w:t></w:r></w:p>"
                + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>A1</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>B1</w:t></w:r></w:p></w:tc></w:tr></w:tbl>"
                + "</w:body></w:document>";
        OfficeText.Result r = OfficeText.read(zip("word/document.xml", doc));
        assertTrue(r.format.contains("Word"));
        String t = r.sections.get(0).text;
        assertTrue(t, t.contains("Hello\t world"));
        assertTrue(t, t.contains("Second\nline"));
        assertTrue(t, t.contains("A1"));
        assertTrue(t, t.contains("B1"));
    }

    @Test
    public void xlsxSharedStringsTypesAndSheetNames() throws Exception {
        String wb = "<workbook xmlns:r='r'><sheets><sheet name='Budget' sheetId='1' r:id='rId1'/></sheets></workbook>";
        String rels = "<Relationships><Relationship Id='rId1' Target='worksheets/sheet1.xml'/></Relationships>";
        String sst = "<sst><si><t>Name</t></si><si><r><t>Am</t></r><r><t>ount</t></r><rPh><t>IGNORED</t></rPh></si></sst>";
        String sheet = "<worksheet><sheetData>"
                + "<row r='1'><c r='A1' t='s'><v>0</v></c><c r='C1' t='s'><v>1</v></c></row>"
                + "<row r='2'><c r='A2' t='inlineStr'><is><t>inline</t></is></c><c r='B2'><v>42</v></c><c r='C2' t='b'><v>1</v></c></row>"
                + "<row r='3'></row></sheetData></worksheet>";
        OfficeText.Result r = OfficeText.read(zip("xl/workbook.xml", wb, "xl/_rels/workbook.xml.rels", rels,
                "xl/sharedStrings.xml", sst, "xl/worksheets/sheet1.xml", sheet));
        assertEquals("Budget", r.sections.get(0).title);
        assertEquals("Name\t\tAmount\ninline\t42\tTRUE\n", r.sections.get(0).text);
    }

    @Test
    public void pptxSlidesFollowPresentationOrder() throws Exception {
        String pres = "<p:presentation xmlns:r='r'><p:sldIdLst><p:sldId id='1' r:id='rId2'/><p:sldId id='2' r:id='rId1'/></p:sldIdLst></p:presentation>";
        String rels = "<Relationships><Relationship Id='rId1' Target='slides/slide1.xml'/><Relationship Id='rId2' Target='slides/slide2.xml'/></Relationships>";
        String s1 = "<p:sld><a:p><a:r><a:t>One</a:t></a:r></a:p></p:sld>";
        String s2 = "<p:sld><a:p><a:r><a:t>Two</a:t></a:r></a:p><a:p><a:r><a:t>more</a:t></a:r></a:p></p:sld>";
        OfficeText.Result r = OfficeText.read(zip("ppt/presentation.xml", pres, "ppt/_rels/presentation.xml.rels", rels,
                "ppt/slides/slide1.xml", s1, "ppt/slides/slide2.xml", s2));
        assertEquals(2, r.sections.size());
        assertEquals("Two\nmore", r.sections.get(0).text);
        assertEquals("One", r.sections.get(1).text);
    }

    @Test
    public void refusesNonOfficeEntitiesAndBadXml() throws Exception {
        try {
            OfficeText.read(zip("readme.txt", "hi"));
            fail();
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
        try {
            OfficeText.read(zip("word/document.xml", "<!DOCTYPE d [<!ENTITY a 'b'>]><d>&a;</d>"));
            fail();
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("entities"));
        }
        try {
            OfficeText.read(zip("word/document.xml", "<w:p><w:t>cut"));
            fail();
        } catch (IOException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void columnsAndPathsResolve() {
        assertEquals(0, OfficeText.column("A1", 9));
        assertEquals(27, OfficeText.column("AB7", 9));
        assertEquals(9, OfficeText.column(null, 9));
        assertEquals("xl/worksheets/sheet1.xml", OfficeText.resolve("xl/", "worksheets/sheet1.xml"));
        assertEquals("xl/sharedStrings.xml", OfficeText.resolve("xl/worksheets/", "../sharedStrings.xml"));
        assertEquals("docProps/a.xml", OfficeText.resolve("xl/", "/docProps/a.xml"));
    }
}
