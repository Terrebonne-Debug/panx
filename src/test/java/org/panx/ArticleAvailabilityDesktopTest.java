package org.panx;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Window;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "PANX_UI_TEST", matches = "1")
@Timeout(30)
class ArticleAvailabilityDesktopTest {
  @TempDir Path data;

  @BeforeAll
  static void rendering() {
    DesktopRendering.configure();
  }

  private static Article article(long number, String id) {
    return new Article(
        number, "Subject " + number, "Fixture", "Today", id, "", 100, 1, false, false);
  }

  private static PanxWindow open(Store store) throws Exception {
    return edt(() -> {
      com.formdev.flatlaf.FlatLightLaf.setup();
      var window = new PanxWindow(store, false);
      window.setVisible(true);
      return window;
    });
  }

  private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
    Field field = owner.getClass().getDeclaredField(name);
    field.setAccessible(true);
    return type.cast(field.get(owner));
  }

  private static <T> T edt(Callable<T> action) throws Exception {
    var task = new FutureTask<T>(action);
    SwingUtilities.invokeAndWait(task);
    return task.get();
  }

  private static void await(Callable<Boolean> test) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (edt(test)) return;
      Thread.sleep(25);
    }
    fail("Timed out waiting for article state");
  }

  @Test
  void unavailableBodyExplainsHeaderWithoutDialogOrReadFlag() throws Exception {
    try (var fixture = new FakeNewsServer()) {
      Store store = new Store(data);
      Server server = store.saveServer(fixture.profile(), false);
      store.subscribe(server.id(), "comp.test", true);
      store.saveArticles(server.id(), "comp.test", List.of(article(1, "<missing@fixture>")));
      PanxWindow window = open(store);
      try {
        JTable articles = field(window, "articles", JTable.class);
        JTextArea body = field(window, "body", JTextArea.class);
        await(() -> articles.getRowCount() == 1);
        edt(() -> {
          articles.setRowSelectionInterval(0, 0);
          return null;
        });
        await(() -> body.getText().contains("This article is unavailable"));
        edt(() -> {
          assertFalse(
              java.util.Arrays.stream(window.getOwnedWindows()).anyMatch(Window::isVisible));
          return null;
        });
        assertFalse(
            store.articles(server.id(), "comp.test", "", false, false, 10).getFirst().read());
        assertNull(store.body(server.id(), "<missing@fixture>"));
        fixture.articles.put(
            "<missing@fixture>", "Subject: now available\r\n\r\nRecovered body\r\n");
        edt(() -> {
          articles.clearSelection();
          articles.setRowSelectionInterval(0, 0);
          return null;
        });
        await(() -> body.getText().contains("Recovered body"));
        assertNotNull(store.body(server.id(), "<missing@fixture>"));
      } finally {
        edt(() -> {
          window.closeResources();
          return null;
        });
      }
    }
  }

  @Test
  void lateMissingArticleDoesNotOverwriteNewSelectionOrOpenDialog() throws Exception {
    try (var fixture = new FakeNewsServer()) {
      fixture.articleArrivals = new CountDownLatch(1);
      fixture.articleRelease = new CountDownLatch(1);
      Store store = new Store(data);
      Server server = store.saveServer(fixture.profile(), false);
      store.subscribe(server.id(), "comp.test", true);
      store.saveArticles(
          server.id(),
          "comp.test",
          List.of(article(2, "<slow-missing@fixture>"), article(1, "<cached@fixture>")));
      store.body(
          server.id(), "<cached@fixture>", "Subject: cached\r\n\r\nCurrent selected message\r\n");
      PanxWindow window = open(store);
      try {
        JTable articles = field(window, "articles", JTable.class);
        JTextArea body = field(window, "body", JTextArea.class);
        await(() -> articles.getRowCount() == 2);
        edt(() -> {
          articles.setRowSelectionInterval(0, 0);
          return null;
        });
        assertTrue(fixture.articleArrivals.await(5, TimeUnit.SECONDS));
        edt(() -> {
          articles.setRowSelectionInterval(1, 1);
          return null;
        });
        await(() -> body.getText().contains("Current selected message"));
        fixture.articleRelease.countDown();
        await(() -> field(window, "pending", Integer.class) == 0);
        edt(() -> {
          assertTrue(body.getText().contains("Current selected message"));
          assertFalse(
              java.util.Arrays.stream(window.getOwnedWindows()).anyMatch(Window::isVisible));
          return null;
        });
      } finally {
        fixture.articleRelease.countDown();
        edt(() -> {
          window.closeResources();
          return null;
        });
      }
    }
  }
}
