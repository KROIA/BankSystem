package net.kroia.banksystem.gui;

import net.minecraft.resources.ResourceLocation;

public class GuiTexture {
    private final ResourceLocation resourceLocation;
    private final int width;
    private final int height;
    private int uvOffsetX = 0;
    private int uvOffsetY = 0;

    public GuiTexture(String modID, String path, int imageWidth, int imageHeight) {
        this.resourceLocation = Gui.createResourceLocation(modID, path);
        this.width = imageWidth;
        this.height = imageHeight;
    }

    public void setUVOffset(int x, int y) {
        this.uvOffsetX = x;
        this.uvOffsetY = y;
    }
    public int getUVOffsetX() {
        return uvOffsetX;
    }
    public int getUVOffsetY() {
        return uvOffsetY;
    }

    public ResourceLocation getResourceLocation() {
        return resourceLocation;
    }
    public int getWidth() {
        return width;
    }
    public int getHeight() {
        return height;
    }
}
