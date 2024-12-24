package net.kroia.banksystem.gui.elements;

import net.kroia.banksystem.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;

public abstract class GuiElement {

    private Gui root;
    private int x, y, width, height;

    public GuiElement() {
        this(0, 0, 0, 0);
    }
    public GuiElement(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void setRoot(Gui root) {
        this.root = root;
    }

    public abstract void renderBackground();
    public abstract void render();
    public abstract void layoutChanged();

    public boolean isOver(int posX, int posY) {
        return posX >= x && posX < x + width && posY >= y && posY < y + height;
    }
    public boolean mouseClicked(int button) {
        return false;
    }
    public boolean mouseScrolled(double delta) {
        return false;
    }
    public boolean charTyped(char codePoint, int modifiers) {
        return false;
    }

    public GuiGraphics getGraphics() {
        return root.getGraphics();
    }
    public int getMouseX() {
        return root.getMousePosX();
    }
    public int getMouseY() {
        return root.getMousePosY();
    }
    public float getPartialTick() {
        return root.getPartialTick();
    }


    public int getX() {
        return x;
    }
    public int getY() {
        return y;
    }
    public int getWidth() {
        return width;
    }
    public int getHeight() {
        return height;
    }
    public void setX(int x) {
        this.x = x;
        layoutChanged();
    }
    public void setY(int y) {
        this.y = y;
        layoutChanged();
    }
    public void setWidth(int width) {
        this.width = width;
        layoutChanged();
    }
    public void setHeight(int height) {
        this.height = height;
        layoutChanged();
    }
}
