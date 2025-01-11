package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.Frame;
import net.kroia.modutilities.gui.elements.Label;
import net.minecraft.network.chat.Component;

public class AskPopupScreen extends GuiScreen {

    public static final Component TITLE = Component.translatable("gui."+BankSystemMod.MOD_ID+".ask_popup.title");
    public static final Component YES = Component.translatable("gui."+BankSystemMod.MOD_ID+".ask_popup.yes");
    public static final Component NO = Component.translatable("gui."+BankSystemMod.MOD_ID+".ask_popup.no");

    private final GuiScreen parent;

    private final Frame frame;
    private final Label titleLabel;
    private final Label msgLabel;
    private final Button yesButton;
    private final Button noButton;

    private int frameWidth = 200;
    private int frameHeight = 100;
    public AskPopupScreen(GuiScreen parent, Runnable onYes, Runnable onNo, String title, String message) {
        super(TITLE);
        this.parent = parent;

        frame = new Frame();
        yesButton = new Button(YES.getString());
        yesButton.setOnFallingEdge(() -> {
            this.minecraft.setScreen(parent);
            onYes.run();
        });
        noButton = new Button(NO.getString());
        noButton.setOnFallingEdge(() -> {
            this.minecraft.setScreen(parent);
            onNo.run();
        });

        titleLabel = new Label(title);
        titleLabel.setAlignment(Label.Alignment.LEFT);
        msgLabel = new Label(message);
        msgLabel.setAlignment(Label.Alignment.TOP_LEFT);

        addElement(frame);
        //frame.setBackgroundColor(0xFFe8711c);
        //frame.setOutlineColor(0xFFe04c12);
        frame.addChild(yesButton);
        frame.addChild(noButton);
        frame.addChild(titleLabel);
        frame.addChild(msgLabel);
    }

    public void setSize(int width, int height)
    {
        frameWidth = width;
        frameHeight = height;
        updateLayout(getGui());
    }

    public void setColors(int background, int outline, int yesButton, int noButton)
    {
        frame.setBackgroundColor(background);
        frame.setOutlineColor(outline);
        this.yesButton.setIdleColor(yesButton);
        this.yesButton.setHoverColor((yesButton*3/4) | 0xFF000000);
        this.yesButton.setPressedColor((yesButton/2) | 0xFF000000);
        this.noButton.setIdleColor(noButton);
        this.noButton.setHoverColor((noButton*3/4) | 0xFF000000);
        this.noButton.setPressedColor((noButton/2) | 0xFF000000);
    }
    @Override
    protected void updateLayout(Gui gui) {
        int width = getWidth();
        int height = getHeight();

        frame.setBounds((width-frameWidth)/2,(height-frameHeight)/2,frameWidth,frameHeight);

        int padding = 5;
        titleLabel.setBounds(padding,padding,frame.getWidth()-2*padding,20);
        msgLabel.setBounds(padding+5,titleLabel.getBottom(),frame.getWidth()-2*padding,frame.getHeight()-titleLabel.getBottom()-padding-20);
        noButton.setBounds(frame.getWidth()-padding-50,msgLabel.getBottom(),50,20);
        yesButton.setBounds(noButton.getLeft()-20-noButton.getWidth(),noButton.getTop(),noButton.getWidth(),noButton.getHeight());

    }
}
