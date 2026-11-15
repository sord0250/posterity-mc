package site.remotearc.employees.build;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.util.Msg;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The blueprint library: files in plugins/ArcEmployees/blueprints (and
 * WorldEdit's schematics folder, so //schem save works too), loaded on demand
 * and cached, plus downloading new ones from a link.
 */
public final class Blueprints {

    private static final Pattern SAFE = Pattern.compile("[^A-Za-z0-9_.-]+");
    private static final Pattern DISPOSITION = Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE);
    private static final long MAX_UNZIPPED = 256L * 1024 * 1024;

    private final ArcEmployees plugin;
    private final File dir;
    private final Map<String, Blueprint> cache = new HashMap<>();
    private final Map<String, CompletableFuture<Blueprint>> loading = new HashMap<>();

    public Blueprints(ArcEmployees plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "blueprints");
        if (!dir.isDirectory() && !dir.mkdirs()) plugin.getLogger().warning("Could not create " + dir);
    }

    public File dir() {
        return dir;
    }

    /**
     * House folders inside the blueprint folder (see HomeCatalog): the NPC Homes
     * catalog, players' own houses, and the copy each placed home is built from.
     * Their blueprints are named with the folder in front ("homes/home-cottage.schem")
     * and found only that way: a bare name never reaches them, so a file put in the
     * main folder can't stand in for a catalog house, and nobody can build someone
     * else's private house by guessing its file name.
     */
    public static final String HOMES = "homes/", PLAYERS = "homes/players/", SITES = "homes/sites/";

    private List<File> dirs() {
        List<File> out = new ArrayList<>(List.of(dir));
        File we = new File(plugin.getDataFolder().getParentFile(), "WorldEdit/schematics");
        if (we.isDirectory()) out.add(we);
        // Builds saved in ArcShop's drafting studio (/studio save).
        File studio = new File(plugin.getDataFolder().getParentFile(), "ArcShop/builds");
        if (studio.isDirectory()) out.add(studio);
        return out;
    }

    /** Every blueprint file name, sorted; ArcEmployees' own folder wins over WorldEdit's on a clash. */
    public List<String> list() {
        Map<String, String> names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (File d : dirs()) {
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (f.isFile() && BlueprintLoader.supported(f.getName())) names.putIfAbsent(f.getName(), f.getName());
            }
        }
        return new ArrayList<>(names.values());
    }

    /**
     * Finds a blueprint by file name, with or without its extension (any case).
     * A name starting with one of the house folders ({@link #HOMES}, {@link #PLAYERS},
     * {@link #SITES}) is looked for in that folder only.
     */
    public File resolve(String name) {
        if (name == null || name.contains("\\") || name.contains("..") || name.startsWith(".")) return null;
        List<File> where = null;
        for (String ns : List.of(SITES, PLAYERS, HOMES)) {
            if (name.startsWith(ns)) {
                where = List.of(new File(dir, ns));
                name = name.substring(ns.length());
                break;
            }
        }
        if (name.isEmpty() || name.contains("/") || name.startsWith(".")) return null;
        for (File d : where != null ? where : dirs()) {
            File[] files = d.listFiles();
            if (files == null) continue;
            for (File f : files) {
                if (!f.isFile() || !BlueprintLoader.supported(f.getName())) continue;
                String n = f.getName();
                if (n.equalsIgnoreCase(name)) return f;
                int dot = n.lastIndexOf('.');
                if (dot > 0 && n.substring(0, dot).equalsIgnoreCase(name)) return f;
            }
        }
        return null;
    }

    /** Loads (or returns the cached copy of) a blueprint. Completes on the main thread. */
    public CompletableFuture<Blueprint> load(String name) {
        File f = resolve(name);
        if (f == null) return CompletableFuture.failedFuture(new IOException("there is no blueprint called " + name));
        String key = f.getAbsolutePath() + "|" + f.lastModified() + "|" + f.length();
        Blueprint cached = cache.get(key);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        CompletableFuture<Blueprint> running = loading.get(key);
        if (running != null) return running;

        CompletableFuture<Blueprint> result = new CompletableFuture<>();
        loading.put(key, result);
        int max = plugin.settings().builderMaxVolume;
        Runnable work = () -> {
            Blueprint bp = null;
            Throwable error = null;
            try {
                bp = BlueprintLoader.load(f, max);
            } catch (IOException | RuntimeException | OutOfMemoryError ex) {
                error = ex;
            }
            Blueprint loaded = bp;
            Throwable failed = error;
            Runnable finish = () -> {
                loading.remove(key);
                if (failed != null) {
                    plugin.getLogger().log(Level.FINE, "Blueprint " + f.getName() + " failed to load", failed);
                    result.completeExceptionally(failed);
                } else {
                    cache.keySet().removeIf(k -> k.startsWith(f.getAbsolutePath() + "|"));
                    cache.put(key, loaded);
                    result.complete(loaded);
                }
            };
            if (Bukkit.isPrimaryThread()) finish.run();
            else Bukkit.getScheduler().runTask(plugin, finish);
        };
        if (BlueprintLoader.needsMainThread(f)) Bukkit.getScheduler().runTask(plugin, work);
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, work);
        return result;
    }

    /** Drops cached blueprints no builder uses any more. */
    public void trim(java.util.Set<String> inUse) {
        cache.entrySet().removeIf(e -> !inUse.contains(e.getValue().name));
    }

    public static String reason(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && (c instanceof java.util.concurrent.CompletionException || c.getMessage() == null)) c = c.getCause();
        if (c instanceof OutOfMemoryError) return "it is too big for the server's memory";
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    // ── downloads ─────────────────────────────────────────────────────────

    /** What to tell players when a link can't be fetched by the server. */
    public String uploadHint() {
        return plugin.uploads() == null || plugin.uploads().baseUrl() == null
                ? "download it in your browser and put it in the blueprints folder"
                : "download it in your browser, then use /employees blueprints upload";
    }

    /**
     * Downloads a schematic (or a .zip of them) into the blueprint folder,
     * checks that it loads, and reports to the sender. Runs off the main thread.
     * Share links from Dropbox, Google Drive and GitHub are turned into
     * download links, and a link to a web page is searched for a link to the
     * file (like a download button).
     */
    public void download(CommandSender sender, String url, String wantedName) {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException ex) {
            Msg.error(sender, "That isn't a valid link.");
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getHost() == null) {
            Msg.error(sender, "Only http and https links can be downloaded.");
            return;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (host.endsWith("wormhole.app") || host.endsWith("send.vis.ee") || host.endsWith("swisstransfer.com")) {
            Msg.error(sender, "That site encrypts the file so only a browser can open it; " + uploadHint() + ".");
            return;
        }
        URI direct = directLink(uri);
        long maxBytes = plugin.settings().downloadMaxBytes;
        Msg.send(sender, "<gray>Downloading...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<String> saved = new ArrayList<>();
            String error = null;
            File tmp = null;
            try {
                HttpClient client = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(Duration.ofSeconds(15)).build();
                URI at = direct;
                HttpResponse<InputStream> resp = null;
                for (int hop = 0; hop < 3; hop++) {
                    resp = get(client, at);
                    String type = resp.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                    if (!type.startsWith("text/html")) break;
                    String page;
                    try (InputStream in = resp.body()) {
                        page = new String(in.readNBytes(3 * 1024 * 1024), StandardCharsets.UTF_8);
                    }
                    URI file = fileLinkIn(page, resp.uri());
                    if (file == null || hop == 2) {
                        throw new IOException("that link opens a web page, and there's no link to a schematic on it that the server can follow. "
                                + "Use the file's own link (right-click the download button, Copy link address), or " + uploadHint());
                    }
                    at = file;
                    resp = null;
                }
                long declared = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
                if (declared > maxBytes) throw new IOException("the file is " + declared / 1024 / 1024 + " MB; the limit is " + maxBytes / 1024 / 1024 + " MB");
                tmp = File.createTempFile("download", ".tmp", dir);
                try (InputStream in = resp.body(); OutputStream out = Files.newOutputStream(tmp.toPath())) {
                    byte[] buf = new byte[65536];
                    long total = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > maxBytes) throw new IOException("the file is bigger than " + maxBytes / 1024 / 1024 + " MB");
                        out.write(buf, 0, n);
                    }
                }
                String remoteName = resp.headers().firstValue("Content-Disposition").map(Blueprints::dispositionName)
                        .orElse(lastSegment(resp.uri()));
                saved.addAll(store(tmp, remoteName, wantedName));
                tmp = null;
            } catch (IOException | RuntimeException ex) {
                error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                error = "interrupted";
            } finally {
                if (tmp != null && !tmp.delete()) tmp.deleteOnExit();
            }
            String failed = error;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (failed != null) {
                    Msg.error(sender, "Download failed: <reason>", Msg.p("reason", failed));
                    return;
                }
                for (String name : saved) verify(sender, name, null);
            });
        });
    }

    /** GETs a link; anything but 200 is an error, explained where we can. */
    private HttpResponse<InputStream> get(HttpClient client, URI uri) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "Mozilla/5.0 (compatible; ArcEmployees/" + plugin.getPluginMeta().getVersion() + "; Minecraft server plugin)")
                .GET().build();
        HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        int code = resp.statusCode();
        if (code == 200) return resp;
        resp.body().close();
        boolean cloudflare = resp.headers().firstValue("cf-mitigated").isPresent()
                || resp.headers().firstValue("Server").orElse("").toLowerCase(Locale.ROOT).contains("cloudflare");
        if ((code == 403 || code == 503) && cloudflare) {
            throw new IOException(uri.getHost() + " blocks downloads from servers (it only lets browsers in), so " + uploadHint());
        }
        throw new IOException("the site answered " + code
                + (code == 403 || code == 401 ? " (it may need you to be logged in), so " + uploadHint() : ""));
    }

    private static final Pattern DRIVE = Pattern.compile("/file/d/([A-Za-z0-9_-]{10,})");
    private static final Pattern DRIVE_ID = Pattern.compile("(?:^|&)id=([A-Za-z0-9_-]{10,})");

    /** Turns a share link into one that gives the file itself, for the sites we know. */
    static URI directLink(URI uri) {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String query = uri.getRawQuery();
        try {
            if (host.endsWith("dropbox.com")) {
                String q = query == null ? "" : query.replaceAll("(^|&)(dl|raw)=[^&]*", "").replaceAll("^&", "");
                return new URI(uri.getScheme() + "://" + uri.getRawAuthority() + path + "?" + (q.isEmpty() ? "" : q + "&") + "dl=1");
            }
            if (host.equals("drive.google.com") || host.equals("docs.google.com")) {
                Matcher m = DRIVE.matcher(path);
                String id = m.find() ? m.group(1) : null;
                if (id == null && query != null) {
                    Matcher q = DRIVE_ID.matcher(query);
                    if (q.find()) id = q.group(1);
                }
                if (id != null) return new URI("https://drive.usercontent.google.com/download?id=" + id + "&export=download&confirm=t");
            }
            if (host.equals("github.com")) {
                String[] parts = path.split("/");   // "", user, repo, blob, branch, path...
                if (parts.length > 5 && parts[3].equals("blob")) {
                    return new URI("https://raw.githubusercontent.com/" + parts[1] + "/" + parts[2] + "/"
                            + String.join("/", java.util.Arrays.copyOfRange(parts, 4, parts.length)));
                }
            }
        } catch (java.net.URISyntaxException ignored) {
            // fall through and use the link as given
        }
        return uri;
    }

    private static final Pattern PAGE_LINK = Pattern.compile(
            "(?:href|src|data-href|data-url)\\s*=\\s*[\"']([^\"']+)[\"']|(https?://[^\"'\\s<>()]+)", Pattern.CASE_INSENSITIVE);

    /**
     * A link to a schematic (or a .zip) on a web page, like the target of
     * its download button; schematics win over zips, the first of each.
     */
    static URI fileLinkIn(String page, URI base) {
        URI zip = null;
        Matcher m = PAGE_LINK.matcher(page);
        while (m.find()) {
            String raw = (m.group(1) != null ? m.group(1) : m.group(2)).replace("&amp;", "&").trim();
            URI link;
            try {
                link = base.resolve(raw.replace(" ", "%20"));
            } catch (IllegalArgumentException ex) {
                continue;
            }
            String scheme = link.getScheme() == null ? "" : link.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !scheme.equals("http")) continue;
            String name = lastSegment(link);
            if (name == null) continue;
            String n = name.toLowerCase(Locale.ROOT);
            if (extensionOf(n) != null) return link;
            if (zip == null && n.endsWith(".zip")) zip = link;
        }
        return zip;
    }

    /**
     * Files a downloaded or uploaded file (deleting the temporary one): a
     * schematic is renamed into the folder, a .zip unpacked into it.
     * Returns the saved file names. Safe off the main thread.
     */
    public synchronized List<String> store(File tmp, String remoteName, String wantedName) throws IOException {
        // synchronized: two uploads or downloads at once must not pick the same free file name.
        try {
            if (isZip(tmp)) {
                List<String> saved = unzip(tmp, wantedName);
                if (saved.isEmpty()) throw new IOException("the zip has no .schem, .schematic, .litematic or .nbt file in it");
                return saved;
            }
            String ext = extensionOf(remoteName);
            if (ext == null) ext = sniff(tmp);
            if (ext == null) throw new IOException("that doesn't look like a schematic (or the link goes to a web page, not the file)");
            String base = wantedName != null ? wantedName : stripExtension(remoteName);
            File target = uniqueFile(clean(base), ext);
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return List.of(target.getName());
        } finally {
            if (tmp.exists() && !tmp.delete()) tmp.deleteOnExit();
        }
    }

    /**
     * Loads a fresh download; reports its size to the sender, or deletes it
     * if it can't be used. {@code done} (if any) gets whether it worked and a
     * plain-text line about it.
     */
    void verify(CommandSender sender, String fileName, java.util.function.BiConsumer<Boolean, String> done) {
        load(fileName).whenComplete((bp, err) -> {
            if (err != null) {
                File f = resolve(fileName);
                if (f != null && f.getParentFile().equals(dir) && !f.delete()) f.deleteOnExit();
                if (sender != null) Msg.error(sender, "Got " + fileName + ", but it can't be used: <reason>", Msg.p("reason", reason(err)));
                if (done != null) done.accept(false, "It can't be used: " + reason(err));
                return;
            }
            String name = stripExtension(fileName);
            if (sender != null) {
                Msg.send(sender, "<green>Saved blueprint <white><name></white> (" + bp.format + ", " + bp.size() + ", "
                        + bp.solidCount() + " blocks).", Msg.p("name", name));
                if (!bp.unknown.isEmpty()) {
                    Msg.send(sender, "<yellow>Blocks this server's Minecraft (" + org.bukkit.Bukkit.getMinecraftVersion() + ") doesn't have"
                            + (bp.newer ? " (the file is from a newer version)" : "") + ": <white><list></white>. <gray>When you build it you can pick what to use instead.",
                            Msg.p("list", String.join(", ", bp.unknown.stream().limit(8).toList()) + (bp.unknown.size() > 8 ? ", ..." : "")));
                }
            }
            if (done != null) {
                done.accept(true, "Saved as blueprint \"" + name + "\" (" + bp.format + ", " + bp.size() + ", " + bp.solidCount() + " blocks)"
                        + (bp.unknown.isEmpty() ? "." : "; " + bp.unknown.size() + " block type(s) this server doesn't have; you pick what to use instead when you build it."));
            }
        });
    }

    private List<String> unzip(File zip, String wantedName) throws IOException {
        List<String> out = new ArrayList<>();
        try {
            return unzip(zip, wantedName, out);
        } catch (IOException | RuntimeException ex) {
            // One bad entry: none of the zip is kept (not half of it lying in the folder).
            for (String n : out) Files.deleteIfExists(new File(dir, n).toPath());
            throw ex;
        }
    }

    private List<String> unzip(File zip, String wantedName, List<String> out) throws IOException {
        long total = 0;
        try (ZipInputStream z = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry entry;
            int count = 0;
            while ((entry = z.getNextEntry()) != null && count < 50) {
                if (entry.isDirectory()) continue;
                String entryName = entry.getName().substring(entry.getName().lastIndexOf('/') + 1);
                String ext = extensionOf(entryName);
                if (ext == null || entryName.startsWith(".")) continue;
                String base = wantedName != null ? wantedName + (count == 0 ? "" : "_" + count) : stripExtension(entryName);
                File target = uniqueFile(clean(base), ext);
                try (OutputStream o = Files.newOutputStream(target.toPath())) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = z.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_UNZIPPED) throw new IOException("the zip unpacks to more than " + MAX_UNZIPPED / 1024 / 1024 + " MB");
                        o.write(buf, 0, n);
                    }
                } catch (IOException ex) {
                    Files.deleteIfExists(target.toPath());
                    throw ex;
                }
                out.add(target.getName());
                count++;
            }
        }
        return out;
    }

    private static boolean isZip(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = in.readNBytes(4);
            return b.length == 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4;
        }
    }

    /** Guesses the format of an unnamed NBT file from its top-level keys. */
    private static String sniff(File f) {
        try (InputStream in = new FileInputStream(f)) {
            Map<String, Object> root = Nbt.read(in);
            if (root.containsKey("Regions")) return ".litematic";
            if (root.containsKey("Schematic") || root.containsKey("Palette") || root.containsKey("BlockData")) return ".schem";
            if (root.containsKey("Blocks") && root.containsKey("Materials")) return ".schematic";
            if (root.containsKey("palette") || root.containsKey("palettes")) return ".nbt";
        } catch (IOException | RuntimeException ignored) {
            // not NBT
        }
        return null;
    }

    private File uniqueFile(String base, String ext) {
        if (base.isEmpty()) base = "blueprint";
        File f = new File(dir, base + ext);
        for (int i = 2; f.exists(); i++) f = new File(dir, base + "_" + i + ext);
        return f;
    }

    private static String extensionOf(String name) {
        if (name == null) return null;
        String n = name.toLowerCase(Locale.ROOT);
        for (String ext : BlueprintLoader.EXTENSIONS) if (n.endsWith(ext)) return ext;
        return null;
    }

    static String stripExtension(String name) {
        if (name == null) return "blueprint";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String clean(String s) {
        String c = SAFE.matcher(s).replaceAll("_");
        c = c.replaceAll("^[._]+", "");
        return c.length() > 48 ? c.substring(0, 48) : c;
    }

    private static String dispositionName(String header) {
        Matcher m = DISPOSITION.matcher(header);
        return m.find() ? URLDecoder.decode(m.group(1), StandardCharsets.UTF_8) : null;
    }

    private static String lastSegment(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty()) return null;
        String seg = path.substring(path.lastIndexOf('/') + 1);
        return seg.isEmpty() ? null : URLDecoder.decode(seg, StandardCharsets.UTF_8);
    }
}
