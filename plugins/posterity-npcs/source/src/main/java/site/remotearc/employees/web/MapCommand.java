package site.remotearc.employees.web;

import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.util.Msg;

import java.util.List;

/**
 * /map: a sign-in link for the web map (BlueMap) and the planner, landing
 * where you stand. /map planner opens the planner here instead; /map logout
 * signs you out of every browser.
 */
public final class MapCommand implements TabExecutor {

    private final ArcEmployees plugin;
    private final WebServer web;

    public MapCommand(ArcEmployees plugin, WebServer web) {
        this.plugin = plugin;
        this.web = web;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        if (!(s instanceof Player p)) {
            Msg.error(s, "Only players can sign in to the map.");
            return true;
        }
        if (!web.running()) {
            Msg.error(p, "The map planner is off (web.enabled in ArcEmployees' config.yml).");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase(java.util.Locale.ROOT) : "";
        if (sub.equals("logout") || sub.equals("signout")) {
            int n = web.signOut(p.getUniqueId());
            Msg.send(p, n > 0 ? "<green>Signed out of the map in " + n + " browser" + (n == 1 ? "" : "s") + "." : "<gray>You weren't signed in anywhere.");
            return true;
        }
        Location l = p.getLocation();
        String to;
        if (sub.equals("planner") || sub.equals("plan") || sub.equals("edit")) {
            // The planner, here: its own page under arc/.
            String link = web.loginLink(p.getUniqueId(), "planner:" + l.getWorld().getName() + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ());
            send(p, link, "Open the planner here");
            return true;
        }
        String map = l.getWorld().getName();
        try {
            if (plugin.getServer().getPluginManager().isPluginEnabled("BlueMap")) map = BlueMapHook.mapIdOrWorld(l.getWorld());
        } catch (NoClassDefFoundError ignored) {
            // no BlueMap
        }
        to = map + ":" + l.getBlockX() + ":" + l.getBlockY() + ":" + l.getBlockZ() + ":120:0:0.9:0:0:perspective";
        send(p, web.loginLink(p.getUniqueId(), to), "Open the map here");
        return true;
    }

    private void send(Player p, String link, String what) {
        Msg.send(p, "<green><click:open_url:'" + link + "'><hover:show_text:'<gray>" + Msg.plain(link) + "'>[" + what + "]</hover></click></green> "
                + "<gray>Signs this browser in for 30 days; the link works once, for 10 minutes. Don't share it. "
                + "<white>/map planner</white> opens the 3D planner where you stand; <white>/map logout</white> signs you out everywhere.");
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, String @NotNull [] args) {
        return args.length == 1 ? List.of("planner", "logout").stream().filter(o -> o.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))).toList() : List.of();
    }
}
