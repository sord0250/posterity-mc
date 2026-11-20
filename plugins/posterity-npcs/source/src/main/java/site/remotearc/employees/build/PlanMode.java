package site.remotearc.employees.build;

import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Msg;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Planning a build in the world itself: while a player plans for a builder,
 * nothing they place, break or change with WorldEdit happens for real. It
 * goes into the builder's plan instead, shown to them as ghost blocks, and
 * the builder builds it from storage like any blueprint (which it becomes:
 * plan-&lt;owner&gt;-&lt;builder&gt;.schem). Breaking a real block plans clearing
 * it; breaking a ghost takes it out of the plan.
 */
public final class PlanMode implements Listener {

    /** One player planning for one builder. */
    static final class Session {
        final UUID player, employee;
        final String world;
        String file;
        /** Position key → planned block (air: clear it). */
        final Map<Long, BlockData> blocks = new HashMap<>();
        /** What the plan was when this session began (for cancel). */
        Map<Long, BlockData> before;
        boolean isNew;
        /** The build was finished when planning began (cancelling leaves it finished). */
        boolean wasFinished;
        /** Ghosts swapped back for real blocks because the player is standing in or on them. */
        final Set<Long> melted = new HashSet<>();
        /** Changed cells not yet sent to the player. */
        final Set<Long> pending = new HashSet<>();
        /** Changed since the build order was last brought up to date / since the file was written. */
        boolean dirty, unsaved;
        long changedAt, savedAt;
        /** The face of the block the player last right-clicked (placing against a ghost lands one block out). */
        BlockFace face;
        BukkitTask task;
        int ticks;
        /** Blocks placed by hand are real (and part of the plan); false: ghosts, like WorldEdit's. */
        boolean real = true;

        Session(UUID player, UUID employee, String world, String file) {
            this.player = player;
            this.employee = employee;
            this.world = world;
            this.file = file;
        }
    }

    private static final Map<UUID, Session> SESSIONS = new ConcurrentHashMap<>();
    private static ArcEmployees plugin;

    public PlanMode(ArcEmployees plugin) {
        PlanMode.plugin = plugin;
    }

    static Session of(UUID player) {
        return SESSIONS.get(player);
    }

    /** Switches what hand placement does; returns the new setting, or null if not planning. */
    public static Boolean handReal(Player p, Boolean real) {
        Session s = SESSIONS.get(p.getUniqueId());
        if (s == null) return null;
        s.real = real != null ? real : !s.real;
        return s.real;
    }

    /** Whether someone is planning for this builder in the game right now. */
    public static boolean planningBuilder(UUID employee) {
        for (Session s : SESSIONS.values()) if (s.employee.equals(employee)) return true;
        return false;
    }

    public static boolean planning(Player p) {
        return SESSIONS.containsKey(p.getUniqueId());
    }

    /** Who the player is planning for, or null. */
    public static UUID planningFor(Player p) {
        Session s = SESSIONS.get(p.getUniqueId());
        return s == null ? null : s.employee;
    }

    // ── starting and stopping ────────────────────────────────────────────

    /** Starts planning for a builder where the player is; null, or why not. */
    public static String enter(Player p, Employee e) {
        return enter(p, e, null);
    }

    /** The same, naming a new plan (its blueprint file) instead of plan-owner-builder. */
    public static String enter(Player p, Employee e, String name) {
        return enter(p, e, name, false);
    }

