package org.panx;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Persistent file jobs. A resumed job reuses already cached articles and verifies every yEnc part.
 */
public final class DownloadQueue implements AutoCloseable {
  private final Store store;
  private final NewsService news;
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final Map<Long, Future<?>> running = new ConcurrentHashMap<>();
  private final Set<Long> paused = ConcurrentHashMap.newKeySet();
  private final Semaphore files = new Semaphore(3, true);
  private volatile boolean closed;

  public DownloadQueue(Store store, NewsService news) {
    this.store = store;
    this.news = news;
  }

  public long add(Server server, String title, List<String> ids, Path directory) throws Exception {
    for (String id : ids) NntpClient.validateId(id);
    if (ids.isEmpty()) throw new IOException("No segments");
    return store.addJob(server.id(), title, String.join("\n", ids), directory);
  }

  public synchronized void start(long id) throws Exception {
    if (closed || running.containsKey(id)) return;
    Store.Job job = store.jobs().stream().filter(j -> j.id() == id).findFirst().orElseThrow();
    if (job.state().equals("Complete")) return;
    Server server =
        store.servers().stream().filter(s -> s.id() == job.server()).findFirst().orElseThrow();
    paused.remove(id);
    store.jobState(id, "Queued", "");
    var task =
        new FutureTask<Void>(
            () -> {
              run(job, server);
              return null;
            }) {
          @Override
          protected void done() {
            running.remove(id, this);
          }
        };
    running.put(id, task);
    workers.execute(task);
  }

  public void pause(long id) throws Exception {
    if (!running.containsKey(id)) return;
    paused.add(id);
    store.jobState(id, "Paused", "Pausing after the current segment");
  }

  private void check(long id) throws InterruptedException {
    if (closed || paused.contains(id) || Thread.currentThread().isInterrupted())
      throw new InterruptedException("Paused");
  }

  private void run(Store.Job job, Server server) {
    Path temp = null;
    boolean acquired = false;
    var prefetched = new ArrayDeque<Future<?>>();
    try {
      files.acquire();
      acquired = true;
      check(job.id());
      store.jobState(job.id(), "Running", "Starting");
      Path directory = Path.of(job.directory());
      Files.createDirectories(directory);
      List<String> ids = job.ids().lines().filter(s -> !s.isBlank()).toList();
      temp = Files.createTempFile(directory, ".panx-" + job.id() + "-", ".part");
      String name = null;
      long next = 1, total = -1;
      boolean yenc = false;
      var fileCrc = new java.util.zip.CRC32();
      Long expectedCrc = null;
      int window = Math.min(server.connections(), ids.size());
      for (int i = 0; i < window; i++) prefetched.addLast(prefetch(job.id(), server, ids.get(i)));
      try (var out = Files.newOutputStream(temp)) {
        for (int i = 0; i < ids.size(); i++) {
          check(job.id());
          prefetched.removeFirst().get();
          check(job.id());
          if (i + window < ids.size()) prefetched.addLast(prefetch(job.id(), server, ids.get(i + window)));
          String raw = store.body(server.id(), ids.get(i));
          if (raw == null) throw new IOException("Segment was removed from cache during download; resume to retry");
          if (raw.contains("=ybegin ")) {
            var part = Mime.decodeYenc(raw);
            if (i == 0) {
              name = part.name();
              total = part.total();
              yenc = true;
            }
            if (!yenc || !part.name().equals(name) || part.total() != total || part.begin() != next)
              throw new IOException(
                  "yEnc segments are missing, reordered, or belong to different files");
            out.write(part.bytes());
            fileCrc.update(part.bytes());
            if (part.fileCrc32() != null) {
              if (expectedCrc != null && !expectedCrc.equals(part.fileCrc32()))
                throw new IOException("Conflicting yEnc file checksums");
              expectedCrc = part.fileCrc32();
            }
            next = part.end() + 1;
          } else {
            if (ids.size() != 1)
              throw new IOException("Multipart NZB assembly currently requires yEnc");
            var decoded = Mime.decode(raw);
            if (!decoded.attachments().isEmpty()) {
              List<Path> saved = new ArrayList<>();
              for (var a : decoded.attachments()) {
                check(job.id());
                saved.add(Mime.saveAttachment(directory, a));
              }
              store.jobState(
                  job.id(), "Complete", "Saved " + saved.size() + " attachment(s) to " + directory);
              return;
            }
            name = "article-" + job.id() + ".eml";
            out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
          }
          store.jobState(job.id(), "Running", (i + 1) + " / " + ids.size() + " segments");
        }
      }
      check(job.id());
      if (yenc && next != total + 1) throw new IOException("Incomplete yEnc file");
      if (expectedCrc != null && fileCrc.getValue() != expectedCrc)
        throw new IOException("yEnc file CRC32 mismatch");
      Path destination = null;
      String safe = Mime.safeName(name == null ? "download.bin" : name);
      for (int i = 0; i < 10000; i++) {
        Path candidate = directory.resolve(i == 0 ? safe : i + "-" + safe);
        try {
          Files.move(temp, candidate);
          destination = candidate;
          temp = null;
          break;
        } catch (FileAlreadyExistsException ignored) {
        }
      }
      if (destination == null) throw new IOException("Too many duplicate filenames");
      store.jobState(job.id(), "Complete", destination.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      state(job.id(), "Paused", "Resume to continue using cached segments");
    } catch (Exception e) {
      Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
      state(
          job.id(),
          closed || paused.contains(job.id()) ? "Paused" : "Failed",
          Objects.requireNonNullElse(cause.getMessage(), cause.getClass().getSimpleName()));
    } finally {
      for (var pending : prefetched) pending.cancel(true);
      if (temp != null)
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
        }
      if (acquired) files.release();
    }
  }

  /** Futures retain no article bodies; the bounded lookahead is persisted in the compressed cache. */
  private Future<?> prefetch(long job, Server server, String id) {
    return workers.submit(() -> { check(job); news.article(server, id, false); check(job); return null; });
  }

  private void state(long id, String state, String detail) {
    try {
      store.jobState(id, state, detail);
    } catch (Exception ignored) {
    }
  }

  public boolean active() {
    return !running.isEmpty();
  }

  @Override
  public void close() {
    closed = true;
    workers.shutdownNow();
    try {
      workers.awaitTermination(3, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
