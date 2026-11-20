package site.remotearc.employees;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent.SpawnReason;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import site.remotearc.employees.gui.Describe;
import site.remotearc.employees.gui.HireMenu;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.job.Stalls;
import site.remotearc.employees.util.Msg;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Hiring employees from the world's own villagers.
 *
 * Shift-right-click a grown-up villager (or look at one and use
 * /employees hire &lt;job&gt;), pick a job, and the villager says what it wants for
 * the job: the tools of the trade and some food (see HireCost). Hand it over
 * and the villager becomes an employee: it's taken out of the world and a
 * Mannequin with a player skin takes its place. A kingdom's settlers are free.
 *
 * Can't afford it yet? Reserve the villager: it walks to its bed and waits
 * there, still as a statue, for {@code hiring.reserve-days}; only that player
 * can hire it meanwhile.
 *
 * The village gets a baby villager in its place after a delay (replenishing),
 * only up to the population it had before the hire. A fired employee that was a
 * villager turns back into one; hiring it again doesn't replenish a second time.
 */
public final class VillagerHiring implements Listener {

    /** Spawned the normal way, though not by the game itself: our replacement babies and released employees. */
    private final NamespacedKey naturalKey;
    /** A villager that used to be an employee: hiring it again doesn't refill its village a second time. */
    private final NamespacedKey formerKey;
    /** The name it had as an employee, used again if it's rehired. */
    private final NamespacedKey nameKey;
    /** A former employee keeps its personality when it's hired again. */
    private final NamespacedKey personalityKey;
    /** A kingdom's settler: only this player (its founder, a UUID) may hire it, for free. */
    private final NamespacedKey settlerKey;
    /** Where a settler leashes back to while it hasn't been hired yet ("world;x;y;z"). */
    private final NamespacedKey coreKey;
    /** When a settler arrived (epoch millis): past settlerExpireDays unhired, it stops being invulnerable instead of staying safe forever. */
    private final NamespacedKey settledAtKey;
    /** A settler that hasn't been given a role wanders no further than this from its kingdom's Core. */
    private static final int SETTLER_LEASH_RADIUS = 50;
    /** Reserved for a player: the player's UUID, the job, and when it ends (epoch millis, 0 = never). */
    private final NamespacedKey reservedKey;
    private final NamespacedKey reservedJobKey;

    /** A villager waiting for a player to come back and hire it. */
    public record Reservation(java.util.UUID villager, java.util.UUID owner, JobType job, long until, String world, double x, double y, double z) {
        boolean expired() {
            return until > 0 && System.currentTimeMillis() > until;
        }
    }

    private final java.util.Map<java.util.UUID, Reservation> reserved = new java.util.LinkedHashMap<>();
    /** Reserved villagers walking to their beds: checks spent on the way (teleported there if it takes too long). */
    private final java.util.Map<java.util.UUID, Integer> walking = new java.util.HashMap<>();
    /** Founders who've had their settlers already: they come with a player's first kingdom only. */
    private final Set<java.util.UUID> settled = new java.util.HashSet<>();
    /** Un-hired settlers, so leashing them to their Core doesn't need a scan of every villager in every world. */
    private final Set<java.util.UUID> settlerIds = new java.util.HashSet<>();
    private boolean settlerIdsScanned;

    /** How villagers can come into the world other than the normal way. */
    private static final Set<SpawnReason> UNNATURAL = EnumSet.of(SpawnReason.SPAWNER_EGG, SpawnReason.DISPENSE_EGG,
            SpawnReason.SPAWNER, SpawnReason.TRIAL_SPAWNER, SpawnReason.COMMAND, SpawnReason.CUSTOM, SpawnReason.BUCKET);

    /** A replacement baby due at a village. */
    private record Pending(String world, double x, double y, double z, long due, String type, int population) {}

    private final ArcEmployees plugin;
    private final File file;
    private final List<Pending> pending = new ArrayList<>();
    private BukkitTask task;

