package site.remotearc.employees.board;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.Skin;
import site.remotearc.employees.job.Worker;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Avatars: life-size stand-ins for employees, set up in a control room.
 * Each wears its employee's skin, holds its tool and shows its live status
 * under its name. Right-click opens the employee's menu, shift-right-click
 * pauses or resumes them (see EmployeeListener). One avatar per employee;
 * the entities are respawned from avatars.yml, never saved with the world.
 */
public final class Avatars {

    public record Avatar(UUID employee, String world, double x, double y, double z, float yaw) {
        Location location() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, 0);
        }
    }

    private final ArcEmployees plugin;
    private final File file;
    private final NamespacedKey key;
    private final Map<UUID, Avatar> avatars = new LinkedHashMap<>();
    private final Map<UUID, Mannequin> shown = new HashMap<>();
    private final Map<UUID, Skin> shownSkin = new HashMap<>();
    private BukkitTask task;

    public Avatars(ArcEmployees plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "avatars.yml");
        this.key = new NamespacedKey(plugin, "avatar");
    }

    public void start() {
        load();
        for (World w : Bukkit.getWorlds()) for (Mannequin m : w.getEntitiesByClass(Mannequin.class)) removeIfStray(m);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 30L, 40L);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (Mannequin m : shown.values()) if (m.isValid()) m.remove();
        shown.clear();
    }

    /** Puts an employee's avatar here (moving it if it already has one). */
    public void place(Employee e, Location at) {
        avatars.put(e.id, new Avatar(e.id, at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw()));
        despawn(e.id);
        save();
        refresh();
    }

    public boolean remove(UUID employee) {
        boolean had = avatars.remove(employee) != null;
        despawn(employee);
        if (had) save();
        return had;
    }

    public boolean has(UUID employee) {
        return avatars.containsKey(employee);
    }

    /** The employee an avatar entity stands for, or null. */
    public Employee employeeOf(Entity entity) {
        String id = entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null) return null;
        UUID eid = UUID.fromString(id);
        for (Employee e : plugin.manager().all()) if (e.id.equals(eid)) return e;
        return null;
    }

    public boolean isAvatar(Entity entity) {
        return entity.getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    public void removeIfStray(Entity entity) {
        String id = entity.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (id == null) return;
        Mannequin mine = shown.get(UUID.fromString(id));
        if (mine == null || !mine.equals(entity)) entity.remove();
    }

    private void despawn(UUID employee) {
        Mannequin m = shown.remove(employee);
        shownSkin.remove(employee);
        if (m != null && m.isValid()) m.remove();
    }

    private void refresh() {
        Map<UUID, Employee> byId = new HashMap<>();
        for (Employee e : plugin.manager().all()) byId.put(e.id, e);
        boolean changed = false;
        for (Avatar a : avatars.values().toArray(new Avatar[0])) {
            Employee e = byId.get(a.employee());
            if (e == null) {   // fired
                avatars.remove(a.employee());
                despawn(a.employee());
                changed = true;
                continue;
            }
            Location l = a.location();
            if (l == null || !l.getWorld().isChunkLoaded(l.getBlockX() >> 4, l.getBlockZ() >> 4)) continue;
            Mannequin m = shown.get(e.id);
            if (m == null || !m.isValid()) {
                m = spawn(e, l);
                shown.put(e.id, m);
            }
            if (!e.skin.equals(shownSkin.get(e.id))) {
                m.setProfile(e.skin.profile(e.id));
                shownSkin.put(e.id, e.skin);
            }
            m.customName(Component.text(e.name, NamedTextColor.GOLD));
            Worker.State s = e.worker == null ? null : e.worker.state();
            NamedTextColor color = s == null ? NamedTextColor.DARK_GRAY : switch (s) {
                case WORKING -> NamedTextColor.GREEN;
                case IDLE -> NamedTextColor.GRAY;
                case FULL -> NamedTextColor.YELLOW;
                case PAUSED, RESTING -> NamedTextColor.DARK_GRAY;
                case STRIKE -> NamedTextColor.RED;
            };
            if (EventLog.isProblem(e.status)) color = NamedTextColor.GOLD;
            String status = e.status.length() > 42 ? e.status.substring(0, 41) + "…" : e.status;
            m.setDescription(Component.text(e.job.display() + ": " + status, color));
        }
        if (changed) save();
    }

    private Mannequin spawn(Employee e, Location at) {
        return at.getWorld().spawn(at, Mannequin.class, mm -> {
            mm.setPersistent(false);
            mm.setRemoveWhenFarAway(false);
            mm.setGravity(false);
            mm.setInvulnerable(true);
            mm.setCollidable(false);
            mm.setSilent(true);
            mm.setImmovable(true);
            mm.setCustomNameVisible(true);
            mm.getEquipment().setItemInMainHand(new ItemStack(plugin.settings().job(e.job).tool()));
            mm.getPersistentDataContainer().set(key, PersistentDataType.STRING, e.id.toString());
        });
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = y.getConfigurationSection("avatars");
        if (s == null) return;
        for (String k : s.getKeys(false)) {
            try {
                UUID id = UUID.fromString(k);
                avatars.put(id, new Avatar(id, s.getString(k + ".world"), s.getDouble(k + ".x"), s.getDouble(k + ".y"), s.getDouble(k + ".z"),
                        (float) s.getDouble(k + ".yaw")));
            } catch (RuntimeException ignored) {
                // skip
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Avatar a : avatars.values()) {
            String k = "avatars." + a.employee();
            y.set(k + ".world", a.world());
            y.set(k + ".x", a.x());
            y.set(k + ".y", a.y());
            y.set(k + ".z", a.z());
            y.set(k + ".yaw", a.yaw());
        }
        try {
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not save avatars.yml", ex);
        }
    }
}
