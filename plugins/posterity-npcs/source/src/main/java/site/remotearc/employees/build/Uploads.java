package site.remotearc.employees.build;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.util.Msg;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Uploading blueprints from a browser. The page is served by this plugin's
 * own web server (web/WebServer, at public-url + "arc/upload/&lt;token&gt;"),
 * or by an outside site set as blueprints.upload-url that drops files in the
 * same folders. A ticket is for one of three things ({@link Kind}): shared
 * blueprints, a house for the NPC Homes catalog (admins), or a player's own
 * NPC house (only they see it).
 *
 * Originally: /employees blueprints upload makes a
 * ticket: a folder plugins/ArcEmployees/uploads/&lt;token&gt;/ with a
 * ticket.json. The mc web site (its /blueprints/upload/&lt;token&gt; page) checks
 * the ticket, takes the files and drops them, finished, into the ticket's
 * in/ folder. Here we pick them up each second, file them like a download,
 * tell the player, and write results.json for the page to show. Tickets
 * last 15 minutes and take any number of files.
 */
public final class Uploads {

    static final long LIFETIME = TimeUnit.MINUTES.toMillis(15);
    private static final int MAX_FILES = 20;

    /** What an upload is for. */
    public enum Kind { SHARED, CATALOG, PERSONAL }

    private record Ticket(String token, UUID player, String wantedName, long expires, File dir, Kind kind) {}

    /** What the upload page needs to know about a ticket. */
    public record View(String player, Kind kind, long expires, long maxBytes, int maxFiles, int received) {}

    private final ArcEmployees plugin;
    private final File root;
    private final Map<String, Ticket> tickets = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, JsonArray> results = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> received = new java.util.concurrent.ConcurrentHashMap<>();
    /** Tokens with a file transfer in progress right now: one at a time per link, so a slow upload can't tie up the web server's whole thread pool by itself. */
    private final Set<String> uploading = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Set<String> busy = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final SecureRandom random = new SecureRandom();
    private final Gson gson = new Gson();
    private BukkitTask task;

    public Uploads(ArcEmployees plugin) {
        this.plugin = plugin;
        this.root = new File(plugin.getDataFolder(), "uploads");
    }

    public void start() {
        File[] old = root.listFiles();
        if (old != null) for (File f : old) deleteTree(f);   // tickets don't survive a restart
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::poll, 20L, 20L);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    /**
     * Where upload pages are: blueprints.upload-url if set, otherwise this
     * plugin's own web server. Null if neither (uploading is off).
     */
    public String baseUrl() {
        String set = plugin.settings().uploadUrl;
        if (!set.isEmpty()) return set;
        var web = plugin.web();
        return web != null && web.running() ? web.publicUrl() + "arc/upload/" : null;
    }

    /** Makes a ticket for the player and returns the page's link (null if it couldn't). */
    public String create(Player p, String wantedName) {
        return create(p, wantedName, Kind.SHARED);
    }

    public String create(Player p, String wantedName, Kind kind) {
        String base = baseUrl();
        if (base == null) return null;
        byte[] b = new byte[16];
        random.nextBytes(b);
        String token = HexFormat.of().formatHex(b);
        File dir = new File(root, token);
        long expires = System.currentTimeMillis() + LIFETIME;
        JsonObject t = new JsonObject();
        t.addProperty("player", p.getName());
        t.addProperty("expires", expires);
        t.addProperty("max_bytes", plugin.settings().downloadMaxBytes);
        t.addProperty("max_files", MAX_FILES);
        if (wantedName != null) t.addProperty("name", wantedName);
        t.addProperty("kind", kind.name().toLowerCase(java.util.Locale.ROOT));
        try {
            Files.createDirectories(new File(dir, "in").toPath());
            writeAtomically(new File(dir, "ticket.json"), gson.toJson(t));
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not make an upload ticket: " + ex.getMessage());
            deleteTree(dir);
            return null;
        }
        tickets.put(token, new Ticket(token, p.getUniqueId(), wantedName, expires, dir, kind));
        results.put(token, new JsonArray());
        received.put(token, new java.util.concurrent.atomic.AtomicInteger());
        return base + token;
    }

    // ── for the built-in upload page (web threads) ────────────────────────

    /** A live ticket, or null if there's none or it has run out. */
    public View view(String token) {
        Ticket t = token == null ? null : tickets.get(token);
        if (t == null || System.currentTimeMillis() > t.expires) return null;
        var n = received.get(token);
        String name = Bukkit.getOfflinePlayer(t.player).getName();
        return new View(name == null ? "?" : name, t.kind, t.expires, plugin.settings().downloadMaxBytes, MAX_FILES, n == null ? 0 : n.get());
    }

