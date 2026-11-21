package org.panx;

import javax.swing.*;
import java.awt.GraphicsEnvironment;
import java.nio.file.*;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        DesktopRendering.configure();
        Path data = Path.of(System.getProperty("user.home"), ".local", "share", "panx");
        boolean offline = false;
        try {
            if (Cli.dispatch(args)) return;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--help", "-h" -> { help(); return; }
                    case "--version" -> { System.out.println("Panx 1.0.0 (Java 21+)"); return; }
                    case "--data-dir" -> {
                        if (++i == args.length) throw new IllegalArgumentException("--data-dir needs a directory");
                        data = Path.of(args[i]);
                    }
                    case "--offline" -> offline = true;
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i] + "; use --help");
                }
            }
            if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Panx needs a desktop display. Run --help for options.");
            com.formdev.flatlaf.FlatLightLaf.setup();
            Store store = new Store(data);
            try {
                boolean startOffline = offline;
                SwingUtilities.invokeLater(() -> {
                    try { new PanxWindow(store, startOffline).setVisible(true); }
                    catch (Exception e) { try { store.close(); } catch (Exception ignored) { } fail(e); }
                });
            } catch (Exception e) { store.close(); throw e; }
        } catch (Exception e) { fail(e); }
    }

    private static void help() {
        System.out.println("""
                Usage: panx [options]
                Java desktop Usenet reader and poster. Requires Java 21 or newer.

                  --offline           Start without network access
                  --data-dir PATH     Set cache/settings directory
                                      (default: ~/.local/share/panx)
                  --help, -h          Show this help
                  --version           Show version

                Build: mvn package
                Run:   ./target/panx
                Add your provider through Servers → Add server.
                PANX_PASSWORD supplies a server password when none has been saved.
                JAVA_OPTS passes JVM options through the Bash launcher.
                """);
        Cli.help();
    }

    private static void fail(Exception e) {
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        System.err.println("Panx: " + message);
        if (!GraphicsEnvironment.isHeadless()) JOptionPane.showMessageDialog(null, message, "Panx", JOptionPane.ERROR_MESSAGE);
        System.exit(1);
    }
}
