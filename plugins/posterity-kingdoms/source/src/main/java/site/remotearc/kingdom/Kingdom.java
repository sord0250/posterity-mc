package site.remotearc.kingdom;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * A player-founded kingdom: just a name, an owner, the rulers its owner has
 * added, and the Core (the single block it was claimed at). A kingdom has no
 * territory of its own - it exists so NPCs can be told apart by whose kingdom
 * they belong to. See {@link Kingdoms} for the registry.
 */
public final class Kingdom {

    public final String id;
    public String name;
    public UUID owner;
    /** Players the owner has put in charge too (see /kingdom addruler); the owner is not listed here. */
    public final Set<UUID> rulers = new LinkedHashSet<>();
    public final String world;
    public final int coreX, coreY, coreZ;
    public long since;

    Kingdom(String name, UUID owner, Location core) {
        this.id = key(name);
        this.name = name;
        this.owner = owner;
        this.world = core.getWorld().getName();
        this.coreX = core.getBlockX();
        this.coreY = core.getBlockY();
        this.coreZ = core.getBlockZ();
        this.since = System.currentTimeMillis();
    }

    private Kingdom(String id, String name, UUID owner, String world, int coreX, int coreY, int coreZ) {
        this.id = id;
        this.name = name;
        this.owner = owner;
        this.world = world;
        this.coreX = coreX;
        this.coreY = coreY;
        this.coreZ = coreZ;
    }

    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    public static boolean validName(String n) {
        return n.matches("[A-Za-z0-9_-]{1,24}");
    }

    /** The owner, or a player they've put in charge. */
    public boolean isRuler(UUID player) {
        return owner.equals(player) || rulers.contains(player);
    }

    public String ownerName() {
        String n = Bukkit.getOfflinePlayer(owner).getName();
        return n == null ? owner.toString().substring(0, 8) : n;
    }

    public Location coreLocation() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, coreX + 0.5, coreY, coreZ + 0.5);
    }

    void save(ConfigurationSection s) {
        s.set("name", name);
        s.set("owner", owner.toString());
        s.set("world", world);
        s.set("core", coreX + "," + coreY + "," + coreZ);
        s.set("since", since);
        s.set("rulers", rulers.stream().map(UUID::toString).toList());
    }

    static Kingdom load(ConfigurationSection s) {
        String[] c = s.getString("core", "0,0,0").split(",");
        Kingdom k = new Kingdom(s.getName(), s.getString("name", s.getName()), UUID.fromString(s.getString("owner")),
                s.getString("world", "world"), Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
        k.since = s.getLong("since", System.currentTimeMillis());
        for (String r : s.getStringList("rulers")) k.rulers.add(UUID.fromString(r));
        return k;
    }
}
