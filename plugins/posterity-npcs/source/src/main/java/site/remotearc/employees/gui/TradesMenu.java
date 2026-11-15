package site.remotearc.employees.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.util.BoundingBox;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.util.Items;
import site.remotearc.employees.util.WorkArea;

import java.util.ArrayList;
import java.util.List;

/** A trader's trades: the ones picked, and the villagers in its area to pick more from. */
public final class TradesMenu extends Menu {

    private final Employee e;
    private final Menu back;

    public TradesMenu(ArcEmployees plugin, Employee e, Menu back) {
        super(plugin, 6, Component.text(e.name + "'s trades"));
        this.e = e;
        this.back = back != null ? back : new EmployeeMenu(plugin, e, null);
    }

    @Override
    protected void draw() {
        for (int s = 0; s < 54; s++) button(s, filler(), null);
        button(4, icon(Material.BOOK, "<gold>How trading works", List.of(
                "<gray>Pick a villager below, then the offers",
                "<gray>to make. Whenever that villager has",
                "<gray>stock, " + e.name + " makes the trade, paying",
                "<gray>from its backpack and your storage, and",
                "<gray>delivers what it gets. Villagers restock",
                "<gray>at their workstations as usual.")), null);

        button(9, icon(Material.EMERALD, "<green>Picked →", List.of("<gray>Click one to drop it.")), null);
        int slot = 10;
        for (Employee.TradeChoice c : new ArrayList<>(e.trades)) {
            if (slot > 26) break;
            if (slot == 18) slot++;
            Entity en = Bukkit.getEntity(c.villager());
            String who = en instanceof Villager v ? villagerName(v) : "a villager that's gone";
            ItemStack it = icon(c.result(), "<white>" + Items.name(c.result()), List.of(
                    "<gray>For " + Items.name(c.input1()) + (c.input2() == Material.AIR ? "" : " + " + Items.name(c.input2())),
                    "<gray>From " + Describe.esc(who), "", "<yellow>Click to drop this trade"));
            button(slot++, glow(it), (p, cl) -> {
                e.trades.remove(c);
                plugin.manager().markDirty();
                redraw();
            });
        }

        button(27, icon(Material.VILLAGER_SPAWN_EGG, "<aqua>Villagers in the area →", List.of("<gray>Click one to see its offers.")), null);
        slot = 28;
        WorkArea a = e.area;
        if (a != null && a.bukkitWorld() != null) {
            WorkArea r = a.expand(4, 4);
            BoundingBox box = new BoundingBox(r.minX(), r.minY(), r.minZ(), r.maxX() + 1, r.maxY() + 1, r.maxZ() + 1);
            List<Villager> vs = new ArrayList<>();
            for (Entity en : a.bukkitWorld().getNearbyEntities(box, x -> x instanceof Villager)) vs.add((Villager) en);
            vs.sort((x, y) -> villagerName(x).compareTo(villagerName(y)));
            for (Villager v : vs) {
                if (slot > 44) break;
                if (slot == 36) slot++;
                long picked = e.trades.stream().filter(c -> c.villager().equals(v.getUniqueId())).count();
                ItemStack it = icon(workstation(v), "<white>" + Describe.esc(villagerName(v)), List.of(
                        "<gray>At " + v.getLocation().getBlockX() + ", " + v.getLocation().getBlockY() + ", " + v.getLocation().getBlockZ(),
                        "<gray>" + v.getRecipeCount() + " offers" + (picked > 0 ? ", <green>" + picked + " picked" : ""),
                        "", "<yellow>Click to see its offers"));
                button(slot++, picked > 0 ? glow(it) : it, (p, cl) -> new VillagerOffersMenu(plugin, e, v, this).open(p));
            }
            if (vs.isEmpty()) button(28, icon(Material.PAPER, "<yellow>No villagers here", List.of("<gray>Set " + e.name + "'s work area",
                    "<gray>over your trading hall.")), null);
        }
        button(48, icon(e.craft ? Material.CRAFTING_TABLE : Material.BARRIER, "<aqua>Crafting: " + (e.craft ? "<green>on" : "<red>off"), List.of(
                "<gray>Short of what a trade costs? " + e.name,
                "<gray>crafts it from your storage: oak logs",
                "<gray>into planks into sticks, and so on.",
                "", "<yellow>Click to turn " + (e.craft ? "off" : "on"))), (p, c) -> {
            run(p, "craft " + ref(p, e) + " " + (e.craft ? "off" : "on"));
            redraw();
        });
        button(50, icon(Material.LAVA_BUCKET, "<aqua>Never craft with: " + e.craftNever.size(), List.of(
                "<gray>Things " + e.name + " never uses up",
                "<gray>to craft trade goods (diamonds...).", "", "<yellow>Click to see or change")),
                (p, c) -> new CraftNeverMenu(plugin, e, this).open(p));
        button(45, back(), (p, c) -> back.open(p));
    }

    /** A trader's never-craft-with list: click one to allow it again, or add more. */
    private static final class CraftNeverMenu extends Menu {

        private final Employee e;
        private final Menu back;

        CraftNeverMenu(ArcEmployees plugin, Employee e, Menu back) {
            super(plugin, 6, Component.text(e.name + " never crafts with"));
            this.e = e;
            this.back = back;
        }

