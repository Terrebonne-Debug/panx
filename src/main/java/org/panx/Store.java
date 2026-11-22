package org.panx;

import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.*;
import java.util.*;
import java.util.zip.*;

/** Indexed, transactional local cache. All methods serialize access to the SQLite connection. */
public final class Store implements AutoCloseable {
  public final Path directory;
  private final Connection db;
  private final FileChannel lockChannel;
  private final FileLock lock;
  private final Map<Long, String> sessionPasswords = new HashMap<>();

  public Store(Path directory) throws Exception {
    this.directory = directory.toAbsolutePath();
    boolean existed = Files.exists(this.directory);
    Files.createDirectories(this.directory);
    if (!existed)
      try {
        Files.setPosixFilePermissions(this.directory, PosixFilePermissions.fromString("rwx------"));
      } catch (UnsupportedOperationException ignored) {
      }
    lockChannel =
        FileChannel.open(
            this.directory.resolve("panx.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    FileLock acquired;
    try {
      acquired = lockChannel.tryLock();
    } catch (OverlappingFileLockException e) {
      acquired = null;
    }
    if (acquired == null) {
      lockChannel.close();
      throw new IOException(
          "Panx is already using "
              + this.directory
              + ". Use another data directory for a separate instance.");
    }
    lock = acquired;
    Connection opened = null;
    try {
      opened = DriverManager.getConnection("jdbc:sqlite:" + this.directory.resolve("panx.db"));
      db = opened;
      try {
        Files.setPosixFilePermissions(
            this.directory.resolve("panx.db"), PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
      }
      try (Statement s = db.createStatement()) {
        s.execute("PRAGMA journal_mode=WAL");
        s.execute("PRAGMA busy_timeout=10000");
        s.execute("PRAGMA foreign_keys=ON");
        s.execute(
            "CREATE TABLE IF NOT EXISTS servers(id INTEGER PRIMARY KEY, name TEXT NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, tls INTEGER NOT NULL, username TEXT NOT NULL, password TEXT NOT NULL, connections INTEGER NOT NULL)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS groups(server INTEGER NOT NULL REFERENCES servers(id) ON DELETE CASCADE, name TEXT NOT NULL, count INTEGER, first INTEGER, last INTEGER, posting TEXT, subscribed INTEGER DEFAULT 0, PRIMARY KEY(server,name))");
        s.execute(
            "CREATE TABLE IF NOT EXISTS articles(server INTEGER NOT NULL REFERENCES servers(id) ON DELETE CASCADE, grp TEXT NOT NULL, number INTEGER NOT NULL, subject TEXT, author TEXT, date TEXT, msgid TEXT, refs TEXT, bytes INTEGER, lines INTEGER, isread INTEGER DEFAULT 0, flagged INTEGER DEFAULT 0, PRIMARY KEY(server,grp,number))");
        s.execute("CREATE INDEX IF NOT EXISTS articles_msgid ON articles(server,msgid)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS bodies(server INTEGER NOT NULL REFERENCES servers(id) ON DELETE CASCADE, msgid TEXT NOT NULL, content BLOB NOT NULL, touched INTEGER NOT NULL, PRIMARY KEY(server,msgid))");
        s.execute("CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS rules(id INTEGER PRIMARY KEY, field TEXT NOT NULL, expression TEXT NOT NULL, score INTEGER NOT NULL)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS jobs(id INTEGER PRIMARY KEY, server INTEGER NOT NULL REFERENCES servers(id), name TEXT NOT NULL, ids TEXT NOT NULL, directory TEXT NOT NULL, state TEXT NOT NULL, detail TEXT NOT NULL)");
        s.execute(
            "CREATE TABLE IF NOT EXISTS drafts(id INTEGER PRIMARY KEY, content TEXT NOT NULL, created TEXT DEFAULT CURRENT_TIMESTAMP)");
      }
      transaction(() -> {
        try (var s = db.createStatement()) {
          // Remove only the retired built-in sample profile and its dependent data.
          s.executeUpdate(
              "DELETE FROM settings WHERE key='default-server' AND value IN (SELECT CAST(id AS TEXT) FROM servers WHERE host='demo.invalid')");
          s.executeUpdate(
              "DELETE FROM jobs WHERE server IN (SELECT id FROM servers WHERE host='demo.invalid')");
          s.executeUpdate("DELETE FROM servers WHERE host='demo.invalid'");
          s.executeUpdate(
              "UPDATE jobs SET state='Paused',detail='Interrupted; resume to retry' WHERE state IN ('Queued','Running')");
        }
      });
    } catch (Exception e) {
      if (opened != null) opened.close();
      lock.release();
      lockChannel.close();
      throw e;
    }
  }

  public synchronized List<Server> servers() throws SQLException {
    var result = new ArrayList<Server>();
    try (var s = db.createStatement();
        var r = s.executeQuery("SELECT * FROM servers ORDER BY id")) {
      while (r.next()) {
        long id = r.getLong("id");
        String password = sessionPasswords.getOrDefault(id, r.getString("password"));
        if (password.isEmpty())
          password = Objects.requireNonNullElse(System.getenv("PANX_PASSWORD"), "");
        result.add(
            new Server(
                id,
                r.getString("name"),
                r.getString("host"),
                r.getInt("port"),
                r.getBoolean("tls"),
                r.getString("username"),
                password,
                r.getInt("connections")));
      }
    }
    return result;
  }

  public synchronized Server saveServer(Server server, boolean rememberPassword)
      throws SQLException {
    long id = server.id();
    try (var p =
        db.prepareStatement(
            "INSERT INTO servers(id,name,host,port,tls,username,password,connections) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET name=excluded.name,host=excluded.host,port=excluded.port,tls=excluded.tls,username=excluded.username,password=excluded.password,connections=excluded.connections",
            Statement.RETURN_GENERATED_KEYS)) {
      if (id == 0) p.setNull(1, Types.INTEGER);
      else p.setLong(1, id);
      p.setString(2, server.name());
      p.setString(3, server.host());
      p.setInt(4, server.port());
      p.setBoolean(5, server.tls());
      p.setString(6, server.username());
      p.setString(7, rememberPassword ? server.password() : "");
      p.setInt(8, server.connections());
      p.executeUpdate();
      if (id == 0)
        try (var r = p.getGeneratedKeys()) {
          if (r.next()) id = r.getLong(1);
        }
    }
    sessionPasswords.put(id, server.password());
    return new Server(
        id,
        server.name(),
        server.host(),
        server.port(),
        server.tls(),
        server.username(),
        server.password(),
        server.connections());
  }

  public synchronized String setting(String key, String fallback) throws SQLException {
    try (var p = db.prepareStatement("SELECT value FROM settings WHERE key=?")) {
      p.setString(1, key);
      try (var r = p.executeQuery()) {
        return r.next() ? r.getString(1) : fallback;
      }
    }
  }

  public synchronized Server defaultServer() throws SQLException {
    var profiles = servers();
    String selected = setting("default-server", "");
    return profiles.stream()
        .filter(s -> Long.toString(s.id()).equals(selected))
        .findFirst()
        .orElse(profiles.isEmpty() ? null : profiles.getFirst());
  }

  public synchronized boolean isDefaultServer(long id) throws SQLException {
    return Long.toString(id).equals(setting("default-server", ""));
  }

  public synchronized boolean remembersPassword(long id) throws SQLException {
    try (var p = db.prepareStatement("SELECT password <> '' FROM servers WHERE id=?")) {
      p.setLong(1, id);
      try (var result = p.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    }
  }

  public synchronized Server saveServer(
      Server server, boolean rememberPassword, boolean startupDefault) throws SQLException {
    Server[] saved = new Server[1];
    transaction(() -> {
      saved[0] = saveServer(server, rememberPassword);
      if (startupDefault) saveSetting("default-server", Long.toString(saved[0].id()));
      else if (isDefaultServer(saved[0].id())) saveSetting("default-server", "");
    });
    return saved[0];
  }

  public synchronized void saveSetting(String key, String value) throws SQLException {
    try (var p =
        db.prepareStatement(
            "INSERT INTO settings VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
      p.setString(1, key);
      p.setString(2, value);
      p.executeUpdate();
    }
  }

  public synchronized void saveGroups(long server, List<NntpClient.Group> groups)
      throws SQLException {
    transaction(() -> {
      try (var p =
          db.prepareStatement(
              "INSERT INTO groups(server,name,count,first,last,posting) VALUES(?,?,?,?,?,?) ON CONFLICT(server,name) DO UPDATE SET count=excluded.count,first=excluded.first,last=excluded.last,posting=excluded.posting")) {
        for (var g : groups) {
          p.setLong(1, server);
          p.setString(2, g.name());
          p.setLong(3, g.count());
          p.setLong(4, g.first());
          p.setLong(5, g.last());
          p.setString(6, g.posting());
          p.addBatch();
        }
        p.executeBatch();
      }
    });
  }

  public synchronized List<NntpClient.Group> groups(long server, String query, boolean subscribed)
      throws SQLException {
    var result = new ArrayList<NntpClient.Group>();
    try (var p =
        db.prepareStatement(
            "SELECT * FROM groups WHERE server=? AND instr(lower(name),lower(?))>0"
                + (subscribed ? " AND subscribed=1" : "")
                + " ORDER BY name LIMIT 10000")) {
      p.setLong(1, server);
      p.setString(2, query);
      try (var r = p.executeQuery()) {
        while (r.next())
          result.add(
              new NntpClient.Group(
                  r.getString("name"),
                  r.getLong("count"),
                  r.getLong("first"),
                  r.getLong("last"),
                  r.getString("posting"),
                  r.getBoolean("subscribed")));
      }
    }
    return result;
  }

  public synchronized void subscribe(long server, String group, boolean value) throws SQLException {
    NntpClient.validateGroup(group);
    try (var p =
        db.prepareStatement(
            "INSERT INTO groups(server,name,subscribed) VALUES(?,?,?) ON CONFLICT(server,name) DO UPDATE SET subscribed=excluded.subscribed")) {
      p.setLong(1, server);
      p.setString(2, group);
      p.setBoolean(3, value);
      p.executeUpdate();
    }
  }

  public synchronized void saveArticles(long server, String group, List<Article> articles)
      throws SQLException {
    transaction(() -> {
      try (var p =
          db.prepareStatement(
              "INSERT INTO articles(server,grp,number,subject,author,date,msgid,refs,bytes,lines) VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(server,grp,number) DO UPDATE SET subject=excluded.subject,author=excluded.author,date=excluded.date,msgid=excluded.msgid,refs=excluded.refs,bytes=excluded.bytes,lines=excluded.lines")) {
        for (var a : articles) {
          p.setLong(1, server);
          p.setString(2, group);
          p.setLong(3, a.number());
          p.setString(4, a.subject());
          p.setString(5, a.from());
          p.setString(6, a.date());
          p.setString(7, a.messageId());
          p.setString(8, a.references());
          p.setLong(9, a.bytes());
          p.setLong(10, a.lines());
          p.addBatch();
        }
        p.executeBatch();
      }
    });
  }

  public synchronized List<Article> articles(
      long server, String group, String search, boolean unread, boolean flagged, int limit)
      throws SQLException {
    var result = new ArrayList<Article>();
    try (var p =
        db.prepareStatement(
            "SELECT * FROM articles WHERE server=? AND grp=? AND (instr(lower(subject),lower(?))>0 OR instr(lower(author),lower(?))>0)"
                + (unread ? " AND isread=0" : "")
                + (flagged ? " AND flagged=1" : "")
                + " ORDER BY number DESC LIMIT ?")) {
      p.setLong(1, server);
      p.setString(2, group);
      p.setString(3, search);
      p.setString(4, search);
      p.setInt(5, limit);
      try (var r = p.executeQuery()) {
        while (r.next())
          result.add(
              new Article(
                  r.getLong("number"),
                  r.getString("subject"),
                  r.getString("author"),
                  r.getString("date"),
                  r.getString("msgid"),
                  r.getString("refs"),
                  r.getLong("bytes"),
                  r.getLong("lines"),
                  r.getBoolean("isread"),
                  r.getBoolean("flagged")));
      }
    }
    return result;
  }

  public synchronized void mark(long server, String group, String id, boolean read, boolean flag)
      throws SQLException {
    try (var p =
        db.prepareStatement(
            "UPDATE articles SET isread=?,flagged=? WHERE server=? AND grp=? AND msgid=?")) {
      p.setBoolean(1, read);
      p.setBoolean(2, flag);
      p.setLong(3, server);
      p.setString(4, group);
      p.setString(5, id);
      p.executeUpdate();
    }
  }

  public synchronized void markGroup(long server, String group) throws SQLException {
    try (var p = db.prepareStatement("UPDATE articles SET isread=1 WHERE server=? AND grp=?")) {
      p.setLong(1, server);
      p.setString(2, group);
      p.executeUpdate();
    }
  }

  public synchronized long oldest(long server, String group) throws SQLException {
    try (var p = db.prepareStatement("SELECT min(number) FROM articles WHERE server=? AND grp=?")) {
      p.setLong(1, server);
      p.setString(2, group);
      try (var r = p.executeQuery()) {
        return r.next() ? r.getLong(1) : 0;
      }
    }
  }

  public synchronized void body(long server, String id, String raw) throws Exception {
    var out = new ByteArrayOutputStream();
    try (var zip = new GZIPOutputStream(out)) {
      zip.write(raw.getBytes(StandardCharsets.ISO_8859_1));
    }
    try (var p =
        db.prepareStatement(
            "INSERT INTO bodies VALUES(?,?,?,?) ON CONFLICT(server,msgid) DO UPDATE SET content=excluded.content,touched=excluded.touched")) {
      p.setLong(1, server);
      p.setString(2, id);
      p.setBytes(3, out.toByteArray());
      p.setLong(4, System.currentTimeMillis());
      p.executeUpdate();
    }
  }

  public synchronized String body(long server, String id) throws Exception {
    try (var p = db.prepareStatement("SELECT content FROM bodies WHERE server=? AND msgid=?")) {
      p.setLong(1, server);
      p.setString(2, id);
      try (var r = p.executeQuery()) {
        if (!r.next()) return null;
        try (var zip = new GZIPInputStream(new ByteArrayInputStream(r.getBytes(1)))) {
          return new String(zip.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
      }
    }
  }

  public synchronized void clearBodies() throws SQLException {
    try (var s = db.createStatement()) {
      s.executeUpdate("DELETE FROM bodies");
      s.execute("PRAGMA wal_checkpoint(TRUNCATE)");
      s.execute("VACUUM");
    }
  }

  public synchronized List<Rule> rules() throws SQLException {
    var list = new ArrayList<Rule>();
    try (var s = db.createStatement();
        var r = s.executeQuery("SELECT * FROM rules ORDER BY id")) {
      while (r.next())
        list.add(new Rule(r.getLong(1), r.getString(2), r.getString(3), r.getInt(4)));
    }
    return list;
  }

  public synchronized void rule(String field, String expression, int score) throws SQLException {
    java.util.regex.Pattern.compile(expression);
    if (!Set.of("subject", "from").contains(field))
      throw new IllegalArgumentException("Rule field must be subject or from");
    try (var p = db.prepareStatement("INSERT INTO rules(field,expression,score) VALUES(?,?,?)")) {
      p.setString(1, field);
      p.setString(2, expression);
      p.setInt(3, score);
      p.executeUpdate();
    }
  }

  public synchronized void deleteRule(long id) throws SQLException {
    try (var p = db.prepareStatement("DELETE FROM rules WHERE id=?")) {
      p.setLong(1, id);
      p.executeUpdate();
    }
  }

  public synchronized long addJob(long server, String name, String ids, Path directory)
      throws SQLException {
    try (var p =
        db.prepareStatement(
            "INSERT INTO jobs(server,name,ids,directory,state,detail) VALUES(?,?,?,?,'Paused','Ready to start')",
            Statement.RETURN_GENERATED_KEYS)) {
      p.setLong(1, server);
      p.setString(2, name);
      p.setString(3, ids);
      p.setString(4, directory.toAbsolutePath().toString());
      p.executeUpdate();
      try (var r = p.getGeneratedKeys()) {
        return r.next() ? r.getLong(1) : 0;
      }
    }
  }

  public synchronized List<Job> jobs() throws SQLException {
    var list = new ArrayList<Job>();
    try (var s = db.createStatement();
        var r = s.executeQuery("SELECT * FROM jobs ORDER BY id DESC")) {
      while (r.next())
        list.add(
            new Job(
                r.getLong("id"),
                r.getLong("server"),
                r.getString("name"),
                r.getString("ids"),
                r.getString("directory"),
                r.getString("state"),
                r.getString("detail")));
    }
    return list;
  }

  public synchronized void jobState(long id, String state, String detail) throws SQLException {
    try (var p = db.prepareStatement("UPDATE jobs SET state=?,detail=? WHERE id=?")) {
      p.setString(1, state);
      p.setString(2, detail);
      p.setLong(3, id);
      p.executeUpdate();
    }
  }

  public synchronized void draft(String raw) throws SQLException {
    try (var p = db.prepareStatement("INSERT INTO drafts(content) VALUES(?)")) {
      p.setString(1, raw);
      p.executeUpdate();
    }
  }

  public synchronized List<String[]> drafts() throws SQLException {
    var list = new ArrayList<String[]>();
    try (var s = db.createStatement();
        var r = s.executeQuery("SELECT id,content FROM drafts ORDER BY id DESC")) {
      while (r.next()) list.add(new String[] {r.getString(1), r.getString(2)});
    }
    return list;
  }

  private void transaction(SqlWork work) throws SQLException {
    db.setAutoCommit(false);
    try {
      work.run();
      db.commit();
    } catch (SQLException | RuntimeException e) {
      db.rollback();
      throw e;
    } finally {
      db.setAutoCommit(true);
    }
  }

  @Override
  public synchronized void close() throws SQLException {
    try {
      db.close();
    } finally {
      try {
        lock.release();
        lockChannel.close();
      } catch (IOException e) {
        throw new SQLException("Could not release data directory lock", e);
      }
    }
  }

  @FunctionalInterface
  private interface SqlWork {
    void run() throws SQLException;
  }

  public record Rule(long id, String field, String expression, int score) {}

  public record Job(
      long id,
      long server,
      String name,
      String ids,
      String directory,
      String state,
      String detail) {}
}