    /** What has happened to the files so far (a copy). */
    public JsonArray results(String token) {
        JsonArray r = results.get(token);
        if (r == null) return new JsonArray();
        synchronized (r) {   // report() adds to it on the main thread
            return r.deepCopy();
        }
    }

    /** What became of one file sent by the upload page: an error, or the name it's filed under (results use that name). */
    public record Received(String error, String name) {}

    /**
     * Takes one file from the upload page into the ticket's in/ folder, where
     * the next poll picks it up.
     */
    public Received receive(String token, String fileName, java.io.InputStream body) throws IOException {
        Ticket t = token == null ? null : tickets.get(token);
        if (t == null || System.currentTimeMillis() > t.expires) return new Received("This upload link has run out. Get a new one in the game.", null);
        String name = fileName == null ? "" : fileName.replaceAll("[^A-Za-z0-9_.-]", "_");
        while (name.startsWith(".")) name = name.substring(1);
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (name.isEmpty() || !(BlueprintLoader.supported(lower) || lower.endsWith(".zip"))) {
            return new Received("Only .schem, .schematic, .litematic, .nbt and .zip files.", null);
        }
        if (name.length() > 100) {   // keep file names well inside the file system's limit
            int dot = name.lastIndexOf('.');
            name = name.substring(0, 100 - (name.length() - dot)) + name.substring(dot);
        }
        var n = received.get(token);
        if (n == null || n.get() >= MAX_FILES) return new Received("That's the most files one link takes (" + MAX_FILES + "). Get a new link in the game.", null);
        if (!uploading.add(token)) return new Received("Already receiving a file from this link; wait for it to finish first.", null);
        try {
            long max = plugin.settings().downloadMaxBytes;
            File in = new File(t.dir, "in");
            File part = new File(in, "." + name + "." + System.nanoTime() + ".part");
            long size;
            long deadline = System.currentTimeMillis() + 60_000L;
            try (var out = Files.newOutputStream(part.toPath())) {
                byte[] buf = new byte[64 * 1024];
                size = 0;
                int r;
                while ((r = body.read(buf)) > 0) {
                    size += r;
                    if (size > max) break;
                    if (System.currentTimeMillis() > deadline) {
                        Files.deleteIfExists(part.toPath());
                        return new Received("That took too long (over a minute); try again on a faster connection.", null);
                    }
                    out.write(buf, 0, r);
                }
            }
            if (size > max) {
                Files.deleteIfExists(part.toPath());
                return new Received("Too big: files can be up to " + (max / (1024 * 1024)) + " MB.", null);
            }
            if (size == 0) {
                Files.deleteIfExists(part.toPath());
                return new Received("The file was empty.", null);
            }
            File target;
            synchronized (t) {   // two files with the same name at once get different names, not one over the other
                target = new File(in, name);
                for (int i = 2; target.exists(); i++) target = new File(in, i + "-" + name);
                Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
            }
            n.incrementAndGet();   // only a file that actually made it counts toward the limit
            return new Received(null, target.getName());
        } finally {
            uploading.remove(token);
        }
    }

