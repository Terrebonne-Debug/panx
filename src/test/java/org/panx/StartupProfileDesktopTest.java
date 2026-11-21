package org.panx;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "PANX_UI_TEST", matches = "1")
@Timeout(30)
class StartupProfileDesktopTest {
    @TempDir Path data;
    @BeforeAll static void rendering() { DesktopRendering.configure(); }

    @Test void startsOnSavedDefaultAndEditorCanUnsetIt() throws Exception {
        Server selected;
        try (var store = new Store(data)) {
            store.saveServer(new Server(0,"First","first.invalid",563,true,"","",40),false);
            selected = store.saveServer(new Server(0,"Second","second.invalid",563,true,"reader","saved-password",40),true,true);
        }
        Store store = new Store(data);
        PanxWindow window = edt(() -> { com.formdev.flatlaf.FlatLightLaf.setup(); var w = new PanxWindow(store, true); w.setVisible(true); return w; });
        try {
            await(() -> {
                JComboBox<?> combo = find(window, JComboBox.class, c -> c.getSelectedItem() instanceof Server);
                return combo != null && ((Server) combo.getSelectedItem()).id() == selected.id();
            });
            SwingUtilities.invokeLater(() -> {
                try { var method = PanxWindow.class.getDeclaredMethod("editServer", boolean.class); method.setAccessible(true); method.invoke(window, true); }
                catch (Exception e) { throw new RuntimeException(e); }
            });
            await(() -> java.util.Arrays.stream(window.getOwnedWindows()).anyMatch(Window::isVisible));
            edt(() -> {
                JDialog dialog = (JDialog) java.util.Arrays.stream(window.getOwnedWindows()).filter(Window::isVisible).findFirst().orElseThrow();
                JCheckBox checkbox = find(dialog, JCheckBox.class, c -> c.getText().equals("Use by default at startup"));
                assertNotNull(checkbox); assertTrue(checkbox.isSelected()); checkbox.setSelected(false);
                JCheckBox remember = find(dialog, JCheckBox.class, c -> c.getText().startsWith("Remember password"));
                assertNotNull(remember); assertTrue(remember.isSelected());
                JButton ok = find(dialog, JButton.class, b -> b.getText().equals("OK")); assertNotNull(ok); ok.doClick(); return null;
            });
            await(() -> !store.isDefaultServer(selected.id()));
            assertEquals("First", store.defaultServer().name());
            assertTrue(store.remembersPassword(selected.id()));
        } finally { edt(() -> { for (Window child : window.getOwnedWindows()) child.dispose(); window.closeResources(); return null; }); }
    }

    private static <T extends Component> T find(Container root, Class<T> type, Predicate<T> test) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && test.test(type.cast(c))) return type.cast(c);
            if (c instanceof Container nested) { T match = find(nested, type, test); if (match != null) return match; }
        }
        return null;
    }
    private static <T> T edt(Callable<T> action) throws Exception { var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get(); }
    private static void await(Callable<Boolean> test) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) { if (edt(test)) return; Thread.sleep(30); }
        fail("Timed out waiting for server selection");
    }
}
