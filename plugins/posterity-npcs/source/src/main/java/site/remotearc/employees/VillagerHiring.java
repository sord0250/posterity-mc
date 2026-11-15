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
 * /employees hire &lt;job&gt;), pick a job, and the villager becomes an employee:
 * it's taken out of the world and a Mannequin with a player skin takes its
 * place, doing that job like any other employee. Hiring a villager is free for
 * now (no hire fee, no first wage).
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
    /** A settler that hasn't been given a role wanders no further than this from its kingdom's Core. */
    private static final int SETTLER_LEASH_RADIUS = 50;
    /** Founders who've had their settlers already: they come with a player's first kingdom only. */
    private final Set<java.util.UUID> settled = new java.util.HashSet<>();

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
        String why = whyNot(p, v);
        if (why != null) {
            Msg.error(p, why);
            return;
        }
        HireMenu.forVillager(plugin, v).open(p);
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
        Location at = v.getLocation();
        if (!plugin.estate().mayWork(p.getUniqueId(), v.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ())) {
            return "That villager lives on someone else's land.";
        }
        return null;
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
            at.getWorld().spawn(at, Villager.class, v -> {
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
            });
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
        for (World w : Bukkit.getWorlds()) {
            for (Villager v : w.getEntitiesByClass(Villager.class)) {
                if (!v.getPersistentDataContainer().has(settlerKey, PersistentDataType.STRING)) continue;
                String tag = v.getPersistentDataContainer().get(coreKey, PersistentDataType.STRING);
                if (tag == null) continue;
                Location core = parseCoreTag(tag);
                if (core == null || core.getWorld() != w) continue;
                if (v.getLocation().distanceSquared(core) > (double) SETTLER_LEASH_RADIUS * SETTLER_LEASH_RADIUS) {
                    v.teleport(standable(core));
                }
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
     * villager goes and a skinned NPC takes its place. Free for now.
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
        String name = wanted != null ? wanted : nameFor(p, v);
        if (!EmployeeManager.NAME.matcher(name).matches()) {
            Msg.error(p, "Names are 1-16 letters, digits or underscores.");
            return null;
        }
        if (mgr.nameTaken(p.getUniqueId(), name)) {
            Msg.error(p, "You already employ someone called <white><name></white>.", Msg.p("name", name));
            return null;
        }

        Location at = v.getLocation();
        World world = v.getWorld();
        boolean settler = settlerOf(v) != null;
        String type = v.getVillagerType().getKey().toString();
        Personality former = Personality.parse(v.getPersistentDataContainer().get(personalityKey, PersistentDataType.STRING));
        boolean refill = plugin.settings().replenishEnabled && !v.getPersistentDataContainer().has(formerKey, PersistentDataType.BYTE);
        int population = refill ? villagersNear(at) : 0;   // counts this one: the village gets back to this many

        world.spawnParticle(Particle.HAPPY_VILLAGER, at.clone().add(0, 1, 0), 20, 0.4, 0.6, 0.4);
        world.playSound(at, Sound.ENTITY_VILLAGER_YES, 1f, 1f);
        v.remove();

        Employee e = mgr.hire(p.getUniqueId(), job, name, at);
        e.villagerType = type;
        if (former != null) e.personality = former;
        mgr.save();
        if (refill) schedule(at, type, population);
        plugin.getLogger().info(p.getName() + " hired a villager at " + world.getName() + " " + at.getBlockX() + " " + at.getBlockY() + " "
                + at.getBlockZ() + " as " + e.name + " the " + job.display().toLowerCase() + (refill ? "; its village gets a baby villager later." : "."));
        EmployeesCommand.welcome(plugin, p, e, "<green>" + (settler ? "Your settler" : "The villager") + " agreed to work for you: meet <gold>"
                + e.name + "</gold> the " + job.display().toLowerCase() + ". <gray>(" + (settler ? "Settlers are free to hire." : "Hiring villagers is free for now.") + ")");
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
