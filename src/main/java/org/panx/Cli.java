package org.panx;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Headless commands share the same cache, protocol implementation and download queue as Swing. */
public final class Cli {
    private Cli() { }
    static boolean dispatch(String[] args) {
        boolean needed = false;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--data") || !args[i].startsWith("-")) { needed = true; break; }
            if (Set.of("--data-dir", "--server", "--host", "--name", "--port", "--user", "--connections", "--limit", "--search", "--file", "--body", "--attach", "--from", "--group", "--subject", "--references", "--output").contains(args[i])) i++;
        }
        if (!needed) return false;
        try { int code = run(args); if (code != 0) System.exit(code); }
        catch (Exception e) { System.err.println("panx: " + Objects.requireNonNullElse(e.getMessage(), e.toString())); System.exit(1); }
        return true;
    }
    static int run(String[] args) throws Exception {
        Options o = Options.parse(args);
        if (o.has("help") || o.command.equals("help")) { help(); return 0; }
        if (o.has("version") || o.command.equals("version")) { System.out.println("Panx 1.0.0"); return 0; }
        if (o.command.equals("gui")) {
            List<String> translated = new ArrayList<>();
            if (o.has("data")) { translated.add("--data-dir"); translated.add(o.value("data", "")); }
            if (o.has("offline")) translated.add("--offline");
            Main.main(translated.toArray(String[]::new)); return 0;
        }
        Path defaultData = Path.of(System.getProperty("user.home"), ".local", "share", "panx");
        try (Store store = new Store(Path.of(o.value("data", defaultData.toString()))); NewsService news = new NewsService(store); DownloadQueue queue = new DownloadQueue(store, news)) {
            switch (o.command) {
                case "servers" -> { for (var s : store.servers()) System.out.printf("%d\t%s\t%s:%d\t%s\t%s%n", s.id(), s.name(), s.host(), s.port(), s.tls() ? "TLS" : "plain", s.username()); return 0; }
                case "server-add" -> {
                    boolean tls = !o.has("plain"); String host = o.required("host");
                    Server saved = store.saveServer(new Server(0, o.value("name", host), host, Integer.parseInt(o.value("port", tls ? "563" : "119")), tls, o.value("user", ""), Objects.requireNonNullElse(System.getenv("PANX_PASSWORD"), ""), Integer.parseInt(o.value("connections", "40"))), o.has("remember-password"));
                    System.out.println("Added server #" + saved.id() + ": " + saved.name()); return 0;
                }
                case "queue" -> { for (var j : store.jobs()) System.out.printf("%d\t%s\t%s\t%s%n", j.id(), j.state(), j.name(), j.detail()); return 0; }
                case "drafts" -> { for (var d : store.drafts()) System.out.println(d[0] + "\t" + Mime.message(d[1]).getSubject()); return 0; }
                case "cache-clear" -> { store.clearBodies(); System.out.println("Cached bodies cleared."); return 0; }
                default -> { }
            }
            Server s = select(store, o); boolean offline = o.has("offline");
            switch (o.command) {
                case "groups" -> { if (o.has("refresh")) { online(offline); news.refreshGroups(s); } for (var g : store.groups(s.id(), o.arg(0, ""), o.has("subscribed"))) System.out.printf("%s\t%d\t%s%n", g.name(), g.count(), g.subscribed() ? "subscribed" : ""); }
                case "subscribe", "unsubscribe" -> { String group = o.argument(0); store.subscribe(s.id(), group, o.command.equals("subscribe")); System.out.println(o.command + ": " + group); }
                case "headers" -> { String group = o.argument(0); int limit = Integer.parseInt(o.value("limit", "3000")); if (limit < 1 || limit > 100000) throw new IllegalArgumentException("Limit must be 1–100000"); if (!offline) news.headers(s, group, limit, o.has("older")); for (var a : store.articles(s.id(), group, o.value("search", ""), o.has("unread"), o.has("flagged"), limit)) System.out.printf("%d\t%s\t%s\t%s%n", a.number(), a.messageId(), a.subject(), a.from()); }
                case "read" -> { String raw = news.article(s, o.argument(0), offline); if (o.has("raw")) System.out.write(raw.getBytes(StandardCharsets.ISO_8859_1)); else { var d = Mime.decode(raw); System.out.println("Subject: " + Mime.message(raw).getSubject()); System.out.println(d.text()); for (var a : d.attachments()) System.out.println("Attachment: " + a); } }
                case "post" -> {
                    String raw;
                    if (o.has("file")) raw = Files.readString(Path.of(o.required("file")), StandardCharsets.ISO_8859_1);
                    else { String text = o.has("body") ? Files.readString(Path.of(o.required("body"))) : new String(System.in.readAllBytes(), StandardCharsets.UTF_8); raw = Mime.compose(o.value("from", store.setting("from", "")), o.required("group"), o.required("subject"), text, o.value("references", ""), o.all("attach").stream().map(Path::of).toList()); }
                    var msg = Mime.message(raw); String groups = msg.getHeader("Newsgroups", null);
                    if (groups == null || msg.getSubject() == null || msg.getFrom() == null) throw new IllegalArgumentException("Post requires From, Newsgroups, and Subject");
                    for (String group : groups.split(",", -1)) NntpClient.validateGroup(group.strip());
                    if (o.has("dry-run")) System.out.write(raw.getBytes(StandardCharsets.ISO_8859_1));
                    else { online(offline); store.draft(raw); try { news.post(s, raw); } catch (Exception e) { throw new java.io.IOException("Post not confirmed; draft saved. Check the group before retrying: " + e.getMessage(), e); } System.out.println("Server accepted the post."); }
                }
                case "download" -> { online(offline); long id = queue.add(s, o.argument(0), List.of(o.argument(0)), Path.of(o.value("output", "."))); queue.start(id); return await(store, queue, Set.of(id)); }
                case "nzb" -> { online(offline); Set<Long> ids = new HashSet<>(); for (var f : Nzb.read(Path.of(o.argument(0)))) { long id = queue.add(s, f.subject(), f.ids(), Path.of(o.value("output", "."))); ids.add(id); queue.start(id); } return await(store, queue, ids); }
                case "resume" -> { online(offline); long id = Long.parseLong(o.argument(0)); queue.start(id); return await(store, queue, Set.of(id)); }
                default -> throw new IllegalArgumentException("Unknown command: " + o.command);
            }
        } return 0;
    }
    private static void online(boolean offline) { if (offline) throw new IllegalArgumentException("This command requires a live server without --offline"); }
    private static Server select(Store store, Options o) throws Exception {
        var list = store.servers(); String wanted = o.value("server", "");
        if (!wanted.isEmpty()) return list.stream().filter(s -> ("" + s.id()).equals(wanted) || s.name().equals(wanted)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown server: " + wanted));
        Server selected = store.defaultServer();
        if (selected == null) throw new IllegalArgumentException("No servers configured. Use server-add --host HOST.");
        return selected;
    }
    private static int await(Store store, DownloadQueue queue, Set<Long> ids) throws Exception {
        Map<Long,String> prior = new HashMap<>();
        do { for (var j : store.jobs()) if (ids.contains(j.id())) { String state = j.state() + " " + j.detail(); if (!state.equals(prior.put(j.id(), state))) System.out.println(j.id() + "\t" + state); } Thread.sleep(150); } while (queue.active());
        boolean ok = true; for (var j : store.jobs()) if (ids.contains(j.id())) { System.out.println(j.id() + "\t" + j.state() + " " + j.detail()); ok &= j.state().equals("Complete"); } return ok ? 0 : 1;
    }
    public static void help() { System.out.println("""
            Headless commands (use --data-dir PATH and --server ID_OR_NAME as needed):
              servers | server-add --host HOST [--user USER] [--name NAME]
                [--plain] [--port PORT] [--connections 1..40] [--remember-password]
              groups [FILTER] [--refresh] [--subscribed]
              subscribe GROUP | unsubscribe GROUP
              headers GROUP [--limit 3000] [--older] [--search TEXT] [--offline]
              read '<message-id>' [--raw] [--offline]
              post --from 'Name <email>' --group GROUP --subject SUBJECT
                [--body UTF8_FILE] [--attach PATH ...] [--references '<id>']
                [--dry-run]   (reads body from stdin if --body is omitted)
              post --file ARTICLE.eml [--dry-run]
              download '<message-id>' --output DIRECTORY
              nzb FILE.nzb --output DIRECTORY
              queue | resume JOB_ID | drafts | cache-clear
            Global: --data aliases --data-dir. Without --server, the startup default is used.
            Set PANX_PASSWORD instead of putting credentials in command arguments.
            """); }
    private static final class Options {
        String command="gui"; final List<String> args=new ArrayList<>(); final Map<String,List<String>> values=new HashMap<>();
        static Options parse(String[] args) {
            Options o=new Options(); boolean command=false;
            Set<String> flags=Set.of("help","version","offline","plain","remember-password","refresh","subscribed","older","unread","flagged","raw","dry-run");
            Set<String> keys=Set.of("data","server","host","name","port","user","connections","limit","search","file","body","attach","from","group","subject","references","output");
            for(int i=0;i<args.length;i++){String a=args[i];if(a.equals("-h"))a="--help";if(a.equals("--data-dir"))a="--data";if(a.startsWith("--")){String k=a.substring(2),v="true";if(!flags.contains(k)){if(!keys.contains(k))throw new IllegalArgumentException("Unknown option: "+a);if(++i==args.length)throw new IllegalArgumentException("Missing value for "+a);v=args[i];}o.values.computeIfAbsent(k,x->new ArrayList<>()).add(v);}else if(!command){o.command=a;command=true;}else o.args.add(a);}return o;
        }
        boolean has(String k){return values.containsKey(k);}String value(String k,String fallback){var v=values.get(k);return v==null?fallback:v.getLast();}String required(String k){String v=value(k,"");if(v.isBlank())throw new IllegalArgumentException("Required: --"+k);return v;}List<String> all(String k){return values.getOrDefault(k,List.of());}String arg(int i,String fallback){return args.size()>i?args.get(i):fallback;}String argument(int i){if(args.size()<=i)throw new IllegalArgumentException("Missing argument for "+command);return args.get(i);}
    }
}