    private void poll() {
        long now = System.currentTimeMillis();
        for (Iterator<Ticket> it = tickets.values().iterator(); it.hasNext(); ) {
            Ticket t = it.next();
            if (now > t.expires + TimeUnit.MINUTES.toMillis(2) && !busy.contains(t.token)) {
                it.remove();
                results.remove(t.token);
                received.remove(t.token);
                deleteTree(t.dir);
                continue;
            }
            if (busy.contains(t.token)) continue;
            File[] in = new File(t.dir, "in").listFiles(f -> f.isFile() && !f.getName().startsWith("."));
            if (in == null || in.length == 0) continue;
            busy.add(t.token);
            List<File> files = List.of(in);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> take(t, files));
        }
    }

    /** Off the main thread: files each upload, then reports back on the main thread. */
    private void take(Ticket t, List<File> files) {
        List<String[]> stored = new ArrayList<>();   // {uploaded name, saved name or null, error}
        for (File f : files) {
            try {
                for (String saved : plugin.blueprints().store(f, f.getName(), t.kind == Kind.SHARED ? t.wantedName : null)) {
                    stored.add(new String[]{f.getName(), saved, null});
                }
            } catch (IOException | RuntimeException ex) {
                stored.add(new String[]{f.getName(), null, ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()});
            }
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            busy.remove(t.token);
            Player p = Bukkit.getPlayer(t.player);
            CommandSender to = p != null ? p : Bukkit.getConsoleSender();
            for (String[] s : stored) {
                if (s[1] == null) {
                    Msg.error(to, "Upload " + s[0] + " failed: <reason>", Msg.p("reason", s[2]));
                    report(t, s[0], false, "Not saved: " + s[2]);
                } else if (t.kind == Kind.SHARED) {
                    plugin.blueprints().verify(to, s[1], (ok, line) -> report(t, s[0], ok, line));
                } else {
                    house(t, to, s[0], s[1]);
                }
            }
        });
    }

    /**
     * An uploaded house (already filed in the blueprints folder as {@code saved}):
     * checked that it loads and has a bed and a door, then moved into the
     * catalog or the player's own houses. Main thread.
     */
    private void house(Ticket t, CommandSender to, String uploaded, String saved) {
        File src = new File(plugin.blueprints().dir(), saved);
        HomeCatalog cat = plugin.homeCatalog();
        plugin.blueprints().load(saved).whenComplete((bp, err) -> {
            String problem = err != null ? Blueprints.reason(err) : HomeCatalog.problem(bp);
            if (problem != null) {
                deleteTree(src);
                Msg.error(to, "Upload " + uploaded + " can't be an NPC home: " + problem + ".");
                report(t, uploaded, false, "Not added: " + problem + ". Every NPC home needs a bed and a door.");
                return;
            }
            String base = saved.substring(0, saved.lastIndexOf('.'));
            try {
                if (t.kind == Kind.CATALOG) {
                    String id = base.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
                    if (id.length() > 32) id = id.substring(0, 32);
                    boolean replaced = cat.get(id) != null;
                    HomeCatalog.Entry en = cat.add(id, src, replaced ? null : base.replace('_', ' '), null, null, null);
                    deleteTree(src);
                    Msg.send(to, "<green>" + (replaced ? "Replaced" : "Added") + " <white>" + en.name() + "</white> in the NPC Homes catalog"
                            + " <gray>(id " + id + ", " + bp.size() + "). Dress it up with <white>/employees homes icon|style|describe " + id + " ...</white>");
                    report(t, uploaded, true, (replaced ? "Replaced" : "Added") + " in the NPC Homes catalog as \"" + en.name() + "\" (" + bp.size() + ").");
                } else {
                    if (cat.personal(t.player).size() >= HomeCatalog.MAX_PERSONAL && cat.personalFile(t.player, base) == null) {
                        deleteTree(src);
                        Msg.error(to, "You already have " + HomeCatalog.MAX_PERSONAL + " houses of your own. Remove one with /employees homes forget <name>.");
                        report(t, uploaded, false, "Not added: you already have " + HomeCatalog.MAX_PERSONAL + " houses of your own.");
                        return;
                    }
                    File mine = cat.addPersonal(t.player, src);
                    String name = HomeCatalog.personalName(mine.getName());
                    Msg.send(to, "<green>Your house <white>" + name + "</white> is ready</green> <gray>(" + bp.size() + "). Pick it in an"
                            + " employee's Home menu, under My houses.");
                    report(t, uploaded, true, "Added to your houses as \"" + name + "\" (" + bp.size() + "). Pick it in an employee's Home menu.");
                }
            } catch (IOException ex) {
                deleteTree(src);
                Msg.error(to, "Couldn't save " + uploaded + ": " + ex.getMessage());
                report(t, uploaded, false, "Not saved: " + ex.getMessage());
            }
        });
    }

    private void report(Ticket t, String file, boolean ok, String message) {
        JsonArray list = results.get(t.token);
        if (list == null) return;
        JsonObject r = new JsonObject();
        r.addProperty("file", file);
        r.addProperty("ok", ok);
        r.addProperty("message", message);
        String text;
        synchronized (list) {   // the upload page reads it from a web thread
            list.add(r);
            text = gson.toJson(list);
        }
        try {
            writeAtomically(new File(t.dir, "results.json"), text);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not write upload results: " + ex.getMessage());
        }
    }

    private static void writeAtomically(File f, String text) throws IOException {
        File tmp = new File(f.getParentFile(), "." + f.getName() + ".tmp");
        Files.writeString(tmp.toPath(), text, StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        if (!f.delete() && f.exists()) f.deleteOnExit();
    }
}
