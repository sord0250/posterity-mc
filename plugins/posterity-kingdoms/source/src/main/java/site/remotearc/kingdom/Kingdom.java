package site.remotearc.kingdom;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
    /** The kingdom's flag: a banner design set once (/kingdom flag set) and bought as many times as wanted. Null: none yet. */
    public DyeColor flagBase;
    public final List<Pattern> flagPatterns = new ArrayList<>();

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

    /** Who rules this kingdom, owner first, the way players think of "who's in charge" (rulers alone leaves the owner out). */
    public String rulerNames() {
        StringBuilder out = new StringBuilder(ownerName()).append(" (owner)");
        for (UUID id : rulers) {
            String n = Bukkit.getOfflinePlayer(id).getName();
            if (n != null) out.append(", ").append(n);
        }
        return out.toString();
    }

    /** A fresh banner in this kingdom's flag design, or null if its owner hasn't set one yet (/kingdom flag set). */
    public ItemStack flagItem() {
        if (flagBase == null) return null;
        ItemStack item = new ItemStack(Material.valueOf(flagBase.name() + "_BANNER"));
        if (item.getItemMeta() instanceof BannerMeta meta) {
            meta.setPatterns(flagPatterns);
            meta.displayName(Component.text(name + " Flag").decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
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
        if (flagBase != null) {
            s.set("flag-base", flagBase.name());
            s.set("flag-patterns", flagPatterns.stream().map(pt -> pt.getColor().name() + ":" + pt.getPattern().getKey().getKey()).toList());
        }
    }

    static Kingdom load(ConfigurationSection s) {
        String[] c = s.getString("core", "0,0,0").split(",");
        Kingdom k = new Kingdom(s.getName(), s.getString("name", s.getName()), UUID.fromString(s.getString("owner")),
                s.getString("world", "world"), Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]));
        k.since = s.getLong("since", System.currentTimeMillis());
        for (String r : s.getStringList("rulers")) k.rulers.add(UUID.fromString(r));
        String fb = s.getString("flag-base");
        if (fb != null) {
            try {
                k.flagBase = DyeColor.valueOf(fb);
                for (String entry : s.getStringList("flag-patterns")) {
                    String[] p = entry.split(":", 2);
                    if (p.length != 2) continue;
                    PatternType pt = Registry.BANNER_PATTERN.get(NamespacedKey.minecraft(p[1]));
                    if (pt != null) k.flagPatterns.add(new Pattern(DyeColor.valueOf(p[0]), pt));
                }
            } catch (IllegalArgumentException ignored) {
                k.flagBase = null;
                k.flagPatterns.clear();
            }
        }
        return k;
    }
}