    VillagerHiring(ArcEmployees plugin) {
        this.plugin = plugin;
        this.naturalKey = new NamespacedKey(plugin, "natural-villager");
        this.formerKey = new NamespacedKey(plugin, "former-employee");
        this.nameKey = new NamespacedKey(plugin, "employee-name");
        this.personalityKey = new NamespacedKey(plugin, "employee-personality");
        this.settlerKey = new NamespacedKey(plugin, "kingdom-settler");
        this.coreKey = new NamespacedKey(plugin, "kingdom-settler-core");
        this.settledAtKey = new NamespacedKey(plugin, "kingdom-settler-since");
        this.reservedKey = new NamespacedKey(plugin, "reserved-for");
        this.reservedJobKey = new NamespacedKey(plugin, "reserved-job");
        this.file = new File(plugin.getDataFolder(), "villager-hiring.yml");
    }

    // ── the hire button ───────────────────────────────────────────────────

    /** Shift-right-clicking a villager opens the hire menu (instead of its trades). */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent ev) {
        if (!(ev.getRightClicked() instanceof Villager v)) return;
        Player p = ev.getPlayer();
        if (!p.isSneaking() || !p.hasPermission("employees.use")) return;
        // A name tag or a lead in hand does its vanilla thing.
        Material inHand = p.getInventory().getItem(ev.getHand()).getType();
        if (inHand == Material.NAME_TAG || inHand == Material.LEAD) return;
        ev.setCancelled(true);
        if (ev.getHand() != EquipmentSlot.HAND) return;
        // Marked reserved but no longer on the books (ran out while unloaded, or the file was lost): let it go.
        if (v.getPersistentDataContainer().has(reservedKey, PersistentDataType.STRING) && reservation(v) == null) release(v);
        String why = whyNot(p, v);
        if (why != null) {
            Msg.error(p, why);
            return;
        }
        Reservation r = reservation(v);
        if (r != null && r.owner().equals(p.getUniqueId())) site.remotearc.employees.gui.HireDialog.show(plugin, p, v, r.job());
        else HireMenu.forVillager(plugin, v).open(p);
    }

    /** Why this player can't hire this villager, or null if they can. */
    public String whyNot(Player p, Villager v) {
        if (!v.isValid() || v.isDead()) return "That villager isn't there any more.";
        if (!v.isAdult()) return "Only grown-up villagers can be hired.";
        if (v.isSleeping()) return "That villager is asleep. Try again when it's up.";
        if (v.isLeashed() || v.isInsideVehicle()) return "That villager is on a lead or riding something.";
        if (plugin.settings().villagerNaturalOnly && !natural(v)) {
            return "Only villagers from a village (generated, born or cured) can be hired, not ones from spawn eggs or commands.";
        }
        java.util.UUID founder = settlerOf(v);
        if (founder != null) {
            if (founder.equals(p.getUniqueId()) || p.hasPermission("employees.admin")) return null;   // theirs, wherever it stands
            String who = Bukkit.getOfflinePlayer(founder).getName();
            return "That settler came to " + (who == null ? "another player" : who) + "'s kingdom; only they can hire it.";
        }
        String stall = stallOf(v);
        if (stall != null) return "That villager works in " + stall + "'s trading hall.";
        Reservation r = reservation(v);
        if (r != null && !r.owner().equals(p.getUniqueId()) && !p.hasPermission("employees.admin")) {
            String who = Bukkit.getOfflinePlayer(r.owner()).getName();
            return "That villager is waiting for " + (who == null ? "another player" : who) + " to hire it.";
        }
        return null;
    }

    /** True for a kingdom's settler (free to hire). */
    public boolean isSettler(Villager v) {
        return settlerOf(v) != null;
    }

    // ── reserving ─────────────────────────────────────────────────────────

    /** The villager's live reservation, or null (none, or it ran out). */
    public Reservation reservation(Villager v) {
        Reservation r = reserved.get(v.getUniqueId());
        return r == null || r.expired() ? null : r;
    }

    /** How many villagers a player has reserved now. */
    public int reservedBy(java.util.UUID owner) {
        return reservationsOf(owner).size();
    }

    /** The player's live reservations. */
    public List<Reservation> reservationsOf(java.util.UUID owner) {
        List<Reservation> out = new ArrayList<>();
        for (Reservation r : reserved.values()) if (r.owner().equals(owner) && !r.expired()) out.add(r);
        return out;
    }

    /**
     * Holds the villager for this player: it walks to its bed (if it has one)
     * and waits there, unmoving and unhurt, until hired or the reservation
     * runs out. True if reserved; the player has been told either way.
     */
    public boolean reserve(Player p, Villager v, JobType job) {
        String why = whyNot(p, v);
        if (why != null) {
            Msg.error(p, why);
            return false;
        }
        Reservation old = reservation(v);
        int max = plugin.settings().maxReserved;
        if (old == null && !p.hasPermission("employees.admin") && reservedBy(p.getUniqueId()) >= max) {
            Msg.error(p, max == 0 ? "Reserving villagers is turned off." : "You can only have " + max + " villagers waiting for you at once.");
            return false;
        }
        double days = plugin.settings().reserveDays;
        long until = days <= 0 ? 0 : System.currentTimeMillis() + (long) (days * 86_400_000L);
        Location at = v.getLocation();
        reserved.put(v.getUniqueId(), new Reservation(v.getUniqueId(), p.getUniqueId(), job, until, at.getWorld().getName(), at.getX(), at.getY(), at.getZ()));
        PersistentDataContainer pdc = v.getPersistentDataContainer();
        pdc.set(reservedKey, PersistentDataType.STRING, p.getUniqueId().toString());
        pdc.set(reservedJobKey, PersistentDataType.STRING, job.name());
        v.setInvulnerable(true);
        walking.put(v.getUniqueId(), 0);
        tendReserved(v);
        save();
        Location bed = v.getMemory(org.bukkit.entity.memory.MemoryKey.HOME);
        Location shown = bed != null ? bed : at;
        Msg.send(p, "<green>" + Describe.esc(label(v)) + " will wait for you as your " + job.display().toLowerCase(java.util.Locale.ROOT)
                + (bed != null ? ", by their bed" : ", right here") + "</green> <gray>(at " + shown.getBlockX() + ", " + shown.getBlockY() + ", "
                + shown.getBlockZ() + (until > 0 ? ", for " + Msg.duration(until - System.currentTimeMillis()) : "")
                + "). Shift-right-click them when you have what they asked for.");
        v.getWorld().playSound(at, Sound.ENTITY_VILLAGER_YES, 1f, 1f);
        return true;
    }

    /** Lets a reserved villager go back to village life. */
    public void release(Villager v) {
        reserved.remove(v.getUniqueId());
        walking.remove(v.getUniqueId());
        PersistentDataContainer pdc = v.getPersistentDataContainer();
        pdc.remove(reservedKey);
        pdc.remove(reservedJobKey);
        v.setAware(true);
        v.setInvulnerable(settlerOf(v) != null);   // settlers stay safe until hired
        save();
    }

    /** Its name, or "The villager". */
    public static String label(Villager v) {
        if (v.customName() == null) return "The villager";
        return PlainTextComponentSerializer.plainText().serialize(v.customName());
    }

    /**
     * Every 20 seconds: reserved villagers that are loaded walk to their bed and
     * then stand still there; ones whose time is up are let go (and their
     * player told, if on). Ones that aren't loaded are seen to when they are.
     */
    private void tendReservations() {
        if (reserved.isEmpty()) return;
        boolean changed = false;
        for (Iterator<Reservation> it = reserved.values().iterator(); it.hasNext(); ) {
            Reservation r = it.next();
            Entity en = Bukkit.getEntity(r.villager());
            if (!(en instanceof Villager v) || !v.isValid()) continue;
            if (r.expired()) {
                it.remove();
                walking.remove(r.villager());
                PersistentDataContainer pdc = v.getPersistentDataContainer();
                pdc.remove(reservedKey);
                pdc.remove(reservedJobKey);
                v.setAware(true);
                v.setInvulnerable(settlerOf(v) != null);
                changed = true;
                Player owner = Bukkit.getPlayer(r.owner());
                if (owner != null) Msg.send(owner, "<gray>" + Describe.esc(label(v)) + " got tired of waiting and went back to village life.");
                continue;
            }
            tendReserved(v);
        }
        if (changed) save();
    }

    /** Walks a reserved villager to its bed, then holds it still there. */
    private void tendReserved(Villager v) {
        if (!v.isAware()) return;   // already waiting
        Location bed = v.getMemory(org.bukkit.entity.memory.MemoryKey.HOME);
        if (bed == null || bed.getWorld() != v.getWorld()) {
            v.setAware(false);   // no bed: waits where it is
            walking.remove(v.getUniqueId());
            return;
        }
        Location spot = besideBed(bed);
        if (spot == null || v.getLocation().distanceSquared(spot) <= 2.5 * 2.5) {   // nowhere to stand by it: waits where it is
            v.setAware(false);
            walking.remove(v.getUniqueId());
            return;
        }
        int tries = walking.merge(v.getUniqueId(), 1, Integer::sum);
        if (tries > 4) {   // over a minute on the way: stuck, so it just goes there
            v.teleport(spot);
            v.setAware(false);
            walking.remove(v.getUniqueId());
            return;
        }
        v.getPathfinder().moveTo(spot, 0.6);
    }

    /** A free spot to stand right beside a bed (either half), or null. */
    private static Location besideBed(Location bed) {
        Block b = bed.getBlock();
        List<Block> halves = new ArrayList<>(List.of(b));
        if (b.getBlockData() instanceof org.bukkit.block.data.type.Bed data) {
            org.bukkit.block.BlockFace f = data.getFacing();
            halves.add(b.getRelative(data.getPart() == org.bukkit.block.data.type.Bed.Part.HEAD ? f.getOppositeFace() : f));
        }
        org.bukkit.block.BlockFace[] sides = {org.bukkit.block.BlockFace.NORTH, org.bukkit.block.BlockFace.SOUTH,
                org.bukkit.block.BlockFace.EAST, org.bukkit.block.BlockFace.WEST};
        for (Block half : halves) {
            for (org.bukkit.block.BlockFace side : sides) {
                Block feet = half.getRelative(side);
                if (feet.isPassable() && !feet.isLiquid() && feet.getRelative(0, 1, 0).isPassable() && feet.getRelative(0, -1, 0).getType().isSolid()) {
                    return feet.getLocation().add(0.5, 0, 0.5);
                }
            }
        }
        return null;
    }

    /** A reserved villager that died is no longer waiting for anyone. */
    @EventHandler
    public void onDeath(org.bukkit.event.entity.EntityDeathEvent ev) {
        settlerIds.remove(ev.getEntity().getUniqueId());
        if (ev.getEntity() instanceof Villager && reserved.remove(ev.getEntity().getUniqueId()) != null) {
            walking.remove(ev.getEntity().getUniqueId());
            save();
        }
    }

    /** The founder a kingdom settler belongs to, or null if it isn't one. */
    private java.util.UUID settlerOf(Villager v) {
        String s = v.getPersistentDataContainer().get(settlerKey, PersistentDataType.STRING);
        if (s == null) return null;
        try {
            return java.util.UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    // ── kingdom settlers ──────────────────────────────────────────────────

    /**
     * A player founded their first kingdom (told by Kingdoms &amp; Claims): its
     * settlers ({@code villager-hiring.kingdom-settlers}, 2) arrive at the Core.
     * They're ordinary grown-up villagers that only the founder can hire, for
     * free, picking each one's job; they can't be hurt until then, and hiring
     * them doesn't bring babies (they aren't from a village). A player gets
     * settlers with their first kingdom only. Returns how many arrived.
     */
    public int settle(java.util.UUID founder, Location core, String kingdom) {
        int count = plugin.settings().kingdomSettlers;
        if (count <= 0 || core == null || core.getWorld() == null || settled.contains(founder)) return 0;
        List<String> names = new ArrayList<>();
        int[][] offsets = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}};
        for (int i = 0; i < count; i++) {
            int[] off = offsets[i % offsets.length];
            Location at = standable(core.clone().add(off[0], 0, off[1]));
            String name = plugin.manager().randomName(founder);
            for (int tries = 0; names.contains(name) && tries < 10; tries++) name = plugin.manager().randomName(founder);
            if (names.contains(name)) name = name + (i + 1);
            String given = name;
            names.add(given);
            Villager spawned = at.getWorld().spawn(at, Villager.class, v -> {
                v.setAdult();
                v.customName(net.kyori.adventure.text.Component.text(given));
                v.setCustomNameVisible(true);
                v.setInvulnerable(true);           // until hired: no mob kills or zombie-converts them first
                v.setRemoveWhenFarAway(false);
                PersistentDataContainer pdc = v.getPersistentDataContainer();
                pdc.set(naturalKey, PersistentDataType.BYTE, (byte) 1);   // hireable though the plugin made it
                pdc.set(formerKey, PersistentDataType.BYTE, (byte) 1);    // no baby replaces it when hired
                pdc.set(settlerKey, PersistentDataType.STRING, founder.toString());
                pdc.set(coreKey, PersistentDataType.STRING, coreTag(core));
                pdc.set(settledAtKey, PersistentDataType.LONG, System.currentTimeMillis());
            });
            settlerIds.add(spawned.getUniqueId());
            at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 1, 0), 20, 0.4, 0.6, 0.4);
        }
        core.getWorld().playSound(core, Sound.ENTITY_VILLAGER_CELEBRATE, 1f, 1f);
        settled.add(founder);
        save();
        Player p = Bukkit.getPlayer(founder);
        if (p != null) {
            Msg.send(p, "<green>" + names.size() + " settlers have come to " + site.remotearc.employees.gui.Describe.esc(kingdom) + ": <gold>" + String.join("</gold> and <gold>", names)
                    + "</gold>.</green> <gray>Shift-right-click each one and pick a job: hiring them is free, and only you can.");
        }
        plugin.getLogger().info(names.size() + " settlers came to " + (p != null ? p.getName() : founder) + "'s new kingdom " + kingdom + ".");
        return names.size();
    }

    private static String coreTag(Location core) {
        return core.getWorld().getName() + ";" + core.getBlockX() + ";" + core.getBlockY() + ";" + core.getBlockZ();
    }

    private static Location parseCoreTag(String tag) {
        String[] p = tag.split(";");
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return null;
        return new Location(w, Integer.parseInt(p[1]) + 0.5, Integer.parseInt(p[2]), Integer.parseInt(p[3]) + 0.5);
    }

    /**
     * A settler nobody has hired yet doesn't wander off: if it strays more
     * than {@link #SETTLER_LEASH_RADIUS} blocks from its kingdom's Core, it's
     * walked back (teleported) to just outside the Core.
     */
    private void leashSettlers() {
        if (!settlerIdsScanned) {
            // Once, at startup: settlers from a previous run are only known by their PDC tag until then.
            settlerIdsScanned = true;
            for (World w : Bukkit.getWorlds()) {
                for (Villager v : w.getEntitiesByClass(Villager.class)) {
                    if (v.getPersistentDataContainer().has(settlerKey, PersistentDataType.STRING)) settlerIds.add(v.getUniqueId());
                }
            }
        }
        for (var it = settlerIds.iterator(); it.hasNext(); ) {
            Entity en = Bukkit.getEntity(it.next());
            if (!(en instanceof Villager v) || !v.isValid()) {
                it.remove();
                continue;
            }
            int expireDays = plugin.settings().settlerExpireDays;
            if (expireDays > 0 && v.isInvulnerable()) {
                Long since = v.getPersistentDataContainer().get(settledAtKey, PersistentDataType.LONG);
                if (since != null && System.currentTimeMillis() - since > expireDays * 86_400_000L) v.setInvulnerable(false);
            }
            String tag = v.getPersistentDataContainer().get(coreKey, PersistentDataType.STRING);
            if (tag == null) continue;
            Location core = parseCoreTag(tag);
            if (core == null || core.getWorld() != v.getWorld()) continue;
            if (v.getLocation().distanceSquared(core) > (double) SETTLER_LEASH_RADIUS * SETTLER_LEASH_RADIUS) {
                v.teleport(standable(core));
            }
        }
    }

    /** Came into the world the normal way: generated with a village, born, cured, or one of ours. */
    private boolean natural(Villager v) {
        if (v.getPersistentDataContainer().has(naturalKey, PersistentDataType.BYTE)) return true;
        SpawnReason why = v.getEntitySpawnReason();
        return why == null || !UNNATURAL.contains(why);
    }

    /** The trader whose stall the villager stands in ("Ann's Cora"), or null. */
    private String stallOf(Villager v) {
        for (Employee e : plugin.manager().all()) {
            if (e.job != JobType.TRADER || e.stalls.isEmpty() || e.area == null) continue;
            World w = e.area.bukkitWorld();
            if (w == null || !w.equals(v.getWorld())) continue;
            for (Employee.Stall st : e.stalls) {
                Villager in = Stalls.inStallPublic(w, st);
                if (in != null && in.getUniqueId().equals(v.getUniqueId())) return e.ownerName() + "'s " + e.name;
            }
        }
        return null;
    }

    // ── hiring ────────────────────────────────────────────────────────────

    /**
     * Turns the villager into an employee with this job, for this player: the
     * villager goes and a skinned NPC takes its place, once the player hands over what it asks for (HireCost).
     * {@code wanted} is a name to give it, or null for its own (or a random one).
     * Returns the new employee, or null (the player has been told why).
     */
    public Employee hire(Player p, Villager v, JobType job, String wanted) {
        String why = whyNot(p, v);
        if (why != null) {
            Msg.error(p, why);
            return null;
        }
        EmployeeManager mgr = plugin.manager();
        int limit = plugin.settings().maxPerPlayer;
        if (!p.hasPermission("employees.admin") && mgr.ownedBy(p.getUniqueId()).size() >= limit) {
            Msg.error(p, "You can employ at most " + limit + " people.");
            return null;
        }
        String kingdom = plugin.kingdomBridge().kingdomOf(p.getUniqueId());
        int kingdomLimit = plugin.settings().maxPerKingdom;
        if (kingdom != null && !p.hasPermission("employees.admin") && mgr.countInKingdom(kingdom) >= kingdomLimit) {
            Msg.error(p, "<white>" + Describe.esc(kingdom) + "</white> already has " + kingdomLimit + " NPCs; that's the most for now.");
            return null;
        }
        String name = wanted != null ? wanted : nameFor(p, v);
        if (!EmployeeManager.NAME.matcher(name).matches()) {
            Msg.error(p, "Names are 1-16 letters, digits or underscores.");
            return null;
        }
        if (mgr.nameTaken(p.getUniqueId(), name)) {
            Msg.error(p, "You already employ someone called <white><name></white>.", Msg.p("name", name));
            return null;
        }

        boolean settler = settlerOf(v) != null;
        java.util.Map<Material, Integer> cost = settler ? java.util.Map.of() : site.remotearc.employees.HireCost.of(plugin, p.getUniqueId(), job);
        if (!site.remotearc.employees.HireCost.take(p, cost)) {
            Msg.error(p, Describe.esc(label(v)) + " still needs " + site.remotearc.employees.HireCost.describe(site.remotearc.employees.HireCost.missing(p, cost)) + " from you.");
            return null;
        }

        Location at = v.getLocation();
        World world = v.getWorld();
        String type = v.getVillagerType().getKey().toString();
        Personality former = Personality.parse(v.getPersistentDataContainer().get(personalityKey, PersistentDataType.STRING));
        boolean refill = plugin.settings().replenishEnabled && !v.getPersistentDataContainer().has(formerKey, PersistentDataType.BYTE);
        int population = refill ? villagersNear(at) : 0;   // counts this one: the village gets back to this many

        world.spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 1, 0), 20, 0.4, 0.6, 0.4);
        world.playSound(at, Sound.ENTITY_VILLAGER_YES, 1f, 1f);
        if (reserved.remove(v.getUniqueId()) != null) save();
        walking.remove(v.getUniqueId());
        settlerIds.remove(v.getUniqueId());
        v.remove();

        Employee e = mgr.hire(p.getUniqueId(), job, name, at);
        Material food = plugin.settings().hireFood;
        if (cost.containsKey(food)) site.remotearc.employees.Food.store(plugin, e, food, cost.get(food));
        e.villagerType = type;
        if (former != null) e.personality = former;
        mgr.save();
        if (refill) schedule(at, type, population);
        plugin.getLogger().info(p.getName() + " hired a villager at " + world.getName() + " " + at.getBlockX() + " " + at.getBlockY() + " "
                + at.getBlockZ() + " as " + e.name + " the " + job.display().toLowerCase() + (refill ? "; its village gets a baby villager later." : "."));
        EmployeesCommand.welcome(plugin, p, e, "<green>" + (settler ? "Your settler" : "The villager") + " agreed to work for you: meet <gold>"
                + e.name + "</gold> the " + job.display().toLowerCase() + ". <gray>(" + (settler ? "Settlers are free to hire."
                : "You gave them " + site.remotearc.employees.HireCost.describe(cost) + ".") + ")");
        return e;
    }

    /** Its old employee name if it had one, its name tag if that makes a valid name, otherwise a random one. */
    private String nameFor(Player p, Villager v) {
        EmployeeManager mgr = plugin.manager();
        List<String> tries = new ArrayList<>();
        String old = v.getPersistentDataContainer().get(nameKey, PersistentDataType.STRING);
        if (old != null) tries.add(old);
        if (v.customName() != null) tries.add(PlainTextComponentSerializer.plainText().serialize(v.customName()).trim().replace(' ', '_'));
        for (String n : tries) {
            if (EmployeeManager.NAME.matcher(n).matches() && !mgr.nameTaken(p.getUniqueId(), n)) return n;
        }
        return mgr.randomName(p.getUniqueId());
    }

    // ── firing: back to being a villager ──────────────────────────────────

    /** Called as an employee is fired: one that was a villager becomes a grown-up villager again where it stands. */
    void released(Employee e) {
        if (e.villagerType == null || !plugin.settings().villagerReleaseOnFire) return;
        if (e.location == null || !e.location.isWorldLoaded()) return;   // its world isn't loaded: nowhere to put it
        Location at = e.location.clone();
        Villager.Type type = villagerType(e.villagerType);
        at.getWorld().spawn(at, Villager.class, v -> {
            v.setAdult();
            if (type != null) v.setVillagerType(type);
            PersistentDataContainer pdc = v.getPersistentDataContainer();
            pdc.set(naturalKey, PersistentDataType.BYTE, (byte) 1);
            pdc.set(formerKey, PersistentDataType.BYTE, (byte) 1);
            pdc.set(nameKey, PersistentDataType.STRING, e.name);
            if (e.personality != null) pdc.set(personalityKey, PersistentDataType.STRING, e.personality.name());
        });
        at.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 1, 0), 20, 0.4, 0.6, 0.4);
    }

    private static Villager.Type villagerType(String key) {
        NamespacedKey k = key == null ? null : NamespacedKey.fromString(key);
        return k == null ? null : Registry.VILLAGER_TYPE.get(k);
    }

    // ── replenishing villages ─────────────────────────────────────────────

    /** Grown-up and baby villagers round a spot. */
    private int villagersNear(Location at) {
        int r = plugin.settings().replenishRadius;
        return at.getWorld().getNearbyEntitiesByType(Villager.class, at, r, r, r).size();
    }

    private void schedule(Location at, String type, int population) {
        pending.add(new Pending(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(),
                System.currentTimeMillis() + plugin.settings().replenishDelayMillis, type, population));
        save();
    }

    void start() {
        load();
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            replenish();
            leashSettlers();
            tendReservations();
        }, 200L, 20L * 20);
    }

    void stop() {
        if (task != null) task.cancel();
        task = null;
        save();
    }

    /**
     * Spawns the replacement babies that are due, where their village's chunk is
     * loaded (otherwise later). One whose village has filled up again by itself
     * (breeding) is dropped, so a village never ends up with more than it had.
     */
    private void replenish() {
        if (pending.isEmpty()) return;
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Pending> it = pending.iterator(); it.hasNext(); ) {
            Pending pd = it.next();
            if (pd.due() > now) continue;
            World w = Bukkit.getWorld(pd.world());
            if (w == null) continue;   // its world isn't loaded: later
            int cx = (int) Math.floor(pd.x()) >> 4, cz = (int) Math.floor(pd.z()) >> 4;
            if (!w.isChunkLoaded(cx, cz)) continue;   // nobody's near the village: later
            Location at = new Location(w, pd.x(), pd.y(), pd.z());
            if (!plugin.settings().replenishEnabled) {
                it.remove();
                changed = true;
                continue;
            }
            int count = villagersNear(at);
            it.remove();
            changed = true;
            if (count >= pd.population() || count >= plugin.settings().replenishMaxNearby) continue;
            Location spot = standable(at);
            Villager.Type type = villagerType(pd.type());
            w.spawn(spot, Villager.class, v -> {
                v.setBaby();
                if (type != null) v.setVillagerType(type);
                v.getPersistentDataContainer().set(naturalKey, PersistentDataType.BYTE, (byte) 1);
            });
            w.spawnParticle(Particle.HEART, spot.clone().add(0, 1, 0), 6, 0.3, 0.3, 0.3);
            plugin.getLogger().info("A baby villager was born at " + w.getName() + " " + spot.getBlockX() + " " + spot.getBlockY() + " "
                    + spot.getBlockZ() + " to replace a villager hired there.");
        }
        if (changed) save();
    }

    /** The spot itself if a villager fits there on solid ground, otherwise the top of the ground there. */
    private static Location standable(Location at) {
        Block feet = at.getBlock();
        if (feet.isPassable() && feet.getRelative(0, 1, 0).isPassable() && feet.getRelative(0, -1, 0).getType().isSolid()) {
            return at;
        }
        Block top = at.getWorld().getHighestBlockAt(at);
        return top.getLocation().add(0.5, 1, 0.5);
    }

    // ── saving ────────────────────────────────────────────────────────────

    private void load() {
        pending.clear();
        settled.clear();
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (String u : y.getStringList("settlers-given")) {
            try {
                settled.add(java.util.UUID.fromString(u));
            } catch (IllegalArgumentException ignored) {
                // skip
            }
        }
        reserved.clear();
        ConfigurationSection rs = y.getConfigurationSection("reserved");
        if (rs != null) {
            for (String k : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(k);
                if (r == null) continue;
                try {
                    java.util.UUID id = java.util.UUID.fromString(k);
                    JobType job = JobType.parse(r.getString("job", ""));
                    if (job == null) continue;
                    reserved.put(id, new Reservation(id, java.util.UUID.fromString(r.getString("owner", "")), job, r.getLong("until"),
                            r.getString("world", "world"), r.getDouble("x"), r.getDouble("y"), r.getDouble("z")));
                } catch (IllegalArgumentException ignored) {
                    // skip a broken entry
                }
            }
        }
        ConfigurationSection s = y.getConfigurationSection("replenish");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            ConfigurationSection r = s.getConfigurationSection(k);
            if (r == null || r.getString("world") == null) continue;
            pending.add(new Pending(r.getString("world"), r.getDouble("x"), r.getDouble("y"), r.getDouble("z"),
                    r.getLong("due"), r.getString("type"), r.getInt("population")));
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.options().setHeader(List.of("Villages waiting for a baby villager to replace one that was hired,",
                "and players who've had their kingdom's settlers. The plugin rewrites this file while it runs."));
        y.set("settlers-given", settled.stream().map(java.util.UUID::toString).sorted().toList());
        for (Reservation r : reserved.values()) {
            ConfigurationSection c = y.createSection("reserved." + r.villager());
            c.set("owner", r.owner().toString());
            c.set("job", r.job().name());
            c.set("until", r.until());
            c.set("world", r.world());
            c.set("x", r.x());
            c.set("y", r.y());
            c.set("z", r.z());
        }
        for (int i = 0; i < pending.size(); i++) {
            Pending pd = pending.get(i);
            ConfigurationSection r = y.createSection("replenish." + i);
            r.set("world", pd.world());
            r.set("x", pd.x());
            r.set("y", pd.y());
            r.set("z", pd.z());
            r.set("due", pd.due());
            r.set("type", pd.type());
            r.set("population", pd.population());
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Couldn't save villager-hiring.yml: " + ex.getMessage());
        }
    }

    /** The villager the player is looking at, within reach, or null. */
    public static Villager lookedAt(Player p) {
        Entity t = p.getTargetEntity(6);
        return t instanceof Villager v ? v : null;
    }
}
