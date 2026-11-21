package org.panx;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class NewsService implements AutoCloseable {
  public final Store store;
  private final Map<Long, Pool> pools = new ConcurrentHashMap<>();
  private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
  private final ConcurrentHashMap<String, CompletableFuture<String>> fetching =
      new ConcurrentHashMap<>();
  private volatile boolean closed;

  public NewsService(Store store) {
    this.store = store;
  }

  private <T> T session(Server server, Operation<T> work) throws Exception {
    if (closed) throw new IOException("Service is closed");
    Pool pool = pool(server);
    pool.slots.acquire();
    NntpClient c = null;
    boolean good = false;
    try {
      if (closed) throw new IOException("Service is closed");
      if (pool.closed) throw new IOException("Server settings changed; retry the operation");
      c = pool.idle.poll();
      if (c == null) {
        pool.lifecycle.readLock().lockInterruptibly();
        try {
          if (closed || pool.closed) throw new IOException("Server connection pool is closed");
          c = new NntpClient(server);
          pool.all.add(c);
        } finally {
          pool.lifecycle.readLock().unlock();
        }
      }
      T value = work.run(c);
      good = true;
      return value;
    } finally {
      if (c != null) {
        if (good && !closed && !pool.closed) pool.idle.offer(c);
        else {
          pool.all.remove(c);
          try {
            c.close();
          } catch (IOException ignored) {
          }
        }
      }
      pool.slots.release();
    }
  }

  private Pool pool(Server server) throws IOException {
    Pool pool = pools.computeIfAbsent(server.id(), id -> new Pool(server));
    if (!pool.server.equals(server))
      throw new IOException("Server settings changed; retry using the updated server");
    return pool;
  }

  /** Close old sockets before opening any connections with a changed configuration. */
  public void configureServer(Server server) {
    pools.compute(
        server.id(),
        (id, old) -> {
          if (old != null && old.server.equals(server)) return old;
          if (old != null) old.close();
          return new Pool(server);
        });
  }

  public int refreshGroups(Server server) throws Exception {
    var groups = session(server, NntpClient::listGroups);
    store.saveGroups(server.id(), groups);
    return groups.size();
  }

  public int headers(Server server, String group, int limit, boolean older) throws Exception {
    if (limit < 1 || limit > 100000)
      throw new IllegalArgumentException("Header batch must be 1–100000");
    var info = session(server, c -> c.group(group));
    store.saveGroups(server.id(), List.of(info));
    long last = info.last();
    if (info.count() == 0 || last == 0) return 0;
    if (older) {
      long oldest = store.oldest(server.id(), group);
      if (oldest > 0) last = Math.min(last, oldest - 1);
    }
    long first = Math.max(info.first(), last - limit + 1);
    if (last < first) return 0;
    List<Future<List<Article>>> tasks = new ArrayList<>();
    for (long begin = first; begin <= last; begin += 1000) {
      long from = begin, to = Math.min(last, begin + 999);
      tasks.add(
          workers.submit(() ->
              session(
                  server,
                  c -> {
                    c.group(group);
                    return c.overview(from, to);
                  })));
    }
    int count = 0;
    try {
      for (var future : tasks) {
        var batch = future.get();
        store.saveArticles(server.id(), group, batch);
        count += batch.size();
      }
    } catch (Exception e) {
      for (var task : tasks) task.cancel(true);
      throw unwrap(e);
    }
    return count;
  }

  public String article(Server server, String id, boolean offline) throws Exception {
    NntpClient.validateId(id);
    String cached = store.body(server.id(), id);
    if (cached != null) return cached;
    if (offline)
      throw new IOException("This article is not cached. Turn off Offline to download it.");
    String key = server.id() + ":" + id;
    var own = new CompletableFuture<String>();
    var existing = fetching.putIfAbsent(key, own);
    if (existing != null)
      try {
        return existing.get();
      } catch (ExecutionException e) {
        throw unwrap(e);
      }
    try {
      String raw = store.body(server.id(), id);
      if (raw == null) {
        raw = session(server, c -> c.article(id));
        store.body(server.id(), id, raw);
      }
      own.complete(raw);
      return raw;
    } catch (Exception e) {
      own.completeExceptionally(e);
      throw e;
    } finally {
      fetching.remove(key, own);
    }
  }

  @FunctionalInterface
  private interface Operation<T> {
    T run(NntpClient c) throws Exception;
  }

  private static final class Pool implements AutoCloseable {
    final Server server;
    final java.util.concurrent.locks.ReentrantReadWriteLock lifecycle =
        new java.util.concurrent.locks.ReentrantReadWriteLock();
    volatile boolean closed;
    final Semaphore slots;
    final ConcurrentLinkedQueue<NntpClient> idle = new ConcurrentLinkedQueue<>();
    final Set<NntpClient> all = ConcurrentHashMap.newKeySet();

    Pool(Server server) {
      this.server = server;
      slots = new Semaphore(server.connections(), true);
    }

    @Override
    public void close() {
      lifecycle.writeLock().lock();
      try {
        closed = true;
        for (var c : all)
          try {
            c.close();
          } catch (IOException ignored) {
          }
        idle.clear();
        all.clear();
      } finally {
        lifecycle.writeLock().unlock();
      }
    }
  }

  public void post(Server server, String raw) throws Exception {
    session(
        server,
        c -> {
          c.post(raw);
          return null;
        });
  }

  private static Exception unwrap(Exception e) {
    return e instanceof ExecutionException && e.getCause() instanceof Exception cause ? cause : e;
  }

  @Override
  public void close() {
    closed = true;
    workers.shutdownNow();
    pools.values().forEach(Pool::close);
  }
}
