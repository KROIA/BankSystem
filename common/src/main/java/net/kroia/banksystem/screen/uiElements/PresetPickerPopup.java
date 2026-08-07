package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.client.company.SharePresetRegistry;
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

import java.util.function.Consumer;

/**
 * v2.0.8 UX6 — modal picker for {@link SharePresetRegistry} ids.
 * Replaces the raw preset TextBox in the Shares tab. On selection, invokes the
 * caller-supplied {@link Consumer} with the chosen id, then closes back to the
 * parent screen. Founders / MANAGE-holders drive this; no permission checks here
 * (the underlying save gate enforces authority).
 */
public class PresetPickerPopup extends BankSystemGuiScreen {

    public static final Component TITLE = Component.translatable(
            "gui." + BankSystemMod.MOD_ID + ".preset_picker.title");
    public static final Component CANCEL = Component.translatable(
            "gui." + BankSystemMod.MOD_ID + ".preset_picker.cancel");

    private final GuiScreen parent;
    private final Frame frame = new Frame();
    private final Label titleLabel;
    private final VerticalListView list;
    private final Button cancel;

    private int frameWidth = 260;
    private int frameHeight = 240;

    public PresetPickerPopup(GuiScreen parent, Consumer<String> onPick) {
        super(TITLE);
        this.parent = parent;
        titleLabel = new Label(TITLE.getString());
        list = new VerticalListView();
        LayoutGrid l = new LayoutGrid();
        l.columns = 1; l.rows = 0; l.spacing = 2; l.padding = 2;
        l.stretchX = true; l.stretchY = false;
        l.alignment = GuiElement.Alignment.TOP;
        list.setLayout(l);
        for (String id : SharePresetRegistry.orderedIds()) {
            Button row = new Button(id, () -> {
                net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(parent);
                onPick.accept(id);
            });
            list.addChild(row);
        }
        cancel = new Button(CANCEL.getString(), () -> net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(parent));
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
