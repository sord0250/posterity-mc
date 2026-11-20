package site.remotearc.employees.gui;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.Settings;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.util.BlockPos;
import site.remotearc.employees.util.Msg;
import site.remotearc.employees.util.WorkArea;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Leaving your body to pick places for an employee. Your body stays behind
 * as a standing figure; you float above the employee's work site, invisible
 * to everyone else, and fly about for a bird's-eye view (through open air
 * only: no going through walls). Then:
 *
 * <ul>
 *   <li>Work area: left-click drops a corner where you look, and the outline
 *   stretches to wherever you look until you left-click again. Quarry and
 *   mine areas then take a depth: scroll the mouse wheel, left-click to
 *   confirm.</li>
 *   <li>Storage or pantry: left-click the chest.</li>
 *   <li>Right-click goes back a step; F (swap hands) cancels; either way you
 *   snap back into your body.</li>
 * </ul>
 *
 * While out you can't hurt or be hurt, pick anything up, open anything or use
 * other commands, and you can't float more than {@value #RANGE} blocks from
 * where you started. If you log out (or the server stops) you're put back in
 * your body; a crash is caught when you next join.
 */
public final class OutOfBody implements Listener {

    public enum Goal { AREA, STORAGE, PANTRY, ZONE, KINGDOM_HANGOUT }

    private static final int RANGE = 96;
    private static final double REACH = 160;
    /** No job settings to size a kingdom hangout spot by, so it gets its own flat cap. */
    private static final int MAX_HANGOUT_SIZE = 128;
    private static final long TIMEOUT_TICKS = 20L * 60 * 5;
    private static final Particle.DustOptions EDGE = new Particle.DustOptions(Color.fromRGB(80, 255, 120), 1.4f);
    private static final Particle.DustOptions CORNER = new Particle.DustOptions(Color.fromRGB(255, 200, 40), 2.0f);
    private static final Particle.DustOptions CURSOR = new Particle.DustOptions(Color.fromRGB(255, 255, 255), 1.2f);
    private static final Particle.DustOptions BAD = new Particle.DustOptions(Color.fromRGB(255, 70, 70), 1.4f);

    private static OutOfBody instance;

    private final ArcEmployees plugin;
    private final NamespacedKey savedKey;
    private final Map<UUID, Session> sessions = new HashMap<>();
    /** When each player was last hurt by something (no leaving your body mid-fight). */
    private final Map<UUID, Long> lastHurt = new HashMap<>();

