package net.kroia.banksystem.screen.uiElements;

import net.kroia.modutilities.gui.elements.base.GuiElement;

/**
 * Spec A.2 (v2.0.8) — primitive 1-px horizontal section divider. Replaces the
 * ad-hoc "─────" text labels. Reusable anywhere a section break is needed.
 */
public class HorizontalDivider extends GuiElement {

    private int color = 0xFF555555;

    public HorizontalDivider() {
        super();
        setHeight(1);
    }

    public void setColor(int argb) {
        this.color = argb;
    }

    @Override
    protected void render() {
        int y = Math.max(0, (getHeight() - 1) / 2);
        drawRect(0, y, getWidth(), 1, color);
    }

    @Override
    protected void layoutChanged() {}
}