        @Override
        protected void draw() {
            for (int s = 0; s < 54; s++) button(s, filler(), null);
            int slot = 0;
            for (Material m : new ArrayList<>(e.craftNever)) {
                if (slot >= 45) break;
                button(slot++, icon(m, "<white>" + Items.name(m), List.of("", "<yellow>Click to allow it again")), (p, c) -> {
                    e.craftNever.remove(m);
                    plugin.manager().markDirty();
                    redraw();
                });
            }
            button(45, back(), (p, c) -> back.open(p));
            button(49, icon(Material.CHEST, "<gold>Add items", List.of("<gray>Search or pick from the list.", "", "<yellow>Click to choose")),
                    (p, c) -> new ItemPickerMenu(plugin, "Never craft with", SUGGESTIONS, null, 0, e.craftNever, m -> {
                        e.craftNever.add(m);
                        plugin.manager().markDirty();
                    }, this).open(p));
        }

        private static final List<Material> SUGGESTIONS = List.of(Material.DIAMOND, Material.DIAMOND_BLOCK, Material.EMERALD,
                Material.EMERALD_BLOCK, Material.GOLD_INGOT, Material.GOLD_BLOCK, Material.IRON_INGOT, Material.IRON_BLOCK,
                Material.NETHERITE_INGOT, Material.NETHERITE_SCRAP, Material.NETHERITE_BLOCK, Material.LAPIS_LAZULI, Material.LAPIS_BLOCK,
                Material.REDSTONE, Material.REDSTONE_BLOCK, Material.COPPER_INGOT, Material.COPPER_BLOCK, Material.AMETHYST_SHARD,
                Material.QUARTZ, Material.NETHER_STAR, Material.ECHO_SHARD, Material.HEAVY_CORE, Material.ENCHANTED_GOLDEN_APPLE,
                Material.GOLDEN_APPLE, Material.SLIME_BALL, Material.HONEYCOMB, Material.STRING, Material.WHITE_WOOL, Material.LEATHER,
                Material.BOOK, Material.PAPER, Material.SUGAR_CANE, Material.WHEAT, Material.HAY_BLOCK, Material.BAMBOO,
                Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG,
                Material.MANGROVE_LOG, Material.CHERRY_LOG);
    }

    static String villagerName(Villager v) {
        if (v.customName() != null) return PlainTextComponentSerializer.plainText().serialize(v.customName());
        String prof = v.getProfession().getKey().getKey().replace('_', ' ');
        return Character.toUpperCase(prof.charAt(0)) + prof.substring(1) + " (level " + v.getVillagerLevel() + ")";
    }

    static Material workstation(Villager v) {
        return switch (v.getProfession().getKey().getKey()) {
            case "armorer" -> Material.BLAST_FURNACE;
            case "butcher" -> Material.SMOKER;
            case "cartographer" -> Material.CARTOGRAPHY_TABLE;
            case "cleric" -> Material.BREWING_STAND;
            case "farmer" -> Material.COMPOSTER;
            case "fisherman" -> Material.BARREL;
            case "fletcher" -> Material.FLETCHING_TABLE;
            case "leatherworker" -> Material.CAULDRON;
            case "librarian" -> Material.LECTERN;
            case "mason" -> Material.STONECUTTER;
            case "shepherd" -> Material.LOOM;
            case "toolsmith" -> Material.SMITHING_TABLE;
            case "weaponsmith" -> Material.GRINDSTONE;
            default -> Material.VILLAGER_SPAWN_EGG;
        };
    }

    /** One villager's offers: click to pick or drop each. */
    private static final class VillagerOffersMenu extends Menu {

        private final Employee e;
        private final Villager v;
        private final Menu back;

        VillagerOffersMenu(ArcEmployees plugin, Employee e, Villager v, Menu back) {
            super(plugin, 3, Component.text(villagerName(v)));
            this.e = e;
            this.v = v;
            this.back = back;
        }

        @Override
        protected void draw() {
            for (int s = 0; s < 27; s++) button(s, filler(), null);
            if (!v.isValid()) return;
            List<MerchantRecipe> list = v.getRecipes();
            for (int i = 0; i < list.size() && i < 18; i++) {
                MerchantRecipe r = list.get(i);
                List<ItemStack> ing = r.getIngredients();
                ItemStack first = r.getAdjustedIngredient1() != null ? r.getAdjustedIngredient1() : ing.getFirst();
                Material in2 = ing.size() > 1 && ing.get(1) != null ? ing.get(1).getType() : Material.AIR;
                Employee.TradeChoice c = new Employee.TradeChoice(v.getUniqueId(), ing.getFirst().getType(), in2, r.getResult().getType());
                boolean picked = e.trades.contains(c);
                List<String> lore = new ArrayList<>();
                lore.add("<gray>Costs: <white>" + first.getAmount() + " " + Items.name(first.getType())
                        + (in2 == Material.AIR ? "" : " + " + ing.get(1).getAmount() + " " + Items.name(in2)));
                lore.add("<gray>Gives: <white>" + r.getResult().getAmount() + " " + Items.name(r.getResult().getType()));
                lore.add("<gray>Stock: <white>" + (r.getMaxUses() - r.getUses()) + " of " + r.getMaxUses());
                lore.add("");
                lore.add(picked ? "<green>Picked. <yellow>Click to drop it" : "<yellow>Click to pick this trade");
                ItemStack it = r.getResult().clone();
                ItemStack shown = icon(it.getType(), (picked ? "<green>" : "<white>") + Items.name(it.getType()), lore);
                shown.setAmount(Math.max(1, Math.min(99, it.getAmount())));
                button(i, picked ? glow(shown) : shown, (p, cl) -> {
                    if (picked) e.trades.remove(c);
                    else e.trades.add(c);
                    plugin.manager().markDirty();
                    redraw();
                });
            }
            button(18, back(), (p, c) -> back.open(p));
        }
    }
}