    /**
     * {@code fresh}: a new, empty plan even if the builder has one already;
     * the old one stays in the blueprint folder under its own name.
     */
    public static String enter(Player p, Employee e, String name, boolean fresh) {
        if (name != null && !validName(name)) return "Names are letters, numbers, - and _ (up to 32).";
        Session old = SESSIONS.get(p.getUniqueId());
        if (old != null) {
            if (old.employee.equals(e.id)) return null;
            done(p);
        }
        BuildOrder o = e.build;
        World w = p.getWorld();
        String file;
        Map<Long, BlockData> start = new HashMap<>();
        boolean isNew;
        if (o != null && o.planned && !fresh) {
            if (o.plan == null) return "Still reading " + e.name + "'s plan; try again in a moment.";
            if (!o.world.equals(w.getName())) return e.name + "'s plan is in " + o.world + ": go there to work on it.";
            for (int y = 0; y < o.plan.sy; y++) {
                for (int z = 0; z < o.plan.sz; z++) {
                    for (int x = 0; x < o.plan.sx; x++) {
                        int idx = o.plan.get(x, y, z);
                        if (idx >= 0) start.put(o.toWorld(x, y, z).key(), o.turnedRaw(idx));
                    }
                }
            }
            file = o.blueprint;
            isNew = false;
        } else {
            if (o != null && o.started && !o.finished && !o.dismantle) {
                return e.name + " is building " + o.blueprint + ". Cancel that first, or plan with another builder.";
            }
            file = name != null ? name + ".schem" : fileName(e);
            // Never write over an older plan: plan-you-builder-2, -3...
            for (int n = 2; name == null && plugin.blueprints().resolve(file.substring(0, file.length() - 6)) != null; n++) {
                file = fileName(e).replace(".schem", "-" + n + ".schem");
            }
            if (name != null && plugin.blueprints().resolve(name) != null) return "There's already a blueprint called " + name + ". Pick another name.";
            isNew = true;
        }
        Session s = new Session(p.getUniqueId(), e.id, w.getName(), file);
        if (!isNew && o != null) {
            s.wasFinished = o.finished;
            o.onHold = true;
            e.worker.interrupt();
        }
        s.blocks.putAll(start);
        s.before = new HashMap<>(start);
        s.isNew = isNew;
        BuildPreview.clear(p);
        SESSIONS.put(p.getUniqueId(), s);
        showAll(p, s);
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(s), 1L, 1L);
        return null;
    }

    private static boolean validName(String n) {
        return n.matches("[A-Za-z0-9_-]{1,32}");
    }

    /**
     * Gives a builder's planned build a name: its blueprint file is renamed
     * (planning or not). Null, or why not.
     */
    public static String rename(Employee e, String name) {
        if (!validName(name)) return "Names are letters, numbers, - and _ (up to 32).";
        BuildOrder o = e.build;
        if (o == null || !o.planned) return e.name + " isn't building something planned in the world (blueprints keep their file names).";
        String to = name + ".schem";
        if (o.blueprint.equalsIgnoreCase(to)) return null;
        if (plugin.blueprints().resolve(name) != null) return "There's already a blueprint called " + name + ". Pick another name.";
        File old = new File(plugin.blueprints().dir(), o.blueprint), now = new File(plugin.blueprints().dir(), to);
        if (old.isFile() && !old.renameTo(now)) return "Couldn't rename the file.";
        for (Session s : SESSIONS.values()) if (s.employee.equals(e.id)) s.file = to;
        o.blueprint = to;
        plugin.manager().markDirty();
        if (!now.isFile() && o.plan != null) {
            try {
                Plans.write(now, o.plan);
            } catch (IOException ex) {
                return "Couldn't save it: " + ex.getMessage();
            }
        }
        return null;
    }

    /** Stops planning, naming the plan first. */
    public static String done(Player p, String name) {
        Session s = SESSIONS.get(p.getUniqueId());
        if (s == null) return "You're not planning.";
        if (name != null) {
            Employee e = plugin.manager().byId(s.employee);
            if (e != null) {
                update(s);
                String why = rename(e, name);
                if (why != null) return why;
            }
        }
        done(p);
        return null;
    }

    private static String fileName(Employee e) {
        return ("plan-" + e.ownerName() + "-" + e.name).replaceAll("[^A-Za-z0-9_.-]+", "_") + ".schem";
    }

    /** Stops planning and keeps the plan. */
    public static void done(Player p) {
        Session s = SESSIONS.remove(p.getUniqueId());
        if (s == null) return;
        stop(p, s);
        if (s.blocks.isEmpty()) {
            Employee e = plugin.manager().byId(s.employee);
            if (e != null && e.build != null && e.build.planned && e.build.blueprint.equals(s.file)) dropOrder(e);
            Msg.send(p, "<gray>Nothing planned, so " + (e == null ? "the builder" : e.name) + " has nothing to build.");
            return;
        }
        update(s);
        save(s, true);
        Employee e = plugin.manager().byId(s.employee);
        if (e == null || e.build == null) return;
        BuildOrder o = e.build;
        String c = "/employees build " + e.name;
        Msg.send(p, "<green>Planned " + o.plan.solidCount() + " blocks" + clears(s) + " for " + e.name + ".</green> <gray>Saved as blueprint <white>"
                + o.blueprint + "</white>, so it can be built elsewhere too.");
        p.sendMessage(Msg.parse((o.started && !o.finished ? "<gray>" + e.name + " carries on with the new plan. " : "<green><click:run_command:'" + c + " start'>[Start building]</click></green> ")
                + "<aqua><click:run_command:'" + c + " materials'>[Materials]</click></aqua> <gray><click:run_command:'" + c + " plan'>[Keep planning]</click> "
                + "<click:run_command:'" + c + " preview'>[Preview]</click>"));
    }

    private static String clears(Session s) {
        long air = s.blocks.values().stream().filter(d -> d.getMaterial().isAir()).count();
        return air > 0 ? " and " + air + " to clear" : "";
    }

    /** Stops planning and puts the plan back the way it was before. */
    public static void cancel(Player p) {
        Session s = SESSIONS.remove(p.getUniqueId());
        if (s == null) return;
        stop(p, s);
        Employee e = plugin.manager().byId(s.employee);
        if (e == null) return;
        if (s.isNew || s.before.isEmpty()) {
            if (e.build != null && e.build.planned && e.build.blueprint.equals(s.file)) dropOrder(e);
            Msg.send(p, "<gray>Plan dropped.");
            return;
        }
        s.blocks.clear();
        s.blocks.putAll(s.before);
        update(s);
        if (s.wasFinished && e.build != null) e.build.finished = true;   // nothing changed: nothing to redo
        save(s, true);
        Msg.send(p, "<gray>Plan put back the way it was.");
    }

    private static void stop(Player p, Session s) {
        if (s.task != null) s.task.cancel();
        Employee held = plugin.manager().byId(s.employee);
        if (held != null && held.build != null && held.build.onHold) {
            held.build.onHold = false;
            held.worker.interrupt();
        }
        // The real blocks back on screen.
        World w = Bukkit.getWorld(s.world);
        if (w != null && p.isOnline() && p.getWorld().equals(w)) {
            Map<Position, BlockData> real = new HashMap<>();
            for (long k : s.blocks.keySet()) {
                BlockPos b = BlockPos.unpack(k);
                if (w.isChunkLoaded(b.x() >> 4, b.z() >> 4)) real.put(Position.block(b.x(), b.y(), b.z()), w.getBlockData(b.x(), b.y(), b.z()));
            }
            if (!real.isEmpty()) p.sendMultiBlockChange(real);
        }
        // WorldEdit's undo history holds the planned blocks: undoing after this would place them for real.
        if (Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
            try {
                PlanWorldEdit.clearHistory(p);
            } catch (NoClassDefFoundError ignored) {
                // no WorldEdit
            }
        }
    }

    private static void dropOrder(Employee e) {
        BuildOrder o = e.build;
        double v = o.takeStockValue();
        if (v > 0 && plugin.economy() != null) plugin.economy().depositPlayer(Bukkit.getOfflinePlayer(e.owner), v);
        e.build = null;
        e.worker.interrupt();
        plugin.manager().markDirty();
    }

    public static void stopAll() {
        for (UUID id : new ArrayList<>(SESSIONS.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) done(p);
            else SESSIONS.remove(id);
        }
    }

    // ── changing the plan ────────────────────────────────────────────────

    /** Plans a block (air: clear what's there; null: take it out of the plan, leaving the world as it is). */
    static void set(Session s, int x, int y, int z, BlockData d) {
        long k = BlockPos.pack(x, y, z);
        BlockData was = d == null ? s.blocks.remove(k) : s.blocks.put(k, d);
        if (d == null ? was == null : d.equals(was)) return;
        s.dirty = true;
        s.unsaved = true;
        s.changedAt = Bukkit.getCurrentTick();
        if (!s.melted.contains(k)) s.pending.add(k);
        if (s.pending.size() >= 4096) flush(s);
    }

    /** Sends the changed ghosts (and real blocks where ghosts went) in one go. */
    private static void flush(Session s) {
        if (s.pending.isEmpty()) return;
        Player p = Bukkit.getPlayer(s.player);
        World w = Bukkit.getWorld(s.world);
        if (p != null && w != null && p.getWorld().equals(w)) {
            Map<Position, BlockData> send = new HashMap<>();
            for (long k : s.pending) {
                BlockPos b = BlockPos.unpack(k);
                if (!w.isChunkLoaded(b.x() >> 4, b.z() >> 4)) continue;
                BlockData d = s.blocks.get(k);
                send.put(Position.block(b.x(), b.y(), b.z()), d != null ? d : w.getBlockData(b.x(), b.y(), b.z()));
            }
            if (!send.isEmpty()) p.sendMultiBlockChange(send);
        }
        s.pending.clear();
    }

    /** The block the plan has there, or null if it leaves the world alone. */
    static BlockData get(Session s, int x, int y, int z) {
        return s.blocks.get(BlockPos.pack(x, y, z));
    }

    /** Brings the builder's order up to date with the plan. */
    private static void update(Session s) {
        s.dirty = false;
        Employee e = plugin.manager().byId(s.employee);
        if (e == null || s.blocks.isEmpty()) return;
        Plans.Built b;
        try {
            b = Plans.build(s.file, s.blocks, plugin.settings().builderMaxVolume, s.world);
        } catch (IOException ex) {
            Player p = Bukkit.getPlayer(s.player);
            if (p != null) p.sendActionBar(Msg.parse("<red>Plan too big: " + ex.getMessage()));
            return;
        }
        BuildOrder o = e.build;
        if (o == null || !o.planned || !o.blueprint.equals(s.file)) {
            if (o != null) {
                double v = o.takeStockValue();
                if (v > 0 && plugin.economy() != null) plugin.economy().depositPlayer(Bukkit.getOfflinePlayer(e.owner), v);
            }
            o = new BuildOrder(s.file, s.world, 0, 0, 0);
            o.planned = true;
            o.keepGround = true;   // everything planned is meant
            e.build = o;
        }
        o.skipped.clear();
        o.rotation = 0;
        o.flip = false;
        o.setPlan(b.bp());
        o.x = b.minX() + b.bp().sx / 2;
        o.y = b.minY();
        o.z = b.minZ() + b.bp().sz / 2;
        o.turnedChanged();
        if (o.finished) o.finished = false;
        o.layer = 0;
        // While planning, the builder only waits (and the materials and preview keep up); it builds once you press Done.
        o.onHold = SESSIONS.get(s.player) == s;
        e.worker.interrupt();
        plugin.manager().setArea(e, o.bounds().expand(2, 2));
        plugin.manager().markDirty();
    }

    /** Writes the plan's blueprint file (in the background unless now). */
    private static void save(Session s, boolean now) {
        s.unsaved = false;
        s.savedAt = Bukkit.getCurrentTick();
        Employee e = plugin.manager().byId(s.employee);
        if (e == null || e.build == null || e.build.plan == null || !e.build.blueprint.equals(s.file)) return;
        Blueprint bp = e.build.plan;
        File f = new File(plugin.blueprints().dir(), s.file);
        Runnable write = () -> {
            try {
                Plans.write(f, bp);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not save the plan " + f.getName(), ex);
            }
        };
        if (now) write.run();
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, write);
    }

    // ── showing it ───────────────────────────────────────────────────────

    private static void showAll(Player p, Session s) {
        World w = Bukkit.getWorld(s.world);
        if (w == null || !p.getWorld().equals(w)) return;
        Map<Position, BlockData> send = new HashMap<>();
        Location at = p.getLocation();
        int view = (Bukkit.getViewDistance() + 1) * 16;
        for (Map.Entry<Long, BlockData> en : s.blocks.entrySet()) {
            if (s.melted.contains(en.getKey())) continue;
            BlockPos b = BlockPos.unpack(en.getKey());
            if (Math.abs(b.x() - at.getBlockX()) > view || Math.abs(b.z() - at.getBlockZ()) > view) continue;
            if (!w.isChunkLoaded(b.x() >> 4, b.z() >> 4)) continue;
            send.put(Position.block(b.x(), b.y(), b.z()), en.getValue());
        }
        if (!send.isEmpty()) p.sendMultiBlockChange(send);
    }

    private static void tick(Session s) {
        Player p = Bukkit.getPlayer(s.player);
        if (p == null) return;
        s.ticks++;
        flush(s);
        melt(p, s);
        long now = Bukkit.getCurrentTick();
        if (s.dirty && now - s.changedAt >= 10) update(s);
        if (s.unsaved && !s.dirty && now - s.savedAt >= 20 * 60) save(s, false);
        // Chunks the client reloads lose their ghosts: show them all again now and then.
        if (s.ticks % 100 == 0) showAll(p, s);
        if (s.ticks % 40 == 0) {
            p.sendActionBar(Msg.parse("<aqua>Planning</aqua> <gray>(" + s.blocks.size() + " blocks) - WorldEdit only plans; "
                    + (s.real ? "blocks you place are real" : "blocks you place are ghosts") + ". <white>/emp plan done</white>"));
        }
    }

    /** Ghosts the player stands in or on go away (the client would treat them as solid), and come back once they've moved off. */
    private static void melt(Player p, Session s) {
        World w = Bukkit.getWorld(s.world);
        if (w == null || !w.equals(p.getWorld())) return;
        org.bukkit.util.BoundingBox box = p.getBoundingBox().expand(0.2, 0.6, 0.2);
        Set<Long> now = new HashSet<>();
        for (int x = (int) Math.floor(box.getMinX()); x <= (int) Math.floor(box.getMaxX()); x++) {
            for (int y = (int) Math.floor(box.getMinY()); y <= (int) Math.floor(box.getMaxY()); y++) {
                for (int z = (int) Math.floor(box.getMinZ()); z <= (int) Math.floor(box.getMaxZ()); z++) {
                    long k = BlockPos.pack(x, y, z);
                    BlockData d = s.blocks.get(k);
                    if (d != null && !d.getMaterial().isAir()) now.add(k);
                }
            }
        }
        if (now.equals(s.melted)) return;
        Map<Position, BlockData> send = new HashMap<>();
        for (long k : s.melted) {
            if (now.contains(k) || !s.blocks.containsKey(k)) continue;
            BlockPos b = BlockPos.unpack(k);
            send.put(Position.block(b.x(), b.y(), b.z()), s.blocks.get(k));
        }
        for (long k : now) {
            if (s.melted.contains(k)) continue;
            BlockPos b = BlockPos.unpack(k);
            send.put(Position.block(b.x(), b.y(), b.z()), w.getBlockData(b.x(), b.y(), b.z()));
        }
        s.melted.clear();
        s.melted.addAll(now);
        if (!send.isEmpty()) p.sendMultiBlockChange(send);
    }

    // ── placing and breaking by hand ─────────────────────────────────────

    private static Session here(Player p) {
        Session s = SESSIONS.get(p.getUniqueId());
        return s != null && p.getWorld().getName().equals(s.world) ? s : null;
    }

    /** Doors and tall plants: the top half that comes with a bottom half (stairs and trapdoors only look alike). */
    private static BlockData topHalf(BlockData d) {
        if (d instanceof Bisected bi && bi.getHalf() == Bisected.Half.BOTTOM && !(d instanceof org.bukkit.block.data.type.Stairs)
                && !(d instanceof org.bukkit.block.data.type.TrapDoor)) {
            Bisected top = (Bisected) d.clone();
            top.setHalf(Bisected.Half.TOP);
            return top;
        }
        return null;
    }

    /** What the player just placed, as the plan has it: every part of a bed, both halves of a door. */
    private static void record(Session s, BlockPlaceEvent ev, int x, int y, int z, BlockData d) {
        if (ev instanceof BlockMultiPlaceEvent multi) {
            for (BlockState st : multi.getReplacedBlockStates()) {
                Block b = st.getBlock();
                set(s, b.getX(), b.getY(), b.getZ(), b.getBlockData().clone());
            }
            return;
        }
        set(s, x, y, z, d);
        BlockData top = topHalf(d);
        if (top != null) set(s, x, y + 1, z, top);
    }

    /** Placing against a ghost: the server sees air there and puts the block in the ghost's spot. It belongs one out. */
    private static boolean againstGhost(Session s, BlockPlaceEvent ev) {
        Block placed = ev.getBlockPlaced();
        BlockData ghost = get(s, placed.getX(), placed.getY(), placed.getZ());
        return ev.getBlockAgainst().equals(placed) && ghost != null && !ghost.getMaterial().isAir() && s.face != null
                && !ghost.equals(placed.getBlockData());
    }

    /** Shows the ghost again where a placement just knocked it off the player's screen. */
    private static void reshowLater(Player p, Session s, Block b) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            flush(s);
            BlockData g = get(s, b.getX(), b.getY(), b.getZ());
            if (g != null && p.isOnline() && !g.equals(b.getBlockData())) p.sendBlockChange(b.getLocation(), g);
        }, 1L);
    }

    /** Ghost blocks by hand: nothing placed for real, nothing used up. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlaceGhost(BlockPlaceEvent ev) {
        Session s = here(ev.getPlayer());
        if (s == null || s.real) return;
        ev.setCancelled(true);
        Block placed = ev.getBlockPlaced();
        BlockData d = placed.getBlockData().clone();
        int x = placed.getX(), y = placed.getY(), z = placed.getZ();
        if (againstGhost(s, ev)) {
            x += s.face.getModX();
            y += s.face.getModY();
            z += s.face.getModZ();
        }
        record(s, ev, x, y, z, d);
        reshowLater(ev.getPlayer(), s, placed);
    }

    /**
     * Real blocks by hand, alongside WorldEdit's planning: placed as usual
     * (protection permitting, items used up) and made part of the plan, so
     * the builder counts them as done and the blueprint has them.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlaceReal(BlockPlaceEvent ev) {
        Player p = ev.getPlayer();
        Session s = here(p);
        if (s == null || !s.real) return;
        Block placed = ev.getBlockPlaced();
        BlockData d = placed.getBlockData().clone();
        if (!againstGhost(s, ev) || ev instanceof BlockMultiPlaceEvent) {
            record(s, ev, placed.getX(), placed.getY(), placed.getZ(), d);
            return;
        }
        // Against a ghost: put it where the player meant, one block out.
        ev.setCancelled(true);
        Block target = placed.getRelative(s.face);
        BlockData there = get(s, target.getX(), target.getY(), target.getZ());
        if (!target.isReplaceable() || (there != null && !there.getMaterial().isAir())) {
            reshowLater(p, s, placed);
            return;
        }
        target.setBlockData(d, true);
        BlockData top = topHalf(d);
        Block above = target.getRelative(BlockFace.UP);
        if (top != null && above.isReplaceable()) above.setBlockData(top, true);
        if (p.getGameMode() != org.bukkit.GameMode.CREATIVE) {
            org.bukkit.inventory.ItemStack hand = p.getInventory().getItem(ev.getHand());
            if (hand != null && hand.getAmount() > 0) hand.setAmount(hand.getAmount() - 1);
        }
        target.getWorld().playSound(target.getLocation(), d.getSoundGroup().getPlaceSound(), 1f, 0.8f);
        plugin.blockLog().placed(p, target);
        set(s, target.getX(), target.getY(), target.getZ(), d);
        if (top != null) set(s, target.getX(), target.getY() + 1, target.getZ(), top);
        reshowLater(p, s, placed);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreakGhost(BlockBreakEvent ev) {
        Session s = here(ev.getPlayer());
        if (s == null || s.real) return;
        ev.setCancelled(true);   // the interact handler below did the planning
    }

    /** Breaking a real block that's part of the plan takes it out of the plan too. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreakReal(BlockBreakEvent ev) {
        Session s = here(ev.getPlayer());
        if (s == null || !s.real) return;
        Block b = ev.getBlock();
        BlockData planned = get(s, b.getX(), b.getY(), b.getZ());
        if (planned == null || planned.getMaterial().isAir()) return;
        set(s, b.getX(), b.getY(), b.getZ(), null);
        BlockData top = topHalf(planned);
        if (top != null && get(s, b.getX(), b.getY() + 1, b.getZ()) != null) set(s, b.getX(), b.getY() + 1, b.getZ(), null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent ev) {
        Player p = ev.getPlayer();
        Session s = here(p);
        if (s == null || ev.getHand() == EquipmentSlot.OFF_HAND) return;
        if (ev.getAction() == Action.RIGHT_CLICK_BLOCK) {
            s.face = ev.getBlockFace();
            return;
        }
        if (ev.getAction() != Action.LEFT_CLICK_BLOCK && ev.getAction() != Action.LEFT_CLICK_AIR) return;
        // The WorldEdit wand and bound tools do their own thing.
        Material held = p.getInventory().getItemInMainHand().getType();
        if (held == Material.WOODEN_AXE || held == Material.COMPASS) return;
        if (Bukkit.getPluginManager().isPluginEnabled("WorldEdit")) {
            try {
                if (PlanWorldEdit.hasTool(p, held)) return;
            } catch (NoClassDefFoundError ignored) {
                // no WorldEdit
            }
        }
        BlockPos hit = target(p, s);
        if (hit == null) return;
        BlockData planned = get(s, hit.x(), hit.y(), hit.z());
        World w = p.getWorld();
        boolean ghost = planned != null && !planned.getMaterial().isAir() && !planned.equals(w.getBlockData(hit.x(), hit.y(), hit.z()));
        if (s.real && !ghost) return;   // a real block: broken for real, as usual
        ev.setCancelled(true);
        if (planned != null && !planned.getMaterial().isAir()) {
            // A ghost: out of the plan. If it's a door or tall plant, the other half goes too.
            set(s, hit.x(), hit.y(), hit.z(), null);
            if (planned instanceof Bisected bi && !(planned instanceof org.bukkit.block.data.type.Stairs) && !(planned instanceof org.bukkit.block.data.type.TrapDoor)) {
                int other = bi.getHalf() == Bisected.Half.BOTTOM ? 1 : -1;
                BlockData o = get(s, hit.x(), hit.y() + other, hit.z());
                if (o != null && o.getMaterial() == planned.getMaterial()) set(s, hit.x(), hit.y() + other, hit.z(), null);
            }
        } else {
            set(s, hit.x(), hit.y(), hit.z(), Material.AIR.createBlockData());   // a real block: plan clearing it
        }
        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_WOOD_HIT, 0.4f, 1.4f);
    }

    /**
     * The block the player is looking at as they see it: the first ghost, or
     * real block that isn't planned away, along their view (5 blocks, 6 in creative).
     */
    private static BlockPos target(Player p, Session s) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection().normalize().multiply(0.05);
        Vector at = eye.toVector();
        double reach = p.getGameMode() == org.bukkit.GameMode.CREATIVE ? 6 : 5;
        int lx = Integer.MIN_VALUE, ly = 0, lz = 0;
        World w = p.getWorld();
        for (int i = 0; i < reach * 20; i++) {
            at.add(dir);
            int x = at.getBlockX(), y = at.getBlockY(), z = at.getBlockZ();
            if (x == lx && y == ly && z == lz) continue;
            lx = x;
            ly = y;
            lz = z;
            BlockData planned = get(s, x, y, z);
            if (planned != null) {
                if (!planned.getMaterial().isAir()) return new BlockPos(x, y, z);
                continue;   // planned away: looking through it
            }
            Block b = w.getBlockAt(x, y, z);
            if (!b.getType().isAir() && !b.isLiquid()) return new BlockPos(x, y, z);
        }
        return null;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent ev) {
        if (SESSIONS.containsKey(ev.getPlayer().getUniqueId())) done(ev.getPlayer());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent ev) {
        Session s = SESSIONS.get(ev.getPlayer().getUniqueId());
        if (s != null && ev.getFrom().getName().equals(s.world)) {
            Msg.send(ev.getPlayer(), "<gray>Left the world you were planning in: planning stopped and the plan kept.");
            done(ev.getPlayer());
        }
    }
}
