package site.remotearc.employees.util;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** An immutable block coordinate, usable as a map key and stored as "x,y,z". */
public record BlockPos(int x, int y, int z) {

    public static BlockPos of(Block b) {
        return new BlockPos(b.getX(), b.getY(), b.getZ());
    }

    public static BlockPos of(Location l) {
        return new BlockPos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public static BlockPos parse(String s) {
        String[] p = s.split(",");
        return new BlockPos(Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()));
    }

    public Block block(World w) {
        return w.getBlockAt(x, y, z);
    }

    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }

    public BlockPos below() {
        return new BlockPos(x, y - 1, z);
    }

    public BlockPos above() {
        return new BlockPos(x, y + 1, z);
    }

    public double centerX() { return x + 0.5; }
    public double centerY() { return y + 0.5; }
    public double centerZ() { return z + 0.5; }

    public double distanceSq(double px, double py, double pz) {
        double dx = centerX() - px, dy = centerY() - py, dz = centerZ() - pz;
        return dx * dx + dy * dy + dz * dz;
    }

    public double horizontalDistanceSq(double px, double pz) {
        double dx = centerX() - px, dz = centerZ() - pz;
        return dx * dx + dz * dz;
    }

    /** Packs into a long for cheap hash keys; covers the whole world border. */
    public long key() {
        return pack(x, y, z);
    }

    /** The inverse of {@link #pack}. */
    public static BlockPos unpack(long key) {
        return new BlockPos((int) (key >> 38), (int) (key << 52 >> 52), (int) (key << 26 >> 38));
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }
}
