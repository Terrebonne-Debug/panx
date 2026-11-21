package org.panx;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.awt.*;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "PANX_UI_TEST", matches = "1")
@Timeout(30)
class DesktopResizeTest {
    @TempDir Path data;
    @BeforeAll static void configureRendering() { DesktopRendering.configure(); }

    @Test void readerTracksWindowSizeAndPaintsNewlyExposedArea() throws Exception {
        Store store = new Store(data); TestData.seed(store);
        PanxWindow window = edt(() -> {
            com.formdev.flatlaf.FlatLightLaf.setup();
            var frame = new PanxWindow(store, true); frame.setLocation(30, 40); frame.setVisible(true); return frame;
        });
        try {
            Robot robot = new Robot(); robot.waitForIdle();
            for (Dimension size : new Dimension[]{new Dimension(1000, 700), new Dimension(1450, 1000), new Dimension(1100, 760)}) {
                edt(() -> { window.setSize(size); return null; });
                robot.waitForIdle(); robot.delay(200);
                edt(() -> {
                    Container content = window.getContentPane();
                    Insets border = window.getInsets();
                    assertEquals(window.getWidth() - border.left - border.right, content.getWidth());
                    assertEquals(window.getHeight() - border.top - border.bottom - window.getJMenuBar().getHeight(), content.getHeight());
                    Component center = ((BorderLayout) content.getLayout()).getLayoutComponent(BorderLayout.CENTER);
                    assertEquals(content.getWidth(), center.getWidth());
                    assertTrue(center.getHeight() > content.getHeight() - 120);
                    Point corner = center.getLocationOnScreen();
                    // The exposed lower-right area must be painted, not left as black X11 background.
                    Color pixel = robot.getPixelColor(corner.x + center.getWidth() - 10, corner.y + center.getHeight() - 10);
                    assertTrue(pixel.getRed() + pixel.getGreen() + pixel.getBlue() > 100, "New window area stayed black: " + size);
                    return null;
                });
            }
        } finally { edt(() -> { window.closeResources(); return null; }); }
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
