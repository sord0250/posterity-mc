package site.remotearc.employees.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/** An axis-aligned box of blocks (inclusive on every side) that an employee works in. */
public record WorkArea(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static WorkArea of(String world, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new WorkArea(world, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** A square around a point: radius blocks each way, from 3 below to 5 above. */
    public static WorkArea around(String world, int x, int y, int z, int radius) {
        return of(world, x - radius, y - 3, z - radius, x + radius, y + 5, z + radius);
    }

    public World bukkitWorld() {
        return Bukkit.getWorld(world);
    }

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(BlockPos p) {
        return contains(p.x(), p.y(), p.z());
    }

    /** Same test without the vertical bounds. */
    public boolean containsColumn(int x, int z) {
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public int width() { return maxX - minX + 1; }
    public int length() { return maxZ - minZ + 1; }
    public int height() { return maxY - minY + 1; }

    public double centerX() { return (minX + maxX + 1) / 2.0; }
    public double centerZ() { return (minZ + maxZ + 1) / 2.0; }

    public WorkArea expand(int horizontal, int vertical) {
        return new WorkArea(world, minX - horizontal, minY - vertical, minZ - horizontal,
                maxX + horizontal, maxY + vertical, maxZ + horizontal);
    }

    public String describe() {
        return width() + "x" + length() + " around " + (int) Math.floor(centerX()) + ", " + (int) Math.floor(centerZ());
    }

    public void save(ConfigurationSection s) {
        s.set("world", world);
        s.set("min", minX + "," + minY + "," + minZ);
        s.set("max", maxX + "," + maxY + "," + maxZ);
    }

    public static WorkArea load(ConfigurationSection s) {
        if (s == null) return null;
        BlockPos a = BlockPos.parse(s.getString("min", "0,0,0"));
        BlockPos b = BlockPos.parse(s.getString("max", "0,0,0"));
        return of(s.getString("world", "world"), a.x(), a.y(), a.z(), b.x(), b.y(), b.z());
    }
}
