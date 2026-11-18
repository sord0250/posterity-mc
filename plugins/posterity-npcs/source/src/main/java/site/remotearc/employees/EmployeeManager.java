package site.remotearc.employees;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import site.remotearc.employees.board.EventLog;
import site.remotearc.employees.job.JobType;
import site.remotearc.employees.job.Worker;
import site.remotearc.employees.storage.StorageTarget;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.Msg;
import site.remotearc.employees.util.Pathfinder;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Owns every employee: spawns and moves their Mannequins, ticks their workers,
 * keeps their work sites loaded and saves to disk.
 */
public final class EmployeeManager {

    public static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final Pattern MC_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final ArcEmployees plugin;
    private final EmployeeStore store;
    private final NamespacedKey entityKey;
    private final Map<UUID, Employee> employees = new LinkedHashMap<>();
    private final Map<UUID, Employee> byEntity = new HashMap<>();
    /** Chunk tickets we currently hold, per world name. */
    private final Map<String, Set<Long>> tickets = new HashMap<>();
    private final Random random = new Random();
    private final List<BukkitTask> tasks = new ArrayList<>();
    private boolean dirty;
    private final EventLog events = new EventLog();

    public EventLog events() {
        return events;
    }

    EmployeeManager(ArcEmployees plugin) {
        this.plugin = plugin;
        this.store = new EmployeeStore(plugin.getDataFolder(), plugin.getLogger());
        this.entityKey = new NamespacedKey(plugin, "employee");
    }

    // ── lifecycle ─────────────────────────────────────────────────────────

    void load() {
        for (Employee e : store.load()) {
            if (e.craftNeverUnset && e.job == JobType.TRADER) e.craftNever.addAll(plugin.settings().traderNeverCraft);
            e.worker = new Worker(plugin, e);
            employees.put(e.id, e);
        }
        plugin.getLogger().info("Loaded " + employees.size() + " employee(s).");
    }

    /** A world loaded after startup: bring in the employees that were waiting for it. */
    public void worldLoaded(World w) {
        boolean any = false;
        for (Employee e : store.retryPreserved()) {
            String was = e.name;
            for (int n = 2; nameTaken(e.owner, e.name); n++) e.name = was + n;
            if (!was.equals(e.name)) {
                plugin.getLogger().info("Renamed " + was + " to " + e.name + ": its owner hired another " + was + " while it was away.");
            }
            if (e.craftNeverUnset && e.job == JobType.TRADER) e.craftNever.addAll(plugin.settings().traderNeverCraft);
            e.worker = new Worker(plugin, e);
            employees.put(e.id, e);
            keepHomeOutdoors(e);
            any = true;
            plugin.getLogger().info("Loaded " + e.name + " now that world " + w.getName() + " is loaded.");
        }
        if (any) {
            refreshTickets();
            markDirty();
        }
    }

    /** "Pip (builder) in build_studio" for a player's employees whose world isn't loaded right now. */
    public List<String> waitingFor(UUID owner) {
        List<String> out = new ArrayList<>();
        for (String[] w : store.waiting()) {
            if (w[0].equals(owner.toString())) out.add(w[1] + " (" + w[2].toLowerCase(java.util.Locale.ROOT) + ") in " + w[3]);
        }
        return out;
    }

