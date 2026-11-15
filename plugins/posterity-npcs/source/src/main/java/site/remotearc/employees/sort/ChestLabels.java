package site.remotearc.employees.sort;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Little signs on the front of chests and barrels, seen only by the player
 * who turned them on: what each one holds (its category, the most of what's
 * in it) or, for a minute after /sort organize, what the plan puts there.
 * Each sits flat on its own container's front, so neighbours don't overlap.
 */
public final class ChestLabels {

    private static final float SCALE = 0.28f;
    private static final int RANGE = 8, MAX = 160;

    private static final class Shown {
        final Map<String, TextDisplay> byBox = new HashMap<>();
        final Map<String, String> text = new HashMap<>();
        Map<String, String> plan;
        long planUntil;
    }

    private final ArcEmployees plugin;
    private final Sorter sorter;
    private final Map<UUID, Shown> shown = new HashMap<>();
    private BukkitTask task;

    ChestLabels(ArcEmployees plugin, Sorter sorter) {
        this.plugin = plugin;
        this.sorter = sorter;
    }

    void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    void stop() {
        if (task != null) task.cancel();
        shown.values().forEach(s -> s.byBox.values().forEach(TextDisplay::remove));
        shown.clear();
    }

    /** For a minute: these texts on these containers (box key → text). */
    void showPlan(Player p, Map<String, String> texts) {
        Shown s = shown.computeIfAbsent(p.getUniqueId(), k -> new Shown());
        s.plan = texts;
        s.planUntil = System.currentTimeMillis() + 60_000;
        update(p, s);
    }

    void clearPlan(Player p) {
        Shown s = shown.get(p.getUniqueId());
        if (s == null) return;
        s.plan = null;
        update(p, s);
    }

