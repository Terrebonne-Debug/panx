package org.panx;

import java.util.*;

/** Cached fixture data for tests only; never included in the application. */
public final class TestData {
  private TestData() {}

  public static Server seed(Store store) throws Exception {
    Server server = store.servers().stream().filter(s -> s.host().equals("fixture.invalid")).findFirst().orElse(null);
    if (server == null)
      server =
          store.saveServer(
              new Server(0, "Cached fixture", "fixture.invalid", 119, false, "", "", 2), false);
    long sid = server.id();
    List<String> groups = List.of("panx.welcome", "comp.lang.java", "news.announce.newusers");
    for (String group : groups) {
      store.saveGroups(sid, List.of(new NntpClient.Group(group, 6, 1, 6, "y", true)));
      store.subscribe(sid, group, true);
    }
    for (String group : groups) {
      var articles = new ArrayList<Article>();
      for (int i = 1; i <= 6; i++) {
        String id = "<demo-" + group + "-" + i + "@panx.invalid>",
            refs = i > 1 && i < 5 ? "<demo-" + group + "-1@panx.invalid>" : "";
        String subject =
            switch (i) {
              case 1 -> "Welcome to Panx — your Usenet workbench";
              case 2 -> "Re: Welcome to Panx — threaded conversations";
              case 3 -> "Re: Welcome to Panx — offline reading";
              case 4 -> "Re: Welcome to Panx — keyboard shortcuts";
              case 5 -> "Getting connected to a news server";
              default -> "Posting etiquette and attachment support";
            };
        String text =
            switch (i) {
              case 1 ->
                  "Welcome to Panx!\n\nThis local demo works without a Usenet account. Select messages, search subjects and authors, flag articles, and try threaded view.\n\nPanx is written in Java. Network work uses virtual threads and bounded connection pools. SQLite indexes the local cache, and downloaded bodies are compressed.\n\nUse Servers → Add server to connect your own NNTP account.\n";
              case 2 ->
                  "Replies are linked using Message-ID and References, including conversations with missing parents.\n\nToggle Threaded to switch to a flat list.\n";
              case 3 ->
                  "Every opened article is cached. Turn on Offline to browse cached headers and bodies without making network requests.\n\nQueue downloads to prefetch articles and save attachments.\n";
              case 4 ->
                  "Keyboard shortcuts\n\nN — next unread article\nR — reply to the selected article\nF — toggle flag\nU — mark unread\nCtrl+Enter — refresh headers\nCtrl+N — compose a post\nCtrl+F — search articles\n";
              case 5 ->
                  "You need an NNTP provider to read live Usenet.\n\nChoose Servers → Add server. Enter its hostname and port. TLS usually uses port 563.\n\nPasswords stay in memory unless you select Remember password. PANX_PASSWORD can supply a password to the CLI or desktop on startup.\n";
              default ->
                  "Be thoughtful about crossposting, quote only relevant context, and follow each group's rules.\n\nPanx can post UTF-8 text and MIME attachments. The download queue also understands NZB files and checks yEnc CRC32 values when supplied.\n";
            };
        String raw =
            Mime.compose("Panx Guide <guide@panx.invalid>", group, subject, text, refs, List.of());
        var msg = Mime.message(raw);
        msg.setHeader("Message-ID", id);
        var out = new java.io.ByteArrayOutputStream();
        msg.writeTo(out);
        raw = out.toString(java.nio.charset.StandardCharsets.ISO_8859_1);
        store.body(sid, id, raw);
        articles.add(
            new Article(
                i,
                subject,
                "Panx Guide <guide@panx.invalid>",
                "Thu, 19 Nov 2026 12:00:00 -0500",
                id,
                refs,
                raw.length(),
                text.lines().count(),
                false,
                false));
      }
      store.saveArticles(sid, group, articles);
    }
    return server;
  }
}
