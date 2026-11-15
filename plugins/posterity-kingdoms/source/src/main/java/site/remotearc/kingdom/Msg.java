package site.remotearc.kingdom;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;

/** MiniMessage chat text, with a "Kingdom »" prefix. */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String PREFIX = "<gold><bold>Kingdom</bold></gold> <dark_gray>»</dark_gray> ";
    private static String currency = "$";

    private Msg() {}

    static void setCurrency(String c) {
        currency = c;
    }

    public static String money(double v) {
        return currency + String.format("%,.2f", v);
    }

    public static Component parse(String mini) {
        return MM.deserialize(mini);
    }

    public static void send(CommandSender to, String mini) {
        to.sendMessage(parse(PREFIX + mini));
    }

    public static void error(CommandSender to, String mini) {
        to.sendMessage(parse(PREFIX + "<red>" + mini));
    }

    public static String plain(String mini) {
        return PlainTextComponentSerializer.plainText().serialize(parse(mini));
    }

    /** Escapes text for MiniMessage. */
    public static String esc(String s) {
        return MM.escapeTags(s);
    }
}
