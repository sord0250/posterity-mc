package site.remotearc.kingdom;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

/**
 * The Kingdom Core: a piece of bedrock handed out by /kingdom core &lt;name&gt;.
 * Placing it founds the kingdom right there (instead of founding it
 * immediately wherever the player stood when they typed the command), and
 * the placed block can't be broken afterward - it's the kingdom's one
 * findable, permanent marker.
 */
final class CoreListener implements Listener {

    private static final NamespacedKey NAME_KEY = new NamespacedKey("posteritykingdoms", "core-name");
    private static final NamespacedKey FOUNDER_KEY = new NamespacedKey("posteritykingdoms", "core-founder");

    private final PosterityKingdoms plugin;

    CoreListener(PosterityKingdoms plugin) {
        this.plugin = plugin;
    }

    /** The bedrock item /kingdom core gives out, carrying the reserved name and who it's for. */
    static ItemStack item(String kingdomName, UUID founder) {
        ItemStack it = new ItemStack(Material.BEDROCK);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(Component.text(kingdomName + "'s Kingdom Core", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Place it where you want your kingdom", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("centered. Can't be broken once it's down.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(NAME_KEY, PersistentDataType.STRING, kingdomName);
        meta.getPersistentDataContainer().set(FOUNDER_KEY, PersistentDataType.STRING, founder.toString());
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent ev) {
        ItemMeta meta = ev.getItemInHand().getItemMeta();
        if (meta == null) return;
        var pdc = meta.getPersistentDataContainer();
        String name = pdc.get(NAME_KEY, PersistentDataType.STRING);
        String founderStr = pdc.get(FOUNDER_KEY, PersistentDataType.STRING);
        if (name == null || founderStr == null) return;
        Player p = ev.getPlayer();
        UUID founder;
        try {
            founder = UUID.fromString(founderStr);
        } catch (IllegalArgumentException ex) {
            return;
        }
        if (!founder.equals(p.getUniqueId())) {
            ev.setCancelled(true);
            Msg.error(p, "That Kingdom Core isn't yours to place.");
            return;
        }
        Kingdoms kingdoms = plugin.kingdoms();
        if (kingdoms.of(p.getUniqueId()) != null) {
            ev.setCancelled(true);
            Msg.error(p, "You're already in a kingdom.");
            return;
        }
        if (kingdoms.get(name) != null || kingdoms.everUsed(name)) {
            ev.setCancelled(true);
            Msg.error(p, "That name's been taken since you got this Core. Get a new one with /kingdom core \\<name>.");
            return;
        }
        Location at = ev.getBlock().getLocation();
        Kingdom k = new Kingdom(name, p.getUniqueId(), at);
        kingdoms.found(k);
        Msg.send(p, "<green>" + Msg.esc(k.name) + "</green> founded! This bedrock is its Core, right here: " + k.coreX + ", " + k.coreY + ", " + k.coreZ
                + ". <gray>It can't be broken.");
        KingdomCommand.settlers(plugin, founder, at, k);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent ev) {
        Block b = ev.getBlock();
        for (Kingdom k : plugin.kingdoms().all()) {
            if (k.world.equals(b.getWorld().getName()) && k.coreX == b.getX() && k.coreY == b.getY() && k.coreZ == b.getZ()) {
                ev.setCancelled(true);
                Msg.error(ev.getPlayer(), "That's " + k.name + "'s Core; it can't be broken. Disband the kingdom first with /kingdom delete.");
                return;
            }
        }
    }
}
