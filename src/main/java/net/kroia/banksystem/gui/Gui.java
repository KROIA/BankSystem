package net.kroia.banksystem.gui;

import net.kroia.banksystem.gui.elements.base.GuiElement;
import net.kroia.banksystem.gui.geometry.Rectangle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;

public class Gui {

    protected GuiGraphics graphics;
    protected Screen parent;
    protected int mousePosX, mousePosY;
    protected float partialTick;

    protected GuiElement focusedElement = null;

    private ArrayList<GuiElement> elements = new ArrayList<>();

    public Gui(Screen parent)
    {
        this.parent = parent;
    }
    public void init()
    {
        for(GuiElement element : elements)
        {
            element.init();
        }
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
    public static Font getFont()
    {
        return Minecraft.getInstance().font;
    }
    public Screen getScreen()
    {
        return parent;
    }
    public boolean isInitialized()
    {
        if(parent == null)
            return false;
        return parent.getMinecraft() != null;
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
    public void setFocusedElement(GuiElement element)
    {
        if(element == this.focusedElement)
            return;
        if(this.focusedElement != null)
            this.focusedElement.focusLost();
        this.focusedElement = element;
        if(this.focusedElement != null)
            this.focusedElement.focusGained();
    }
    public GuiElement getFocusedElement()
    {
        return this.focusedElement;
    }

    public void renderBackground(GuiGraphics pGuiGraphics, int pMouseX, int pMouseY, float pPartialTick)
    {
        this.graphics = pGuiGraphics;
        this.mousePosX = pMouseX;
        this.mousePosY = pMouseY;
        this.partialTick = pPartialTick;
        for(GuiElement element : elements)
        {
            element.renderBackgroundInternal();
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
            element.renderInternal();
        }
    }
    public void renderGizmos()
    {
        for(GuiElement element : elements)
        {
            element.renderGizmosInternal();
        }
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        this.mousePosX = (int)mouseX;
        this.mousePosY = (int)mouseY;
        for(GuiElement element : elements)
        {
            if(element.mouseClickedInternal(button, true))
                return true;
        }
        return false;
    }
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY)
    {
        this.mousePosX = (int)mouseX;
        this.mousePosY = (int)mouseY;
        for(GuiElement element : elements)
        {
            if(element.mouseDraggedInternal(button, deltaX, deltaY))
                return true;
        }
        return false;
    }
    public boolean mouseReleased(double mouseX, double mouseY, int button)
    {
        this.mousePosX = (int)mouseX;
        this.mousePosY = (int)mouseY;
        for(GuiElement element : elements)
        {
            if(element.mouseReleasedInternal(button,true))
                return true;
        }
        return false;
    }
    public boolean mouseScrolled(double mouseX, double mouseY, double delta)
    {
        this.mousePosX = (int)mouseX;
        this.mousePosY = (int)mouseY;
        for(GuiElement element : elements)
        {
            if(element.mouseScrolledInternal(delta,true))
                return true;
        }
        return false;
    }
    public boolean keyPressed(int keyCode, int scanCode, int modifiers)
    {
        for(GuiElement element : elements)
        {
            if(element.keyPressedInternal(keyCode, scanCode, modifiers))
                return true;
        }
        return false;
    }
    public boolean charTyped(char codePoint, int modifiers)
    {
        for(GuiElement element : elements)
        {
            if(element.charTypedInternal(codePoint, modifiers))
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

    public void drawGradient(int x, int y, int width, int height, int color1, int color2)
    {
        graphics.fillGradient(x,y,width+x,height+y,color1,color2);
    }
    public void drawOutline(int x, int y, int width, int height, int color)
    {
        graphics.renderOutline(x,y,width,height,color);
    }
    public void drawTooltip(Component tooltip, int x, int y)
    {
        graphics.renderTooltip(getFont(), tooltip, x,y);
    }
    public void drawItem(ItemStack item, int x, int y, int seed)
    {
        graphics.renderItem(item, x, y, seed);
    }
    public static double getGuiScale()
    {
        return Minecraft.getInstance().getWindow().getGuiScale();
    }
    public void enableScissor(Rectangle rect)
    {
        //int guiScale = (int)getGuiScale();
        int x1 = rect.x;
        int y1 = rect.y;
        int x2 = (rect.x+rect.width);
        int y2 = (rect.y+rect.height);

        graphics.enableScissor(x1,y1,x2,y2);
    }
    public void disableScissor()
    {
        graphics.disableScissor();
    }

    public static void playLocalSound(SoundEvent sound, float volume, float pitch)
    {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.level.playLocalSound(
                minecraft.player.getX(),            // X coordinate
                minecraft.player.getY(),            // Y coordinate
                minecraft.player.getZ(),            // Z coordinate
                sound,        // Sound to play
                SoundSource.PLAYERS,                // Sound category
                volume,                               // Volume
                pitch,                               // Pitch
                false                                // Delay
        );
    }

}
