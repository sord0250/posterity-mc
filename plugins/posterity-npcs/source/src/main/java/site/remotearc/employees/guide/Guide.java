package site.remotearc.employees.guide;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.util.Msg;
import site.remotearc.employees.util.Pathfinder;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * /guide &lt;warp&gt;: walks you there instead of teleporting. An arrow and the
 * distance in the action bar, and a trail of sparks along a walkable way a
 * little ahead of you, until you arrive or turn it off. Also takes
 * coordinates, or one of your employees' names.
 */
public final class Guide implements TabExecutor, Listener {

    private record Target(String name, String world, double x, double y, double z) {}

    private static final class Guiding {
        final Target to;
        List<Vector> trail = List.of();
        int ticks;

        Guiding(Target to) {
            this.to = to;
        }
    }

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
    private static final Particle.DustOptions DUST = new Particle.DustOptions(Color.fromRGB(255, 200, 40), 1.1f);

    private final ArcEmployees plugin;
    private final Map<UUID, Guiding> guiding = new HashMap<>();
    private BukkitTask task;

    public Guide(ArcEmployees plugin) {
        this.plugin = plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 10L, 10L);
    }

    public void stop() {
        if (task != null) task.cancel();
        guiding.clear();
    }

    // ── where to ─────────────────────────────────────────────────────────

    /** EssentialsX warps, by name. */
    private Map<String, Target> warps() {
        Map<String, Target> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        File dir = new File(plugin.getDataFolder().getParentFile(), "Essentials/warps");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return out;
        for (File f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            String world = y.getString("world-name");
            if (world == null) {
                String id = y.getString("world");
                World w = null;
                try {
                    w = id == null ? null : Bukkit.getWorld(UUID.fromString(id));
                } catch (IllegalArgumentException ignored) {
                    // not a uuid
                }
                world = w != null ? w.getName() : id;
            }
            String name = y.getString("name", f.getName().substring(0, f.getName().length() - 4));
            if (world != null) out.put(name, new Target(name, world, y.getDouble("x"), y.getDouble("y"), y.getDouble("z")));
        }
        return out;
    }

    private Target find(Player p, String[] args) {
        if (args.length >= 2 && isNumber(args[0]) && isNumber(args[args.length - 1])) {
            double x = Double.parseDouble(args[0]), z = Double.parseDouble(args[args.length - 1]);
            double y = args.length >= 3 && isNumber(args[1]) ? Double.parseDouble(args[1]) : p.getLocation().getY();
            return new Target((int) x + " " + (int) y + " " + (int) z, p.getWorld().getName(), x + 0.5, y, z + 0.5);
        }
        String name = String.join(" ", args);
        Target w = warps().get(name);
        if (w != null) return w;
        for (Employee e : plugin.manager().ownedBy(p.getUniqueId())) {
            if (e.name.equalsIgnoreCase(name) && e.location != null && e.location.getWorld() != null) {
                Location l = e.location;
                return new Target(e.name, l.getWorld().getName(), l.getX(), l.getY(), l.getZ());
            }
        }
        return null;
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    // ── the command ──────────────────────────────────────────────────────

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Only players can be guided.");
            return true;
        }
        if (args.length == 0) {
            list(p);
            return true;
        }
        if (args.length == 1 && List.of("off", "stop", "cancel").contains(args[0].toLowerCase(Locale.ROOT))) {
            Msg.send(p, guiding.remove(p.getUniqueId()) != null ? "<gray>Guide off." : "<gray>You aren't being guided anywhere.");
            return true;
        }
        Target t = find(p, args);
        if (t == null) {
            Msg.error(p, "No warp, employee or coordinates called " + String.join(" ", args) + ". <white>/guide</white> lists the warps.");
            return true;
        }
        Guiding now = guiding.get(p.getUniqueId());
        if (now != null && now.to.name().equalsIgnoreCase(t.name())) {
            guiding.remove(p.getUniqueId());
            Msg.send(p, "<gray>Guide off.");
            return true;
        }
        guiding.put(p.getUniqueId(), new Guiding(t));
        Msg.send(p, "<gold>Guiding you to " + t.name() + ".</gold> <gray>Follow the arrow and the sparks. "
                + "<white><click:run_command:'/guide off'>[Stop]</click></white>");
        return true;
    }

    private void list(Player p) {
        Map<String, Target> warps = warps();
        Guiding now = guiding.get(p.getUniqueId());
        if (now != null) Msg.send(p, "<gray>Guiding you to <white>" + now.to.name() + "</white>. <white><click:run_command:'/guide off'>[Stop]</click>");
        if (warps.isEmpty()) {
            Msg.send(p, "<gray>No warps. <white>/guide \\<x> \\<z></white> or <white>/guide \\<employee></white> work too.");
            return;
        }
        List<String> parts = new ArrayList<>();
        for (Target t : warps.values()) {
            parts.add("<click:run_command:'/guide " + t.name() + "'><hover:show_text:'<gray>" + t.world() + " " + (int) t.x() + " " + (int) t.y() + " "
                    + (int) t.z() + "'><aqua>" + t.name() + "</aqua></hover></click>");
        }
        Msg.send(p, "<gold>Walk to a warp</gold> <gray>(click one; again to stop):</gray> " + String.join("<gray>, </gray>", parts));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        if (args.length != 1) return out;
        out.add("off");
        out.addAll(warps().keySet());
        if (s instanceof Player p) for (Employee e : plugin.manager().ownedBy(p.getUniqueId())) out.add(e.name);
        String last = args[0].toLowerCase(Locale.ROOT);
        out.removeIf(o -> !o.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent ev) {
        guiding.remove(ev.getPlayer().getUniqueId());
    }

    // ── showing the way ──────────────────────────────────────────────────

    private void tick() {
        guiding.entrySet().removeIf(en -> {
            Player p = Bukkit.getPlayer(en.getKey());
            return p == null || !step(p, en.getValue());
        });
    }

    /** One update for a guided player; false once they've arrived. */
    private boolean step(Player p, Guiding g) {
        Target t = g.to;
        Location l = p.getLocation();
        if (!l.getWorld().getName().equals(t.world())) {
            p.sendActionBar(Msg.parse("<gold>" + t.name() + "</gold> <gray>is in " + t.world()));
            return true;
        }
        double dx = t.x() - l.getX(), dz = t.z() - l.getZ(), dy = t.y() - l.getY();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 4 && Math.abs(dy) < 6) {
            p.sendActionBar(Msg.parse("<green>You're at " + t.name()));
            Msg.send(p, "<green>You've arrived at " + t.name() + ".");
            p.playSound(l, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.2f);
            return false;
        }
        float bearing = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float rel = wrap(bearing - l.getYaw());
        String arrow = ARROWS[Math.floorMod(Math.round(rel / 45f), 8)];
        String height = Math.abs(dy) >= 8 ? " <gray>(" + (int) Math.abs(dy) + (dy > 0 ? " up" : " down") + ")" : "";
        p.sendActionBar(Msg.parse("<gold><bold>" + arrow + "</bold> " + t.name() + "</gold> <white>" + (int) dist + " blocks" + height));
        // A walkable way a little ahead, worked out again every few seconds.
        if (g.ticks++ % 6 == 0) g.trail = trail(p, t, dist);
        draw(p, g);
        return true;
    }

    private static float wrap(float a) {
        a %= 360;
        if (a >= 180) a -= 360;
        if (a < -180) a += 360;
        return a;
    }

    /** Up to about 32 blocks of a walkable path toward the target (a straight line if none is found). */
    private List<Vector> trail(Player p, Target t, double dist) {
        Location l = p.getLocation();
        double step = Math.min(dist, 32);
        Vector dir = new Vector(t.x() - l.getX(), 0, t.z() - l.getZ()).normalize();
        double gx = l.getX() + dir.getX() * step, gz = l.getZ() + dir.getZ() * step;
        double gy = dist <= 32 ? t.y() : l.getY();
        List<Vector> out = new ArrayList<>();
        if (!p.isFlying() && !p.isGliding() && !p.isInsideVehicle()) {
            BoundingBox box = BoundingBox.of(l.toVector(), new Vector(gx, gy, gz)).expand(10, 12, 10);
            Pathfinder pf = new Pathfinder(p.getWorld(), box).leavesOpen(false);
            List<Pathfinder.Waypoint> path = pf.find(l.toVector(), Pathfinder.near(gx, gy, gz, 3, 24, 24), 6000);
            if (path != null && !path.isEmpty()) {
                for (Pathfinder.Waypoint w : path) out.add(w.pos().clone());
                return out;
            }
        }
        for (double d = 1.5; d <= step; d += 1.5) out.add(new Vector(l.getX() + dir.getX() * d, l.getY(), l.getZ() + dir.getZ() * d));
        return out;
    }

    /** Sparks along the trail from where the player is now, about 20 blocks of it. */
    private void draw(Player p, Guiding g) {
        if (g.trail.isEmpty()) return;
        Vector at = p.getLocation().toVector();
        int start = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < g.trail.size(); i++) {
            double d = g.trail.get(i).distanceSquared(at);
            if (d < best) {
                best = d;
                start = i;
            }
        }
        Vector prev = null;
        int shown = 0;
        for (int i = start; i < g.trail.size() && shown < 20; i++) {
            Vector v = g.trail.get(i);
            if (prev != null && prev.distanceSquared(v) < 1.0) continue;
            p.spawnParticle(Particle.DUST, v.getX(), v.getY() + 0.25, v.getZ(), 2, 0.08, 0.05, 0.08, 0, DUST);
            prev = v;
            shown++;
        }
    }
}
