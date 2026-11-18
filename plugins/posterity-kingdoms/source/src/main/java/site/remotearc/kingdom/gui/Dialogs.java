package site.remotearc.kingdom.gui;

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
import site.remotearc.kingdom.Msg;
import site.remotearc.kingdom.PosterityKingdoms;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Pop-up windows (Paper's dialog API, Minecraft 1.21.6+): a title, a few lines of text, and a grid of buttons. */
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

    /** A plain close button for a screen with nothing to go back to. */
    public static Button close(String label) {
        return new Button(label, null, pl -> {});
    }

    /**
     * Shows a dialog: {@code title} on top, each of {@code lines} as a block of
     * text (MiniMessage), then the buttons in {@code columns} columns, and
     * {@code exit} both as the bottom exit button and what escape does.
     */
    public static void show(PosterityKingdoms plugin, Player p, String title, List<String> lines, List<Button> buttons, int columns, Button exit) {
        List<DialogBody> body = new ArrayList<>();
        for (String line : lines) {
            if (line == null) continue;
            body.add(DialogBody.plainMessage(Msg.parse(line.isEmpty() ? " " : line), 300));
        }
        int width = columns <= 1 ? 300 : columns == 2 ? 150 : 100;
        List<ActionButton> actions = new ArrayList<>();
        for (Button b : buttons) actions.add(action(plugin, b, width));
        ActionButton exitBtn = action(plugin, exit, 200);
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

    private static ActionButton action(PosterityKingdoms plugin, Button b, int width) {
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
