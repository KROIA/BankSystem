package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Frame;
import net.kroia.modutilities.gui.elements.HorizontalSlider;
import net.kroia.modutilities.gui.elements.Label;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.function.IntConsumer;

/**
 * Task #51 (v2.0.8, spec §11.3) — modal RGB color picker for the share tint.
 * Replaces the raw hex TextBox in the Shares tab. Three 0–255 sliders (R, G, B),
 * a live preview swatch, and a read-only {@code #RRGGBB} hex label. Alpha is
 * fixed at {@code 0xFF} (opaque). Apply invokes {@code onPicked(argb)} and
 * returns to the parent screen; Cancel discards.
 */
public class ColorPickerPopup extends BankSystemGuiScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".color_picker.";
    public static final Component TITLE = Component.translatable(PREFIX + "title");
    public static final Component APPLY = Component.translatable(PREFIX + "apply");
    public static final Component CANCEL = Component.translatable(PREFIX + "cancel");

    private final GuiScreen parent;
    private final Frame frame = new Frame();
    private final Label titleLabel;
    private final Label redLabel;
    private final Label greenLabel;
    private final Label blueLabel;
    private final HorizontalSlider redSlider;
    private final HorizontalSlider greenSlider;
    private final HorizontalSlider blueSlider;
    private final Frame previewSwatch = new Frame();
    private final Label hexLabel;
    private final Button applyButton;
    private final Button cancelButton;

    private int frameWidth = 280;
    private int frameHeight = 200;

    public ColorPickerPopup(GuiScreen parent, int initialArgb, IntConsumer onPicked) {
        super(TITLE);
        this.parent = parent;

        titleLabel = new Label(TITLE.getString());
        redLabel = new Label("R");
        greenLabel = new Label("G");
        blueLabel = new Label("B");
        redSlider = new HorizontalSlider();
        greenSlider = new HorizontalSlider();
        blueSlider = new HorizontalSlider();
        redSlider.setSliderValue(((initialArgb >> 16) & 0xFF) / 255.0);
        greenSlider.setSliderValue(((initialArgb >> 8) & 0xFF) / 255.0);
        blueSlider.setSliderValue((initialArgb & 0xFF) / 255.0);
        redSlider.setOnValueChanged(v -> refreshPreview());
        greenSlider.setOnValueChanged(v -> refreshPreview());
        blueSlider.setOnValueChanged(v -> refreshPreview());

        hexLabel = new Label("#FFFFFF");
        hexLabel.setAlignment(Label.Alignment.LEFT);
        previewSwatch.setEnableOutline(true);

        applyButton = new Button(APPLY.getString(), () -> {
            int argb = currentArgb();
            net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(parent);
            onPicked.accept(argb);
        });
        cancelButton = new Button(CANCEL.getString(), () -> net.kroia.banksystem.util.BankSystemGuiScreen.switchScreen(parent));

        addElement(frame);
        frame.addChild(titleLabel);
        frame.addChild(previewSwatch);
        frame.addChild(redLabel);
        frame.addChild(redSlider);
        frame.addChild(greenLabel);
        frame.addChild(greenSlider);
        frame.addChild(blueLabel);
        frame.addChild(blueSlider);
        frame.addChild(hexLabel);
        frame.addChild(applyButton);
        frame.addChild(cancelButton);
        refreshPreview();
    }

    private int currentArgb() {
        int r = (int) Math.round(redSlider.getSliderValue() * 255.0);
        int g = (int) Math.round(greenSlider.getSliderValue() * 255.0);
        int b = (int) Math.round(blueSlider.getSliderValue() * 255.0);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void refreshPreview() {
        int argb = currentArgb();
        previewSwatch.setBackgroundColor(argb);
        hexLabel.setText(String.format("#%06X", argb & 0xFFFFFF));
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = getWidth();
        int height = getHeight();
        frame.setBounds((width - frameWidth) / 2, (height - frameHeight) / 2, frameWidth, frameHeight);
        int p = 5;
        int w = frame.getWidth();
        titleLabel.setBounds(p, p, w - 2 * p - 40, 20);
        previewSwatch.setBounds(w - p - 32, p, 32, 20);
        int y = p + 24;
        int labelW = 12;
        int sliderW = w - 2 * p - labelW - 4;
        redLabel.setBounds(p, y, labelW, 20);
        redSlider.setBounds(p + labelW + 4, y, sliderW, 20);
        y += 24;
        greenLabel.setBounds(p, y, labelW, 20);
        greenSlider.setBounds(p + labelW + 4, y, sliderW, 20);
        y += 24;
        blueLabel.setBounds(p, y, labelW, 20);
        blueSlider.setBounds(p + labelW + 4, y, sliderW, 20);
        y += 26;
        hexLabel.setBounds(p, y, 100, 20);
        cancelButton.setBounds(w - p - 60, frame.getHeight() - p - 20, 60, 20);
        applyButton.setBounds(cancelButton.getLeft() - 4 - 60, cancelButton.getTop(), 60, 20);
    }
}
