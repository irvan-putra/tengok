#!/usr/bin/env python3
"""Synthetic PDF, SVG and Office fixtures for the formats smoke. No real documents."""
import sys
import zipfile
from pathlib import Path

out = Path(sys.argv[1] if len(sys.argv) > 1 else "ci/format-fixtures")
out.mkdir(parents=True, exist_ok=True)


def pdf():
    def page_stream(text, rect):
        s = "q %s rg 40 600 200 100 re f Q\nBT /F1 28 Tf 40 540 Td (%s) Tj ET\n" % (rect, text)
        return s.encode("latin-1")

    objs = []
    objs.append(b"<< /Type /Catalog /Pages 2 0 R >>")
    objs.append(b"<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>")
    objs.append(b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 800] /Contents 4 0 R /Resources << /Font << /F1 7 0 R >> >> >>")
    s1 = page_stream("Tengok PDF page one", "0.8 0.1 0.1")
    objs.append(b"<< /Length %d >>\nstream\n" % len(s1) + s1 + b"endstream")
    objs.append(b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 800] /Contents 6 0 R /Resources << /Font << /F1 7 0 R >> >> >>")
    s2 = page_stream("Tengok PDF page two", "0.1 0.4 0.8")
    objs.append(b"<< /Length %d >>\nstream\n" % len(s2) + s2 + b"endstream")
    objs.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    body = bytearray(b"%PDF-1.4\n")
    offsets = []
    for i, o in enumerate(objs, 1):
        offsets.append(len(body))
        body += b"%d 0 obj\n" % i + o + b"\nendobj\n"
    xref = len(body)
    body += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
    for off in offsets:
        body += b"%010d 00000 n \n" % off
    body += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)
    return bytes(body)


(out / "sample.pdf").write_bytes(pdf())

(out / "sample.svg").write_text(
    '<?xml version="1.0"?>\n'
    '<svg xmlns="http://www.w3.org/2000/svg" width="240" height="160" viewBox="0 0 240 160">'
    '<rect x="10" y="10" width="100" height="60" rx="8" fill="#2a9d8f"/>'
    '<circle cx="170" cy="50" r="35" fill="orange" stroke="navy" stroke-width="4"/>'
    '<path d="M20 140 L80 90 L140 140 Z" fill="none" stroke="crimson" stroke-width="5"/>'
    '<text x="120" y="150" font-size="18" text-anchor="middle">Tengok SVG fixture</text>'
    '<style>.x{fill:red}</style></svg>', encoding="utf-8")


def office(name, files):
    with zipfile.ZipFile(out / name, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>')
        for n, c in files.items():
            z.writestr(n, c)


office("sample.docx", {"word/document.xml":
    '<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>'
    '<w:p><w:r><w:t>Tengok Word fixture</w:t></w:r></w:p>'
    '<w:p><w:r><w:t>Second paragraph with</w:t><w:tab/><w:t>a tab.</w:t></w:r></w:p></w:body></w:document>'})

office("sample.xlsx", {
    "xl/workbook.xml": '<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" '
                       'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">'
                       '<sheets><sheet name="Budget" sheetId="1" r:id="rId1"/></sheets></workbook>',
    "xl/_rels/workbook.xml.rels": '<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
                                  '<Relationship Id="rId1" Type="x" Target="worksheets/sheet1.xml"/></Relationships>',
    "xl/sharedStrings.xml": '<?xml version="1.0"?><sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">'
                            '<si><t>Item</t></si><si><t>Tengok Excel fixture</t></si></sst>',
    "xl/worksheets/sheet1.xml": '<?xml version="1.0"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>'
                                '<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c></row>'
                                '<row r="2"><c r="A2" t="inlineStr"><is><t>Rice</t></is></c><c r="B2"><v>42</v></c></row>'
                                '</sheetData></worksheet>'})

office("sample.pptx", {
    "ppt/presentation.xml": '<?xml version="1.0"?><p:presentation xmlns:p="p" xmlns:r="r"><p:sldIdLst>'
                            '<p:sldId id="256" r:id="rId1"/><p:sldId id="257" r:id="rId2"/></p:sldIdLst></p:presentation>',
    "ppt/_rels/presentation.xml.rels": '<?xml version="1.0"?><Relationships xmlns="r">'
                                       '<Relationship Id="rId1" Target="slides/slide1.xml"/><Relationship Id="rId2" Target="slides/slide2.xml"/></Relationships>',
    "ppt/slides/slide1.xml": '<p:sld xmlns:p="p" xmlns:a="a"><a:p><a:r><a:t>Tengok PowerPoint fixture</a:t></a:r></a:p></p:sld>',
    "ppt/slides/slide2.xml": '<p:sld xmlns:p="p" xmlns:a="a"><a:p><a:r><a:t>Second slide text</a:t></a:r></a:p></p:sld>'})
print("fixtures written to", out)
