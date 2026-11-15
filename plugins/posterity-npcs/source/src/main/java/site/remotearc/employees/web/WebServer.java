package site.remotearc.employees.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.build.WebPlans;
import site.remotearc.employees.job.JobType;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * The map planner's server: sign-in from the game, live blocks and villagers
 * round a spot, each builder's plan and what's left of it, your drafts, and
 * sending a draft to a builder, and the blueprint upload page (/upload/).
 * Listens on 127.0.0.1 only; nginx puts it at web.public-url + "arc/"
 * (e.g. https://your-domain.example/map/arc/) next to BlueMap (/map/).
 */
public final class WebServer {

    private static final Gson GSON = new Gson();
    private static final String COOKIE = "arc_map";

    private final ArcEmployees plugin;
    private final WebAuth auth;
    private final File drafts, suggestions;
    private HttpServer server;
    private String publicUrl;

    public WebServer(ArcEmployees plugin) {
        this.plugin = plugin;
        File dir = new File(plugin.getDataFolder(), "web");
        this.auth = new WebAuth(new File(dir, "sessions.yml"));
        this.drafts = new File(dir, "drafts");
        this.suggestions = new File(dir, "suggestions");
    }

    public String publicUrl() {
        return publicUrl;
    }

    /** The sign-in cookie's path: public-url's path ("/map" behind nginx, "/" when this port is reached directly). */
    private String cookiePath() {
        String p;
        try {
            p = java.net.URI.create(publicUrl).getPath();
        } catch (IllegalArgumentException ex) {
            p = null;
        }
        if (p == null || p.isEmpty()) return "/";
        return p.length() > 1 && p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
    }

    public boolean running() {
        return server != null;
    }

    public void start() {
        var c = plugin.getConfig();
        if (!c.getBoolean("web.enabled", true)) return;
        publicUrl = c.getString("web.public-url", "https://your-domain.example/map/");
        if (!publicUrl.endsWith("/")) publicUrl += "/";
        String bind = c.getString("web.bind", "127.0.0.1");
        int port = c.getInt("web.port", 8101);
        try {
            server = HttpServer.create(new InetSocketAddress(bind, port), 0);
        } catch (IOException ex) {
            plugin.getLogger().warning("The map planner couldn't listen on " + bind + ":" + port + ": " + ex.getMessage());
            return;
        }
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "ArcEmployees-web");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
        suggestions.mkdirs();
        drafts.mkdirs();
        plugin.getLogger().info("Map planner listening on " + bind + ":" + port + " (" + publicUrl + "arc/)");
    }

    public void stop() {
        if (server != null) server.stop(0);
        server = null;
    }

    /** A sign-in link for /map; {@code to} is a BlueMap hash to land on (or null). */
    public String loginLink(UUID player, String to) {
        return publicUrl + "arc/login?code=" + auth.code(player, to);
    }

    public int signOut(UUID player) {
        return auth.signOut(player);
    }

    // ── routing ─────────────────────────────────────────────────────────

    private void handle(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath();
            // Behind nginx, public-url + "arc/" arrives here as "/". Reached directly (no nginx: public-url
            // pointing at this port, e.g. http://mc.example.com:8101/), links still carry the "arc/" part.
            if (path.equals("/arc")) path = "/";
            else if (path.startsWith("/arc/")) path = path.substring(4);
            Map<String, String> q = query(x.getRequestURI().getRawQuery());
            switch (path) {
                case "/auth" -> {
                    // nginx asks before every BlueMap request: only signed-in players see the map.
                    send(x, who(x) != null ? 204 : 401, "text/plain", new byte[0]);
                    return;
                }
                case "/login" -> {
                    String[] got = auth.redeem(q.get("code"));
                    if (got == null) {
                        html(x, 403, page("That sign-in link has expired", "Links from <b>/map</b> work once, for 10 minutes. Type <b>/map</b> in the game for a new one."));
                        return;
                    }
                    boolean https = "https".equalsIgnoreCase(x.getRequestHeaders().getFirst("X-Forwarded-Proto"));
                    x.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + got[0] + "; Path=" + cookiePath() + "; Max-Age=" + WebAuth.SESSION_MS / 1000
                            + "; HttpOnly; SameSite=Lax" + (https ? "; Secure" : ""));
                    String to = got[1];
                    x.getResponseHeaders().add("Location", to != null && to.startsWith("planner:") ? publicUrl + "arc/editor#" + to.substring(8)
                            : publicUrl + (to != null ? "#" + to : ""));
                    send(x, 302, "text/plain", new byte[0]);
                    return;
                }
                case "/signin", "/" -> {
                    html(x, 200, page("Sign in from the game", "Type <b>/map</b> in Minecraft and click the link it gives you. "
                            + "It signs this browser in for 30 days, so you see the map and can plan builds for your builders. <b>/map logout</b> signs you out everywhere."));
                    return;
                }
                case "/editor" -> {
                    if (who(x) == null) {
                        redirect(x, publicUrl + "arc/signin");
                        return;
                    }
                    send(x, 200, "text/html; charset=utf-8", resource("web/editor.html"));
                    return;
                }
                default -> {
                }
            }
            if (path.startsWith("/upload/")) {
                upload(x, path, q);
                return;
            }
            if (!path.startsWith("/api/")) {
                send(x, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                return;
            }
            UUID me = who(x);
            if (me == null) {
                json(x, 401, error("Signed out. Type /map in the game for a sign-in link."));
                return;
            }
            boolean post = "POST".equals(x.getRequestMethod());
            if (post && !"1".equals(x.getRequestHeaders().getFirst("X-Arc-Map"))) {
                json(x, 403, error("Missing X-Arc-Map header"));
                return;
            }
            JsonElement out = switch (path) {
                case "/api/me" -> sync(() -> me(me));
                case "/api/region" -> region(q);
                case "/api/villagers" -> sync(() -> villagers(me, q));
                case "/api/plans" -> sync(() -> plans(me, q.get("world")));
                case "/api/blocks" -> blocks();
                case "/api/suggestions" -> suggestions(me, q.get("world"));
                case "/api/draft" -> post ? saveDraft(me, body(x)) : draft(me, q.get("builder"));
                case "/api/send" -> post ? sendDraft(me, body(x)) : null;
                default -> null;
            };
            if (out == null) json(x, 404, error("No such call"));
            else json(x, 200, out);
        } catch (Bad b) {
            json(x, 400, error(b.getMessage()));
        } catch (Exception ex) {
            plugin.getLogger().log(Level.WARNING, "Map planner request failed: " + x.getRequestURI(), ex);
            json(x, 500, error("Something went wrong on the server: " + ex.getMessage()));
        }
    }

    // ── the upload page ─────────────────────────────────────────────────

    /**
     * /upload/&lt;token&gt;: the page; /upload/&lt;token&gt;/status: the ticket and
     * what happened to each file; POST /upload/&lt;token&gt;/file?name=...: one
     * file as the request body. The token (from /employees ... upload in the
     * game, good for 15 minutes) is the sign-in.
     */
    private void upload(HttpExchange x, String path, Map<String, String> q) throws IOException {
        String[] parts = path.substring("/upload/".length()).split("/");
        String token = parts.length > 0 ? parts[0] : "";
        String action = parts.length > 1 ? parts[1] : "";
        if (!token.matches("[0-9a-f]{32}")) {
            send(x, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
            return;
        }
        var uploads = plugin.uploads();
        var view = uploads.view(token);
        switch (action) {
            case "" -> {
                if (view == null) {
                    html(x, 410, page("That upload link has run out", "Upload links work for 15 minutes. Get a new one in the game:"
                            + " <b>/employees homes upload</b> for your own NPC house, or <b>/employees blueprints upload</b>."));
                    return;
                }
                x.getResponseHeaders().add("Cache-Control", "no-store");
                send(x, 200, "text/html; charset=utf-8", resource("web/upload.html"));
            }
            case "status" -> {
                if (view == null) {
                    json(x, 410, error("This upload link has run out. Get a new one in the game."));
                    return;
                }
                JsonObject o = new JsonObject();
                o.addProperty("player", view.player());
                o.addProperty("kind", view.kind().name().toLowerCase(java.util.Locale.ROOT));
                o.addProperty("expires", view.expires());
                o.addProperty("now", System.currentTimeMillis());
                o.addProperty("maxBytes", view.maxBytes());
                o.addProperty("maxFiles", view.maxFiles());
                o.addProperty("received", view.received());
                o.add("results", uploads.results(token));
                json(x, 200, o);
            }
            case "file" -> {
                if (!"POST".equals(x.getRequestMethod()) || !"1".equals(x.getRequestHeaders().getFirst("X-Arc-Map"))) {
                    json(x, 405, error("POST the file with an X-Arc-Map: 1 header"));
                    return;
                }
                if (view == null) {
                    json(x, 410, error("This upload link has run out. Get a new one in the game."));
                    return;
                }
                site.remotearc.employees.build.Uploads.Received got;
                try (InputStream in = x.getRequestBody()) {
                    got = uploads.receive(token, q.get("name"), in);
                }
                if (got.error() != null) json(x, 400, error(got.error()));
                else {
                    JsonObject ok = new JsonObject();
                    ok.addProperty("ok", true);
                    ok.addProperty("name", got.name());
                    json(x, 200, ok);
                }
            }
            default -> send(x, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class Bad extends RuntimeException {
        Bad(String m) {
            super(m);
        }
    }

    private UUID who(HttpExchange x) {
        List<String> cookies = x.getRequestHeaders().get("Cookie");
        if (cookies == null) return null;
        for (String header : cookies) {
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith(COOKIE + "=")) {
                    UUID u = auth.who(p.substring(COOKIE.length() + 1));
                    if (u != null) return u;
                }
            }
        }
        return null;
    }

    private <T> T sync(Callable<T> c) throws Exception {
        return Bukkit.getScheduler().callSyncMethod(plugin, c).get(15, TimeUnit.SECONDS);
    }

    private boolean mayManage(UUID player, Employee e) {
        return e.owner.equals(player) || plugin.managers().trusts(e.owner, player);
    }

    // ── calls ───────────────────────────────────────────────────────────

    private JsonObject me(UUID me) {
        JsonObject o = new JsonObject();
        OfflinePlayer op = Bukkit.getOfflinePlayer(me);
        o.addProperty("name", op.getName());
        o.addProperty("uuid", me.toString());
        Player p = op.getPlayer();
        if (p != null) {
            Location l = p.getLocation();
            JsonArray at = new JsonArray();
            at.add(l.getWorld().getName());
            at.add(l.getBlockX());
            at.add(l.getBlockY());
            at.add(l.getBlockZ());
            o.add("at", at);
        }
        JsonArray builders = new JsonArray(), traders = new JsonArray();
        for (Employee e : plugin.manager().all()) {
            if (!mayManage(me, e)) continue;
            JsonObject b = new JsonObject();
            b.addProperty("id", e.id.toString());
            b.addProperty("name", e.name);
            b.addProperty("owner", e.ownerName());
            if (e.job == JobType.BUILDER) {
                BuildOrder ord = e.build;
                if (ord != null) {
                    b.addProperty("blueprint", ord.blueprint);
                    b.addProperty("world", ord.world);
                    b.addProperty("status", ord.dismantle ? "taking it down" : ord.finished ? "finished" : ord.started ? "building" : "waiting for Start");
                    if (ord.plan != null) b.add("bounds", box(ord));
                } else b.addProperty("status", "nothing to build");
                b.addProperty("paused", e.paused);
                builders.add(b);
            } else if (e.job == JobType.TRADER) {
                b.addProperty("stalls", e.stalls.size());
                traders.add(b);
            }
        }
        o.add("builders", builders);
        o.add("traders", traders);
        JsonArray worlds = new JsonArray();
        for (World w : Bukkit.getWorlds()) worlds.add(w.getName());
        o.add("worlds", worlds);
        return o;
    }

    private static JsonArray box(BuildOrder o) {
        var b = o.bounds();
        JsonArray a = new JsonArray();
        for (int v : new int[]{b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()}) a.add(v);
        return a;
    }

    /** The blocks in a box: a palette and one 16-bit palette index a cell (y, then z, then x), base64. */
    private JsonObject region(Map<String, String> q) throws Exception {
        String wn = q.getOrDefault("world", "world");
        int cx = num(q, "x"), cy = num(q, "y"), cz = num(q, "z");
        int r = Math.max(4, Math.min(64, q.containsKey("r") ? num(q, "r") : 24));
        int dy = Math.max(4, Math.min(48, q.containsKey("dy") ? num(q, "dy") : 20));
        World w = Bukkit.getWorld(wn);
        if (w == null) throw new Bad("No world called " + wn);
        int x0 = cx - r, x1 = cx + r, z0 = cz - r, z1 = cz + r;
        int y0 = Math.max(w.getMinHeight(), cy - dy), y1 = Math.min(w.getMaxHeight() - 1, cy + dy);
        // Snapshots on the server thread, read here.
        Map<Long, ChunkSnapshot> snaps = sync(() -> {
            Map<Long, ChunkSnapshot> m = new HashMap<>();
            for (int a = x0 >> 4; a <= x1 >> 4; a++) {
                for (int b = z0 >> 4; b <= z1 >> 4; b++) {
                    if (!w.isChunkGenerated(a, b)) continue;
                    m.put(((long) a << 32) ^ (b & 0xffffffffL), w.getChunkAt(a, b).getChunkSnapshot(false, false, false));
                }
            }
            return m;
        });
        int sx = x1 - x0 + 1, sy = y1 - y0 + 1, sz = z1 - z0 + 1;
        Map<String, Integer> pal = new LinkedHashMap<>();
        pal.put("air", 0);
        ByteBuffer buf = ByteBuffer.allocate(sx * sy * sz * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = y0; y <= y1; y++) {
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    ChunkSnapshot s = snaps.get(((long) (x >> 4) << 32) ^ ((z >> 4) & 0xffffffffL));
                    int idx = 0;
                    if (s != null) {
                        BlockData d = s.getBlockData(x & 15, y, z & 15);
                        if (!d.getMaterial().isAir()) idx = pal.computeIfAbsent(d.getAsString().replace("minecraft:", ""), k -> pal.size());
                    }
                    buf.putShort((short) idx);
                }
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("world", wn);
        JsonArray origin = new JsonArray();
        origin.add(x0);
        origin.add(y0);
        origin.add(z0);
        o.add("origin", origin);
        JsonArray size = new JsonArray();
        size.add(sx);
        size.add(sy);
        size.add(sz);
        o.add("size", size);
        JsonArray p = new JsonArray();
        pal.keySet().forEach(p::add);
        o.add("palette", p);
        o.addProperty("cells", Base64.getEncoder().encodeToString(buf.array()));
        return o;
    }

    private JsonArray villagers(UUID me, Map<String, String> q) {
        World w = Bukkit.getWorld(q.getOrDefault("world", "world"));
        if (w == null) throw new Bad("No such world");
        int r = Math.max(8, Math.min(96, q.containsKey("r") ? num(q, "r") : 32));
        Location c = new Location(w, num(q, "x"), num(q, "y"), num(q, "z"));
        // Which stall each villager is in, for the owner's traders.
        Map<UUID, String> stallOf = new HashMap<>();
        for (Employee e : plugin.manager().all()) {
            if (e.job != JobType.TRADER || !mayManage(me, e)) continue;
            for (int i = 0; i < e.stalls.size(); i++) {
                Villager v = site.remotearc.employees.job.Stalls.inStallPublic(w, e.stalls.get(i));
                if (v != null) stallOf.put(v.getUniqueId(), e.name + "'s stall " + (i + 1));
            }
        }
        JsonArray out = new JsonArray();
        for (Villager v : w.getNearbyEntitiesByType(Villager.class, c, r, 48, r)) {
            JsonObject o = new JsonObject();
            o.addProperty("job", v.getProfession().getKey().getKey());
            o.addProperty("level", v.getVillagerLevel());
            o.addProperty("xp", v.getVillagerExperience());
            JsonArray pos = new JsonArray();
            pos.add(round(v.getLocation().getX()));
            pos.add(round(v.getLocation().getY()));
            pos.add(round(v.getLocation().getZ()));
            o.add("pos", pos);
            if (v.customName() != null) o.addProperty("name", net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(v.customName()));
            if (stallOf.containsKey(v.getUniqueId())) o.addProperty("stall", stallOf.get(v.getUniqueId()));
            JsonArray offers = new JsonArray();
            for (MerchantRecipe r2 : v.getRecipes()) {
                JsonObject of = new JsonObject();
                of.add("sell", item(r2.getResult()));
                List<ItemStack> in = r2.getIngredients();
                if (!in.isEmpty()) of.add("buy", item(in.get(0)));
                if (in.size() > 1 && in.get(1) != null && !in.get(1).getType().isAir()) of.add("buyB", item(in.get(1)));
                of.addProperty("uses", r2.getUses());
                of.addProperty("max", r2.getMaxUses());
                offers.add(of);
            }
            o.add("offers", offers);
            out.add(o);
        }
        return out;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }

    private static JsonObject item(ItemStack it) {
        JsonObject o = new JsonObject();
        o.addProperty("id", it.getType().getKey().getKey());
        o.addProperty("n", it.getAmount());
        if (it.getItemMeta() instanceof EnchantmentStorageMeta es && !es.getStoredEnchants().isEmpty()) {
            JsonObject en = new JsonObject();
            for (Map.Entry<Enchantment, Integer> e : es.getStoredEnchants().entrySet()) en.addProperty(e.getKey().getKey().getKey(), e.getValue());
            o.add("ench", en);
        }
        return o;
    }

    /** Each of your builders' plans in this world, and the blocks it still has to place or clear. */
    private JsonArray plans(UUID me, String world) {
        JsonArray out = new JsonArray();
        World w = Bukkit.getWorld(world == null ? "world" : world);
        if (w == null) return out;
        for (Employee e : plugin.manager().all()) {
            if (e.job != JobType.BUILDER || !mayManage(me, e)) continue;
            BuildOrder o = e.build;
            if (o == null || o.plan == null || !o.world.equals(w.getName())) continue;
            JsonObject p = new JsonObject();
            p.addProperty("builder", e.id.toString());
            p.addProperty("name", e.name);
            p.addProperty("blueprint", o.blueprint);
            p.addProperty("status", o.finished ? "finished" : o.started ? "building" : "waiting for Start");
            p.add("bounds", box(o));
            JsonArray todo = new JsonArray();
            int left = 0;
            if (o.plan.volume() <= 2_000_000) {
                for (int y = 0; y < o.plan.sy; y++) {
                    for (int z = 0; z < o.plan.sz; z++) {
                        for (int x = 0; x < o.plan.sx; x++) {
                            int idx = o.cell(x, y, z);
                            if (idx < 0) continue;
                            var at = o.toWorld(x, y, z);
                            if (!w.isChunkLoaded(at.x() >> 4, at.z() >> 4)) continue;
                            BlockData want = o.data(idx), now = w.getBlockData(at.x(), at.y(), at.z());
                            boolean done = want.getMaterial().isAir() ? now.getMaterial().isAir() || !o.clearAir : now.getMaterial() == want.getMaterial();
                            if (done) continue;
                            left++;
                            if (todo.size() < 20000) {
                                JsonArray c = new JsonArray();
                                c.add(at.x());
                                c.add(at.y());
                                c.add(at.z());
                                c.add(want.getAsString().replace("minecraft:", ""));
                                todo.add(c);
                            }
                        }
                    }
                }
            }
            p.add("todo", todo);
            p.addProperty("left", left);
            out.add(p);
        }
        return out;
    }

    private JsonArray blocks() {
        JsonArray out = new JsonArray();
        for (Material m : Material.values()) {
            if (!m.isBlock() || !m.isItem() || m.isAir() || m.isLegacy()) continue;
            out.add(m.getKey().getKey());
        }
        return out;
    }

    /** Upgrade ideas left in web/suggestions/ (by Claude, or anyone): each a set of changes and the reasons for them. */
    private JsonArray suggestions(UUID me, String world) throws IOException {
        JsonArray out = new JsonArray();
        File[] files = suggestions.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        String name = Bukkit.getOfflinePlayer(me).getName();
        for (File f : files) {
            try {
                JsonElement el = JsonParser.parseString(Files.readString(f.toPath()));
                List<JsonElement> list = new ArrayList<>();
                if (el.isJsonArray()) el.getAsJsonArray().forEach(list::add);
                else list.add(el);
                for (JsonElement s : list) {
                    JsonObject o = s.getAsJsonObject();
                    if (world != null && o.has("world") && !o.get("world").getAsString().equals(world)) continue;
                    if (o.has("for") && !o.get("for").getAsString().equalsIgnoreCase(name) && !o.get("for").getAsString().equals(me.toString())) continue;
                    out.add(o);
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("Skipping " + f.getName() + " in web/suggestions: " + ex.getMessage());
            }
        }
        return out;
    }

    // ── drafts ──────────────────────────────────────────────────────────

    private File draftFile(UUID me) {
        return new File(drafts, me + ".json");
    }

    private synchronized JsonObject drafts(UUID me) throws IOException {
        File f = draftFile(me);
        if (!f.isFile()) return new JsonObject();
        return JsonParser.parseString(Files.readString(f.toPath())).getAsJsonObject();
    }

    private synchronized void writeDrafts(UUID me, JsonObject all) throws IOException {
        drafts.mkdirs();
        File tmp = new File(drafts, me + ".json.tmp");
        Files.writeString(tmp.toPath(), GSON.toJson(all));
        Files.move(tmp.toPath(), draftFile(me).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private JsonObject draft(UUID me, String builder) throws IOException {
        JsonObject all = drafts(me);
        if (builder == null) return all;
        return all.has(builder) ? all.getAsJsonObject(builder) : new JsonObject();
    }

    private JsonObject saveDraft(UUID me, JsonObject body) throws Exception {
        String builder = str(body, "builder");
        Employee e = builderFor(me, builder);
        JsonObject d = new JsonObject();
        d.addProperty("world", str(body, "world"));
        d.add("edits", body.has("edits") ? body.get("edits") : new JsonArray());
        if (body.has("note")) d.add("note", body.get("note"));
        d.addProperty("updated", System.currentTimeMillis());
        JsonObject all = drafts(me);
        if (d.getAsJsonArray("edits").isEmpty()) all.remove(builder);
        else all.add(builder, d);
        writeDrafts(me, all);
        JsonObject ok = new JsonObject();
        ok.addProperty("saved", d.getAsJsonArray("edits").size());
        ok.addProperty("builder", e.name);
        return ok;
    }

    private JsonObject sendDraft(UUID me, JsonObject body) throws Exception {
        String builder = str(body, "builder");
        boolean start = !body.has("start") || body.get("start").getAsBoolean();
        Employee e = builderFor(me, builder);
        JsonObject d = draft(me, builder);
        if (!d.has("edits") || d.getAsJsonArray("edits").isEmpty()) throw new Bad("Nothing planned for " + e.name + " yet.");
        String world = d.get("world").getAsString();
        List<WebPlans.Edit> edits = new ArrayList<>();
        for (JsonElement el : d.getAsJsonArray("edits")) {
            JsonObject ed = el.getAsJsonObject();
            String[] p = ed.get("p").getAsString().split(",");
            edits.add(new WebPlans.Edit(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), ed.get("b").getAsString()));
        }
        String msg = sync(() -> WebPlans.send(plugin, e, world, edits, start));
        JsonObject out = new JsonObject();
        boolean ok = !msg.startsWith("!");
        out.addProperty("ok", ok);
        out.addProperty("message", ok ? msg : msg.substring(1));
        if (ok) {
            JsonObject all = drafts(me);
            all.remove(builder);
            writeDrafts(me, all);
            sync(() -> {
                plugin.manager().events().add(e.owner, e.name, "Got " + edits.size() + " changes from the map planner", false);
                Player p = Bukkit.getPlayer(me);
                if (p != null) site.remotearc.employees.util.Msg.send(p, "<green>Map planner:</green> <gray>" + msg);
                return null;
            });
        }
        return out;
    }

    private Employee builderFor(UUID me, String id) {
        Employee e;
        try {
            e = id == null ? null : plugin.manager().byId(UUID.fromString(id));
        } catch (IllegalArgumentException ex) {
            e = null;
        }
        if (e == null || e.job != JobType.BUILDER || !mayManage(me, e)) throw new Bad("That isn't one of your builders.");
        return e;
    }

    // ── plumbing ────────────────────────────────────────────────────────

    private static String str(JsonObject o, String k) {
        if (!o.has(k) || o.get(k).isJsonNull()) throw new Bad("Missing " + k);
        return o.get(k).getAsString();
    }

    private static int num(Map<String, String> q, String k) {
        try {
            return (int) Math.floor(Double.parseDouble(q.getOrDefault(k, "")));
        } catch (NumberFormatException ex) {
            throw new Bad("Missing or odd " + k);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String part : raw.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            m.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static JsonObject body(HttpExchange x) throws IOException {
        try (InputStream in = x.getRequestBody()) {
            byte[] b = in.readNBytes(8 * 1024 * 1024 + 1);
            if (b.length > 8 * 1024 * 1024) throw new Bad("Too big");
            return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IllegalStateException | com.google.gson.JsonParseException ex) {
            throw new Bad("Not JSON");
        }
    }

    private static JsonObject error(String m) {
        JsonObject o = new JsonObject();
        o.addProperty("error", m);
        return o;
    }

    private static void json(HttpExchange x, int code, JsonElement body) throws IOException {
        x.getResponseHeaders().add("Cache-Control", "no-store");
        send(x, code, "application/json; charset=utf-8", GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static void html(HttpExchange x, int code, String body) throws IOException {
        send(x, code, "text/html; charset=utf-8", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void redirect(HttpExchange x, String to) throws IOException {
        x.getResponseHeaders().add("Location", to);
        send(x, 302, "text/plain", new byte[0]);
    }

    private static void send(HttpExchange x, int code, String type, byte[] body) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        x.getResponseHeaders().set("Referrer-Policy", "same-origin");
        if (code == 204 || body.length == 0) {
            x.sendResponseHeaders(code, -1);
            x.close();
            return;
        }
        x.sendResponseHeaders(code, body.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(body);
        }
    }

    private byte[] resource(String name) throws IOException {
        // A copy in the data folder wins (to try changes without a new jar).
        File local = new File(plugin.getDataFolder(), name);
        if (local.isFile()) return Files.readAllBytes(local.toPath());
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) throw new IOException(name + " is missing from the jar");
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            in.transferTo(b);
            return b.toByteArray();
        }
    }

    private static String page(String title, String text) {
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'><title>" + title
                + "</title><style>body{font:16px/1.5 system-ui,sans-serif;background:#15191c;color:#e7ebe8;display:grid;place-items:center;min-height:100vh;margin:0;padding:16px;box-sizing:border-box}"
                + "main{max-width:32em}h1{font-size:22px}b{color:#2fbf71}</style></head><body><main><h1>" + title + "</h1><p>" + text + "</p></main></body></html>";
    }
}