    void start() {
        loadActivity();
        removeStrayEntities();
        for (Employee e : employees.values()) {
            keepHomeOutdoors(e);
            // Homes left behind by an area changed in an older version.
            if (e.area != null && e.job != JobType.BUILDER) followArea(e, e.area);
        }
        refreshTickets();
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!Bukkit.getOnlinePlayers().isEmpty()) playerSeen();
        }, 100L, 20L * 30));
        tasks.add(Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (dirty) save();
        }, 20L * 30, 20L * 30));
    }

    void stop() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
        for (Employee e : employees.values()) {
            if (e.worker != null) e.worker.interrupt();
            despawn(e);
        }
        save();
        if (!Bukkit.getOnlinePlayers().isEmpty()) lastPlayerOnline = System.currentTimeMillis();
        saveActivity();
        for (World w : Bukkit.getWorlds()) w.removePluginChunkTickets(plugin);
        tickets.clear();
    }

    public void markDirty() {
        dirty = true;
    }

    public void save() {
        store.save(employees.values());
        dirty = false;
    }

    /** Nobody on the server this tick. */
    private boolean empty;

    /**
     * Resting: nobody is on the server and the owner hasn't told this one to
     * work while everyone's away. It does nothing (and eats nothing).
     */
    public boolean resting(Employee e) {
        return empty && !e.worksAway && plugin.settings().restWhenEmpty;
    }

    private void tick() {
        empty = Bukkit.getOnlinePlayers().isEmpty();
        for (Employee e : employees.values()) {
            try {
                if (ensureEntity(e)) e.worker.tick();
            } catch (RuntimeException ex) {
                // One broken employee must not stop the others; log it at most once a minute.
                if (Bukkit.getCurrentTick() % 1200 == 0 || e.status == null || !e.status.startsWith("Error")) {
                    plugin.getLogger().log(Level.WARNING, "Employee " + e.name + " failed to tick", ex);
                }
                e.status = "Error: see server log";
            }
        }
    }

    // ── entities ──────────────────────────────────────────────────────────

    public boolean isEmployeeEntity(Entity entity) {
        return entity.getPersistentDataContainer().has(entityKey, PersistentDataType.STRING);
    }

    public Employee byEntity(Entity entity) {
        return byEntity.get(entity.getUniqueId());
    }

    /** Spawns the employee's Mannequin if it is missing and its chunk is loaded. */
    private boolean ensureEntity(Employee e) {
        if (e.isLoaded()) return true;
        if (e.entity != null) {
            byEntity.remove(e.entity.getUniqueId());
            e.entity = null;
        }
        World w = e.location.getWorld();
        if (w == null || !w.isChunkLoaded(e.location.getBlockX() >> 4, e.location.getBlockZ() >> 4)) {
            e.status = "Off shift: work site not loaded";
            return false;
        }
        spawn(e);
        return e.isLoaded();
    }

    private void spawn(Employee e) {
        lastLine.remove(e.id);   // a new entity: its line has to be put up again
        Mannequin m = e.location.getWorld().spawn(e.location, Mannequin.class, mm -> {
            mm.setPersistent(false);   // never saved with the chunk: we respawn it from employees.yml
            mm.setRemoveWhenFarAway(false);
            mm.setGravity(false);      // the worker places it exactly on the floor
            mm.setInvulnerable(!takesDamage(e));   // fighters can be hurt by mobs (see EmployeeListener); the rest never
            mm.setCollidable(false);
            mm.setSilent(true);
            mm.setImmovable(true);
            mm.setCustomNameVisible(true);
            mm.getPersistentDataContainer().set(entityKey, PersistentDataType.STRING, e.id.toString());
            mm.getEquipment().setItemInMainHand(new ItemStack(plugin.settings().job(e.job).tool()));
            ItemStack[] worn = new ItemStack[4];
            for (int i = 0; i < 4; i++) worn[i] = e.armor[i] == null ? null : e.armor[i].clone();
            mm.getEquipment().setArmorContents(worn);
            mm.setProfile(e.skin.profile(e.id));
            applyLabel(e, mm);
        });
        e.entity = m;
        byEntity.put(m.getUniqueId(), e);
    }

    private void despawn(Employee e) {
        if (e.entity != null) {
            byEntity.remove(e.entity.getUniqueId());
            if (e.entity.isValid()) e.entity.remove();
            e.entity = null;
        }
    }

    /** Removes Mannequins of ours that are not tracked (left behind by a crash or /reload). */
    private void removeStrayEntities() {
        for (World w : Bukkit.getWorlds()) {
            for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) removeIfStray(m);
        }
    }

    public void removeIfStray(Entity entity) {
        if (isEmployeeEntity(entity) && byEntity(entity) == null) entity.remove();
    }

    /** The line last put over each employee's head, so it's only sent when it changes. */
    private final Map<UUID, Component> lastLine = new HashMap<>();

    public void updateLabel(Employee e) {
        if (e.isLoaded()) applyLabel(e, e.entity);
    }

    /**
     * Its name and job over its head, and under that what it's doing right now
     * (see Activity): "⚒ Harvesting wheat", "☾ Sleeping in bed", "⚠ Too hungry to work".
     */
    private void applyLabel(Employee e, Mannequin m) {
        Component name = Component.text(e.name, NamedTextColor.GOLD).append(Component.text(" · " + e.job.display(), NamedTextColor.GRAY));
        if (!name.equals(m.customName())) m.customName(name);
        Component desc = Activity.line(plugin, e);
        if (!desc.equals(lastLine.put(e.id, desc))) m.setDescription(desc);
    }

    public void applySkin(Employee e) {
        if (e.isLoaded()) e.entity.setProfile(e.skin.profile(e.id));
        markDirty();
    }

    /** Re-equips tools after a config reload. */
    void refreshEquipment() {
        for (Employee e : employees.values()) {
            if (e.isLoaded()) e.entity.getEquipment().setItemInMainHand(new ItemStack(plugin.settings().job(e.job).tool()));
        }
    }

    // ── queries ───────────────────────────────────────────────────────────

    // ── homes ─────────────────────────────────────────────────────────────

    /** True if homes are switched on and this employee has none: it works unhappily and slower. */
    public boolean homeless(Employee e) {
        return plugin.settings().homesEnabled && e.house == null;
    }

    /** The employee (anyone's) whose home has this bed, or null. */
    public Employee bedOwner(String world, site.remotearc.employees.util.BlockPos bed) {
        for (Employee o : employees.values()) {
            if (o.house != null && o.house.world.equals(world) && o.house.bed.equals(bed)) return o;
        }
        return null;
    }

    public void giveHouse(Employee e, House h) {
        e.house = h;
        events().add(e.owner, e.name, "Moved into a home: " + h.describe(), false);
        updateLabel(e);
        markDirty();
    }

    /** Takes its home away (evicted, or the bed was broken); it's homeless again. */
    public void loseHouse(Employee e, String why) {
        if (e.house == null) return;
        e.house = null;
        events().add(e.owner, e.name, "Lost their home: " + why, true);
        updateLabel(e);
        markDirty();
    }

    public Collection<Employee> all() {
        return Collections.unmodifiableCollection(employees.values());
    }

    public List<Employee> ownedBy(UUID owner) {
        List<Employee> out = new ArrayList<>();
        for (Employee e : employees.values()) if (e.owner.equals(owner)) out.add(e);
        return out;
    }

    /**
     * Finds an employee by name for a command sender: their own first; admins
     * may also name anyone's (as "Name" if unique, or "Owner:Name").
     */
    public Employee byId(UUID id) {
        return employees.get(id);
    }

    public Employee find(CommandSender sender, String name) {
        boolean admin = sender.hasPermission("employees.admin");
        if (name.contains(":")) {
            String owner = name.substring(0, name.indexOf(':'));
            String n = name.substring(name.indexOf(':') + 1);
            for (Employee e : employees.values()) {
                if (e.name.equalsIgnoreCase(n) && e.ownerName().equalsIgnoreCase(owner)) return canManage(sender, e) ? e : null;
            }
            return null;
        }
        if (sender instanceof Player p) {
            for (Employee e : employees.values()) {
                if (e.owner.equals(p.getUniqueId()) && e.name.equalsIgnoreCase(name)) return e;
            }
            // Then the employees of owners who let this player manage theirs (if the name is unique among them).
            Employee only = null;
            for (Employee e : employees.values()) {
                if (!e.name.equalsIgnoreCase(name) || !plugin.managers().trusts(e.owner, p.getUniqueId())) continue;
                if (only != null) {
                    only = null;
                    break;
                }
                only = e;
            }
            if (only != null) return only;
        }
        if (!admin) return null;
        Employee only = null;
        for (Employee e : employees.values()) {
            if (!e.name.equalsIgnoreCase(name)) continue;
            if (only != null) return null;   // ambiguous
            only = e;
        }
        return only;
    }

    /** Guards and bodyguards fight, so (with jobs.guard.take-damage) mobs can hurt them. */
    public boolean takesDamage(Employee e) {
        return (e.job == JobType.GUARD || e.job == JobType.BODYGUARD) && plugin.settings().guardsTakeDamage;
    }

    /** Down for jobs.guard.knockout-seconds instead of dying; back on its feet at full health after. */
    public void knockOut(Employee e, String by) {
        e.knockedOutUntil = System.currentTimeMillis() + plugin.settings().knockoutSeconds * 1000L;
        e.knockedOutBy = by;
        if (e.worker != null) e.worker.interrupt();
        if (e.isLoaded()) {
            e.entity.setHealth(1);
            e.entity.getWorld().playSound(e.entity.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_HURT, 1f, 0.8f);
        }
        notifyOwner(e, "<yellow>" + e.name + " was knocked out by " + by + " and is down for " + plugin.settings().knockoutSeconds
                + "s. Better armour helps (put it in their backpack).", false);
        markDirty();
    }

    /** The owner, a player the owner trusts to manage them, or an admin. */
    public boolean canManage(CommandSender sender, Employee e) {
        if (sender.hasPermission("employees.admin")) return true;
        return sender instanceof Player p && (p.getUniqueId().equals(e.owner) || plugin.managers().trusts(e.owner, p.getUniqueId()));
    }

    public boolean nameTaken(UUID owner, String name) {
        for (Employee e : employees.values()) {
            if (e.owner.equals(owner) && e.name.equalsIgnoreCase(name)) return true;
        }
        // One away while its world isn't loaded keeps its name too.
        for (String[] w : store.waiting()) if (w[0].equals(owner.toString()) && w[1].equalsIgnoreCase(name)) return true;
        return false;
    }

    public String randomName(UUID owner) {
        List<String> pool = new ArrayList<>(plugin.settings().names);
        Collections.shuffle(pool, random);
        for (String n : pool) {
            if (NAME.matcher(n).matches() && !nameTaken(owner, n)) return n;
        }
        for (int i = 1; ; i++) {
            if (!nameTaken(owner, "Worker" + i)) return "Worker" + i;
        }
    }

    // ── hiring and firing ─────────────────────────────────────────────────

    /** Hires an employee standing at {@code at}, working the area around it. Payment must already be taken. */
    public Employee hire(UUID owner, JobType job, String name, Location at) {
        return hire(owner, job, name, at, plugin.kingdomBridge().kingdomOf(owner));
    }

    /** Same, tagged with a kingdom (PosterityKingdoms) directly, instead of looking the owner's up. */
    public Employee hire(UUID owner, JobType job, String name, Location at, String kingdom) {
        Employee e = new Employee(UUID.randomUUID(), owner, job, name);
        e.kingdom = kingdom;
        if (job == JobType.TRADER) e.craftNever.addAll(plugin.settings().traderNeverCraft);
        e.skin = Skin.random(random);
        e.personality = Personality.random(random);
        e.hiredAt = System.currentTimeMillis();
        e.food = plugin.settings().foodTopUpDays * plugin.settings().foodPerDay;   // turns up having eaten
        Location feet = standingSpot(at);
        e.home = feet.clone();
        e.location = feet.clone();
        e.area = defaultArea(job, at);
        e.storage = job == JobType.COURIER ? StorageTarget.NONE : defaultStorage(owner, job);   // a courier only carries
        switch (job) {
            case MINER -> e.filter.addAll(plugin.settings().minerTargets);
            case SMELTER -> e.filter.addAll(plugin.settings().smelterInputs);
            default -> { }
        }
        e.worker = new Worker(plugin, e);
        employees.put(e.id, e);
        keepHomeOutdoors(e);
        refreshTickets();
        save();
        return e;
    }

    /**
     * The work area hiring sets up around a spot, from the job's settings:
     * a square of area-radius, from area-below blocks under the spot to
     * area-above over it (-1 = the block under the spot, for quarries).
     */
    public WorkArea defaultArea(JobType job, Location at) {
        return defaultArea(job, at, plugin.settings().job(job).areaRadius());
    }

    public WorkArea defaultArea(JobType job, Location at, int r) {
        int x = at.getBlockX(), z = at.getBlockZ();
        return areaFor(job, at.getWorld(), x - r, z - r, x + r, z + r, at.getBlockY(), at.getBlockY());
    }

    /** A box between two corners, reaching the job's usual depth below the lower one and height above the higher one. */
    public WorkArea areaFor(JobType job, World w, int x1, int z1, int x2, int z2, int lowY, int highY) {
        Settings.JobSettings js = plugin.settings().job(job);
        int bottom = Math.max(w.getMinHeight(), lowY - Math.max(0, js.areaBelow()));
        int top = Math.min(w.getMaxHeight() - 1, highY + js.areaAbove());
        if (top < bottom) top = bottom;
        return WorkArea.of(w.getName(), x1, bottom, z1, x2, top, z2);
    }

    /** The first empty /chest number at or above the configured start that no other employee uses. */
    private StorageTarget defaultStorage(UUID owner, JobType job) {
        if (!plugin.settings().vcEnabled || !plugin.vchests().available()) return StorageTarget.NONE;
        int inGroup = plugin.chestGroups().free(owner, job);
        if (inGroup > 0) return StorageTarget.virtual(inGroup);
        Set<Integer> used = usedChests(owner);
        int first = plugin.settings().vcFirstChest;
        for (int n = first; n < first + 50; n++) {
            if (!used.contains(n) && !plugin.vchests().hasContents(owner, n)) return StorageTarget.virtual(n);
        }
        return StorageTarget.NONE;
    }

    /** Every /chest number the owner's employees deliver to, overflow into, or take supplies from. */
    public Set<Integer> usedChests(UUID owner) {
        Set<Integer> used = new HashSet<>();
        for (Employee other : ownedBy(owner)) {
            if (other.storage.kind() == StorageTarget.Kind.VIRTUAL) used.add(other.storage.chestNumber());
            used.addAll(other.overflow);
            for (StorageTarget t : other.supply) if (t.kind() == StorageTarget.Kind.VIRTUAL) used.add(t.chestNumber());
            for (Employee.Route r : other.routes) {
                if (r.from().kind() == StorageTarget.Kind.VIRTUAL) used.add(r.from().chestNumber());
                if (r.to().kind() == StorageTarget.Kind.VIRTUAL) used.add(r.to().chestNumber());
            }
        }
        return used;
    }

    /**
     * Claims the owner's next empty /chest number that no employee uses, as
     * overflow for this employee's full chest. Returns it, or -1.
     */
    public int claimOverflow(Employee e) {
        if (e.storage.kind() != StorageTarget.Kind.VIRTUAL || e.overflow.size() >= plugin.settings().vcOverflow) return -1;
        int n = plugin.chestGroups().free(e.owner, e.job);
        if (n < 0) {
            long now = System.currentTimeMillis();
            if (now - e.groupFullWarned > 3_600_000L) {   // once an hour, not on every delivery
                e.groupFullWarned = now;
                notifyOwner(e, "<yellow>" + e.name + "'s /chest " + e.storage.chestNumber() + " is full and the " + e.job.display().toLowerCase()
                        + " chest group (/chest " + plugin.chestGroups().range(e.owner, e.job) + ") has no free chest left. Empty some, or move"
                        + " the group to new numbers in /employees chests.", true);
            }
            return -1;
        }
        notifyOwner(e, "<yellow>" + e.name + "'s /chest " + e.storage.chestNumber() + " is full, so they started filling /chest " + n
                + " (overflow " + (e.overflow.size() + 1) + " of " + plugin.settings().vcOverflow + ").", true);
        e.overflow.add(n);
        markDirty();
        return n;
    }

    /**
     * Fires an employee. Its backpack goes to {@code receiver} (overflow drops
     * at their feet) or, with no receiver, is dropped where the employee stood.
     */
    public void fire(Employee e, Player receiver) {
        e.worker.interrupt();
        if (e.homeSite != null) plugin.homeSites().clear(e);   // deletes its copy of the house plans
        for (var viewer : List.copyOf(e.backpack.getViewers())) viewer.closeInventory();
        List<ItemStack> items = new ArrayList<>();
        for (ItemStack s : e.backpack.getContents()) if (s != null && !s.getType().isAir()) items.add(s);
        e.backpack.clear();
        if (receiver != null) {
            for (ItemStack s : Items.add(receiver.getInventory(), items)) receiver.getWorld().dropItem(receiver.getLocation(), s);
        } else if (e.location.getWorld() != null) {
            for (ItemStack s : items) e.location.getWorld().dropItem(e.location, s);
        }
        if (e.build != null && !e.build.stock.isEmpty() && plugin.economy() != null) {
            // Materials bought for its build that it never used: the owner gets the money back.
            plugin.economy().depositPlayer(Bukkit.getOfflinePlayer(e.owner), e.build.takeStockValue());
        }
        despawn(e);
        if (plugin.villagerHiring() != null) plugin.villagerHiring().released(e);   // a hired villager goes back to being one
        employees.remove(e.id);
        plugin.ledger().forget(e.id);
        refreshTickets();
        save();
    }

    /** Puts the employee somewhere else (any world) without changing its home or area: a bodyguard catching up. */
    public void teleport(Employee e, Location at) {
        e.location = at.clone();
        if (e.isLoaded() && e.entity.getWorld().equals(at.getWorld())) e.entity.teleport(at);
        else despawn(e);   // respawned in the new place on the next tick
        markDirty();
    }

    /** Moves the employee (and its home) to a spot, keeping its work area. */
    public void relocate(Employee e, Location at) {
        e.worker.interrupt();
        Location feet = standingSpot(at);
        e.home = feet.clone();
        e.location = feet.clone();
        if (e.isLoaded() && e.entity.getWorld().equals(feet.getWorld())) e.entity.teleport(feet);
        else despawn(e);
        refreshTickets();
        markDirty();
    }

    public void setArea(Employee e, WorkArea area) {
        e.area = area;
        e.spots.keySet().removeIf(p -> !area.contains(p));
        e.worker.interrupt();
        refreshTickets();
        if (e.job != JobType.BUILDER) followArea(e, area);
        markDirty();
    }

    /**
     * Keeps an employee's home with its work area: if the area moved away from
     * home, home becomes a standing spot in the middle of the new area, and an
     * employee that is far from it (more than a long walk) is moved there.
     */
    /** Jobs that work out in the open: a home under a roof only traps them. */
    private static final java.util.Set<JobType> OUTDOOR = java.util.EnumSet.of(JobType.GUARD, JobType.BODYGUARD,
            JobType.LUMBERJACK, JobType.FARMER, JobType.RANCHER);

    /** Moves an outdoor worker's home out from under a roof onto open ground nearby. */
    public void keepHomeOutdoors(Employee e) {
        if (!OUTDOOR.contains(e.job) || e.home == null || e.home.getWorld() == null) return;
        Location h = e.home;
        if (!h.getWorld().isChunkLoaded(h.getBlockX() >> 4, h.getBlockZ() >> 4)) return;
        if (h.getBlock().getLightFromSky() >= 15) return;
        Location open = openSpot(e);
        if (open != null) {
            e.home = open;
            events.add(e.owner, e.name, "Home spot was under a roof; moved it to open ground", false);
            markDirty();
        }
    }

    /**
     * Open ground for an employee: the nearest spot under the sky to its home
     * (or its area's middle), inside its area when it has one.
     */
    public Location openSpot(Employee e) {
        Location from = e.home != null ? e.home : e.location;
        World w = from.getWorld();
        if (w == null) return null;
        int fx = from.getBlockX(), fz = from.getBlockZ();
        for (int r = 0; r <= 24; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = fx + dx, z = fz + dz;
                    if (e.area != null && !e.area.containsColumn(x, z)) continue;
                    if (!w.isChunkLoaded(x >> 4, z >> 4)) continue;
                    int y = w.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
                    Pathfinder pf = new Pathfinder(w, org.bukkit.util.BoundingBox.of(new Vector(x, y, z), 2, 3, 2)).leavesOpen(false);
                    double f = pf.floor(x, y, z);
                    if (Double.isNaN(f)) continue;
                    if (w.getBlockAt(x, y, z).getLightFromSky() < 15) continue;
                    return new Location(w, x + 0.5, f, z + 0.5, from.getYaw(), 0);
                }
            }
        }
        return null;
    }

    private void followArea(Employee e, WorkArea area) {
        World w = area.bukkitWorld();
        if (w == null) return;
        boolean homeOk = e.home != null && e.home.getWorld() == w
                && area.expand(2, 0).containsColumn(e.home.getBlockX(), e.home.getBlockZ());
        if (homeOk) return;
        Location spot = standingSpotIn(w, area);
        if (spot == null) return;
        e.home = spot.clone();
        keepHomeOutdoors(e);
        spot = e.home.clone();
        boolean near = e.location.getWorld() == w && area.expand(32, 0).containsColumn(e.location.getBlockX(), e.location.getBlockZ());
        if (!near) relocate(e, spot);
    }

    /** A place to stand near the middle of an area: the ground there, or failing that anywhere along the middle row. */
    private static Location standingSpotIn(World w, WorkArea a) {
        int cx = (int) Math.floor(a.centerX()), cz = (int) Math.floor(a.centerZ());
        for (int[] d : new int[][]{{0, 0}, {2, 0}, {-2, 0}, {0, 2}, {0, -2}, {4, 4}, {-4, -4}}) {
            int x = cx + d[0], z = cz + d[1];
            if (!a.containsColumn(x, z)) continue;
            w.getChunkAt(x >> 4, z >> 4);   // make sure it's loaded
            int top = Math.max(a.minY(), Math.min(a.maxY() + 1, w.getHighestBlockYAt(x, z) + 1));
            Pathfinder pf = new Pathfinder(w, org.bukkit.util.BoundingBox.of(new Vector(x, top, z), 4, 10, 4));
            Vector v = pf.snap(x + 0.5, top, z + 0.5);
            if (v != null) return new Location(w, v.getX(), v.getY(), v.getZ());
        }
        return null;
    }

    public void setStorage(Employee e, StorageTarget target) {
        if (!target.equals(e.storage)) e.overflow.clear();
        e.storage = target;
        e.worker.interrupt();
        refreshTickets();
        markDirty();
    }

    /** Where it gets its food; NONE = wherever it delivers. */
    public void setPantry(Employee e, StorageTarget target) {
        e.pantry = target;
        refreshTickets();
        markDirty();
    }

    public void setPaused(Employee e, boolean paused) {
        if (e.paused == paused) return;
        if (paused) e.worker.interrupt();
        e.paused = paused;
        updateLabel(e);
        markDirty();
    }

    public void rename(Employee e, String name) {
        e.rename(name);
        updateLabel(e);
        markDirty();
    }

    private static Location standingSpot(Location at) {
        Pathfinder pf = new Pathfinder(at.getWorld(), org.bukkit.util.BoundingBox.of(at.toVector(), 4, 8, 4));
        Vector v = pf.snap(at.getX(), at.getY(), at.getZ());
        Location l = v == null ? at.clone() : new Location(at.getWorld(), v.getX(), v.getY(), v.getZ());
        l.setYaw(at.getYaw());
        l.setPitch(0);
        return l;
    }

    // ── chunk tickets ─────────────────────────────────────────────────────

    /** Holds plugin chunk tickets on every work site so employees (and their crops and trees) keep going. */
    public void refreshTickets() {
        Map<String, Set<Long>> want = new HashMap<>();
        if (plugin.settings().keepLoaded) {
            for (Employee e : employees.values()) {
                if (e.area != null) {
                    WorkArea a = e.area.expand(8, 0);
                    for (int cx = a.minX() >> 4; cx <= a.maxX() >> 4; cx++) {
                        for (int cz = a.minZ() >> 4; cz <= a.maxZ() >> 4; cz++) add(want, a.world(), cx, cz);
                    }
                }
                if (e.location.getWorld() != null) {
                    add(want, e.location.getWorld().getName(), e.location.getBlockX() >> 4, e.location.getBlockZ() >> 4);
                }
                // A home it's building itself: it walks there and back, so the site stays loaded too.
                if (e.selfBuild && e.siteArea != null) {
                    WorkArea a = e.siteArea.expand(4, 0);
                    for (int cx = a.minX() >> 4; cx <= a.maxX() >> 4; cx++) {
                        for (int cz = a.minZ() >> 4; cz <= a.maxZ() >> 4; cz++) add(want, a.world(), cx, cz);
                    }
                }
                if (e.storage.kind() == StorageTarget.Kind.CHEST) {
                    add(want, e.storage.world(), e.storage.pos().x() >> 4, e.storage.pos().z() >> 4);
                }
                if (e.pantry.kind() == StorageTarget.Kind.CHEST) {
                    add(want, e.pantry.world(), e.pantry.pos().x() >> 4, e.pantry.pos().z() >> 4);
                }
                if (e.house != null) add(want, e.house.world, e.house.bed.x() >> 4, e.house.bed.z() >> 4);   // it goes home to sleep
                for (StorageTarget t : e.supply) {
                    if (t.kind() == StorageTarget.Kind.CHEST) add(want, t.world(), t.pos().x() >> 4, t.pos().z() >> 4);
                }
                for (Employee.Route r : e.routes) {
                    for (StorageTarget t : List.of(r.from(), r.to())) {
                        if (t.kind() == StorageTarget.Kind.CHEST) add(want, t.world(), t.pos().x() >> 4, t.pos().z() >> 4);
                    }
                }
            }
        }
        for (Map.Entry<String, Set<Long>> held : tickets.entrySet()) {
            World w = Bukkit.getWorld(held.getKey());
            if (w == null) continue;
            Set<Long> keep = want.getOrDefault(held.getKey(), Set.of());
            for (long k : held.getValue()) {
                if (!keep.contains(k)) w.removePluginChunkTicket((int) (k >> 32), (int) k, plugin);
            }
        }
        for (Map.Entry<String, Set<Long>> wanted : want.entrySet()) {
            World w = Bukkit.getWorld(wanted.getKey());
            if (w == null) continue;
            Set<Long> have = tickets.getOrDefault(wanted.getKey(), Set.of());
            for (long k : wanted.getValue()) {
                if (!have.contains(k)) w.addPluginChunkTicket((int) (k >> 32), (int) k, plugin);
            }
        }
        tickets.clear();
        tickets.putAll(want);
    }

    private static void add(Map<String, Set<Long>> map, String world, int cx, int cz) {
        map.computeIfAbsent(world, k -> new HashSet<>()).add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
    }

    public int ticketCount() {
        int n = 0;
        for (Set<Long> s : tickets.values()) n += s.size();
        return n;
    }

    /**
     * Somewhere safe to stand in the middle of a work area, to look at it:
     * the top of the centre column if that's within (or just above) the area,
     * otherwise the first open floor inside it, searching down from the top.
     */
    public Location areaViewpoint(WorkArea a) {
        return areaViewpoint(a, (a.minX() + a.maxX()) / 2, (a.minZ() + a.maxZ()) / 2);
    }

    /** The same, at one column of the area (a corner). */
    public Location areaViewpoint(WorkArea a, int cx, int cz) {
        World w = a.bukkitWorld();
        if (w == null) return null;
        w.getChunkAt(cx >> 4, cz >> 4);
        int top = w.getHighestBlockYAt(cx, cz, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (top < a.minY() - 1 || top > a.maxY() + 24) {
            Pathfinder pf = new Pathfinder(w, new org.bukkit.util.BoundingBox(a.minX(), a.minY(), a.minZ(), a.maxX() + 1, a.maxY() + 1, a.maxZ() + 1));
            for (int y = a.maxY(); y >= a.minY(); y--) {
                double f = pf.floor(cx, y, cz);
                if (!Double.isNaN(f)) return new Location(w, cx + 0.5, f, cz + 0.5);
            }
        }
        return new Location(w, cx + 0.5, top + 1, cz + 0.5);
    }

    // ── activity ──────────────────────────────────────────────────────────

    /** When a player was last on the server (epoch millis), kept in activity.yml across restarts. */
    private long lastPlayerOnline = -1;
    private long lastActivitySave;

    public void playerSeen() {
        long now = System.currentTimeMillis();
        lastPlayerOnline = now;
        if (now - lastActivitySave < 5 * 60_000) return;
        lastActivitySave = now;
        saveActivity();
    }

    private java.io.File activityFile() {
        return new java.io.File(plugin.getDataFolder(), "activity.yml");
    }

    private void loadActivity() {
        var y = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(activityFile());
        // No record yet (first start with this setting): count from now, so nobody loses a day to the upgrade.
        lastPlayerOnline = y.getLong("last-player-online", System.currentTimeMillis());
    }

    private void saveActivity() {
        var y = new org.bukkit.configuration.file.YamlConfiguration();
        y.set("last-player-online", lastPlayerOnline);
        try {
            y.save(activityFile());
        } catch (java.io.IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save activity.yml", ex);
        }
    }

    /** Tells the owner something: in chat if online, otherwise (optionally) by Essentials mail. */
    public void notifyOwner(Employee e, String mini, boolean mailIfOffline) {
        events.add(e.owner, e.name, Msg.plain(mini).replaceFirst("^" + java.util.regex.Pattern.quote(e.name) + "('s)? ?", ""),
                mini.startsWith("<red>") || mini.startsWith("<yellow>"));
        Player online = Bukkit.getPlayer(e.owner);
        if (online != null) {
            Msg.send(online, mini);
            return;
        }
        if (!mailIfOffline || !plugin.settings().mailWhenOffline) return;
        if (!Bukkit.getPluginManager().isPluginEnabled("Essentials")) return;
        String name = e.ownerPlayer().getName();
        if (name == null || !MC_NAME.matcher(name).matches()) return;
        String text = Msg.plain(mini).replaceAll("[^A-Za-z0-9 .,:;!?()'$/-]", "");
        try {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mail send " + name + " [Employees] " + text);
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.FINE, "Could not mail " + name, ex);
        }
    }

    public static String jobKeyList() {
        List<String> keys = new ArrayList<>();
        for (JobType t : JobType.values()) keys.add(t.key());
        return String.join(", ", keys).toLowerCase(Locale.ROOT);
    }
}
