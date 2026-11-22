package org.panx;

import java.awt.*;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import javax.swing.*;

/** Tests a snapshot of unsaved settings without blocking the dialog or writing to the store. */
final class ConnectionTestPanel extends JPanel {
  private final JButton test = new JButton("Test connection");
  private final JTextArea result = new JTextArea("Not tested", 2, 44);
  private long revision;
  private SwingWorker<String, Void> running;

  ConnectionTestPanel(Callable<Server> settings) {
    super(new BorderLayout(8, 4));
    result.setEditable(false);
    result.setOpaque(false);
    result.setLineWrap(true);
    result.setWrapStyleWord(true);
    result.setFont(UIManager.getFont("Label.font"));
    add(test, BorderLayout.WEST);
    add(result, BorderLayout.CENTER);
    test.addActionListener(event -> {
      Server candidate;
      try {
        candidate = settings.call();
      } catch (Exception error) {
        result.setText("Check settings: " + error.getMessage());
        return;
      }
      long testedRevision = revision;
      test.setEnabled(false);
      result.setText("Connecting and checking login…");
      running =
          new SwingWorker<>() {
            @Override
            protected String doInBackground() throws Exception {
              // A single fresh connection verifies TCP, optional TLS and optional AUTHINFO.
              try (var client = new NntpClient(candidate)) {
                return "Connection successful. "
                    + (candidate.tls() ? "TLS verified. " : "")
                    + (candidate.username().isBlank() ? "No login supplied." : "Login accepted.");
              }
            }

            @Override
            protected void done() {
              test.setEnabled(true);
              if (isCancelled() || testedRevision != revision || !isDisplayable()) return;
              try {
                result.setText(get());
              } catch (CancellationException ignored) {
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
              } catch (ExecutionException error) {
                Throwable cause = error.getCause();
                if (cause instanceof NntpClient.ProtocolException protocol)
                  result.setText(
                      "Connection failed: server rejected the connection or login ("
                          + protocol.code
                          + ").");
                else
                  result.setText(
                      "Connection failed: "
                          + Objects.requireNonNullElse(cause.getMessage(), cause.toString()));
              }
            }
          };
      running.execute();
    });
  }

  void settingsChanged() {
    revision++;
    result.setText("Settings changed; test again.");
  }

  @Override
  public void removeNotify() {
    if (running != null) running.cancel(true);
    super.removeNotify();
  }
}
