package org.panx;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "PANX_UI_TEST", matches = "1")
class DesktopTest {
  @TempDir Path dir;

  @BeforeAll
  static void configureRendering() {
    DesktopRendering.configure();
  }

  private static <T> T field(Object owner, String name, Class<T> type) throws Exception {
    Field f = owner.getClass().getDeclaredField(name);
    f.setAccessible(true);
    return type.cast(f.get(owner));
  }

  private static void await(BooleanSupplier condition) throws Exception {
    long until = System.nanoTime() + 15_000_000_000L;
    while (System.nanoTime() < until) {
      boolean[] ready = {false};
      SwingUtilities.invokeAndWait(() -> ready[0] = condition.getAsBoolean());
      if (ready[0]) return;
      Thread.sleep(40);
    }
    fail("Timed out waiting for desktop state");
  }

  @Test
  void demoLoadsArticlesSearchesAndOpensComposer() throws Exception {
    assertFalse(GraphicsEnvironment.isHeadless());
    Store store = new Store(dir);
    TestData.seed(store);
    PanxWindow[] frame = {null};
    try {
      SwingUtilities.invokeAndWait(() -> {
        try {
          com.formdev.flatlaf.FlatLightLaf.setup();
          frame[0] = new PanxWindow(store, true);
          frame[0].setVisible(true);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      PanxWindow w = frame[0];
      JList<?> groups = field(w, "groups", JList.class);
      JTable articles = field(w, "articles", JTable.class);
      JTextArea body = field(w, "body", JTextArea.class);
      JTextField search = field(w, "articleSearch", JTextField.class);
      await(() -> groups.getModel().getSize() == 3 && articles.getRowCount() == 6);
      SwingUtilities.invokeAndWait(() -> articles.setRowSelectionInterval(0, 0));
      await(() -> body.getText().contains("From: Panx Guide"));
      SwingUtilities.invokeAndWait(() -> search.setText("Getting connected"));
      await(() -> articles.getRowCount() == 1);
      SwingUtilities.invokeAndWait(() -> articles.setRowSelectionInterval(0, 0));
      await(() -> body.getText().contains("You need an NNTP provider"));
      SwingUtilities.invokeAndWait(() -> search.setText(""));
      await(() -> articles.getRowCount() == 6);
      SwingUtilities.invokeAndWait(() -> {
        try {
          Method m = PanxWindow.class.getDeclaredMethod("compose", Article.class, String.class);
          m.setAccessible(true);
          m.invoke(w, null, null);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
      await(() -> java.util.Arrays.stream(w.getOwnedWindows()).anyMatch(Window::isVisible));
      SwingUtilities.invokeAndWait(() -> {
        for (Window child : w.getOwnedWindows()) child.dispose();
        articles.setRowSelectionInterval(0, 0);
      });
      await(() -> body.getText().contains("From:"));
      SwingUtilities.invokeAndWait(() -> {
        try {
          BufferedImage img =
              new BufferedImage(w.getWidth(), w.getHeight(), BufferedImage.TYPE_INT_RGB);
          Graphics2D g = img.createGraphics();
          w.paint(g);
          g.dispose();
          Path screenshot = Path.of("target", "panx-demo.png");
          ImageIO.write(img, "png", screenshot.toFile());
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      });
    } finally {
      if (frame[0] != null) SwingUtilities.invokeAndWait(frame[0]::closeResources);
      else store.close();
    }
  }
}
