package org.panx;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsTest {
  @TempDir Path dir;

  @Test
  void tlsAcceptsTrustedHostnameAndRejectsWrongHostname() throws Exception {
    Path file = dir.resolve("fixture.p12");
    Process keytool =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair",
                "-alias",
                "fixture",
                "-keyalg",
                "RSA",
                "-keysize",
                "2048",
                "-validity",
                "2",
                "-dname",
                "CN=localhost",
                "-ext",
                "SAN=dns:localhost",
                "-storetype",
                "PKCS12",
                "-keystore",
                file.toString(),
                "-storepass",
                "changeit",
                "-keypass",
                "changeit")
            .redirectErrorStream(true)
            .start();
    assertTrue(keytool.waitFor(20, TimeUnit.SECONDS));
    assertEquals(0, keytool.exitValue(), new String(keytool.getInputStream().readAllBytes()));
    KeyStore keys = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(file)) {
      keys.load(in, "changeit".toCharArray());
    }
    KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    km.init(keys, "changeit".toCharArray());
    TrustManagerFactory tm =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tm.init(keys);
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(km.getKeyManagers(), tm.getTrustManagers(), new SecureRandom());
    SSLContext previous = SSLContext.getDefault();
    SSLContext.setDefault(context);
    try (var server =
        new FakeNewsServer(
            context
                .getServerSocketFactory()
                .createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")))) {
      Server good =
          new Server(
              0,
              "TLS fixture",
              "localhost",
              server.listener.getLocalPort(),
              true,
              "reader",
              "secret",
              1);
      try (var client = new NntpClient(good)) {
        assertEquals(2, client.listGroups().size());
      }
      Server wrong =
          new Server(
              0,
              "TLS wrong hostname",
              "127.0.0.1",
              server.listener.getLocalPort(),
              true,
              "reader",
              "secret",
              1);
      assertThrows(SSLHandshakeException.class, () -> new NntpClient(wrong));
    } finally {
      SSLContext.setDefault(previous);
    }
  }
}
