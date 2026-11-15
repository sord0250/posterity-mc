package site.remotearc.employees.storage;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import site.remotearc.employees.util.BlockPos;

/** Where an employee delivers what it harvests. */
public record StorageTarget(Kind kind, int chestNumber, String world, BlockPos pos) {

    public enum Kind {
        /** The owner's VirtualChests chest {@code chestNumber} (/chest N). */
        VIRTUAL,
        /** A container block in the world that the employee walks to. */
        CHEST,
        /** Keep everything in the employee's backpack. */
        NONE
    }

    public static final StorageTarget NONE = new StorageTarget(Kind.NONE, 0, null, null);

    public static StorageTarget virtual(int number) {
        return new StorageTarget(Kind.VIRTUAL, number, null, null);
    }

    public static StorageTarget chest(Block b) {
        return new StorageTarget(Kind.CHEST, 0, b.getWorld().getName(), BlockPos.of(b));
    }

    public Container container() {
        if (kind != Kind.CHEST) return null;
        World w = Bukkit.getWorld(world);
        if (w == null || !w.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) return null;
        return pos.block(w).getState(false) instanceof Container c ? c : null;
    }

    public String describe() {
        return switch (kind) {
            case VIRTUAL -> "/chest " + chestNumber;
            case CHEST -> "chest at " + pos.x() + ", " + pos.y() + ", " + pos.z();
            case NONE -> "its backpack";
        };
    }

    /**
     * Several places in one short line: "/chest 10, 11, 30 and the chest at
     * 5, 64, -3". The /chest prefix is written once.
     */
    public static String describeAll(java.util.Collection<StorageTarget> all) {
        java.util.List<String> nums = new java.util.ArrayList<>(), rest = new java.util.ArrayList<>();
        for (StorageTarget t : all) {
            if (t.kind == Kind.VIRTUAL) nums.add(String.valueOf(t.chestNumber));
            else if (t.kind == Kind.CHEST) rest.add("the " + t.describe());
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (!nums.isEmpty()) parts.add("/chest " + String.join(", ", nums));
        parts.addAll(rest);
        return String.join(" and ", parts);
    }

    /** Why someone may not take from or put into this place, or null if they may. */
    public String problemFor(org.bukkit.command.CommandSender s) {
        if (kind == Kind.VIRTUAL && !s.hasPermission("employees.admin") && !s.hasPermission("virtualchests.open." + chestNumber)) {
            return "You don't have access to /chest " + chestNumber + ".";
        }
        if (kind == Kind.CHEST && container() == null) return "There's no container at " + describe() + ".";
        return null;
    }

    /** "vc:10" or "chest:world:5,64,-3", for one-line saves (courier routes). */
    public String toShort() {
        return switch (kind) {
            case VIRTUAL -> "vc:" + chestNumber;
            case CHEST -> "chest:" + world + ":" + pos;
            case NONE -> "none";
        };
    }

    public static StorageTarget fromShort(String s) {
        String[] p = s.split(":", 3);
        try {
            if (p[0].equals("vc") && p.length == 2) return virtual(Math.max(1, Integer.parseInt(p[1])));
            if (p[0].equals("chest") && p.length == 3) return new StorageTarget(Kind.CHEST, 0, p[1], BlockPos.parse(p[2]));
        } catch (RuntimeException ignored) {
            // fall through
        }
        return NONE;
    }

    public void save(ConfigurationSection s) {
        s.set("type", kind.name());
        if (kind == Kind.VIRTUAL) s.set("chest", chestNumber);
        if (kind == Kind.CHEST) {
            s.set("world", world);
            s.set("pos", pos.toString());
        }
    }

    public static StorageTarget load(ConfigurationSection s) {
        if (s == null) return NONE;
        Kind k;
        try {
            k = Kind.valueOf(s.getString("type", "NONE"));
        } catch (IllegalArgumentException ex) {
            return NONE;
        }
        return switch (k) {
            case VIRTUAL -> virtual(Math.max(1, s.getInt("chest", 1)));
            case CHEST -> new StorageTarget(Kind.CHEST, 0, s.getString("world", "world"), BlockPos.parse(s.getString("pos", "0,0,0")));
            case NONE -> NONE;
        };
    }
}
