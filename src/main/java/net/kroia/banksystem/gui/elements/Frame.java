package net.kroia.banksystem.gui.elements;

import net.kroia.banksystem.gui.elements.base.GuiElement;

public class Frame extends GuiElement {

    protected boolean enableBackground = true;
    protected boolean enableBorder = true;
    protected int borderSize = 1;
    protected int borderColor = 0xFF000000;

    public Frame(int x, int y, int width, int height) {
        super(x, y, width, height);
    }

    @Override
    protected void renderBackground() {
        if(enableBackground)
            renderBackgroundColor();
        if(enableBorder)
            drawFrame(getBounds(), borderSize, borderColor);
    }

    @Override
    protected void render() {

    }

    @Override
    protected void layoutChanged() {

    }
}
