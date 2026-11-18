package site.remotearc.employees.gui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import site.remotearc.employees.ArcEmployees;
import site.remotearc.employees.util.Msg;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Pop-up windows (Paper's dialog API, Minecraft 1.21.6+): a title, a few
 * lines of text, and a grid of buttons. Used where a short read and a few
 * big choices beat a chest full of icons: hiring a villager, and the card an
 * employee shows when you click it. Everything that uses the dialog API goes
 * through here.
 */
public final class Dialogs {

    private Dialogs() {}

    /** One button: its label, hover text, and what clicking it does (on the main thread). */
    public record Button(String label, String hover, Consumer<Player> onClick) {}

    public static Button button(String label, String hover, Consumer<Player> onClick) {
        return new Button(label, hover, onClick);
    }

    /** A "Back" button: use as a dialog's {@code exit} too, so pressing escape goes back a screen instead of just closing. */
    public static Button back(Consumer<Player> onBack) {
        return new Button("<yellow>Back", null, onBack);
    }

    /** A plain close button for a screen with nothing to go back to (the root card, a hire prompt). */
    public static Button close(String label) {
        return new Button(label, null, pl -> {});
    }

    /**
     * Shows a dialog: {@code title} on top, each of {@code lines} as a block of
     * text (MiniMessage), then the buttons in {@code columns} columns. Paper
     * always renders {@code exit} as its own extra button (so pass the same
     * button that's already in {@code buttons} and you'll see it twice) - use
     * it only when there's a real "back" target distinct from every button
     * already on screen, and pass null when {@code buttons} already has its
     * own single way out (escape then just closes, same as clicking it).
     * {@code buttons} itself must never be empty: Paper rejects that outright.
     */
    public static void show(ArcEmployees plugin, Player p, String title, List<String> lines, List<Button> buttons, int columns, Button exit) {
        List<DialogBody> body = new ArrayList<>();
        for (String line : lines) {
            if (line == null) continue;
            body.add(DialogBody.plainMessage(Msg.parse(line.isEmpty() ? " " : line), 300));
        }
        int width = columns <= 1 ? 300 : columns == 2 ? 150 : 100;
        List<ActionButton> actions = new ArrayList<>();
        for (Button b : buttons) actions.add(action(plugin, b, width));
        ActionButton exitBtn = exit == null ? null : action(plugin, exit, 200);
        Dialog dialog = Dialog.create(f -> f.empty()
                .base(DialogBase.builder(Msg.parse(title))
                        .canCloseWithEscape(true)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body)
                        .build())
                .type(DialogType.multiAction(actions).columns(Math.max(1, columns)).exitAction(exitBtn).build()));
        p.closeInventory();
        p.showDialog(dialog);
    }

    private static ActionButton action(ArcEmployees plugin, Button b, int width) {
        DialogAction click = DialogAction.customClick((view, audience) -> {
            if (!(audience instanceof Player who)) return;
            // Callbacks may arrive off the main thread: the world is only touched on it.
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (who.isOnline()) b.onClick().accept(who);
            });
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build());
        Component hover = b.hover() == null || b.hover().isEmpty() ? null : Msg.parse(b.hover());
        return ActionButton.create(Msg.parse(b.label()), hover, width, click);
    }
}
