package org.panx;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class NntpWireTest {
  private static void send(BufferedWriter out, String... lines) throws IOException {
    for (String line : lines) {
      out.write(line);
      out.write("\r\n");
    }
    out.flush();
  }

  private static void authenticate(BufferedReader in, BufferedWriter out) throws Exception {
    assertEquals("MODE READER", in.readLine());
    send(out, "480 Authentication required");
    assertEquals("AUTHINFO USER reader", in.readLine());
    send(out, "381 Password required");
    assertEquals("AUTHINFO PASS secret", in.readLine());
    send(out, "281 Authentication accepted");
    assertEquals("MODE READER", in.readLine());
    send(out, "200 Reader mode");
  }

  private void exchange(Exchange exchange, ClientWork work) throws Exception {
    try (var listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        var executor = Executors.newSingleThreadExecutor()) {
      listener.setSoTimeout(5000);
      Future<?> fixture =
          executor.submit(() -> {
            try (Socket socket = listener.accept()) {
              socket.setSoTimeout(5000);
              var in =
                  new BufferedReader(
                      new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
              var out =
                  new BufferedWriter(
                      new OutputStreamWriter(
                          socket.getOutputStream(), StandardCharsets.ISO_8859_1));
              send(out, "200 fixture ready");
              exchange.run(in, out);
            } catch (Exception e) {
              throw new CompletionException(e);
            }
          });
      try (var client =
          new NntpClient(
              new Server(
                  1,
                  "Fixture",
                  listener.getInetAddress().getHostAddress(),
                  listener.getLocalPort(),
                  false,
                  "reader",
                  "secret",
                  1))) {
        work.run(client);
      }
      fixture.get(10, TimeUnit.SECONDS);
    }
  }

  @Test
  void authenticatesFallsBackToXoverAndUnstuffsArticle() throws Exception {
    exchange(
        (in, out) -> {
          authenticate(in, out);
          assertEquals("GROUP comp.test", in.readLine());
          send(out, "211 1 42 42 comp.test");
          assertEquals("OVER 42-42", in.readLine());
          send(out, "500 Unsupported");
          assertEquals("XOVER 42-42", in.readLine());
          send(
              out,
              "224 Overview",
              "42\tA subject\tReader <r@example.org>\tToday\t<42@example.org>\t\t100\t3",
              ".");
          assertEquals("ARTICLE <42@example.org>", in.readLine());
          send(out, "220 Article follows", "Subject: A subject", "", "..dot", "...two", ".");
        },
        client -> {
          assertEquals(42, client.group("comp.test").first());
          var articles = client.overview(42, 42);
          assertEquals(1, articles.size());
          assertEquals("<42@example.org>", articles.getFirst().messageId());
          assertEquals(
              "Subject: A subject\r\n\r\n.dot\r\n..two\r\n", client.article("<42@example.org>"));
        });
  }

  @Test
  void postingNormalizesLinesAndEscapesLeadingDots() throws Exception {
    exchange(
        (in, out) -> {
          authenticate(in, out);
          assertEquals("POST", in.readLine());
          send(out, "340 Send article");
          assertEquals("Subject: Test", in.readLine());
          assertEquals("", in.readLine());
          assertEquals("..", in.readLine());
          assertEquals("...hello", in.readLine());
          assertEquals("end", in.readLine());
          assertEquals(".", in.readLine());
          send(out, "240 Posted");
        },
        client -> client.post("Subject: Test\n\n.\n..hello\rend\n"));
  }

  @Test
  void fallsBackForLegacy400AndRemembersXoverForTheSession() throws Exception {
    exchange(
        (in, out) -> {
          authenticate(in, out);
          assertEquals("GROUP comp.test", in.readLine());
          send(out, "211 2 41 42 comp.test");
          assertEquals("OVER 41-41", in.readLine());
          send(out, "400 Unrecognized command");
          assertEquals("XOVER 41-41", in.readLine());
          send(
              out,
              "224 Overview follows",
              "41\tFirst\tReader\tToday\t<41@example.org>\t\t100\t3",
              ".");
          assertEquals("XOVER 42-42", in.readLine());
          send(
              out,
              "224 Overview follows",
              "42\tSecond\tReader\tToday\t<42@example.org>\t\t100\t3",
              ".");
        },
        client -> {
          client.group("comp.test");
          assertEquals(41, client.overview(41, 41).getFirst().number());
          assertEquals(42, client.overview(42, 42).getFirst().number());
        });
  }

  @Test
  void reportsReal400ShutdownWithoutSendingAnotherCommand() throws Exception {
    exchange(
        (in, out) -> {
          authenticate(in, out);
          assertEquals("OVER 1-1", in.readLine());
          send(out, "400 Service temporarily unavailable");
          assertNull(
              in.readLine(), "Client must close, rather than retry XOVER on a shutdown response");
        },
        client -> {
          var error = assertThrows(NntpClient.ProtocolException.class, () -> client.overview(1, 1));
          assertEquals(400, error.code);
        });
  }

  @Test
  void propagatesPostingRejection() throws Exception {
    exchange(
        (in, out) -> {
          authenticate(in, out);
          assertEquals("POST", in.readLine());
          send(out, "440 Posting not permitted");
        },
        client -> {
          var ex =
              assertThrows(
                  NntpClient.ProtocolException.class,
                  () -> client.post("Subject: Test\r\n\r\nBody"));
          assertEquals(440, ex.code);
        });
  }

  @FunctionalInterface
  interface Exchange {
    void run(BufferedReader in, BufferedWriter out) throws Exception;
  }

  @FunctionalInterface
  interface ClientWork {
    void run(NntpClient client) throws Exception;
  }

  @Test
  void rejectsCommandInjection() {
    assertThrows(
        IllegalArgumentException.class, () -> NntpClient.validateGroup("comp.test\r\nQUIT"));
    assertThrows(
        IllegalArgumentException.class, () -> NntpClient.validateId("<id@example.org>\r\nQUIT"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Server(1, "Test", "localhost", 119, false, "user\nQUIT", "", 1));
  }
}
