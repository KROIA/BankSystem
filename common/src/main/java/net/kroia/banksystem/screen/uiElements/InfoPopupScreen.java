package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.client.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Frame;
import net.kroia.modutilities.gui.elements.Label;
import net.minecraft.network.chat.Component;

/**
 * Single-button acknowledgment popup for informational messages (errors, warnings,
 * status reports). Mirrors {@link AskPopupScreen} but exposes only an OK button —
 * use this for popups that report a result rather than actually asking the user
 * to choose between two outcomes.
 */
public class InfoPopupScreen extends BankSystemGuiScreen {

    public static final Component TITLE = Component.translatable("gui." + BankSystemMod.MOD_ID + ".info_popup.title");
    public static final Component OK = Component.translatable("gui." + BankSystemMod.MOD_ID + ".info_popup.ok");

    private final GuiScreen parent;

    private final Frame frame;
    private final Label titleLabel;
    private final Label msgLabel;
    private final Button okButton;

    private int frameWidth = 200;
    private int frameHeight = 100;

    public InfoPopupScreen(GuiScreen parent, String title, String message) {
        this(parent, title, message, () -> {});
    }

    public InfoPopupScreen(GuiScreen parent, String title, String message, Runnable onOk) {
        super(TITLE);
        this.parent = parent;

        frame = new Frame();
        okButton = new Button(OK.getString());
        okButton.setOnFallingEdge(() -> {
            this.minecraft.setScreen(parent);
            onOk.run();
        });

        titleLabel = new Label(title);
        titleLabel.setAlignment(Label.Alignment.LEFT);
        msgLabel = new Label(message);
        msgLabel.setAlignment(Label.Alignment.TOP_LEFT);

        addElement(frame);
        frame.addChild(okButton);
        frame.addChild(titleLabel);
        frame.addChild(msgLabel);
    }

    public void setSize(int width, int height) {
        frameWidth = width;
        frameHeight = height;
        updateLayout(getGui());
    }

    public void setColors(int background, int outline, int okButton) {
        frame.setBackgroundColor(background);
        frame.setOutlineColor(outline);
        this.okButton.setBackgroundColor(okButton);
        this.okButton.setHoverColor((okButton * 3 / 4) | 0xFF000000);
        this.okButton.setPressedColor((okButton / 2) | 0xFF000000);
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = getWidth();
        int height = getHeight();

        frame.setBounds((width - frameWidth) / 2, (height - frameHeight) / 2, frameWidth, frameHeight);

        int padding = 5;
        titleLabel.setBounds(padding, padding, frame.getWidth() - 2 * padding, 20);
        msgLabel.setBounds(padding + 5, titleLabel.getBottom(), frame.getWidth() - 2 * padding, frame.getHeight() - titleLabel.getBottom() - padding - 20);
        int buttonWidth = 60;
        okButton.setBounds((frame.getWidth() - buttonWidth) / 2, msgLabel.getBottom(), buttonWidth, 20);
    }
}
