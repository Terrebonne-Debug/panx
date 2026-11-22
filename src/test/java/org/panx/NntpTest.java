package org.panx;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class NntpTest {
  @TempDir Path dir;

  @Test
  void authenticatesListsReadsAndUnstuffsDots() throws Exception {
    try (var fixture = new FakeNewsServer()) {
      fixture.articles.put("<one@fixture>", "Subject: Hello\r\n\r\n.leading dot\r\n..two dots\r\n");
      try (var client = new NntpClient(fixture.profile())) {
        assertEquals(2, client.listGroups().size());
        assertEquals(6500, client.group("comp.test").last());
        var list = client.overview(10, 12);
        assertEquals(3, list.size());
        assertEquals("<10@fixture>", list.get(0).messageId());
        assertTrue(client.article("<one@fixture>").contains("\r\n.leading dot\r\n..two dots"));
        var ex =
            assertThrows(
                NntpClient.ProtocolException.class, () -> client.article("<missing@fixture>"));
        assertEquals(430, ex.code);
        assertThrows(IllegalArgumentException.class, () -> client.article("<x>\r\nPOST"));
        assertThrows(IllegalArgumentException.class, () -> client.group("comp.test\r\nPOST"));
      }
      assertTrue(fixture.commands.contains("AUTHINFO PASS secret"));
      assertTrue(fixture.commands.contains("XOVER 10-12"));
    }
  }

  @Test
  void postingPreservesUtf8AndDotsAndReportsDenial() throws Exception {
    try (var fixture = new FakeNewsServer();
        var client = new NntpClient(fixture.profile())) {
      String raw =
          Mime.compose(
              "Writer <a@example.org>",
              "comp.test",
              "Café ✓",
              ".one\n..two\nBonjour, 世界",
              "",
              List.of());
      client.post(raw);
      var decoded = Mime.decode(fixture.posted);
      assertEquals("Café ✓", Mime.message(fixture.posted).getSubject());
      assertTrue(decoded.text().contains(".one"));
      assertTrue(decoded.text().contains("Bonjour, 世界"));
      fixture.rejectPost = true;
      assertEquals(
          440, assertThrows(NntpClient.ProtocolException.class, () -> client.post(raw)).code);
    }
  }

  @Test
  void boundsAndReusesConnectionsAndCachesBodies() throws Exception {
    try (var fixture = new FakeNewsServer();
        var store = new Store(dir);
        var news = new NewsService(store)) {
      Server server = store.saveServer(fixture.profile(), false);
      news.refreshGroups(server);
      assertEquals(2500, news.headers(server, "comp.test", 2500, false));
      assertEquals(2500, store.articles(server.id(), "comp.test", "", false, false, 3000).size());
      assertTrue(fixture.connections.get() <= 2);
      assertEquals(1000, news.headers(server, "comp.test", 1000, true));
      assertTrue(fixture.connections.get() <= 2);
      String raw = "Subject: cached\r\n\r\nCache me\r\n";
      fixture.articles.put("<cached@fixture>", raw);
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        List<Future<String>> requests = new ArrayList<>();
        for (int i = 0; i < 12; i++)
          requests.add(executor.submit(() -> news.article(server, "<cached@fixture>", false)));
        for (var r : requests) assertEquals(raw, r.get());
      }
      assertEquals(1, fixture.articleRequests.get());
      assertEquals(raw, news.article(server, "<cached@fixture>", true));
      assertThrows(
          java.io.IOException.class, () -> news.article(server, "<uncached@fixture>", true));
    }
  }
}
