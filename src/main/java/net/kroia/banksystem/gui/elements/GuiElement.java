package net.kroia.banksystem.gui.elements;

import net.kroia.banksystem.gui.Gui;
import net.kroia.banksystem.gui.geometry.Point;
import net.kroia.banksystem.gui.geometry.Rectangle;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;

public abstract class GuiElement {

    public enum LayoutType
    {
        CENTER,
        LEFT,
        RIGHT
    }

    private Gui root;
    private final Rectangle bounds;
    private final ArrayList<GuiElement> childs = new ArrayList<>();

    public GuiElement() {
        this(0, 0, 0, 0);
    }
    public GuiElement(int x, int y, int width, int height) {
        bounds = new Rectangle(x,y,width, height);
        layoutChanged();
    }

    public void setRoot(Gui root) {
        this.root = root;
        for (GuiElement child : childs) {
            child.setRoot(root);
        }
    }

    public abstract void renderBackground();
    public abstract void render();
    public void renderGizmos()
    {
        // Draw debug infos to help debug the layout and so on
        drawFrame(bounds, 0xFF0000, 5);
    }
    public abstract void layoutChanged();

    public boolean isOver(int posX, int posY) {
        return posX >= bounds.x && posX < bounds.x + bounds.width &&
               posY >= bounds.y && posY < bounds.y + bounds.height;
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
    public Gui getGui()
    {
        return root;
    }
    public Font getFont()
    {
        return root.getFont();
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

    public void addChild(GuiElement el)
    {
        childs.add(el);
    }
    public void removeChild(GuiElement el)
    {
        childs.remove(el);
    }
    public void removeChilds()
    {
        childs.clear();
    }
    public ArrayList<GuiElement> getChilds()
    {
        return childs;
    }



    public int getX() {
        return bounds.x;
    }
    public int getY() {
        return bounds.y;
    }
    public int getWidth() {
        return bounds.width;
    }
    public int getHeight() {
        return bounds.height;
    }
    public void setX(int x) {
        bounds.x = x;
        layoutChanged();
    }
    public void setY(int y) {
        bounds.y = y;
        layoutChanged();
    }
    public void setWidth(int width) {
        bounds.width = width;
        layoutChanged();
    }
    public void setHeight(int height) {
        bounds.height = height;
        layoutChanged();
    }
    public void setBounts(Rectangle rect)
    {
        bounds.x = rect.x;
        bounds.y = rect.y;
        bounds.width = rect.width;
        bounds.height = rect.height;
    }
    public void setBounds(int x, int y, int width, int height)
    {
        bounds.x = x;
        bounds.y = y;
        bounds.width = width;
        bounds.height = height;
    }



    public void drawText(String text, int x, int y, int color)
    {
        root.drawText(text, x, y, color);
    }
    public void drawText(String text, int x, int y)
    {
        drawText(text, x, y, 0xFFFFFF);
    }
    public void drawText(String text, Point pos, int color)
    {
        drawText(text, pos.x, pos.y, color);
    }
    public void drawText(String text, Point pos)
    {
        drawText(text, pos.x, pos.y, 0xFFFFFF);
    }
    public void drawRect(int x,int y, int width, int height, int color)
    {
        root.drawRect(x,y,width, height, color);
    }
    public void drawRect(Rectangle rect, int color)
    {
        drawRect(rect.x,rect.y, rect.width, rect.height, color);
    }

    public void drawTooltip(Component tooltip, int x, int y)
    {
        root.drawTooltip(tooltip, x,y);
    }
    public void drawTooltip(Component tooltip, Point pos)
    {
        drawTooltip(tooltip, pos.x, pos.y);
    }

    public void drawFrame(int x, int y, int width, int height, int color, int thickness)
    {
        // Horizontal
        drawRect(x, y, width, thickness, color);
        drawRect(x, y+height-thickness, width, thickness, color);

        // Vertical
        drawRect(x, y+thickness, thickness, height-2*thickness, color);
        drawRect(x+width-thickness, y+thickness, thickness, height-2*thickness, color);
    }
    public void drawFrame(Rectangle rect, int color, int thickness)
    {
        drawFrame(rect.x, rect.y, rect.width, rect.height, color, thickness);
    }
}
