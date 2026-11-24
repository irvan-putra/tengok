package com.vanipy.tengok;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * SAX parsing for hostile XML (SVG and Office parts). Files that declare
 * entities are refused outright, which rules out entity-expansion bombs and
 * external entity reads. Nothing is fetched from a network or from disk.
 */
public final class SafeXml {
    private SafeXml() {
    }

    public static void parse(byte[] data, DefaultHandler handler) throws IOException {
        int probe = Math.min(data.length, 64 * 1024);
        String head = new String(data, 0, probe, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (head.contains("<!ENTITY") || (head.contains("<!DOCTYPE") && head.contains("["))) {
            throw new IOException("this file declares XML entities, which tengok refuses to expand");
        }
        try {
            SAXParserFactory f = SAXParserFactory.newInstance();
            f.setNamespaceAware(false);
            trySet(f, "http://xml.org/sax/features/external-general-entities", false);
            trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
            trySet(f, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            SAXParser p = f.newSAXParser();
            org.xml.sax.XMLReader r = p.getXMLReader();
            r.setContentHandler(handler);
            r.setErrorHandler(handler);
            r.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            r.parse(new InputSource(new ByteArrayInputStream(data)));
        } catch (ParserConfigurationException | SAXException e) {
            if (e.getCause() instanceof IOException) throw (IOException) e.getCause();
            if (e instanceof SAXException && e.getMessage() != null && e.getMessage().startsWith("STOP")) return;
            throw new IOException("the XML could not be read: " + e.getMessage(), e);
        }
    }

    /** Thrown by handlers to stop early when an output limit is reached. */
    public static final class Stop extends SAXException {
        public Stop() {
            super("STOP");
        }
    }

    private static void trySet(SAXParserFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (Exception ignored) {
            // not every parser knows every feature; entity declarations are refused above anyway
        }
    }

    /** Local name without a namespace prefix ("w:p" becomes "p"). */
    public static String local(String qName) {
        int c = qName.indexOf(':');
        return c < 0 ? qName : qName.substring(c + 1);
    }
}
