package site.remotearc.employees.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/** MiniMessage formatting with a shared prefix. User-supplied text always goes in as an unparsed placeholder. */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<gold><bold>Employees</bold></gold> <dark_gray>»</dark_gray> ";
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");
    private static String currency = "$";

    private Msg() {}

    public static void setCurrency(String symbol) {
        currency = symbol;
    }

    public static String money(double amount) {
        return currency + MONEY.format(amount);
    }

    public static String plain(String mini) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(MM.deserialize(mini));
    }

    public static Component parse(String mini, TagResolver... resolvers) {
        return MM.deserialize(mini, resolvers);
    }

    /** A line for item names and lore: no default italics. */
    public static Component item(String mini, TagResolver... resolvers) {
        return MM.deserialize(mini, resolvers).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public static List<Component> lore(List<String> lines, TagResolver... resolvers) {
        List<Component> out = new ArrayList<>(lines.size());
        for (String l : lines) out.add(item(l, resolvers));
        return out;
    }

    public static void send(CommandSender to, String mini, TagResolver... resolvers) {
        to.sendMessage(MM.deserialize(PREFIX + mini, resolvers));
    }

    public static void error(CommandSender to, String mini, TagResolver... resolvers) {
        to.sendMessage(MM.deserialize(PREFIX + "<red>" + mini, resolvers));
    }

    public static TagResolver p(String key, Object value) {
        return Placeholder.unparsed(key, String.valueOf(value));
    }

    public static TagResolver c(String key, Component value) {
        return Placeholder.component(key, value);
    }

    public static String duration(long millis) {
        long minutes = Math.max(0, millis / 60000);
        long h = minutes / 60, m = minutes % 60;
        if (h >= 24) return (h / 24) + "d " + (h % 24) + "h";
        if (h > 0) return h + "h " + m + "m";
        return m + "m";
    }

    public static String titleCase(String s) {
        StringBuilder b = new StringBuilder();
        for (String w : s.toLowerCase().split("_")) {
            if (w.isEmpty()) continue;
            if (!b.isEmpty()) b.append(' ');
            b.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return b.toString();
    }
}
