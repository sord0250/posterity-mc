package site.remotearc.kingdom;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** /kingdom (also /kd, /kingdoms): claiming a kingdom's Core and running it. */
final class KingdomCommand implements TabExecutor {

    private static final List<String> SUBS = List.of("help", "claim", "info", "addruler", "removeruler", "leave", "delete", "flag");
    private static final List<String> ADMIN = List.of("money");

    private final PosterityKingdoms plugin;

    KingdomCommand(PosterityKingdoms plugin) {
        this.plugin = plugin;
    }

    private Kingdoms kingdoms() {
        return plugin.kingdoms();
    }

    private boolean admin(CommandSender s) {
        return s.hasPermission("posteritykingdoms.admin");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        String sub = args.length == 0 ? "info" : args[0].toLowerCase(Locale.ROOT);
        if (ADMIN.contains(sub) && !admin(s)) {
            Msg.error(s, "Only admins can do that.");
            return true;
        }
        if (!(s instanceof Player p)) {
            if (!sub.equals("info")) {
                Msg.error(s, "Players only.");
                return true;
            }
            info(s, args.length > 1 ? kingdoms().get(args[1]) : null);
            return true;
        }
        switch (sub) {
            case "help" -> help(p, label);
            case "claim" -> claim(p, args);
            case "info" -> {
                if (args.length > 1) info(p, kingdoms().get(args[1]));
                else site.remotearc.kingdom.gui.KingdomMenu.show(plugin, p);
            }
            case "addruler" -> ruler(p, args, true);
            case "removeruler" -> ruler(p, args, false);
            case "leave" -> leave(p);
            case "delete" -> delete(p, args, label);
            case "flag" -> flag(p, args);
            case "money" -> money(p, args);
            default -> help(p, label);
        }
        return true;
    }

    private void help(CommandSender s, String label) {
        String c = "<gold>/" + label;
        s.sendMessage(Msg.parse(c + " claim <name></white> <gray>- claim this one spot as your kingdom's Core; its first"
                + " two NPCs arrive free to hire (with Posterity NPCs installed)"));
        s.sendMessage(Msg.parse(c + " info [kingdom]</white> <gray>- bare, or with no kingdom named: your own kingdom's info"
                + " screen, with buttons for all of the above; named: another kingdom's owner, rulers and Core as plain text"));
        s.sendMessage(Msg.parse(c + " addruler | removeruler <player></white> <gray>- put someone else in charge of your"
                + " kingdom too (they can also buy NPCs for it); owner only"));
        s.sendMessage(Msg.parse(c + " leave</white> <gray>- give up being a ruler of the kingdom you're in"));
        s.sendMessage(Msg.parse(c + " delete [kingdom]</white> <gray>- disband your kingdom (or, for admins, anyone's)"));
        s.sendMessage(Msg.parse(c + " flag set</white> <gray>- make the banner in your hand your kingdom's flag design (owner only)"));
        s.sendMessage(Msg.parse(c + " flag buy</white> <gray>- a copy of your kingdom's flag for " + plugin.settings().flagEmeralds + " emeralds"));
        if (admin(s)) {
            s.sendMessage(Msg.parse(c + " money <amount></white> <gray>- give yourself money (economy stub, until merchants exist)"));
        }
    }

    private void claim(Player p, String[] args) {
        if (args.length < 2) {
            Msg.error(p, "Usage: /kingdom claim <name>");
            return;
        }
        if (kingdoms().of(p.getUniqueId()) != null) {
            Msg.error(p, "You're already in a kingdom; /kingdom leave first (the owner disbands theirs with /kingdom delete).");
            return;
        }
        String name = args[1];
        if (!Kingdom.validName(name) || kingdoms().get(name) != null) {
            Msg.error(p, "Pick another name (1-24 letters, numbers, - or _, not already used).");
            return;
        }
        Kingdom k = new Kingdom(name, p.getUniqueId(), p.getLocation());
        kingdoms().found(k);
        Msg.send(p, "<green>" + Msg.esc(k.name) + "</green> claimed! Its Core is right here: " + k.coreX + ", " + k.coreY + ", " + k.coreZ + ".");
        settlers(p, k);
    }