    /** Where you were and how you were playing, to put back afterwards. */
    private record Saved(Location body, GameMode mode, boolean allowFlight, boolean flying, float flySpeed,
                         boolean invulnerable, boolean collidable, boolean pickup) {

        String save() {
            return body.getWorld().getName() + ";" + body.getX() + ";" + body.getY() + ";" + body.getZ() + ";" + body.getYaw() + ";"
                    + body.getPitch() + ";" + mode.name() + ";" + allowFlight + ";" + flying + ";" + flySpeed + ";" + invulnerable + ";"
                    + collidable + ";" + pickup;
        }

        static Saved load(String s) {
            try {
                String[] p = s.split(";");
                World w = Bukkit.getWorld(p[0]);
                if (w == null) w = Bukkit.getWorlds().get(0);
                Location at = new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                        Float.parseFloat(p[4]), Float.parseFloat(p[5]));
                return new Saved(at, GameMode.valueOf(p[6]), Boolean.parseBoolean(p[7]), Boolean.parseBoolean(p[8]),
                        Float.parseFloat(p[9]), Boolean.parseBoolean(p[10]), Boolean.parseBoolean(p[11]), Boolean.parseBoolean(p[12]));
            } catch (RuntimeException ex) {
                return null;
            }
        }
    }

    private final class Session {
        final Player p;
        /** Null only for KINGDOM_HANGOUT: picking a spot isn't about any one employee. */
        final Employee e;
        final Goal goal;
        final Saved saved;
        final Location anchor;
        /** KINGDOM_HANGOUT only: the name it's given the new spot. */
        final String hangoutName;
        Mannequin body;
        BukkitTask task;
        BlockPos a, b;
        /** Quarry and mine areas: picking the depth (after both corners). */
        boolean depthStep;
        int depth;
        long ticks;
        long lastClick = -100;
        /** Our own teleports, which the teleport guard lets through. */
        boolean moving;

        Session(Player p, Employee e, Goal goal, Saved saved, Location anchor, String hangoutName) {
            this.p = p;
            this.e = e;
            this.goal = goal;
            this.saved = saved;
            this.anchor = anchor;
            this.hangoutName = hangoutName;
        }
    }

    public OutOfBody(ArcEmployees plugin) {
        this.plugin = plugin;
        this.savedKey = new NamespacedKey(plugin, "out-of-body");
        instance = this;
    }

    /** Leaves the player's body to pick a place for this employee. */
    public static void start(ArcEmployees plugin, Player p, Employee e, Goal goal) {
        if (instance == null) {
            Msg.error(p, "That isn't available right now.");
            return;
        }
        instance.begin(p, e, goal, null);
    }

    /** Leaves the player's body to pick an area for a new, named kingdom hangout spot (no employee involved). */
    public static void startForHangout(ArcEmployees plugin, Player p, String name) {
        if (instance == null) {
            Msg.error(p, "That isn't available right now.");
            return;
        }
        instance.begin(p, null, Goal.KINGDOM_HANGOUT, name);
    }

    /** Everyone back in their bodies (plugin stopping). */
    public static void stopAll() {
        if (instance == null) return;
        for (Session s : new java.util.ArrayList<>(instance.sessions.values())) instance.end(s, null);
    }

    public static boolean isOut(Player p) {
        return instance != null && instance.sessions.containsKey(p.getUniqueId());
    }

    // ── going out and coming back ─────────────────────────────────────────

    private void begin(Player p, Employee e, Goal goal, String hangoutName) {
        if (sessions.containsKey(p.getUniqueId())) {
            Msg.error(p, "You're already out of your body. Press <white>F</white> to go back.");
            return;
        }
        if (goal == Goal.AREA && e.job == JobType.BUILDER) {
            Msg.error(p, "A builder's area follows its blueprint.");
            return;
        }
        if (p.getGameMode() == GameMode.SPECTATOR || p.isInsideVehicle() || p.isDead() || p.isSleeping()) {
            Msg.error(p, "You can't leave your body right now.");
            return;
        }
        Long hurt = lastHurt.get(p.getUniqueId());
        if (hurt != null && System.currentTimeMillis() - hurt < 10_000) {
            Msg.error(p, "Not in the middle of a fight. Try again in a few seconds.");
            return;
        }
        Location anchor = goal == Goal.KINGDOM_HANGOUT ? hangoutAnchor(p) : startSpot(e);
        if (anchor == null) {
            Msg.error(p, (e != null ? e.name + "'s" : "Your") + " world isn't loaded.");
            return;
        }
        Saved saved = new Saved(p.getLocation().clone(), p.getGameMode(), p.getAllowFlight(), p.isFlying(), p.getFlySpeed(),
                p.isInvulnerable(), p.isCollidable(), p.getCanPickupItems());
        p.getPersistentDataContainer().set(savedKey, PersistentDataType.STRING, saved.save());
        Session s = new Session(p, e, goal, saved, anchor, hangoutName);
        sessions.put(p.getUniqueId(), s);

        s.body = spawnBody(p);
        p.closeInventory();
        p.setGameMode(GameMode.ADVENTURE);
        p.setAllowFlight(true);
        p.setFlying(true);
        p.setFlySpeed(0.15f);
        p.setInvulnerable(true);
        p.setCollidable(false);
        p.setCanPickupItems(false);
        for (Player other : Bukkit.getOnlinePlayers()) if (!other.equals(p)) other.hidePlayer(plugin, p);
        Location view = anchor.clone();
        view.setPitch(60);
        view.setYaw(p.getLocation().getYaw());
        s.moving = true;
        p.teleport(view);
        s.moving = false;
        p.setFlying(true);
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.6f);
        p.showTitle(net.kyori.adventure.title.Title.title(Component.text("Out of body", NamedTextColor.AQUA),
                Component.text(switch (goal) {
                    case AREA -> "Left-click two corners of " + e.name + "'s work area";
                    case STORAGE -> "Left-click the chest " + e.name + " delivers to";
                    case PANTRY -> "Left-click the chest " + e.name + " eats from";
                    case ZONE -> "Left-click two corners of the zone " + e.name + " should leave alone";
                    case KINGDOM_HANGOUT -> "Left-click two corners of \"" + hangoutName + "\"";
                }, NamedTextColor.GRAY)));
        Msg.send(p, "<aqua>You've left your body.</aqua> <gray>Fly about (space up, shift down) and look where you mean."
                + " <white>Left-click</white> to pick, <white>right-click</white> (holding anything) to go back a step,"
                + " <white>F</white> (swap hands) to cancel.");
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(s), 1L, 2L);
    }

    /** Over wherever the player is standing, high enough to look down on it: for a kingdom hangout spot, which isn't about any one site. */
    private static Location hangoutAnchor(Player p) {
        Location base = p.getLocation().clone();
        World w = base.getWorld();
        if (w == null) return null;
        int ground = w.getHighestBlockYAt(base.getBlockX(), base.getBlockZ());
        base.setY(Math.min(w.getMaxHeight() - 2, Math.max(ground, base.getBlockY()) + 14));
        return base;
    }

    /** Over the work area's middle (or the employee), high enough to look down on it. */
    private static Location startSpot(Employee e) {
        Location base;
        if (e.area != null && e.area.bukkitWorld() != null) {
            World w = e.area.bukkitWorld();
            base = new Location(w, (e.area.minX() + e.area.maxX()) / 2.0 + 0.5, 0, (e.area.minZ() + e.area.maxZ()) / 2.0 + 0.5);
        } else if (e.location != null && e.location.getWorld() != null) {
            base = e.location.clone();
        } else {
            return null;
        }
        World w = base.getWorld();
        w.getChunkAt(base.getBlockX() >> 4, base.getBlockZ() >> 4);
        int ground = w.getHighestBlockYAt(base.getBlockX(), base.getBlockZ());
        base.setY(Math.min(w.getMaxHeight() - 2, ground + 14));
        return base;
    }

    private Mannequin spawnBody(Player p) {
        Location at = p.getLocation();
        return at.getWorld().spawn(at, Mannequin.class, m -> {
            m.setPersistent(false);   // never saved: a crash leaves no statue behind
            m.setRemoveWhenFarAway(false);
            m.setInvulnerable(true);
            m.setImmovable(true);
            m.setSilent(true);
            m.setCustomNameVisible(true);
            m.customName(Component.text(p.getName(), NamedTextColor.WHITE));
            m.setDescription(Component.text("(out of body)", NamedTextColor.GRAY));
            var b = ResolvableProfile.resolvableProfile().uuid(p.getUniqueId()).name(p.getName());
            for (ProfileProperty prop : p.getPlayerProfile().getProperties()) b.addProperty(prop);
            m.setProfile(b.build());
            m.getEquipment().setArmorContents(p.getInventory().getArmorContents());
            m.getEquipment().setItemInMainHand(p.getInventory().getItemInMainHand());
        });
    }

    /** Back into the body. {@code done}: a message to finish with, or null. */
    private void end(Session s, String done) {
        sessions.remove(s.p.getUniqueId());
        if (s.task != null) s.task.cancel();
        if (s.body != null && s.body.isValid()) s.body.remove();
        restore(s.p, s.saved);
        if (done != null) Msg.send(s.p, done);
    }

    private void restore(Player p, Saved saved) {
        p.getPersistentDataContainer().remove(savedKey);
        p.setFlying(false);
        p.teleport(saved.body());
        p.setGameMode(saved.mode());
        p.setAllowFlight(saved.allowFlight() || saved.mode() == GameMode.CREATIVE);
        p.setFlying(saved.flying() && p.getAllowFlight());
        p.setFlySpeed(saved.flySpeed());
        p.setInvulnerable(saved.invulnerable());
        p.setCollidable(saved.collidable());
        p.setCanPickupItems(saved.pickup());
        p.setFallDistance(0);
        for (Player other : Bukkit.getOnlinePlayers()) if (!other.equals(p)) other.showPlayer(plugin, p);
        p.playSound(saved.body(), Sound.BLOCK_BEACON_DEACTIVATE, 0.6f, 1.6f);
    }

    // ── every other tick: outline, hint, limits ───────────────────────────

    private void tick(Session s) {
        Player p = s.p;
        if (!p.isOnline()) return;
        s.ticks += 2;
        if (s.ticks > TIMEOUT_TICKS) {
            end(s, "<gray>Back in your body: you were out for five minutes. Nothing was changed.");
            return;
        }
        if (s.e != null && !plugin.manager().all().contains(s.e)) {
            end(s, "<gray>Back in your body: " + s.e.name + " doesn't work for you any more.");
            return;
        }
        if (p.getWorld() != s.anchor.getWorld()) {
            end(s, null);
            return;
        }
        if (p.getLocation().distanceSquared(s.anchor) > (double) RANGE * RANGE) {
            Location l = p.getLocation();
            Location back = s.anchor.clone().add(l.toVector().subtract(s.anchor.toVector()).normalize().multiply(RANGE - 2));
            back.setYaw(l.getYaw());
            back.setPitch(l.getPitch());
            s.moving = true;
            p.teleport(back);
            s.moving = false;
            p.setFlying(true);
            p.sendActionBar(Component.text("You can't float further than " + RANGE + " blocks from " + (s.e != null ? s.e.name + "'s site" : "where you started") + ".", NamedTextColor.RED));
            return;
        }
        if (!p.isFlying()) p.setFlying(true);

        Block look = target(p);
        if (s.goal == Goal.STORAGE || s.goal == Goal.PANTRY) {
            boolean ok = look != null && look.getState(false) instanceof Container;
            if (look != null) box(p, look.getX(), look.getY(), look.getZ(), look.getX() + 1, look.getY() + 1, look.getZ() + 1, ok ? EDGE : BAD, 0.25);
            p.sendActionBar(Msg.parse(ok ? "<green>Left-click: use this " + look.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ')
                    + " <dark_gray>· <gray>F: cancel" : "<gray>Look at a chest or barrel <dark_gray>· <gray>F: cancel"));
            return;
        }

        World w = p.getWorld();
        Settings.JobSettings js = s.e != null ? plugin.settings().job(s.e.job) : null;
        if (s.a == null) {
            if (look != null) cursor(p, look);
            p.sendActionBar(Msg.parse("<yellow>Left-click: first corner <dark_gray>· <gray>F: cancel"));
            if (s.goal == Goal.AREA && s.e.area != null && s.ticks % 10 == 0) outline(p, s.e.area, CURSOR, 2);   // the area as it is now
            return;
        }
        BlockPos other = s.depthStep ? s.b : look == null ? null : BlockPos.of(look);
        if (other == null) {
            cursor(p, s.a.block(w));
            p.sendActionBar(Msg.parse("<yellow>Look at the far corner <dark_gray>· <gray>right-click: undo <dark_gray>· <gray>F: cancel"));
            return;
        }
        WorkArea area = build(s, other);
        int wide = area.width(), longSide = area.length();
        int maxSize = js != null ? js.maxAreaSize() : MAX_HANGOUT_SIZE;
        boolean tooBig = wide > maxSize || longSide > maxSize;
        outline(p, area, tooBig ? BAD : EDGE, wide + longSide > 120 ? 2 : 1);
        corner(p, s.a);
        corner(p, other);
        String size = wide + " × " + longSide + (s.depthStep ? " · " + area.height() + " deep (y " + area.minY() + " to " + area.maxY() + ")" : "");
        if (tooBig) {
            p.sendActionBar(Msg.parse("<red>" + size + ": too big (most " + maxSize + " × " + maxSize + ")"));
        } else if (s.depthStep) {
            p.sendActionBar(Msg.parse("<white>" + size + " <dark_gray>· <yellow>scroll: deeper/shallower · left-click: done <dark_gray>· <gray>right-click: back"));
        } else {
            p.sendActionBar(Msg.parse("<white>" + size + " <dark_gray>· <yellow>left-click: " + (digs(s) ? "next, the depth" : "done")
                    + " <dark_gray>· <gray>right-click: undo"));
        }
    }

    private static boolean digs(Session s) {
        return s.goal == Goal.AREA && (s.e.job == JobType.QUARRY || s.e.job == JobType.MINER);
    }

    /** The area from the first corner to {@code other}: an exact box for a zone, or as hiring would make it there (and the depth picked, if any). */
    private WorkArea build(Session s, BlockPos other) {
        World w = s.p.getWorld();
        if (s.goal == Goal.ZONE || s.goal == Goal.KINGDOM_HANGOUT) {
            int low = Math.min(s.a.y(), other.y()), high = Math.max(s.a.y(), other.y());
            return WorkArea.of(w.getName(), s.a.x(), low, s.a.z(), other.x(), high, other.z());
        }
        Settings.JobSettings js = plugin.settings().job(s.e.job);
        // As if standing on the clicked blocks: hiring's height rules apply from the feet up.
        int low = Math.min(s.a.y(), other.y()) + 1, high = Math.max(s.a.y(), other.y()) + 1;
        WorkArea a = plugin.manager().areaFor(s.e.job, w, s.a.x(), s.a.z(), other.x(), other.z(), low, high);
        if (s.depthStep) {
            int bottom = Math.max(w.getMinHeight(), a.maxY() - s.depth + 1);
            a = WorkArea.of(a.world(), a.minX(), bottom, a.minZ(), a.maxX(), a.maxY(), a.maxZ());
        }
        if (a.height() > js.maxAreaHeight()) {
            a = WorkArea.of(a.world(), a.minX(), a.maxY() - js.maxAreaHeight() + 1, a.minZ(), a.maxX(), a.maxY(), a.maxZ());
        }
        return a;
    }

    // ── input ─────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent ev) {
        Session s = sessions.get(ev.getPlayer().getUniqueId());
        if (s == null) return;
        ev.setCancelled(true);
        if (ev.getHand() == EquipmentSlot.OFF_HAND) return;
        if (s.ticks - s.lastClick < 4) return;   // one click, not two events
        s.lastClick = s.ticks;
        Action a = ev.getAction();
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) pick(s);
        else if (a == Action.RIGHT_CLICK_AIR || a == Action.RIGHT_CLICK_BLOCK) back(s);
    }

    private void pick(Session s) {
        Player p = s.p;
        Block look = target(p);
        if (s.goal == Goal.STORAGE || s.goal == Goal.PANTRY) {
            if (look == null || !(look.getState(false) instanceof Container)) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                return;
            }
            chest(s, look);
            return;
        }
        Settings.JobSettings js = s.e != null ? plugin.settings().job(s.e.job) : null;
        if (s.a == null) {
            if (look == null) return;
            s.a = BlockPos.of(look);
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.2f);
            return;
        }
        if (!s.depthStep) {
            if (look == null) return;
            BlockPos other = BlockPos.of(look);
            WorkArea area = build(s, other);
            int maxSize = js != null ? js.maxAreaSize() : MAX_HANGOUT_SIZE;
            if (area.width() > maxSize || area.length() > maxSize) {
                Msg.error(p, "That's " + area.width() + " × " + area.length() + "; the most for " + (js != null ? "a " + s.e.job.display().toLowerCase(Locale.ROOT) : "a hangout spot")
                        + " is " + maxSize + " × " + maxSize + ".");
                return;
            }
            s.b = other;
            if (digs(s)) {
                s.depthStep = true;
                s.depth = area.height();
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.5f);
                return;
            }
            finish(s, area);
            return;
        }
        finish(s, build(s, s.b));
    }

    private void back(Session s) {
        if (s.goal != Goal.AREA && s.goal != Goal.ZONE && s.goal != Goal.KINGDOM_HANGOUT) return;
        if (s.depthStep) {
            s.depthStep = false;
            s.b = null;
        } else if (s.a != null) {
            s.a = null;
        } else {
            return;
        }
        s.p.playSound(s.p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1f);
    }

    /** Scrolling while picking a depth: down is deeper. The held slot stays put. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onScroll(PlayerItemHeldEvent ev) {
        Session s = sessions.get(ev.getPlayer().getUniqueId());
        if (s == null || !s.depthStep) return;
        ev.setCancelled(true);
        int d = ev.getNewSlot() - ev.getPreviousSlot();
        if (d > 4) d -= 9;
        if (d < -4) d += 9;
        int max = plugin.settings().job(s.e.job).maxAreaHeight();
        int step = s.depth >= 64 ? 8 : s.depth >= 16 ? 4 : 1;
        s.depth = Math.max(1, Math.min(max, s.depth + d * step));
        s.p.playSound(s.p.getLocation(), Sound.UI_BUTTON_CLICK, 0.3f, d > 0 ? 0.8f : 1.2f);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent ev) {
        Session s = sessions.get(ev.getPlayer().getUniqueId());
        if (s == null) return;
        ev.setCancelled(true);
        end(s, "<gray>Back in your body. Nothing was changed.");
    }

    private void finish(Session s, WorkArea area) {
        Employee e = s.e;
        Player p = s.p;
        Goal goal = s.goal;
        String hangoutName = s.hangoutName;
        end(s, null);
        if (goal == Goal.ZONE) {
            e.zones.add(area);
            plugin.zonePresets().changed();
            plugin.manager().markDirty();
            e.worker.interrupt();
            String what = e.job == JobType.BODYGUARD ? "leaves mobs alone" : "won't work";
            Msg.send(p, "<green>" + e.name + " " + what + " in <white>" + area.describe() + "</white> (y " + area.minY() + " to " + area.maxY() + ") now.");
        } else if (goal == Goal.KINGDOM_HANGOUT) {
            UUID kingdomOwner = plugin.kingdomBridge().kingdomOwnerOf(p.getUniqueId());
            if (kingdomOwner == null) {
                Msg.error(p, "You're not in a kingdom any more; the spot wasn't saved.");
                return;
            }
            plugin.hangoutSpots().add(kingdomOwner, new site.remotearc.employees.HangoutSpots.Spot(hangoutName, area));
            Msg.send(p, "<green>\"" + Describe.esc(hangoutName) + "\" is now a hangout spot: <white>" + area.describe() + "</white> (y " + area.minY() + " to " + area.maxY() + ").");
        } else {
            plugin.manager().setArea(e, area);
            Msg.send(p, "<green>" + e.name + " now works in <white>" + area.describe() + "</white> (y " + area.minY() + " to " + area.maxY() + ").");
        }
        AreaPreview.show(plugin, p, area, 200);
    }

    private void chest(Session s, Block b) {
        Employee e = s.e;
        Player p = s.p;
        if (!plugin.settings().chestsEnabled) {
            Msg.error(p, "Placed chests are turned off in the config.");
            return;
        }
        if (!p.hasPermission("employees.admin")
                && !plugin.manager().mayUseChest(e.owner, b.getWorld().getName(), b.getX(), b.getY(), b.getZ())) {
            Msg.error(p, "Someone else's employees already use that chest.");
            return;
        }
        String what = b.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        end(s, null);
        if (s.goal == Goal.PANTRY) {
            plugin.manager().setPantry(e, StorageTarget.chest(b));
            Msg.send(p, "<green>" + e.name + " will take their food from that " + what + ".</green> <gray>Keep some food in it.");
        } else {
            plugin.manager().setStorage(e, StorageTarget.chest(b));
            Msg.send(p, "<green>" + e.name + " will carry their work to that " + what + ".");
            if (e.area != null && (!e.area.world().equals(b.getWorld().getName()) || !e.area.expand(12, 8).contains(b.getX(), b.getY(), b.getZ()))) {
                p.sendMessage(Msg.parse("<yellow>That's more than 12 blocks outside the work area; " + e.name + " may not be able to walk to it."));
            }
        }
    }

    // ── keeping it safe ───────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent ev) {
        Session s = sessions.get(ev.getPlayer().getUniqueId());
        if (s != null) end(s, null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent ev) {
        Player p = ev.getPlayer();
        for (Session s : sessions.values()) p.hidePlayer(plugin, s.p);
        // Out of body when the server went down: back into the body.
        String saved = p.getPersistentDataContainer().get(savedKey, PersistentDataType.STRING);
        if (saved != null && !sessions.containsKey(p.getUniqueId())) {
            Saved s = Saved.load(saved);
            if (s != null) restore(p, s);
            else p.getPersistentDataContainer().remove(savedKey);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent ev) {
        Session s = sessions.get(ev.getPlayer().getUniqueId());
        if (s == null || s.moving) return;
        ev.setCancelled(true);
        if (ev.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN || ev.getCause() == PlayerTeleportEvent.TeleportCause.COMMAND) {
            // Not from inside the event: back in the body on the next tick.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (sessions.get(s.p.getUniqueId()) == s) end(s, "<gray>Back in your body (something tried to teleport you).");
            });
        }
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent ev) {
        Session s = sessions.get(ev.getPlayer().getUniqueId());
        if (s != null) end(s, null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent ev) {
        if (!sessions.containsKey(ev.getPlayer().getUniqueId())) return;
        ev.setCancelled(true);
        Msg.error(ev.getPlayer(), "Not while you're out of your body. Press <white>F</white> to go back.");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent ev) {
        if (ev.getEntity() instanceof Player p && sessions.containsKey(p.getUniqueId())) ev.setCancelled(true);
        if (ev instanceof EntityDamageByEntityEvent by && by.getDamager() instanceof Player p && sessions.containsKey(p.getUniqueId())) {
            ev.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageByEntityEvent ev) {
        if (ev.getEntity() instanceof Player p) lastHurt.put(p.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onTarget(EntityTargetLivingEntityEvent ev) {
        if (ev.getTarget() instanceof Player p && sessions.containsKey(p.getUniqueId())) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent ev) {
        if (ev.getEntity() instanceof Player p && sessions.containsKey(p.getUniqueId())) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent ev) {
        if (sessions.containsKey(ev.getPlayer().getUniqueId())) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onOpen(InventoryOpenEvent ev) {
        if (sessions.containsKey(ev.getPlayer().getUniqueId())) ev.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEntity(PlayerInteractEntityEvent ev) {
        if (sessions.containsKey(ev.getPlayer().getUniqueId())) ev.setCancelled(true);
    }

    // ── drawing ───────────────────────────────────────────────────────────

    /** The block the player is looking at, up to {@value #REACH} blocks off (grass and flowers are looked through). */
    private static Block target(Player p) {
        Location eye = p.getEyeLocation();
        RayTraceResult r = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), REACH, FluidCollisionMode.NEVER, true);
        return r == null ? null : r.getHitBlock();
    }

    private static void cursor(Player p, Block b) {
        double x = b.getX() + 0.5, y = b.getY() + 1.1, z = b.getZ() + 0.5;
        for (double d = -0.6; d <= 0.6; d += 0.3) {
            dot(p, x + d, y, z, CURSOR);
            dot(p, x, y, z + d, CURSOR);
        }
    }

    private static void corner(Player p, BlockPos c) {
        dot(p, c.x() + 0.5, c.y() + 1.2, c.z() + 0.5, CORNER);
        dot(p, c.x() + 0.5, c.y() + 1.7, c.z() + 0.5, CORNER);
    }

    /** The area's outline: its top rectangle, and its corners down to the bottom. */
    private static void outline(Player p, WorkArea a, Particle.DustOptions o, int step) {
        double x1 = a.minX(), x2 = a.maxX() + 1, z1 = a.minZ(), z2 = a.maxZ() + 1;
        double top = a.maxY() + 1.05, bottom = a.minY();
        for (double x = x1; x <= x2; x += step) {
            dot(p, x, top, z1, o);
            dot(p, x, top, z2, o);
        }
        for (double z = z1; z <= z2; z += step) {
            dot(p, x1, top, z, o);
            dot(p, x2, top, z, o);
        }
        int vstep = Math.max(1, (int) ((top - bottom) / 24));
        for (double y = bottom; y < top; y += vstep) {
            dot(p, x1, y, z1, o);
            dot(p, x2, y, z1, o);
            dot(p, x1, y, z2, o);
            dot(p, x2, y, z2, o);
        }
    }

    private static void box(Player p, double x1, double y1, double z1, double x2, double y2, double z2, Particle.DustOptions o, double step) {
        for (double x = x1; x <= x2 + 1e-6; x += step) {
            dot(p, x, y1, z1, o);
            dot(p, x, y2, z1, o);
            dot(p, x, y1, z2, o);
            dot(p, x, y2, z2, o);
        }
        for (double z = z1; z <= z2 + 1e-6; z += step) {
            dot(p, x1, y1, z, o);
            dot(p, x2, y1, z, o);
            dot(p, x1, y2, z, o);
            dot(p, x2, y2, z, o);
        }
        for (double y = y1; y <= y2 + 1e-6; y += step) {
            dot(p, x1, y, z1, o);
            dot(p, x2, y, z1, o);
            dot(p, x1, y, z2, o);
            dot(p, x2, y, z2, o);
        }
    }

    private static void dot(Player p, double x, double y, double z, Particle.DustOptions o) {
        p.spawnParticle(Particle.DUST, new Location(p.getWorld(), x, y, z), 1, 0, 0, 0, 0, o);
    }
}
