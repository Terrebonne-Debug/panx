package org.panx;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class CacheAndDownloadTest {
    @TempDir Path temp;

    @Test void refreshingHeadersPreservesReadFlagsAndSubscriptionAcrossRestart() throws Exception {
        long id;
        try (var store = new Store(temp)) {
            Server s = TestData.seed(store); id = s.id();
            var articles = store.articles(id, "panx.welcome", "", false, false, 100);
            var a = articles.getFirst(); store.mark(id, "panx.welcome", a.messageId(), true, true);
            store.saveArticles(id, "panx.welcome", articles);
            store.saveGroups(id, List.of(new NntpClient.Group("panx.welcome", 99, 1, 99, "y", false)));
            store.saveSetting("identity", "Reader <reader@example.org>");
            assertNotNull(store.body(id, a.messageId()));
        }
        try (var store = new Store(temp)) {
            var marked = store.articles(id, "panx.welcome", "", false, true, 100);
            assertEquals(1, marked.size()); assertTrue(marked.getFirst().read());
            assertEquals(5, store.articles(id, "panx.welcome", "", true, false, 100).size());
            assertTrue(store.groups(id, "panx.welcome", true).getFirst().subscribed());
            assertEquals("Reader <reader@example.org>", store.setting("identity", ""));
        }
    }

    @Test void interruptedJobsBecomePausedOnRestart() throws Exception {
        long job;
        try (var store = new Store(temp)) {
            var s = TestData.seed(store); job = store.addJob(s.id(), "Interrupted", "<x@example.org>", temp.resolve("out")); store.jobState(job, "Running", "1/2");
        }
        try (var store = new Store(temp)) { assertEquals("Paused", store.jobs().getFirst().state()); }
    }

    static String yenc(byte[] bytes, String name, int begin, int total, int part) {
        var encoded = new StringBuilder();
        for (byte value : bytes) { int b = ((value & 255) + 42) & 255; if (b == 0 || b == 10 || b == 13 || b == 61) { encoded.append('='); b = (b + 64) & 255; } encoded.append((char) b); }
        var crc = new CRC32(); crc.update(bytes);
        return "From: fixture@example.org\r\nSubject: binary\r\n\r\n=ybegin part=" + part + " line=128 size=" + total + " name=" + name + "\r\n=ypart begin=" + begin + " end=" + (begin + bytes.length - 1) + "\r\n" + encoded + "\r\n=yend size=" + bytes.length + " part=" + part + " pcrc32=" + String.format("%08x", crc.getValue()) + "\r\n";
    }

    @Test void assemblesCachedMultipartYencWithoutOverwritingExistingFile() throws Exception {
        try (var store = new Store(temp.resolve("cache")); var news = new NewsService(store); var queue = new DownloadQueue(store, news)) {
            var s = TestData.seed(store); Path output = temp.resolve("out"); Files.createDirectories(output); Files.writeString(output.resolve("file.bin"), "keep");
            byte[] first = {0, 10, 13, 19, 42, -1}, second = {1, 2, 3};
            store.body(s.id(), "<p1@example.org>", yenc(first, "../../file.bin", 1, 9, 1));
            store.body(s.id(), "<p2@example.org>", yenc(second, "../../file.bin", 7, 9, 2));
            long job = queue.add(s, "Binary", List.of("<p1@example.org>", "<p2@example.org>"), output); queue.start(job);
            await(queue); assertEquals("Complete", store.jobs().getFirst().state());
            assertEquals("keep", Files.readString(output.resolve("file.bin")));
            assertArrayEquals(new byte[]{0, 10, 13, 19, 42, -1, 1, 2, 3}, Files.readAllBytes(output.resolve("1-file.bin")));
            try (var files = Files.list(output)) { assertEquals(2, files.count()); }
        }
    }

    @Test void missingSegmentFailsWithoutPublishingPartialFile() throws Exception {
        try (var store = new Store(temp.resolve("cache")); var news = new NewsService(store); var queue = new DownloadQueue(store, news)) {
            var s = TestData.seed(store); Path output = temp.resolve("out");
            store.body(s.id(), "<p1@example.org>", yenc(new byte[]{1, 2}, "file.bin", 1, 4, 1));
            long job = queue.add(s, "Binary", List.of("<p1@example.org>"), output); queue.start(job); await(queue);
            assertEquals("Failed", store.jobs().getFirst().state());
            try (var files = Files.list(output)) { assertEquals(0, files.count()); }
        }
    }

    @Test void offlineCacheMissDoesNotAttemptNetwork() throws Exception {
        try (var store = new Store(temp); var news = new NewsService(store)) {
            var s = store.saveServer(new Server(0,"Unavailable", "localhost", 1, false, "", "", 1), false);
            var e = assertThrows(java.io.IOException.class, () -> news.article(s, "<missing@example.org>", true));
            assertTrue(e.getMessage().contains("not cached"));
        }
    }

    @Test void verifiesWholeFileChecksumSeparatelyFromPartChecksum() throws Exception {
        try (var store = new Store(temp.resolve("cache")); var news = new NewsService(store); var queue = new DownloadQueue(store, news)) {
            var s = TestData.seed(store); var crc = new CRC32(); crc.update(new byte[]{1, 2, 3, 4});
            store.body(s.id(), "<one@example.org>", yenc(new byte[]{1, 2}, "file.bin", 1, 4, 1));
            String second = yenc(new byte[]{3, 4}, "file.bin", 3, 4, 2);
            // The whole-file checksum precedes pcrc32, as some encoders emit it.
            store.body(s.id(), "<two@example.org>", second.replace("pcrc32=", "crc32=" + String.format("%08x", crc.getValue()) + " pcrc32="));
            long valid = queue.add(s, "Valid", List.of("<one@example.org>", "<two@example.org>"), temp.resolve("valid")); queue.start(valid); await(queue);
            assertEquals("Complete", store.jobs().getFirst().state());
            store.body(s.id(), "<two@example.org>", second.replace("pcrc32=", "crc32=00000000 pcrc32="));
            long invalid = queue.add(s, "Invalid", List.of("<one@example.org>", "<two@example.org>"), temp.resolve("invalid")); queue.start(invalid); await(queue);
            assertEquals("Failed", store.jobs().getFirst().state());
            assertTrue(store.jobs().getFirst().detail().contains("file CRC32"));
            try (var files = Files.list(temp.resolve("invalid"))) { assertEquals(0, files.count()); }
        }
    }

    private static void await(DownloadQueue queue) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (queue.active() && System.nanoTime() < deadline) Thread.sleep(10);
        assertFalse(queue.active(), "Download did not finish");
    }
}
