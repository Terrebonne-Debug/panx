package org.panx;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessageFormatsTest {
  @TempDir Path temp;

  @Test
  void unicodeTextAndBinaryAttachmentsRoundTrip() throws Exception {
    byte[] data = {0, 10, -1, 42};
    Path file = temp.resolve("résumé.bin");
    Files.write(file, data);
    String raw =
        Mime.compose(
            "Reader <reader@example.org>",
            "comp.test, comp.lang.java",
            "Bonjour — café",
            "Allô, monde!",
            "<parent@example.org>",
            List.of(file));
    var msg = Mime.message(raw);
    var decoded = Mime.decode(raw);
    assertEquals("Bonjour — café", msg.getSubject());
    assertEquals("comp.test,comp.lang.java", msg.getHeader("Newsgroups", null));
    assertEquals("<parent@example.org>", msg.getHeader("In-Reply-To", null));
    assertTrue(decoded.text().contains("Allô, monde!"));
    assertEquals(1, decoded.attachments().size());
    assertArrayEquals(data, decoded.attachments().getFirst().bytes());
  }

  @Test
  void rejectsHeaderInjection() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            Mime.compose(
                "reader@example.org",
                "comp.test",
                "subject\r\nX-Evil: yes",
                "text",
                "",
                List.of()));
  }

  @Test
  void detectsYencCorruption() throws Exception {
    String raw = CacheAndDownloadTest.yenc(new byte[] {1, 2, 3}, "file.bin", 1, 3, 1);
    assertArrayEquals(new byte[] {1, 2, 3}, Mime.decodeYenc(raw).bytes());
    assertThrows(
        java.io.IOException.class,
        () -> Mime.decodeYenc(raw.replace("pcrc32=55bc801d", "pcrc32=00000000")));
  }

  @Test
  void restoredDraftRetainsAttachmentsWithoutOriginalFile() throws Exception {
    Path file = temp.resolve("original.bin");
    byte[] data = {1, 2, -1};
    Files.write(file, data);
    String raw = Mime.compose("r@example.org", "comp.test", "Draft", "Text", "", List.of(file));
    Files.delete(file);
    var draft = Mime.decode(raw);
    String repost =
        Mime.compose(
            "r@example.org",
            "comp.test",
            "Draft edited",
            draft.text(),
            "",
            List.of(),
            draft.attachments());
    var restored = Mime.decode(repost);
    assertArrayEquals(data, restored.attachments().getFirst().bytes());
    assertTrue(restored.text().contains("Text"));
  }

  @Test
  void savesUntrustedNamesAndAvoidsCollisions() throws Exception {
    var a = new Mime.Attachment("..\\..\\file.bin", new byte[] {42});
    Path first = Mime.saveAttachment(temp, a), second = Mime.saveAttachment(temp, a);
    assertEquals(temp.resolve("file.bin"), first);
    assertNotEquals(first, second);
    assertArrayEquals(new byte[] {42}, Files.readAllBytes(first));
  }

  @Test
  void nzbRoundTripPreservesXmlCharactersAndIds() throws Exception {
    var a =
        new Article(
            1,
            "A & B <test>",
            "Reader <r@example.org>",
            "today",
            "<one@example.org>",
            "",
            12,
            1,
            false,
            false);
    Path path = temp.resolve("test.nzb");
    Nzb.write(path, "comp.test", List.of(a));
    var entries = Nzb.read(path);
    assertEquals(a.subject(), entries.getFirst().subject());
    assertEquals(List.of(a.messageId()), entries.getFirst().ids());
  }

  @Test
  void rejectsMissingNzbSegmentsAndDoesNotExpandExternalEntities() throws Exception {
    Path secret = temp.resolve("secret");
    Files.writeString(secret, "DO_NOT_READ");
    Path path = temp.resolve("bad.nzb");
    Files.writeString(
        path,
        "<nzb><file subject=\"test\"><segments><segment number=\"2\">x@example.org</segment></segments></file></nzb>");
    assertThrows(java.io.IOException.class, () -> Nzb.read(path));
    Files.writeString(
        path,
        "<!DOCTYPE nzb [<!ENTITY xxe SYSTEM \""
            + secret.toUri()
            + "\">]><nzb><file subject=\"test\"><segments><segment number=\"1\">&xxe;</segment></segments></file></nzb>");
    assertThrows(Exception.class, () -> Nzb.read(path));
  }

  @Test
  void threadsDeepChainsAndCyclesWithoutRecursion() {
    var list = new ArrayList<Article>();
    for (int i = 1; i <= 20000; i++)
      list.add(
          new Article(
              i,
              "subject",
              "author",
              "date",
              "<" + i + "@x>",
              i == 1 ? "" : "<" + (i - 1) + "@x>",
              1,
              1,
              false,
              false));
    var rows = Threads.build(list, List.of(), true, false);
    assertEquals(20000, rows.size());
    assertEquals(30, rows.getLast().depth());
    var a = new Article(1, "spam", "author", "date", "<a@x>", "<b@x>", 1, 1, false, false);
    var b = new Article(2, "good", "author", "date", "<b@x>", "<a@x>", 1, 1, false, false);
    rows =
        Threads.build(
            List.of(a, b), List.of(new Store.Rule(1, "subject", "spam", -999)), true, true);
    assertEquals(1, rows.size());
    assertEquals(b, rows.getFirst().article());
  }
}
