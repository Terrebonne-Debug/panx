package org.panx;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import org.w3c.dom.*;

public final class Nzb {
  private Nzb() {}

  public static List<FileEntry> read(Path path) throws Exception {
    if (Files.size(path) > 32L * 1024 * 1024) throw new IOException("NZB exceeds 32 MiB limit");
    var f = DocumentBuilderFactory.newInstance();
    f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    f.setFeature("http://xml.org/sax/features/external-general-entities", false);
    f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    f.setXIncludeAware(false);
    f.setExpandEntityReferences(false);
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    Document doc = f.newDocumentBuilder().parse(path.toFile());
    var files = doc.getElementsByTagName("file");
    List<FileEntry> result = new ArrayList<>();
    for (int i = 0; i < files.getLength(); i++) {
      var e = (Element) files.item(i);
      var segments = e.getElementsByTagName("segment");
      TreeMap<Integer, String> ids = new TreeMap<>();
      for (int j = 0; j < segments.getLength(); j++) {
        var seg = (Element) segments.item(j);
        String id = seg.getTextContent().strip();
        if (!id.startsWith("<")) id = "<" + id + ">";
        NntpClient.validateId(id);
        int n = Integer.parseInt(seg.getAttribute("number"));
        if (n < 1 || ids.put(n, id) != null) throw new IOException("Duplicate/invalid NZB segment");
      }
      if (ids.isEmpty()) throw new IOException("NZB file has no segments");
      if (ids.firstKey() != 1 || ids.lastKey() != ids.size())
        throw new IOException("NZB has missing segment numbers");
      result.add(new FileEntry(e.getAttribute("subject"), List.copyOf(ids.values())));
    }
    if (result.isEmpty()) throw new IOException("No files in NZB");
    return result;
  }

  public static void write(Path path, String group, List<Article> articles) throws IOException {
    StringBuilder xml =
        new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<nzb xmlns=\"http://www.newzbin.com/DTD/2003/nzb\">\n");
    for (var a : articles) {
      xml.append("<file poster=\"")
          .append(escape(a.from()))
          .append("\" date=\"0\" subject=\"")
          .append(escape(a.subject()))
          .append("\"><groups><group>")
          .append(escape(group))
          .append("</group></groups><segments><segment bytes=\"")
          .append(a.bytes())
          .append("\" number=\"1\">")
          .append(escape(a.messageId().replace("<", "").replace(">", "")))
          .append("</segment></segments></file>\n");
    }
    Files.writeString(path, xml.append("</nzb>\n").toString(), StandardOpenOption.CREATE_NEW);
  }

  private static String escape(String s) {
    return s.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  public record FileEntry(String subject, List<String> ids) {}
}
