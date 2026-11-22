package org.panx;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupProfileTest {
  @TempDir Path data;

  private Server profile(String name) {
    return new Server(0, name, name + ".invalid", 563, true, "", "", 40);
  }

  @Test
  void defaultPersistsCanBeReplacedAndCanBeUnset() throws Exception {
    Server first, second;
    try (var store = new Store(data)) {
      assertNull(store.defaultServer());
      assertTrue(store.servers().isEmpty());
      first = store.saveServer(profile("first"), false, true);
      second = store.saveServer(profile("second"), false, true);
      assertFalse(store.isDefaultServer(first.id()));
      assertTrue(store.isDefaultServer(second.id()));
      store.saveServer(first, false, false); // Editing a non-default must not clear the default.
      assertEquals(second.id(), store.defaultServer().id());
    }
    try (var store = new Store(data)) {
      assertEquals(second.id(), store.defaultServer().id());
      store.saveServer(second, false, false);
      assertFalse(store.isDefaultServer(second.id()));
      assertEquals(first.id(), store.defaultServer().id());
      store.saveSetting("default-server", "999999");
      assertEquals(first.id(), store.defaultServer().id());
    }
  }

  @Test
  void upgradeRemovesOldSampleProfileAndItsJobsButPreservesRealData() throws Exception {
    long real;
    try (var store = new Store(data)) {
      var demo =
          store.saveServer(
              new Server(0, "Local demo", "demo.invalid", 119, false, "", "", 2), false, true);
      var provider = store.saveServer(profile("provider"), false);
      real = provider.id();
      for (var server : List.of(demo, provider)) {
        store.subscribe(server.id(), "comp.test", true);
        store.saveArticles(
            server.id(),
            "comp.test",
            List.of(
                new Article(1, "Saved", "Author", "Today", "<saved@x>", "", 10, 1, true, true)));
        store.mark(server.id(), "comp.test", "<saved@x>", true, true);
        store.body(server.id(), "<saved@x>", "Subject: Saved\r\n\r\nBody\r\n");
        store.addJob(server.id(), "Saved", "<saved@x>", data.resolve("out"));
      }
    }
    try (var store = new Store(data)) {
      assertEquals(1, store.servers().size());
      assertEquals(real, store.servers().getFirst().id());
      assertEquals(real, store.defaultServer().id());
      assertEquals("", store.setting("default-server", ""));
      assertEquals(1, store.jobs().size());
      assertEquals(real, store.jobs().getFirst().server());
      assertTrue(store.groups(real, "", true).getFirst().subscribed());
      assertTrue(store.articles(real, "comp.test", "", false, true, 10).getFirst().read());
      assertNotNull(store.body(real, "<saved@x>"));
    }
  }

  @Test
  void removedDemoFlagIsRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> Cli.run(new String[] {"--demo", "groups", "--data-dir", data.toString()}));
  }

  @Test
  void editsEndpointAndCredentialsInPlaceAndPreservesDefaultAcrossRestart() throws Exception {
    Server edited;
    try (var store = new Store(data)) {
      var original = store.saveServer(profile("original"), false, true);
      store.subscribe(original.id(), "comp.test", true);
      edited =
          new Server(
              original.id(),
              "Updated",
              "new-host.invalid",
              119,
              false,
              "new-user",
              "new-password",
              20);
      assertEquals(edited, store.saveServer(edited, true, true));
    }
    try (var store = new Store(data)) {
      assertEquals(List.of(edited), store.servers());
      assertEquals(edited, store.defaultServer());
      assertTrue(store.groups(edited.id(), "comp.test", true).getFirst().subscribed());
    }
  }
}
