# Panx

Panx is a Java desktop Usenet reader and poster inspired by [PAN](https://pan.rebelbase.com/). It includes a three-pane reader, a local SQLite cache, a posting composer, a persistent download queue, and headless command-line tools.

This is an independent implementation. It is usable for the workflows below, but does **not** yet implement every feature of the mature PAN project. Performance against PAN has not been benchmarked.

## Build and run

Install a JDK version 21 or newer and Maven 3.9 or newer. The first build downloads dependencies from Maven Central.

```bash
mvn package
./target/panx
```

Maven runs the tests, packages dependencies into `target/panx.jar`, and creates an executable Bash launcher at `target/panx`. Keep those two files together when copying the application. A Java runtime is still required; this is not a native machine-code binary.

```bash
java -jar target/panx.jar
./target/panx --offline
./target/panx --help
```

The desktop needs a graphical display. Headless commands work over SSH and in terminals without a display. `JAVA_OPTS` passes JVM options through the Bash launcher, for example `JAVA_OPTS='-Xmx1g' ./target/panx`.

On Linux, Panx defaults to the basic X11 rendering path and system-memory offscreen surfaces to avoid black or stale areas during resizing on affected graphics drivers. It also refreshes the full client area on resize. Explicit JVM settings take precedence; use `JAVA_OPTS='-Dsun.java2d.xrender=true -Dsun.java2d.pmoffscreen=true'` to opt into accelerated surfaces. These rendering settings also apply when running the JAR directly.

## Connect to Usenet

The first desktop launch starts with an empty server list. Existing built-in demo profiles and their sample data are removed automatically on startup.

1. Choose **Servers → Add server** and enter your NNTP provider's hostname, port, username, and password. Implicit TLS normally uses port **563**; plain NNTP normally uses **119**. Select **Use by default at startup** to open that profile automatically on each launch. The option is also available under **Servers → Edit selected server**; choosing it replaces the previous default. Without an explicit default, the first configured profile is selected. CLI commands also use this default unless `--server` is supplied.
2. Set the connection limit to your provider's allowance. Panx supports **1–40 connections per server profile**. New profiles default to **40**; reduce this if your plan permits fewer. Multiple profiles for the same provider and other Usenet applications may count against the same account limit.
3. Select the server and turn off **Offline**. Click **Get groups** and filter the list. Select a group and click **Get headers**. **Go to…** opens a known group by name without first downloading the full group list.
4. **Subscribe / remove** controls your local subscriptions. Enable **Subscribed** to show just those groups. **Older** fetches a batch preceding the oldest cached header.
5. Choose **Tools → Identity and signature** before posting. Use **New post** or **Reply** to compose, attach files, and save drafts. Posting requires an explicit confirmation.

The **Test connection** button in the Add/Edit server dialog checks the current, unsaved hostname, port, TLS settings, and credentials using one fresh NNTP connection. Results appear inside the dialog while it remains responsive. A successful test verifies the connection and supplied login; it does not check article retention or posting permission, save the profile, fetch groups, or send an article. Editing the settings clears the previous test result. Click **OK** to save, or **Cancel** to discard the profile.

All server settings, including hostname, port, TLS, and username, can be changed directly under **Servers → Edit selected server** and saved with **OK**. The existing profile and startup-default choice are retained; active connections are replaced with connections using the saved settings.

Passwords remain in memory unless **Remember password** is selected. Remembered passwords are stored **unencrypted** in the local database. Alternatively, export `PANX_PASSWORD` before starting Panx. The environment value is used when no session or saved password is available. TLS uses the Java trust store and verifies the server's hostname; there is no insecure-certificate bypass.

## Included workflows

| Area | Implemented |
| --- | --- |
| Servers | Multiple profiles, implicit TLS, certificate and hostname validation, AUTHINFO USER/PASS, bounded reusable connections |
| Groups | Group-list download, name filtering, subscriptions, open by name, latest/older header batches |
| Reader | Message-ID/References threading, flat view, unread and flagged filters, subject/author search, raw source, catch-up, article saving |
| Persistence | SQLite WAL cache, batched header transactions, compressed article bodies, saved read/flag states and subscriptions, offline reading |
| Rules | Case-insensitive regular expressions for subject or author; additive scoring; score ≤ −999 hides an article when Hide killed is enabled |
| Posting | UTF-8 text, MIME attachments, quoted replies, References, crossposting, identity/signature, saved drafts |
| Attachments | MIME transfer and charset decoding, safe filenames, duplicate-name protection, yEnc decoding and integrity checks |
| Downloads | Persistent queue, pause/resume/retry, cached segment reuse, NZB import, multipart yEnc assembly, NZB export of selected articles |
| Terminal | Server setup, groups, subscriptions, headers, reading, posting, downloads, NZB import, queue status, cache clearing |

The article reader renders plain text. HTML alternatives are converted to text, without executing scripts or loading remote content. Search covers cached header subjects and authors, not remote article bodies. An exported NZB contains one file entry per selected article; importing a provider-supplied multipart NZB is the supported way to assemble a multipart binary.

A listed header does not guarantee that its article body is still available. Providers can keep overview entries after bodies expire or are removed, and Panx also retains cached headers. When the provider returns 430/423 for an article, the reader explains its unavailability inline and leaves it unread. Select it again to retry; an already cached body remains readable even if the provider removes its copy. Fetching headers again cannot recover a body that is absent from the provider.

Keyboard shortcuts in the reader: **N** next unread, **R** reply, **F** flag, **U** toggle read/unread, **Ctrl+N** new post, **Ctrl+F** search, **Ctrl+Enter** get headers.

## Command-line examples

Use the same data directory as the desktop, or specify `--data-dir PATH` (`--data` is an alias). Only one running Panx instance can own a data directory, to prevent duplicate download jobs. Close the desktop before using CLI commands against its cache, or use a separate directory.

```bash
# Configure a provider. TLS/563 and 40 connections are the defaults.
# Export PANX_PASSWORD separately; no password command-line option is needed.
./target/panx server-add --host news.example.net --name MyNews --user myuser
./target/panx servers
./target/panx groups comp.lang --refresh --server MyNews
./target/panx subscribe comp.lang.java --server MyNews
./target/panx headers comp.lang.java --limit 5000 --server MyNews
./target/panx read '<message-id@example.net>' --server MyNews

# Preview before posting. Omit --dry-run to submit to the selected server.
./target/panx post --from 'Your Name <you@example.net>' \
  --group comp.test --subject 'Hello from Panx' --body message.txt \
  --server MyNews --dry-run

./target/panx post --file article.eml --server MyNews
./target/panx download '<message-id@example.net>' --output ./downloads
./target/panx nzb collection.nzb --output ./downloads
./target/panx queue
./target/panx resume 1

# Read a previously cached article without connecting.
./target/panx read '<message-id@example.net>' --offline
```

`post` reads UTF-8 body text from stdin when `--body` is omitted. Repeat `--attach PATH` for multiple attachments. Use `--references '<parent-id>'` for a reply. `--file` accepts a complete RFC message, preserving its MIME encoding. `--raw` on `read` writes the original message bytes. Download commands wait for their jobs and return a nonzero status if a job fails. A posting connection can fail after a server accepts a message; check the group before retrying an unconfirmed post to avoid duplicates.

## Performance and data

Network work runs off Swing's event thread using Java virtual threads. Group filtering queries and measures results in the background, replaces the list in one update, and lays out only visible rows. Typing invalidates older searches immediately, and the reader stays open when its selected group still matches. Header retrieval splits requested ranges into batches and shares bounded NNTP sessions. Multipart downloads prefetch up to the configured connection limit, store segments in the compressed cache, and assemble them in order; one multipart file can use all 40 connections. Headers, reading, posting, and downloads share the same connection pool. Changing a server's configuration retires its old connections before opening replacements. Duplicate requests for an article share one download. Thread construction uses indexed Message-IDs and iterative traversal, handling cycles without recursive stack overflow. SQLite uses transactions and indexes; cached bodies are gzip compressed. The tests include a 50,000-article threading workload and print its elapsed time. These are implementation choices and local measurements, not evidence of being faster than PAN on every workload.

The default data directory is `~/.local/share/panx`. Newly created data directories have owner-only permissions on POSIX filesystems. Existing directory permissions are preserved, and the database is restricted to its owner. It contains server settings, optional saved passwords, headers, bodies, flags, scoring rules, drafts, and queued jobs. Back it up while Panx is closed. Downloaded files are stored in the destination you select. Existing filenames are never silently overwritten.

Interrupted jobs are paused on the next startup and require **Resume**. Already downloaded article segments are reused from the cache. Temporary assembly files are removed on handled failures; a process killed abruptly may leave `.panx-*.part` files in a download destination. Those can be removed while Panx is closed. Clearing cached bodies also removes the segment cache used for resumed downloads.

## Validation

```bash
mvn test

# Optional desktop integration test on Linux; requires Xvfb.
xvfb-run -a env PANX_UI_TEST=1 mvn test
```

Tests use local protocol fixtures, generated TLS certificates, temporary caches, and sample MIME/yEnc/NZB data. They exercise authentication, overview fallback, dot stuffing, posting, TLS hostname checks, connection limits, cache persistence/isolation, threading cycles and large threads, attachment decoding, corruption handling, queue assembly, and the desktop reader. No provider credentials are used by the test suite. Real-provider interoperability still needs testing with your account.

## Current limits

Panx is a starting implementation, not a complete PAN replacement. Not yet included: PAN configuration/scorefile or `.newsrc` import, NNTP compression and STARTTLS, SASL/OAuth, automatic provider failover, server-side body search, full thread collapse/watch/ignore semantics, automatic task scheduling, multipart binary posting, general multipart uuencode assembly, PAR2 repair, RAR extraction, and native application installers. Post attachments are limited to 64 MiB total, NNTP multiline responses to 128 MiB, and header batches to 100,000. Group and article views are bounded; use filters and older-header batches to navigate large collections.

Maven bundles FlatLaf, SQLite JDBC, Jakarta Mail/Activation, and SLF4J. Their licenses remain applicable. No PAN source code or branding assets are bundled.
