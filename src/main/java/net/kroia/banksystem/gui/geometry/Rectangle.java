package net.kroia.banksystem.gui.geometry;

import net.minecraft.client.gui.GuiGraphics;

public class Rectangle {
    public int x;
    public int y;
    public int width;
    public int height;
    public Rectangle(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public void render(GuiGraphics graphics, int color) {
        graphics.fill(x, y, x + width, y + height, color);
    }

    public boolean contains(double posX, double posY) {
        return posX >= x &&
                posX < x + width &&
                posY >= y &&
                posY < y + height;
    }
    public boolean intersects(Rectangle other) {
        return x < other.x + other.width &&
                x + width > other.x &&
                y < other.y + other.height &&
                y + height > other.y;
    }
}
