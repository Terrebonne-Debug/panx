package org.panx;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.Predicate;
import javax.swing.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfEnvironmentVariable(named = "PANX_UI_TEST", matches = "1")
@Timeout(30)
class ConnectionTestDesktopTest {
  @TempDir Path data;

  @BeforeAll
  static void rendering() {
    DesktopRendering.configure();
  }

  private static Component field(Container root, String label) {
    JLabel found = find(root, JLabel.class, c -> label.equals(c.getText()));
    assertNotNull(found, label);
    Component[] children = found.getParent().getComponents();
    for (int i = 0; i < children.length - 1; i++) if (children[i] == found) return children[i + 1];
    throw new AssertionError("No field for " + label);
  }

  private static <T extends Component> T find(Container root, Class<T> type, Predicate<T> test) {
    for (Component c : root.getComponents()) {
      if (type.isInstance(c) && test.test(type.cast(c))) return type.cast(c);
      if (c instanceof Container child) {
        T match = find(child, type, test);
        if (match != null) return match;
      }
    }
    return null;
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
    fail("Timed out waiting for connection-test state");
  }

  @Test
  void testsUnsavedDialogSettingsWithoutBlockingOrSavingAndReportsLoginFailure() throws Exception {
    try (var fixture = new FakeNewsServer()) {
      fixture.authArrivals = new CountDownLatch(1);
      fixture.authRelease = new CountDownLatch(1);
      Store store = new Store(data);
      PanxWindow window =
          edt(() -> {
            com.formdev.flatlaf.FlatLightLaf.setup();
            var w = new PanxWindow(store, true);
            w.setVisible(true);
            return w;
          });
      try {
        SwingUtilities.invokeLater(() -> {
          try {
            var method = PanxWindow.class.getDeclaredMethod("editServer", boolean.class);
            method.setAccessible(true);
            method.invoke(window, false);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
        await(() -> java.util.Arrays.stream(window.getOwnedWindows()).anyMatch(Window::isVisible));
        JDialog dialog =
            edt(() ->
                (JDialog)
                    java.util.Arrays.stream(window.getOwnedWindows())
                        .filter(Window::isVisible)
                        .findFirst()
                        .orElseThrow());
        ConnectionTestPanel panel = edt(() -> find(dialog, ConnectionTestPanel.class, c -> true));
        JButton test =
            edt(() -> find(panel, JButton.class, b -> b.getText().equals("Test connection")));
        JTextArea result = edt(() -> find(panel, JTextArea.class, c -> true));
        edt(() -> {
          ((JTextField) field(dialog, "Host")).setText("localhost");
          ((JSpinner) field(dialog, "Port")).setValue(fixture.listener.getLocalPort());
          find(dialog, JCheckBox.class, c -> c.getText().startsWith("TLS")).setSelected(false);
          ((JTextField) field(dialog, "Username")).setText("reader");
          ((JPasswordField) field(dialog, "Password")).setText("secret");
          test.doClick();
          return null;
        });
        assertTrue(fixture.authArrivals.await(5, TimeUnit.SECONDS));
        edt(() -> {
          assertFalse(test.isEnabled());
          assertTrue(find(dialog, JButton.class, b -> b.getText().equals("Cancel")).isEnabled());
          assertTrue(result.getText().contains("Connecting"));
          return null;
        });
        assertTrue(store.servers().isEmpty(), "Testing must not save the profile");
        fixture.authRelease.countDown();
        await(() -> result.getText().contains("Login accepted"));
        await(() -> fixture.sockets.isEmpty());
        edt(() -> {
          ((JPasswordField) field(dialog, "Password")).setText("wrong-password");
          assertTrue(result.getText().contains("test again"));
          test.doClick();
          return null;
        });
        await(() -> result.getText().contains("Connection failed"));
        edt(() -> {
          assertFalse(result.getText().contains("wrong-password"));
          assertTrue(test.isEnabled());
          return null;
        });
        assertTrue(store.servers().isEmpty());
        edt(() -> {
          find(dialog, JButton.class, b -> b.getText().equals("Cancel")).doClick();
          return null;
        });
        assertTrue(store.servers().isEmpty());
      } finally {
        fixture.authRelease.countDown();
        edt(() -> {
          for (Window child : window.getOwnedWindows()) child.dispose();
          window.closeResources();
          return null;
        });
      }
    }
  }

  @Test
  void inFlightResultCannotValidateChangedSettings() throws Exception {
    try (var fixture = new FakeNewsServer()) {
      fixture.authArrivals = new CountDownLatch(1);
      fixture.authRelease = new CountDownLatch(1);
      JFrame window =
          edt(() -> {
            var frame = new JFrame();
            frame.add(new ConnectionTestPanel(fixture::profile));
            frame.pack();
            frame.setVisible(true);
            return frame;
          });
      try {
        ConnectionTestPanel panel = edt(() -> find(window, ConnectionTestPanel.class, c -> true));
        JButton test = edt(() -> find(panel, JButton.class, c -> true));
        JTextArea result = edt(() -> find(panel, JTextArea.class, c -> true));
        edt(() -> {
          test.doClick();
          return null;
        });
        assertTrue(fixture.authArrivals.await(5, TimeUnit.SECONDS));
        edt(() -> {
          panel.settingsChanged();
          return null;
        });
        fixture.authRelease.countDown();
        await(test::isEnabled);
        edt(() -> {
          assertEquals("Settings changed; test again.", result.getText());
          return null;
        });
      } finally {
        fixture.authRelease.countDown();
        edt(() -> {
          window.dispose();
          return null;
        });
      }
    }
  }
}
