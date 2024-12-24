package net.kroia.banksystem.gui;

import net.kroia.banksystem.gui.elements.GuiElement;
import net.kroia.banksystem.gui.geometry.Point;
import net.kroia.banksystem.gui.geometry.Rectangle;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;

public class Gui {

    protected GuiGraphics graphics;
    protected Screen parent;
    protected int mousePosX, mousePosY;
    protected float partialTick;

    private ArrayList<GuiElement> elements = new ArrayList<>();

    public Gui(Screen parent)
    {
        this.parent = parent;
    }


    public GuiGraphics getGraphics()
    {
        return this.graphics;
    }
    public int getMousePosX()
    {
        return this.mousePosX;
    }
    public int getMousePosY()
    {
        return this.mousePosY;
    }
    public float getPartialTick()
    {
        return this.partialTick;
    }
    public Font getFont()
    {
        return parent.getMinecraft().font;
    }

    public void addElement(GuiElement element)
    {
        element.setRoot(this);
        elements.add(element);
    }
    public void removeElement(GuiElement element)
    {
        element.setRoot(null);
        elements.remove(element);
    }

    public void renderBackground(GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick)
    {
        this.graphics = pGuiGraphics;
        this.mousePosX = pMouseX;
        this.mousePosY = pMouseY;
        this.partialTick = pPartialTick;
        for(GuiElement element : elements)
        {
            element.renderBackground();
        }
    }
    public void render(GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick)
    {
        this.graphics = pGuiGraphics;
        this.mousePosX = pMouseX;
        this.mousePosY = pMouseY;
        this.partialTick = pPartialTick;
        for(GuiElement element : elements)
        {
            element.render();
        }
    }
    public void renderGizmos()
    {
        for(GuiElement element : elements)
        {
            element.renderGizmos();
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        this.mousePosX = (int)mouseX;
        this.mousePosY = (int)mouseY;
        for(GuiElement element : elements)
        {
            if(element.isOver(mousePosX, mousePosY))
            {
                if(element.mouseClicked(button))
                    return true;
            }
        }
        return false;
    }
    public boolean mouseScrolled(double mouseX, double mouseY, double delta)
    {
        this.mousePosX = (int)mouseX;
        this.mousePosY = (int)mouseY;
        for(GuiElement element : elements)
        {
            if(element.isOver(mousePosX, mousePosY))
            {
                if(element.mouseScrolled(delta))
                    return true;
            }
        }
        return false;
    }
    public boolean charTyped(char codePoint, int modifiers)
    {
        for(GuiElement element : elements)
        {
            if(element.charTyped(codePoint, modifiers))
                return true;
        }
        return false;
    }



    // Drawing primitives
    public void drawText(String text, int x, int y, int color)
    {
        graphics.drawString(getFont(), text, x, y, color);
    }
    public void drawRect(int x,int y, int width, int height, int color)
    {
        graphics.fill(x,y,width+x,height+y,color);
    }
    public void drawTooltip(Component tooltip, int x, int y)
    {
        graphics.renderTooltip(getFont(), tooltip, x,y);
    }

}
