package org.panx;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliTest {
  @TempDir Path dir;

  @Test
  void configuresServerWithFortyConnectionDefault() throws Exception {
    assertEquals(
        0,
        Cli.run(
            new String[] {
              "server-add",
              "--data-dir",
              dir.toString(),
              "--host",
              "news.invalid",
              "--name",
              "fixture"
            }));
    try (var store = new Store(dir)) {
      Server s = store.servers().getFirst();
      assertEquals(40, s.connections());
      assertTrue(s.tls());
      assertEquals(563, s.port());
    }
  }

  @Test
  void readsCachedFixtureWithoutNetwork() throws Exception {
    try (var store = new Store(dir)) {
      TestData.seed(store);
    }
    PrintStream original = System.out;
    var output = new ByteArrayOutputStream();
    try (var out = new PrintStream(output, true, StandardCharsets.UTF_8)) {
      System.setOut(out);
      assertEquals(0, Cli.run(new String[] {"--data", dir.toString(), "groups"}));
      assertEquals(
          0,
          Cli.run(
              new String[] {"--data-dir", dir.toString(), "headers", "panx.welcome", "--offline"}));
      assertEquals(
          0,
          Cli.run(
              new String[] {
                "read",
                "<demo-panx.welcome-1@panx.invalid>",
                "--data-dir",
                dir.toString(),
                "--offline"
              }));
    } finally {
      System.setOut(original);
    }
    String text = output.toString(StandardCharsets.UTF_8);
    assertTrue(text.contains("panx.welcome"));
    assertTrue(text.contains("Welcome to Panx"));
    assertTrue(text.contains("virtual threads"));
  }

  @Test
  void previewsPostingWithoutConnecting() throws Exception {
    try (var store = new Store(dir)) {
      store.saveServer(new Server(0, "invalid", "news.invalid", 563, true, "", "", 1), false);
    }
    Path body = dir.resolve("message.txt");
    Files.writeString(body, "Hello, 世界");
    PrintStream original = System.out;
    var output = new ByteArrayOutputStream();
    try (var out = new PrintStream(output, true, StandardCharsets.ISO_8859_1)) {
      System.setOut(out);
      assertEquals(
          0,
          Cli.run(
              new String[] {
                "post",
                "--data-dir",
                dir.toString(),
                "--from",
                "Writer <writer@example.org>",
                "--group",
                "comp.test",
                "--subject",
                "CLI test",
                "--body",
                body.toString(),
                "--dry-run"
              }));
    } finally {
      System.setOut(original);
    }
    String raw = output.toString(StandardCharsets.ISO_8859_1);
    assertTrue(Mime.decode(raw).text().contains("Hello, 世界"));
    try (var store = new Store(dir)) {
      assertTrue(store.drafts().isEmpty());
    }
  }

  @Test
  void postsToLocalFixtureAndKeepsRecoveryDraft() throws Exception {
    try (var server = new FakeNewsServer()) {
      try (var store = new Store(dir)) {
        store.saveServer(server.profile(), true);
      }
      Path body = dir.resolve("message.txt");
      Files.writeString(body, ".CLI posting\nBonjour");
      assertEquals(
          0,
          Cli.run(
              new String[] {
                "post",
                "--data-dir",
                dir.toString(),
                "--from",
                "Writer <writer@example.org>",
                "--group",
                "comp.test",
                "--subject",
                "CLI fixture",
                "--body",
                body.toString()
              }));
      assertTrue(Mime.decode(server.posted).text().contains(".CLI posting"));
      try (var store = new Store(dir)) {
        assertEquals(1, store.drafts().size());
      }
    }
  }
}
