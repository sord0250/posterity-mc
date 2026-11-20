package site.remotearc.employees.web;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.scheduler.BukkitTask;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.build.BuildOrder;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.trades.TradeBoard;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BlueMap: three layers (builders' plans as boxes you can see through the
 * ground, villagers with their trades, and your employees), plus the
 * planner's button in BlueMap's own page. Only loaded when BlueMap is.
 */
public final class BlueMapHook {

    private static volatile BlueMapAPI api;
    private static BukkitTask task;

    private BlueMapHook() {}

    public static void start(ArcEmployees plugin, WebServer web) {
        BlueMapAPI.onEnable(a -> {
            api = a;
            try {
                Path dir = a.getWebApp().getWebRoot().resolve("assets/arc");
                Files.createDirectories(dir);
                for (String f : List.of("arc-map.js", "arc-map.css")) {
                    try (InputStream in = plugin.getResource("web/" + f)) {
                        if (in != null) Files.copy(in, dir.resolve(f), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                a.getWebApp().registerScript("assets/arc/arc-map.js");
                a.getWebApp().registerStyle("assets/arc/arc-map.css");
            } catch (IOException ex) {
                plugin.getLogger().warning("Couldn't add the planner button to BlueMap: " + ex.getMessage());
            }
            plugin.getLogger().info("BlueMap: showing building plans, villager trades and employees");
        });
        BlueMapAPI.onDisable(a -> api = null);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> refresh(plugin, web), 100L, 20L * 30);
    }

    /** BlueMap's map id for a world (its first map), or null without BlueMap. */
    public static String mapId(World w) {
        BlueMapAPI a = api;
        if (a == null) return null;
        var bw = a.getWorld(w).orElse(null);
        if (bw == null || bw.getMaps().isEmpty()) return null;
        return bw.getMaps().iterator().next().getId();
    }

    public static String mapIdOrWorld(World w) {
        String id = null;
        try {
            id = mapId(w);
        } catch (NoClassDefFoundError ignored) {
            // no BlueMap
        }
        return id != null ? id : w.getName();
    }

    public static void stop() {
        if (task != null) task.cancel();
        task = null;
    }

    /** What goes on the map, worked out on the server thread and handed to BlueMap's marker sets. */
    private static void refresh(ArcEmployees plugin, WebServer web) {
        BlueMapAPI a = api;
        if (a == null) return;
        for (World w : Bukkit.getWorlds()) {
            var bw = a.getWorld(w).orElse(null);
            if (bw == null) continue;
            Map<String, Marker> plans = new HashMap<>(), trades = new HashMap<>(), staff = new HashMap<>();
            Map<UUID, String> stallOf = new HashMap<>();
            var settings = plugin.settings();
            for (Employee e : plugin.manager().all()) {
                if (e.job == JobType.TRADER) {
                    for (int i = 0; i < e.stalls.size(); i++) {
                        Villager v = site.remotearc.employees.job.Stalls.inStallPublic(w, e.stalls.get(i));
                        if (v != null) stallOf.put(v.getUniqueId(), e.name + "'s stall " + (i + 1));
                    }
                }
                if (settings.blueMapShowEmployees && e.location != null && e.location.getWorld() == w) {
                    Location l = e.location;
                    staff.put("emp-" + e.id, POIMarker.builder().label(e.name + " (" + e.job.display() + ")")
                            .detail("<b>" + esc(e.name) + "</b> · " + esc(e.job.display()) + " · " + esc(e.ownerName()) + "<br>" + esc(e.paused ? "Paused" : e.status))
                            .position(l.getX(), l.getY() + 1.8, l.getZ()).maxDistance(800).build());
                }
                if (!settings.blueMapShowPlans) continue;
                BuildOrder o = e.build;
                if (e.job != JobType.BUILDER || o == null || o.plan == null || !o.world.equals(w.getName())) continue;
                var b = o.bounds();
                String state = o.dismantle ? "taking it down" : o.finished ? "finished" : o.started ? (e.paused ? "paused" : "building") : "waiting for Start";
                Color line = o.finished ? new Color(47, 191, 113, 1f) : o.started ? new Color(255, 170, 40, 1f) : new Color(90, 160, 255, 1f);
                Color fill = new Color(line.getRed(), line.getGreen(), line.getBlue(), 0.10f);
                String link = web.publicUrl() + "arc/editor#" + w.getName() + ":" + (b.minX() + b.maxX()) / 2 + ":" + (b.minY() + b.maxY()) / 2 + ":" + (b.minZ() + b.maxZ()) / 2
                        + ":" + e.id;
                ExtrudeMarker m = ExtrudeMarker.builder()
                        .label(e.name + ": " + o.blueprint)
                        .shape(Shape.createRect(b.minX(), b.minZ(), b.maxX() + 1, b.maxZ() + 1), b.minY(), b.maxY() + 1)
                        .centerPosition()
                        .lineColor(line).fillColor(fill).lineWidth(2).depthTestEnabled(false)
                        .detail("<b>" + esc(e.name) + "</b> · " + esc(o.blueprint) + "<br>" + state + " · " + (b.maxX() - b.minX() + 1) + "×" + (b.maxY() - b.minY() + 1)
                                + "×" + (b.maxZ() - b.minZ() + 1) + "<br><a href='" + link + "' target='_blank'>Open in the planner</a>")
                        .link(link, true)
                        .build();
                plans.put("plan-" + e.id, m);
            }
            int n = 0;
            for (Villager v : settings.blueMapShowTrades ? w.getEntitiesByClass(Villager.class) : List.<Villager>of()) {
                if (v.getRecipes().isEmpty() || n++ >= 500) continue;
                List<String> books = new ArrayList<>(), all = new ArrayList<>();
                for (MerchantRecipe r : v.getRecipes()) {
                    String s = TradeBoard.describe(r.getResult());
                    if (s.endsWith("book")) books.add(s);
                    all.add(esc(s) + (r.getUses() >= r.getMaxUses() ? " <i>(sold out)</i>" : ""));
                }
                String job = TradeBoard.title(v.getProfession().getKey().getKey());
                String stall = stallOf.get(v.getUniqueId());
                Location l = v.getLocation();
                trades.put("v-" + v.getUniqueId(), POIMarker.builder()
                        .label(job + (books.isEmpty() ? "" : ": " + String.join(", ", books)))
                        .detail("<b>" + esc(job) + "</b> · " + TradeBoard.levelName(v.getVillagerLevel()) + (stall != null ? " · " + esc(stall) : "")
                                + (v.getVillagerExperience() > 0 ? " · locked" : " · rerollable") + "<br>" + String.join("<br>", all))
                        .position(l.getX(), l.getY() + 1.9, l.getZ()).maxDistance(250).build());
            }
            for (BlueMapMap map : bw.getMaps()) {
                put(map, "arc-plans", "Building plans", 0, plans);
                put(map, "arc-trades", "Villager trades", 1, trades);
                put(map, "arc-employees", "Employees", 2, staff);
            }
        }
    }

    private static void put(BlueMapMap map, String id, String label, int sorting, Map<String, Marker> markers) {
        MarkerSet set = map.getMarkerSets().get(id);
        if (set == null) {
            set = MarkerSet.builder().label(label).toggleable(true).defaultHidden(false).sorting(sorting).build();
            map.getMarkerSets().put(id, set);
        }
        set.getMarkers().keySet().removeIf(k -> !markers.containsKey(k));
        set.getMarkers().putAll(markers);
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;");
    }
}
