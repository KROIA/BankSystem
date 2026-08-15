package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.client.company.SharePresetRegistry;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiTexture;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.EmptyButton;
import net.kroia.modutilities.gui.elements.Frame;
import net.kroia.modutilities.gui.elements.Label;
import net.kroia.modutilities.gui.elements.TextureElement;
import net.kroia.modutilities.gui.elements.VerticalListView;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.layout.LayoutGrid;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * v2.1.0 UX6 — modal picker for {@link SharePresetRegistry} ids.
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
            PresetRow row = new PresetRow(id, () -> {
                net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(parent);
                onPick.accept(id);
            });
            row.setHeight(20);
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

    /**
     * One picker row: symbol preview (the full-size glyph texture from
     * {@link SharePresetRegistry#getTexture(String)}) followed by the preset id.
     */
    private static final class PresetRow extends EmptyButton {
        private final TextureElement icon;
        private final Label label;

        PresetRow(String id, Runnable onFallingEdge) {
            super(onFallingEdge);
            icon = new TextureElement(new GuiTexture(BankSystemMod.MOD_ID,
                    SharePresetRegistry.getTexture(id).getPath(), 16, 16));
            label = new Label(id);
            label.setAlignment(Alignment.LEFT);
            addChild(icon);
            addChild(label);
        }

        @Override
        protected void layoutChanged() {
            int iconSize = Math.min(16, Math.max(8, getHeight() - 4));
            icon.setBounds(3, (getHeight() - iconSize) / 2, iconSize, iconSize);
            int textX = 3 + iconSize + 5;
            label.setBounds(textX, 0, Math.max(0, getWidth() - textX - 2), getHeight());
        }
    }
}
