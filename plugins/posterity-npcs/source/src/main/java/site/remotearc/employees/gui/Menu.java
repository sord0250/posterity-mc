package site.remotearc.employees.gui;

import io.papermc.paper.datacomponent.DataComponentTypes;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.Employee;
import site.remotearc.employees.util.Msg;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A chest-style menu: fixed buttons, every click cancelled, redrawn every second while open. */
public abstract class Menu implements InventoryHolder {

    public interface Action {
        void run(Player player, ClickType click);
    }

    /** Menus to reopen when a player closes a backpack or chest they opened from them. */
    static final Map<java.util.UUID, Menu> RETURN_TO = new java.util.concurrent.ConcurrentHashMap<>();

    protected final ArcEmployees plugin;
    private final Inventory inventory;
    private final Map<Integer, Action> actions = new HashMap<>();

    protected Menu(ArcEmployees plugin, int rows, Component title) {
        this.plugin = plugin;
        this.inventory = Bukkit.createInventory(this, rows * 9, title);
    }

    /** (Re)fills the inventory. Called on open and once a second while anyone has it open (if {@link #live}). */
    protected abstract void draw();

    /** Whether to redraw every second; menus that read chest contents only draw on open. */
    protected boolean live() {
        return true;
    }

    /** Opens the menu. A dialog-based menu (e.g. {@link EmployeeMenu}) overrides this instead of using the chest inventory. */
    public void open(Player p) {
        openFor(p);
        redraw();
        p.openInventory(inventory);
    }

    /** Called with the viewer just before the menu is drawn and opened. */
    public void openFor(Player p) {}

    public final void redraw() {
        inventory.clear();
        actions.clear();
        draw();
    }

    final void click(Player p, int rawSlot, ClickType click) {
        Action a = actions.get(rawSlot);
        if (a != null) a.run(p, click);
    }

    protected void button(int slot, ItemStack icon, Action action) {
        inventory.setItem(slot, icon);
        if (action != null) actions.put(slot, action);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    // ── icon helpers ──────────────────────────────────────────────────────

    protected static ItemStack icon(Material m, String name, List<String> lore) {
        ItemStack s = ItemStack.of(m);
        ItemMeta meta = s.getItemMeta();
        meta.displayName(Msg.item(name));
        meta.lore(Msg.lore(lore));
        meta.addItemFlags(ItemFlag.values());
        s.setItemMeta(meta);
        return s;
    }

    /** A player head wearing the employee's skin. */
    protected static ItemStack head(Employee e, String name, List<String> lore) {
        ItemStack s = icon(Material.PLAYER_HEAD, name, lore);
        s.setData(DataComponentTypes.PROFILE, e.skin.profile(e.id));
        return s;
    }

    /** Opens another inventory (a backpack, a chest); closing it comes back here. */
    protected void openThenReturn(Player p, Runnable opener) {
        opener.run();
        RETURN_TO.put(p.getUniqueId(), this);
    }

    /** Runs an /employees subcommand as the player, so menus and commands always behave the same. */
    protected static void run(Player p, String args) {
        p.performCommand("employees " + args);
    }

    /** How a player names an employee in commands: plain for their own, Owner:Name for an admin managing someone else's. */
    protected static String ref(Player p, Employee e) {
        return e.owner.equals(p.getUniqueId()) ? e.name : e.ownerName() + ":" + e.name;
    }

    protected static ItemStack glow(ItemStack s) {
        s.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, true);
        return s;
    }

    protected static ItemStack back() {
        return icon(Material.ARROW, "<yellow>Back", List.of());
    }

    /** Splits text into lore lines of about this many characters. */
    protected static List<String> wrap(String color, String text, int width) {
        List<String> out = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && line.length() + word.length() + 1 > width) {
                out.add(color + line);
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) out.add(color + line);
        return out;
    }

    /** Lines followed by more lines, for lore. */
    protected static List<String> concat(List<String> first, String... more) {
        List<String> out = new java.util.ArrayList<>(first);
        out.addAll(List.of(more));
        return out;
    }

    protected static ItemStack filler() {
        return icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
    }
}
