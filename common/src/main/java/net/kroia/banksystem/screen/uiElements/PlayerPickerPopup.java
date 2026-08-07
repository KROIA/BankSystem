package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Frame;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * v2.0.8 UX6 — modal picker for currently-online players. Replaces the raw
 * transfer-target TextBox in the Danger tab. Emits the selected display name
 * back to the caller and returns to the parent screen. Own name is filtered
 * (transferring to self is meaningless).
 */
public class PlayerPickerPopup extends BankSystemGuiScreen {

    public static final Component TITLE = Component.translatable(
            "gui." + BankSystemMod.MOD_ID + ".player_picker.title");
    public static final Component CANCEL = Component.translatable(
            "gui." + BankSystemMod.MOD_ID + ".player_picker.cancel");
    public static final Component EMPTY = Component.translatable(
            "gui." + BankSystemMod.MOD_ID + ".player_picker.empty");

    private final GuiScreen parent;
    private final Frame frame = new Frame();
    private final Label titleLabel;
    private final VerticalListView list;
    private final Button cancel;

    private int frameWidth = 260;
    private int frameHeight = 240;

    public PlayerPickerPopup(GuiScreen parent, Consumer<String> onPick) {
        super(TITLE);
        this.parent = parent;
        titleLabel = new Label(TITLE.getString());
        list = new VerticalListView();
        LayoutGrid l = new LayoutGrid();
        l.columns = 1; l.rows = 0; l.spacing = 2; l.padding = 2;
        l.stretchX = true; l.stretchY = false;
        l.alignment = GuiElement.Alignment.TOP;
        list.setLayout(l);

        List<String> names = new ArrayList<>();
        String self = "";
        try {
            self = Minecraft.getInstance().player.getDisplayName().getString();
            for (Player p : Minecraft.getInstance().level.players()) {
                String n = p.getDisplayName().getString();
                if (!n.equals(self)) names.add(n);
            }
        } catch (Throwable ignored) { /* empty list falls through */ }

        if (names.isEmpty()) {
            list.addChild(new Label(EMPTY.getString()));
        } else {
            for (String n : names) {
                // BUG 1 fix — deferred swaps; direct setScreen inside a click callback CMEs.
                Button row = new Button(n, () -> {
                    switchScreen(parent);
                    onPick.accept(n);
                });
                row.setHeight(20);
                list.addChild(row);
            }
        }
        cancel = new Button(CANCEL.getString(), () -> switchScreen(parent));
        addElement(frame);
        frame.addChild(titleLabel);
        frame.addChild(list);
        frame.addChild(cancel);
    }

    public void setSize(int w, int h) {
        frameWidth = w;
        frameHeight = h;
        updateLayout(getGui());
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = getWidth();
        int height = getHeight();
        frame.setBounds((width - frameWidth) / 2, (height - frameHeight) / 2, frameWidth, frameHeight);
        int p = 5;
        titleLabel.setBounds(p, p, frame.getWidth() - 2 * p, 20);
        cancel.setBounds(frame.getWidth() - p - 60, frame.getHeight() - p - 20, 60, 20);
        list.setBounds(p, titleLabel.getBottom() + 2, frame.getWidth() - 2 * p,
                frame.getHeight() - titleLabel.getBottom() - 30);
    }
}
