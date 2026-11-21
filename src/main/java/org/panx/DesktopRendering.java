package org.panx;

import java.util.Locale;

/** Set before AWT initializes; avoid stale accelerated surfaces on Linux/X11 desktops. */
final class DesktopRendering {
    private DesktopRendering() { }

    static void configure() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
            System.getProperties().putIfAbsent("sun.java2d.xrender", "false");
            System.getProperties().putIfAbsent("sun.java2d.pmoffscreen", "false");
        }
    }
}
