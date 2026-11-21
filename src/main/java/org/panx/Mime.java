package org.panx;

import jakarta.activation.*;
import jakarta.mail.*;
import jakarta.mail.internet.*;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.CRC32;

public final class Mime {
  private Mime() {}

  public static String decodeHeader(String value) {
    try {
      return MimeUtility.decodeText(value);
    } catch (Exception e) {
      return value;
    }
  }

  public static MimeMessage message(String raw) throws MessagingException {
    return new MimeMessage(
        Session.getInstance(new Properties()),
        new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)));
  }

  public static Decoded decode(String raw) throws Exception {
    var msg = message(raw);
    Map<String, String> headers = new LinkedHashMap<>();
    var enumeration = msg.getAllHeaders();
    while (enumeration.hasMoreElements()) {
      var h = enumeration.nextElement();
      headers.merge(h.getName(), decodeHeader(h.getValue()), (a, b) -> a + ", " + b);
    }
    var text = new StringBuilder();
    var attachments = new ArrayList<Attachment>();
    walk(msg, text, attachments, 0);
    if (raw.contains("=ybegin ")) {
      Yenc y = decodeYenc(raw);
      if (!y.multipart()) attachments.add(new Attachment(safeName(y.name()), y.bytes()));
      else
        text.append("\n[yEnc part ")
            .append(y.begin())
            .append("–")
            .append(y.end())
            .append("; import its NZB to assemble all parts]\n");
    }
    return new Decoded(headers, text.toString(), List.copyOf(attachments));
  }

  private static void walk(Part part, StringBuilder text, List<Attachment> attachments, int depth)
      throws Exception {
    if (depth > 30) throw new IOException("MIME nesting is too deep");
    String filename = part.getFileName();
    if (filename != null || Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
      attachments.add(
          new Attachment(
              safeName(filename == null ? "attachment.bin" : decodeHeader(filename)),
              part.getInputStream().readAllBytes()));
      return;
    }
    if (part.isMimeType("multipart/*")) {
      Multipart multi = (Multipart) part.getContent();
      if (part.isMimeType("multipart/alternative")) {
        Part selected = multi.getBodyPart(0);
        for (int i = 0; i < multi.getCount(); i++)
          if (multi.getBodyPart(i).isMimeType("text/plain")) {
            selected = multi.getBodyPart(i);
            break;
          }
        walk(selected, text, attachments, depth + 1);
      } else
        for (int i = 0; i < multi.getCount(); i++)
          walk(multi.getBodyPart(i), text, attachments, depth + 1);
    } else if (part.isMimeType("text/plain")) text.append(part.getContent()).append('\n');
    else if (part.isMimeType("text/html"))
      text.append(
              part.getContent()
                  .toString()
                  .replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                  .replaceAll("(?is)<br\\s*/?>|</p>", "\n")
                  .replaceAll("<[^>]+>", "")
                  .replace("&lt;", "<")
                  .replace("&gt;", ">")
                  .replace("&amp;", "&")
                  .replace("&nbsp;", " "))
          .append('\n');
    else if (part.isMimeType("message/rfc822"))
      walk((Part) part.getContent(), text, attachments, depth + 1);
    else attachments.add(new Attachment("attachment.bin", part.getInputStream().readAllBytes()));
  }

  public static String compose(
      String from, String groups, String subject, String body, String references, List<Path> files)
      throws Exception {
    return compose(from, groups, subject, body, references, files, List.of());
  }

  public static String compose(
      String from, String groups, String subject, String body, String references, List<Path> files,
      List<Attachment> embedded) throws Exception {
    for (String value : List.of(from, groups, subject, references))
      if (value.contains("\r") || value.contains("\n"))
        throw new IllegalArgumentException("Headers cannot contain newlines");
    if (subject.isBlank() || body.isBlank())
      throw new IllegalArgumentException("Subject and message text are required");
    var sender = new InternetAddress(from, true);
    sender.validate();
    String[] names = groups.split(",", -1);
    for (String name : names) NntpClient.validateGroup(name.strip());
    var msg = new MimeMessage(Session.getInstance(new Properties()));
    msg.setFrom(sender);
    msg.setSubject(subject, "UTF-8");
    msg.setSentDate(new Date());
    msg.setHeader("Newsgroups", String.join(",", Arrays.stream(names).map(String::strip).toList()));
    msg.setHeader("User-Agent", "Panx/1.0");
    if (!references.isBlank()) {
      for (String id : references.strip().split("\\s+")) NntpClient.validateId(id);
      msg.setHeader("References", references);
      String[] refs = references.strip().split("\\s+");
      msg.setHeader("In-Reply-To", refs[refs.length - 1]);
    }
    if (files.isEmpty() && embedded.isEmpty()) msg.setText(body, "UTF-8");
    else {
      var multipart = new MimeMultipart();
      var text = new MimeBodyPart();
      text.setText(body, "UTF-8");
      multipart.addBodyPart(text);
      long total = 0;
      for (Path file : files) {
        total += Files.size(file);
        if (total > 64L * 1024 * 1024)
          throw new IOException("Posting attachments are limited to 64 MiB total");
        var p = new MimeBodyPart();
        p.attachFile(file.toFile());
        multipart.addBodyPart(p);
      }
      for (var attachment : embedded) {
        total += attachment.bytes().length;
        if (total > 64L * 1024 * 1024)
          throw new IOException("Posting attachments are limited to 64 MiB total");
        var p = new MimeBodyPart();
        p.setDataHandler(new DataHandler(new jakarta.mail.util.ByteArrayDataSource(attachment.bytes(), "application/octet-stream")));
        p.setFileName(safeName(attachment.name()));
        p.setDisposition(Part.ATTACHMENT);
        multipart.addBodyPart(p);
      }
      msg.setContent(multipart);
    }
    msg.saveChanges();
    var out = new ByteArrayOutputStream();
    msg.writeTo(out);
    return out.toString(StandardCharsets.ISO_8859_1);
  }

  public static String safeName(String name) {
    String clean = name.replace('\\', '/');
    clean = clean.substring(clean.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}:*?\"<>|]", "_");
    if (clean.isBlank() || clean.equals(".") || clean.equals("..")) return "attachment.bin";
    return clean.length() > 200 ? clean.substring(0, 200) : clean;
  }

  /** CREATE_NEW prevents silent clobbering or following an existing symlink. */
  public static Path saveAttachment(Path directory, Attachment a) throws IOException {
    Files.createDirectories(directory);
    String name = safeName(a.name());
    for (int i = 0; i < 10000; i++) {
      Path p = directory.resolve(i == 0 ? name : i + "-" + name);
      try {
        Files.write(p, a.bytes(), StandardOpenOption.CREATE_NEW);
        return p;
      } catch (FileAlreadyExistsException ignored) {
      }
    }
    throw new IOException("Too many conflicting filenames");
  }

  public static Yenc decodeYenc(String raw) throws IOException {
    String[] lines = raw.replace("\r\n", "\n").split("\n", -1);
    int start = -1;
    for (int i = 0; i < lines.length; i++)
      if (lines[i].startsWith("=ybegin ")) {
        start = i;
        break;
      }
    if (start < 0) throw new IOException("No yEnc data");
    String header = lines[start];
    var nameMatch = Pattern.compile("(?:^| )name=(.*)$").matcher(header);
    if (!nameMatch.find()) throw new IOException("yEnc filename missing");
    String name = nameMatch.group(1);
    long total = attribute(header, "size", -1), begin = 1, end = total;
    boolean multipart = false;
    int pos = start + 1;
    if (pos < lines.length && lines[pos].startsWith("=ypart ")) {
      begin = attribute(lines[pos], "begin", -1);
      end = attribute(lines[pos], "end", -1);
      multipart = true;
      pos++;
    }
    if (total < 0 || begin < 1 || end < begin || end > total)
      throw new IOException("Invalid yEnc size/range");
    var out = new ByteArrayOutputStream();
    String footer = null;
    for (; pos < lines.length; pos++) {
      String line = lines[pos];
      if (line.startsWith("=yend ")) {
        footer = line;
        break;
      }
      for (int j = 0; j < line.length(); j++) {
        int b = line.charAt(j) & 255;
        if (b == '=') {
          if (++j >= line.length()) throw new IOException("Truncated yEnc escape");
          b = (line.charAt(j) - 64) & 255;
        }
        out.write((b - 42) & 255);
      }
    }
    byte[] bytes = out.toByteArray();
    if (footer == null
        || bytes.length != attribute(footer, "size", -1)
        || bytes.length != end - begin + 1) throw new IOException("Incomplete yEnc part");
    Long fileCrc = checksum(footer, "crc32");
    Long crc = multipart ? checksum(footer, "pcrc32") : fileCrc;
    if (crc != null) {
      CRC32 actual = new CRC32();
      actual.update(bytes);
      if (actual.getValue() != crc)
        throw new IOException("yEnc CRC32 mismatch");
    }
    return new Yenc(name, begin, end, total, bytes, multipart, fileCrc);
  }

  private static Long checksum(String footer, String field) throws IOException {
    var match = Pattern.compile("(?:^| )" + field + "=([^ ]+)(?: |$)").matcher(footer);
    if (!match.find()) return null;
    if (!match.group(1).matches("[0-9a-fA-F]{8}")) throw new IOException("Invalid yEnc " + field);
    return Long.parseUnsignedLong(match.group(1), 16);
  }

  public record Attachment(String name, byte[] bytes) {
    @Override
    public String toString() {
      return name + " (" + bytes.length + " bytes)";
    }
  }

  public record Decoded(Map<String, String> headers, String text, List<Attachment> attachments) {}

  public record Yenc(
      String name, long begin, long end, long total, byte[] bytes, boolean multipart, Long fileCrc32) {}

  private static long attribute(String line, String name, long fallback) throws IOException {
    var m = Pattern.compile("(?:^| )" + name + "=(\\d+)(?: |$)").matcher(line);
    if (!m.find()) return fallback;
    try {
      return Long.parseLong(m.group(1));
    } catch (NumberFormatException e) {
      throw new IOException("Invalid yEnc " + name, e);
    }
  }
}