    /**
     * With Posterity NPCs (ArcEmployees) installed, a player's first kingdom gets
     * settler villagers at its Core, free for the founder to hire. Reached by
     * reflection, the way ArcEmployees reaches PosterityKingdoms.kingdomOf:
     * neither plugin needs the other, and without ArcEmployees nothing happens.
     */
    private void settlers(Player p, Kingdom k) {
        var employees = Bukkit.getPluginManager().getPlugin("ArcEmployees");
        if (employees == null || !employees.isEnabled()) return;
        try {
            employees.getClass().getMethod("kingdomFounded", UUID.class, org.bukkit.Location.class, String.class)
                    .invoke(null, p.getUniqueId(), p.getLocation(), k.name);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            plugin.getLogger().warning("Couldn't send settlers to the new kingdom " + k.name + " (ArcEmployees): " + ex);
        }
    }

    private void info(CommandSender s, Kingdom k) {
        if (k == null) {
            Msg.error(s, "No such kingdom; stand in one or name one: /kingdom info <kingdom>");
            return;
        }
        s.sendMessage(Msg.parse("<gold><bold>" + Msg.esc(k.name) + "</bold></gold> <gray>· rulers: " + Msg.esc(k.rulerNames())
                + " · Core at " + k.coreX + ", " + k.coreY + ", " + k.coreZ + " in " + Msg.esc(k.world)));
    }

    private void ruler(Player p, String[] args, boolean adding) {
        Kingdom k = kingdoms().of(p.getUniqueId());
        if (k == null || !k.owner.equals(p.getUniqueId())) {
            Msg.error(p, "Only a kingdom's owner can manage its rulers.");
            return;
        }
        if (args.length < 2) {
            Msg.error(p, "Usage: /kingdom " + (adding ? "addruler" : "removeruler") + " <player>");
            return;
        }
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        UUID id = target.getUniqueId();
        if (id.equals(k.owner)) {
            Msg.error(p, "That's the owner.");
            return;
        }
        if (adding) {
            k.rulers.add(id);
            Msg.send(p, "<green>" + Msg.esc(args[1]) + "</green> can now rule " + Msg.esc(k.name) + " too: they can buy NPCs for it.");
        } else {
            k.rulers.remove(id);
            Msg.send(p, "<gray>" + Msg.esc(args[1]) + " no longer rules " + Msg.esc(k.name) + ".");
        }
        kingdoms().save();
    }

    private void leave(Player p) {
        Kingdom k = kingdoms().of(p.getUniqueId());
        if (k == null) {
            Msg.error(p, "You're not in a kingdom.");
            return;
        }
        if (k.owner.equals(p.getUniqueId())) {
            Msg.error(p, "The owner can't leave; /kingdom delete disbands it instead.");
            return;
        }
        k.rulers.remove(p.getUniqueId());
        kingdoms().save();
        Msg.send(p, "<gray>You're no longer a ruler of " + Msg.esc(k.name) + ".");
    }

    private void delete(Player p, String[] args, String label) {
        boolean confirmed = args.length > 1 && args[args.length - 1].equalsIgnoreCase("confirm");
        String name = args.length > 1 && !(confirmed && args.length == 2) ? args[1] : null;
        Kingdom k = name != null ? kingdoms().get(name) : kingdoms().of(p.getUniqueId());
        if (k == null) {
            Msg.error(p, "Usage: /kingdom delete <kingdom>");
            return;
        }
        if (!admin(p) && !k.owner.equals(p.getUniqueId())) {
            Msg.error(p, "Only " + Msg.esc(k.name) + "'s owner (or an admin) can disband it.");
            return;
        }
        if (!confirmed) {
            String rulerNote = k.rulers.isEmpty() ? "" : ", and " + k.rulers.size() + " ruler(s) lose their place in it";
            Msg.send(p, "<yellow>Really disband " + Msg.esc(k.name) + "? Its Core stops being claimed (anyone can claim that"
                    + " spot, or another, afterward)" + rulerNote + ". Employees already tagged with it keep showing the old"
                    + " name. <red>This can't be undone.</red> <click:run_command:'/" + label + " delete " + Msg.esc(k.name)
                    + " confirm'><red><u>[Disband " + Msg.esc(k.name) + "]</u></red></click>");
            return;
        }
        kingdoms().disband(k);
        Msg.send(p, "<gray>" + Msg.esc(k.name) + " disbanded.");
    }

