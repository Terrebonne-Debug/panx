package org.panx;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(25)
class ProviderLimitTest {
  @TempDir Path temp;

  @Test
  void acceptsFortyConnectionsButRejectsFortyOne() {
    assertEquals(40, new Server(1, "Provider", "localhost", 563, true, "", "", 40).connections());
    assertThrows(
        IllegalArgumentException.class,
        () -> new Server(1, "Provider", "localhost", 563, true, "", "", 41));
  }

  @Test
  void saturatesFortyConnectionsAndQueuesExcessRequests() throws Exception {
    try (var fixture = new FakeNewsServer();
        var store = new Store(temp);
        var news = new NewsService(store)) {
      fixture.articleArrivals = new CountDownLatch(40);
      fixture.articleRelease = new CountDownLatch(1);
      Server s =
          store.saveServer(
              new Server(
                  0,
                  "Provider",
                  "localhost",
                  fixture.listener.getLocalPort(),
                  false,
                  "reader",
                  "secret",
                  40),
              false);
      String raw = "Subject: fixture\r\n\r\nCached body\r\n";
      for (int i = 0; i < 45; i++) fixture.articles.put("<" + i + "@fixture>", raw);
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        var requests = new ArrayList<Future<String>>();
        for (int i = 0; i < 45; i++) {
          String id = "<" + i + "@fixture>";
          requests.add(executor.submit(() -> news.article(s, id, false)));
        }
        try {
          assertTrue(
              fixture.articleArrivals.await(8, TimeUnit.SECONDS),
              "Pool did not reach 40 concurrent requests");
          assertEquals(40, fixture.connections.get());
          assertEquals(40, fixture.articleRequests.get());
        } finally {
          fixture.articleRelease.countDown();
        }
        for (var request : requests) assertEquals(raw, request.get(5, TimeUnit.SECONDS));
        assertEquals(
            40, fixture.connections.get(), "Excess requests should reuse existing connections");
      }
    }
  }

  @Test
  void oneMultipartDownloadUsesFortyConnectionsAndWritesPartsInOrder() throws Exception {
    try (var fixture = new FakeNewsServer();
        var store = new Store(temp);
        var news = new NewsService(store);
        var queue = new DownloadQueue(store, news)) {
      fixture.articleArrivals = new CountDownLatch(40);
      fixture.articleRelease = new CountDownLatch(1);
      Server s =
          store.saveServer(
              new Server(
                  0,
                  "Provider",
                  "localhost",
                  fixture.listener.getLocalPort(),
                  false,
                  "reader",
                  "secret",
                  40),
              false);
      var ids = new ArrayList<String>();
      byte[] expected = new byte[45];
      for (int i = 0; i < expected.length; i++) {
        expected[i] = (byte) i;
        String id = "<part-" + i + "@fixture>";
        ids.add(id);
        fixture.articles.put(
            id,
            CacheAndDownloadTest.yenc(
                new byte[] {(byte) i}, "file.bin", i + 1, expected.length, i + 1));
      }
      long job = queue.add(s, "file.bin", ids, temp.resolve("output"));
      queue.start(job);
      try {
        assertTrue(
            fixture.articleArrivals.await(8, TimeUnit.SECONDS),
            "Multipart prefetch did not use all 40 connections");
        assertEquals(40, fixture.connections.get());
      } finally {
        fixture.articleRelease.countDown();
      }
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
      while (queue.active() && System.nanoTime() < deadline) Thread.sleep(10);
      assertFalse(queue.active());
      assertEquals("Complete", store.jobs().getFirst().state());
      assertArrayEquals(
          expected, java.nio.file.Files.readAllBytes(temp.resolve("output/file.bin")));
      assertEquals(40, fixture.connections.get());
    }
  }

  @Test
  void changingServerSettingsRetiresOldPoolAndRejectsStaleRequests() throws Exception {
    try (var fixture = new FakeNewsServer();
        var store = new Store(temp);
        var news = new NewsService(store)) {
      Server first = store.saveServer(fixture.profile(), false);
      news.refreshGroups(first);
      Server updated =
          new Server(
              first.id(),
              first.name(),
              first.host(),
              first.port(),
              first.tls(),
              first.username(),
              first.password(),
              40);
      news.configureServer(updated);
      assertThrows(java.io.IOException.class, () -> news.refreshGroups(first));
      assertEquals(2, news.refreshGroups(updated));
    }
  }
}
