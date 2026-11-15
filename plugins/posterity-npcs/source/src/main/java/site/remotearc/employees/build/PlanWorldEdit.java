package site.remotearc.employees.build;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.entity.BaseEntity;
import com.sk89q.worldedit.entity.Entity;
import com.sk89q.worldedit.event.extent.EditSessionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.AbstractDelegateExtent;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.Location;
import com.sk89q.worldedit.util.eventbus.Subscribe;
import com.sk89q.worldedit.world.biome.BiomeType;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import site.remotearc.employees.ArcEmployees;

/**
 * Plan mode for WorldEdit: edits by a player who's planning go into their
 * plan instead of the world. The plan sits right under WorldEdit's history,
 * so //undo and //redo work on the plan, and //copy, masks and the like see
 * the planned blocks as if they were there. Only loaded with WorldEdit.
 */
public final class PlanWorldEdit {

    private static ArcEmployees plugin;

    private PlanWorldEdit() {}

    public static void register(ArcEmployees p) {
        plugin = p;
        WorldEdit.getInstance().getEventBus().register(new PlanWorldEdit());
    }

    @Subscribe
    public void onEditSession(EditSessionEvent ev) {
        if (ev.getStage() != EditSession.Stage.BEFORE_CHANGE) return;
        Actor a = ev.getActor();
        if (a == null || !a.isPlayer() || ev.getWorld() == null) return;
        PlanMode.Session s = PlanMode.of(a.getUniqueId());
        if (s != null && ev.getWorld().getName().equals(s.world)) {
            ev.setExtent(new PlanExtent(ev.getExtent(), s));
            return;
        }
        // Real edits in and round a finished build: the build takes them in (FollowChanges).
        Player p = Bukkit.getPlayer(a.getUniqueId());
        if (p == null || !Bukkit.isPrimaryThread()) return;
        java.util.List<site.remotearc.employees.util.WorkArea> boxes = FollowChanges.watched(p, ev.getWorld().getName());
        org.bukkit.World w = Bukkit.getWorld(ev.getWorld().getName());
        if (!boxes.isEmpty() && w != null) ev.setExtent(new FollowExtent(ev.getExtent(), p, w, boxes));
    }

    /** Passes edits through, noting the ones in a followed build. */
    private static final class FollowExtent extends AbstractDelegateExtent {
        private final Player p;
        private final org.bukkit.World w;
        private final java.util.List<site.remotearc.employees.util.WorkArea> boxes;
        private final java.util.Set<Long> touched = java.util.concurrent.ConcurrentHashMap.newKeySet();

        FollowExtent(Extent below, Player p, org.bukkit.World w, java.util.List<site.remotearc.employees.util.WorkArea> boxes) {
            super(below);
            this.p = p;
            this.w = w;
            this.boxes = boxes;
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 v, T block) throws com.sk89q.worldedit.WorldEditException {
            boolean r = super.setBlock(v, block);
            int x = v.x(), y = v.y(), z = v.z();
            for (site.remotearc.employees.util.WorkArea b : boxes) {
                if (b.contains(x, y, z)) {
                    // Looked at once the edit is in the world, all together.
                    if (touched.isEmpty()) Bukkit.getScheduler().runTask(plugin, () -> {
                        java.util.List<Long> keys = new java.util.ArrayList<>(touched);
                        touched.clear();
                        FollowChanges.touched(p, w, keys);
                    });
                    touched.add(site.remotearc.employees.util.BlockPos.pack(x, y, z));
                    break;
                }
            }
            return r;
        }
    }

    static void clearHistory(Player p) {
        LocalSession ls = WorldEdit.getInstance().getSessionManager().getIfPresent(BukkitAdapter.adapt(p));
        if (ls != null) ls.clearHistory();
    }

    /** Whether WorldEdit has a tool (a brush...) bound to this item for the player. */
    static boolean hasTool(Player p, Material m) {
        if (m.isAir() || !m.isItem()) return false;
        LocalSession ls = WorldEdit.getInstance().getSessionManager().getIfPresent(BukkitAdapter.adapt(p));
        return ls != null && ls.getTool(BukkitAdapter.asItemType(m)) != null;
    }

    /** Reads the plan over the world, and writes only to the plan. */
    private static final class PlanExtent extends AbstractDelegateExtent {

        private final PlanMode.Session s;

        PlanExtent(Extent below, PlanMode.Session s) {
            super(below);
            this.s = s;
        }

        @Override
        public BlockState getBlock(BlockVector3 p) {
            BlockData d = PlanMode.get(s, p.x(), p.y(), p.z());
            return d != null ? BukkitAdapter.adapt(d) : super.getBlock(p);
        }

        @Override
        public BaseBlock getFullBlock(BlockVector3 p) {
            BlockData d = PlanMode.get(s, p.x(), p.y(), p.z());
            return d != null ? BukkitAdapter.adapt(d).toBaseBlock() : super.getFullBlock(p);
        }

        @Override
        public <T extends BlockStateHolder<T>> boolean setBlock(BlockVector3 p, T block) {
            BlockData d = BukkitAdapter.adapt(block.toImmutableState());
            int x = p.x(), y = p.y(), z = p.z();
            if (Bukkit.isPrimaryThread()) PlanMode.set(s, x, y, z, d);
            else Bukkit.getScheduler().runTask(plugin, () -> PlanMode.set(s, x, y, z, d));
            return true;
        }

        @Override
        public Entity createEntity(Location location, BaseEntity entity) {
            return null;   // plans are blocks only
        }

        @Override
        public boolean setBiome(BlockVector3 position, BiomeType biome) {
            return false;
        }
    }
}