    /** Sets the kingdom's flag design from a banner in hand, or buys a copy of the design already set. */
    private void flag(Player p, String[] args) {
        Kingdom k = kingdoms().of(p.getUniqueId());
        if (k == null) {
            Msg.error(p, "You're not in a kingdom.");
            return;
        }
        String mode = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        switch (mode) {
            case "set" -> {
                if (!admin(p) && !k.owner.equals(p.getUniqueId())) {
                    Msg.error(p, "Only " + Msg.esc(k.name) + "'s owner can set its flag.");
                    return;
                }
                org.bukkit.inventory.ItemStack hand = p.getInventory().getItemInMainHand();
                org.bukkit.DyeColor color = bannerColor(hand.getType());
                if (color == null || !(hand.getItemMeta() instanceof org.bukkit.inventory.meta.BannerMeta bm)) {
                    Msg.error(p, "Hold the banner you want as " + Msg.esc(k.name) + "'s flag (made on a loom).");
                    return;
                }
                k.flagBase = color;
                k.flagPatterns.clear();
                k.flagPatterns.addAll(bm.getPatterns());
                kingdoms().save();
                Msg.send(p, "<green>" + Msg.esc(k.name) + "'s flag is set.</green> <gray>Anyone can now get a copy with"
                        + " /kingdom flag buy (" + plugin.settings().flagEmeralds + " emeralds).");
            }
            case "buy" -> {
                if (k.flagBase == null) {
                    Msg.error(p, Msg.esc(k.name) + " has no flag yet; its owner sets one with /kingdom flag set (hold a banner).");
                    return;
                }
                int cost = plugin.settings().flagEmeralds;
                if (!takeEmeralds(p, cost)) {
                    Msg.error(p, "You need " + cost + " emeralds.");
                    return;
                }
                var left = p.getInventory().addItem(k.flagItem());
                for (var s : left.values()) p.getWorld().dropItem(p.getLocation(), s);
                Msg.send(p, "<green>Here's " + Msg.esc(k.name) + "'s flag.</green> <gray>(" + cost + " emeralds.)");
            }
            default -> Msg.error(p, "Usage: /kingdom flag set (hold a banner) | buy");
        }
    }

    private static org.bukkit.DyeColor bannerColor(org.bukkit.Material m) {
        if (!m.name().endsWith("_BANNER")) return null;
        try {
            return org.bukkit.DyeColor.valueOf(m.name().substring(0, m.name().length() - "_BANNER".length()));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** True (and the emeralds removed) only if the player had at least {@code n}. */
    private static boolean takeEmeralds(Player p, int n) {
        int have = 0;
        for (org.bukkit.inventory.ItemStack s : p.getInventory().all(org.bukkit.Material.EMERALD).values()) have += s.getAmount();
        if (have < n) return false;
        p.getInventory().removeItem(new org.bukkit.inventory.ItemStack(org.bukkit.Material.EMERALD, n));
        return true;
    }

    /** A stand-in for a real economy: gives the sender money, until merchants (and real income) exist. */
    private void money(Player p, String[] args) {
        Economy econ = plugin.economy();
        if (econ == null) {
            Msg.error(p, "There's no economy on this server (Vault has no provider).");
            return;
        }
        if (args.length < 2) {
            Msg.error(p, "Usage: /kingdom money <amount>");
            return;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[1]);
        } catch (NumberFormatException ex) {
            Msg.error(p, "That's not a number.");
            return;
        }
        if (amount <= 0) {
            Msg.error(p, "Amount must be positive.");
            return;
        }
        EconomyResponse r = econ.depositPlayer(p, amount);
        if (!r.transactionSuccess()) {
            Msg.error(p, "The deposit didn't go through: " + r.errorMessage);
            return;
        }
        Msg.send(p, "<green>Gave you " + Msg.money(amount) + ".</green> <gray>(Temporary: there's no real economy yet.)");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(SUBS);
            if (admin(s)) opts.addAll(ADMIN);
            return opts.stream().filter(o -> o.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && List.of("info", "delete").contains(args[0].toLowerCase(Locale.ROOT))) {
            return kingdoms().all().stream().map(k -> k.name).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && List.of("addruler", "removeruler").contains(args[0].toLowerCase(Locale.ROOT))) {
            return null;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("flag")) {
            return List.of("set", "buy").stream().filter(o -> o.startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("delete")) {
            return "confirm".startsWith(args[2].toLowerCase(Locale.ROOT)) ? List.of("confirm") : List.of();
        }
        return List.of();
    }
}