    private void tick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Shown s = shown.get(p.getUniqueId());
            boolean on = sorter.profiles().get(p.getUniqueId()).labels;
            if (s == null && !on) continue;
            if (s == null) shown.put(p.getUniqueId(), s = new Shown());
            update(p, s);
        }
        shown.keySet().removeIf(id -> {
            if (Bukkit.getPlayer(id) != null) return false;
            shown.get(id).byBox.values().forEach(TextDisplay::remove);
            return true;
        });
    }

    private void update(Player p, Shown s) {
        if (s.plan != null && System.currentTimeMillis() > s.planUntil) s.plan = null;
        boolean live = sorter.profiles().get(p.getUniqueId()).labels;
        Map<String, Sorter.Box> boxes = new HashMap<>();
        if (live) for (Sorter.Box b : near(p)) boxes.put(b.key, b);
        if (s.plan != null) {
            for (String k : s.plan.keySet()) {
                if (boxes.containsKey(k) || k.startsWith("vc:")) continue;
                Location at = Sorter.location(k);
                if (at == null || !at.getWorld().equals(p.getWorld()) || !at.isChunkLoaded()) continue;
                Sorter.Box b = sorter.boxAt(at.getBlock());
                if (b != null) boxes.put(b.key, b);
            }
        }
        SortProfiles.Profile prof = sorter.profiles().get(p.getUniqueId());
        Set<String> keep = new HashSet<>();
        for (Sorter.Box b : boxes.values()) {
            if (keep.size() >= MAX) break;
            String text = s.plan != null && s.plan.containsKey(b.key) ? s.plan.get(b.key) : live ? liveText(prof, b) : null;
            if (text == null) continue;
            int lines = (int) text.chars().filter(ch -> ch == '\n').count() + 1;
            for (Anchor a : anchors(b, lines)) {
                String key = b.key + "|" + a.face;
                keep.add(key);
                TextDisplay d = s.byBox.get(key);
                if (d == null || !d.isValid() || d.getLocation().distanceSquared(a.at) > 0.0001) {
                    if (d != null) d.remove();
                    d = spawn(p, a);
                    s.byBox.put(key, d);
                    s.text.remove(key);
                }
                if (!text.equals(s.text.get(key))) {
                    d.text(Msg.parse(text));
                    s.text.put(key, text);
                }
            }
        }
        s.byBox.entrySet().removeIf(e -> {
            if (keep.contains(e.getKey())) return false;
            e.getValue().remove();
            s.text.remove(e.getKey());
            return true;
        });
    }

    /** Containers round the player, from the chunks' block entities. */
    private List<Sorter.Box> near(Player p) {
        List<Sorter.Box> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Location l = p.getLocation();
        World w = p.getWorld();
        for (int cx = (l.getBlockX() - RANGE) >> 4; cx <= (l.getBlockX() + RANGE) >> 4; cx++) {
            for (int cz = (l.getBlockZ() - RANGE) >> 4; cz <= (l.getBlockZ() + RANGE) >> 4; cz++) {
                if (!w.isChunkLoaded(cx, cz)) continue;
                Chunk c = w.getChunkAt(cx, cz);
                for (BlockState st : c.getTileEntities(false)) {
                    if (!(st instanceof Container)) continue;
                    Material m = st.getType();
                    if (m != Material.CHEST && m != Material.TRAPPED_CHEST && m != Material.BARREL) continue;
                    if (Math.abs(st.getX() - l.getBlockX()) > RANGE || Math.abs(st.getZ() - l.getBlockZ()) > RANGE
                            || Math.abs(st.getY() - l.getBlockY()) > 5) continue;
                    Block b = st.getBlock();
                    if (!p.hasPermission("employees.admin") && !plugin.estate().mayWork(p.getUniqueId(), w, b.getX(), b.getY(), b.getZ())) continue;
                    Sorter.Box box = sorter.boxAt(b);
                    if (box != null && seen.add(box.key)) out.add(box);
                }
            }
        }
        return out;
    }

    /** What the container is for (kept for a category, or what it mostly holds) and the most of what's in it. */
    private static String liveText(SortProfiles.Profile prof, Sorter.Box b) {
        Map<Material, Integer> counts = new EnumMap<>(Material.class);
        int used = 0;
        for (ItemStack it : b.inv.getStorageContents()) {
            if (it == null || it.getType().isAir()) continue;
            counts.merge(it.getType(), it.getAmount(), Integer::sum);
            used++;
        }
        String pin = prof.pinnedTo(b.key);
        StringBuilder sb = new StringBuilder();
        if (pin != null) sb.append("<aqua>").append(prof.name(pin)).append("</aqua>");
        if (counts.isEmpty()) return sb.append(sb.isEmpty() ? "" : "\n").append("<gray>empty").toString();
        if (pin == null) {
            Map<String, Integer> cats = new HashMap<>();
            counts.forEach((m, n) -> cats.merge(prof.categoryOf(m), n, Integer::sum));
            String top = cats.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(Categories.MISC);
            sb.append("<gold>").append(prof.name(top)).append("</gold>");
        }
        List<Map.Entry<Material, Integer>> list = new ArrayList<>(counts.entrySet());
        list.sort((x, y) -> y.getValue() - x.getValue());
        for (int i = 0; i < list.size() && i < 2; i++) {
            sb.append("\n<white>").append(list.get(i).getValue()).append(" ").append(Items.name(list.get(i).getKey()));
        }
        if (list.size() > 2) sb.append("\n<gray>+").append(list.size() - 2).append(" more, ").append(used).append("/").append(b.size()).append(" slots");
        else sb.append("\n<gray>").append(used).append("/").append(b.size()).append(" slots");
        return sb.toString();
    }

    /** Where a label goes, and how wide it may be; one per side that can be seen. */
    private record Anchor(Location at, int width, BlockFace face) {}

    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};

    /**
     * Flat on every side of the container that isn't against a solid block
     * or another container: the front (below a chest's latch, so it isn't
     * covered), the ends and the back; on top for a barrel facing up or down.
     */
    private static List<Anchor> anchors(Sorter.Box b, int lines) {
        Block block = b.at.getBlock();
        boolean barrel = block.getType() == Material.BARREL;
        BlockFace front = block.getBlockData() instanceof Directional dir ? dir.getFacing() : BlockFace.NORTH;
        double cx = b.at.getBlockX() + 0.5, cz = b.at.getBlockZ() + 0.5, by = b.at.getBlockY();
        // Half the container's size each way (a chest is a sixteenth smaller than its block all round).
        double ex = barrel ? 0.5 : 0.4375, ez = ex;
        List<Block> parts = new ArrayList<>(List.of(block));
        if (b.inv.getHolder(false) instanceof DoubleChest dc && dc.getRightSide(false) instanceof BlockState right
                && dc.getLeftSide(false) instanceof BlockState left) {
            cx = (left.getX() + right.getX()) / 2.0 + 0.5;
            cz = (left.getZ() + right.getZ()) / 2.0 + 0.5;
            if (left.getX() != right.getX()) ex += 0.5;
            else ez += 0.5;
            parts = List.of(left.getBlock(), right.getBlock());
        }
        double lineH = 0.25 * SCALE, textH = lines * lineH + 0.02;
        List<Anchor> out = new ArrayList<>();
        boolean any = false;
        for (BlockFace f : SIDES) {
            // Seen from this side only if a part of it on that side has open space next to it.
            boolean open = false;
            for (Block part : parts) {
                Block next = part.getRelative(f);
                if (parts.contains(next)) continue;
                Material m = next.getType();
                if (!m.isOccluding() && m != Material.CHEST && m != Material.TRAPPED_CHEST && m != Material.BARREL) open = true;
            }
            if (!open) continue;
            boolean xAxis = f == BlockFace.EAST || f == BlockFace.WEST;
            double off = (xAxis ? ex : ez) + 0.015, span = 2 * (xAxis ? ez : ex) - 0.05;
            int width = (int) (span * 40 / SCALE);
            double y;
            if (!barrel && f == front) y = by + 0.05;                  // under the latch
            else y = by + (barrel ? 0.5 : 0.44) - textH / 2;            // the middle of the side
            Location at = new Location(block.getWorld(), cx + f.getModX() * off, Math.max(by + 0.03, y), cz + f.getModZ() * off, yaw(f), 0);
            out.add(new Anchor(at, width, f));
            any = true;
        }
        if (!any || (barrel && (front == BlockFace.UP || front == BlockFace.DOWN))) {
            // On top: a barrel looking up, or a container boxed in on every side.
            if (!block.getRelative(BlockFace.UP).getType().isOccluding()) {
                double h = barrel ? 1.0 : 0.875;
                float yaw = front == BlockFace.UP || front == BlockFace.DOWN ? 0 : yaw(front);
                out.add(new Anchor(new Location(block.getWorld(), cx, by + h + 0.01, cz, yaw, -90), (int) (2 * Math.min(ex, ez) * 40 / SCALE), BlockFace.UP));
            }
        }
        return out;
    }

    private static float yaw(BlockFace f) {
        return switch (f) {
            case SOUTH -> 0;
            case WEST -> 90;
            case NORTH -> 180;
            default -> -90;
        };
    }

    private TextDisplay spawn(Player p, Anchor a) {
        TextDisplay d = a.at.getWorld().spawn(a.at, TextDisplay.class, t -> {
            t.setBillboard(Display.Billboard.FIXED);
            t.setPersistent(false);
            t.setVisibleByDefault(false);
            t.setLineWidth(a.width);
            t.setDefaultBackground(false);
            t.setBackgroundColor(Color.fromARGB(150, 0, 0, 0));
            t.setShadowed(false);
            t.setBrightness(new Display.Brightness(15, 15));
            t.setViewRange(0.3f);   // about 20 blocks
            t.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(), new Vector3f(SCALE, SCALE, SCALE), new AxisAngle4f()));
        });
        p.showEntity(plugin, d);
        return d;
    }
}
