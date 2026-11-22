package org.panx;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.*;

/** One sequential NNTP session; bounded workers use separate sessions. */
public final class NntpClient implements AutoCloseable {
  private final Socket socket;
  private final BufferedReader in;
  private final BufferedWriter out;
  private boolean legacyOverview;

  public NntpClient(Server server) throws IOException {
    Socket plain = new Socket();
    try {
      plain.connect(new InetSocketAddress(server.host(), server.port()), 15000);
      plain.setSoTimeout(30000);
      if (server.tls()) {
        SSLSocket ssl =
            (SSLSocket)
                ((SSLSocketFactory) SSLSocketFactory.getDefault())
                    .createSocket(plain, server.host(), server.port(), true);
        SSLParameters params = ssl.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        ssl.setSSLParameters(params);
        ssl.startHandshake();
        socket = ssl;
      } else socket = plain;
      in =
          new BufferedReader(
              new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1), 65536);
      out =
          new BufferedWriter(
              new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1), 65536);
      expect(readLine(), 200, 201);
      String mode = command("MODE READER");
      if (!server.username().isBlank()) {
        String r = command("AUTHINFO USER " + server.username());
        if (r.startsWith("381")) expect(command("AUTHINFO PASS " + server.password()), 281);
        else expect(r, 281);
        if (mode.startsWith("480")) expect(command("MODE READER"), 200, 201);
      } else if (mode.startsWith("480")) throw new ProtocolException(mode);
    } catch (IOException | RuntimeException e) {
      plain.close();
      throw e;
    }
  }

  private static String expect(String response, int... codes) throws IOException {
    for (int c : codes)
      if (response.startsWith(c + " ") || response.equals("" + c)) return response;
    throw new ProtocolException(response);
  }

  public static void validateGroup(String name) {
    if (name == null || !name.matches("[A-Za-z0-9_+.-]+") || name.length() > 255)
      throw new IllegalArgumentException("Invalid newsgroup name");
  }

  public static void validateId(String id) {
    if (id == null || !id.matches("<[^<>\\s]+>"))
      throw new IllegalArgumentException("Invalid Message-ID");
  }

  private static boolean unsupportedOverview(String response) {
    if (response.startsWith("500 ") || response.startsWith("501 ")) return true;
    // Some legacy providers use 400 for an unknown verb while keeping the session open.
    // Other 400 replies mean service shutdown and must be reported without a retry.
    return response.startsWith("400 ")
        && response
            .toLowerCase(Locale.ROOT)
            .matches(
                ".*(?:unrecogni[sz]ed|unknown|unsupported|not supported|not recogni[sz]ed).*command.*|.*command.*(?:unrecogni[sz]ed|unknown|unsupported|not supported|not recogni[sz]ed).*");
  }

  private String readLine() throws IOException {
    String line = in.readLine();
    if (line == null) throw new EOFException("Server closed the connection");
    return line;
  }

  private String command(String value) throws IOException {
    if (value.contains("\r") || value.contains("\n")) throw new IOException("Invalid NNTP command");
    out.write(value);
    out.write("\r\n");
    out.flush();
    return readLine();
  }

  private List<String> multiline(int limit) throws IOException {
    List<String> lines = new ArrayList<>();
    long bytes = 0;
    for (String s; !(s = readLine()).equals("."); ) {
      if (s.startsWith("..")) s = s.substring(1);
      bytes += s.length();
      if (lines.size() >= limit || bytes > 128L * 1024 * 1024)
        throw new IOException("Server response exceeded 128 MiB safety limit");
      lines.add(s);
    }
    return lines;
  }

  public List<Group> listGroups() throws IOException {
    expect(command("LIST ACTIVE"), 215);
    var result = new ArrayList<Group>();
    for (String line : multiline(2_000_000)) {
      String[] p = line.split("\\s+");
      if (p.length < 4) continue;
      try {
        long last = Long.parseLong(p[1]), first = Long.parseLong(p[2]);
        result.add(new Group(p[0], Math.max(0, last - first + 1), first, last, p[3], false));
      } catch (NumberFormatException ignored) {
      }
    }
    return result;
  }

  public Group group(String name) throws IOException {
    validateGroup(name);
    String[] p = expect(command("GROUP " + name), 211).split("\\s+");
    if (p.length < 5) throw new IOException("Malformed GROUP response");
    try {
      return new Group(
          p[4], Long.parseLong(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3]), "", false);
    } catch (NumberFormatException e) {
      throw new IOException("Malformed GROUP response", e);
    }
  }

  public List<Article> overview(long first, long last) throws IOException {
    if (first > last) return List.of();
    String range = first + "-" + last;
    String r = command((legacyOverview ? "XOVER " : "OVER ") + range);
    if (!legacyOverview && unsupportedOverview(r)) {
      legacyOverview = true;
      r = command("XOVER " + range);
    }
    if (r.startsWith("423")) return List.of();
    expect(r, 224);
    var articles = new ArrayList<Article>();
    for (String line : multiline(200_000))
      try {
        articles.add(Article.overview(line));
      } catch (IllegalArgumentException ignored) {
      }
    return articles;
  }

  public String article(String messageId) throws IOException {
    validateId(messageId);
    expect(command("ARTICLE " + messageId), 220);
    return String.join("\r\n", multiline(2_000_000)) + "\r\n";
  }

  /** Raw article is an ISO-8859-1 byte container, as returned by ARTICLE or Mime.compose. */
  public void post(String raw) throws IOException {
    expect(command("POST"), 340);
    String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');
    String[] lines = normalized.split("\n", -1);
    int count = lines.length;
    if (count > 0 && lines[count - 1].isEmpty()) count--;
    for (int i = 0; i < count; i++) {
      if (lines[i].startsWith(".")) out.write('.');
      out.write(lines[i]);
      out.write("\r\n");
    }
    out.write(".\r\n");
    out.flush();
    expect(readLine(), 240);
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }

  public record Group(
      String name, long count, long first, long last, String posting, boolean subscribed) {
    @Override
    public String toString() {
      return name;
    }
  }

  public static class ProtocolException extends IOException {
    public final int code;

    ProtocolException(String message) {
      super(message);
      code =
          message.length() >= 3 && message.substring(0, 3).matches("\\d{3}")
              ? Integer.parseInt(message.substring(0, 3))
              : 0;
    }
  }
}
