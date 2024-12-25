package net.kroia.banksystem.gui.elements.base;

import net.kroia.banksystem.gui.Gui;
import net.kroia.banksystem.gui.geometry.Point;
import net.kroia.banksystem.gui.geometry.Rectangle;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;

public abstract class GuiElement {

    public enum LayoutType
    {
        CENTER,
        LEFT,
        RIGHT
    }
    public enum LayoutDirection
    {
        HORIZONTAL,
        VERTICAL
    }
    public static int DEFAULT_PADDING = 1;
    public static int DEFAULT_TEXT_COLOR = 0xFFFFFF;
    public static int DEFAULT_BACKGROUND_COLOR = 0xAA888888;
    public static int DEFAULT_FOCUSED_BACKGROUND_COLOR = 0xAA666666;
    public static int DEFAULT_HOVER_BACKGROUND_COLOR = 0xFFAAAAAA;

    private Gui root;
    private GuiElement parent = null;
    private GuiElement rootParent = null;
    private final Rectangle bounds;
    private final Point globalPositon = new Point(0,0);
    private final ArrayList<GuiElement> childs = new ArrayList<>();

    private boolean isEnabled = true;
    private int gizmoColor = 0x55FF0000;
    private int backgroundColor = DEFAULT_BACKGROUND_COLOR;

    public GuiElement() {
        this(0, 0, 0, 0);
    }
    public GuiElement(int x, int y, int width, int height) {
        bounds = new Rectangle(x,y,width, height);
        rootParent = this;
    }

    public void setRoot(Gui root) {
        this.root = root;
        for (GuiElement child : childs) {
            child.setRoot(root);
        }
    }
    public void init()
    {
        for (GuiElement child : childs) {
            child.init();
        }
        layoutChangedInternal();
    }

    public Gui getRoot() {
        return root;
    }
    public GuiElement getParent() {
        return parent;
    }
    public GuiElement getRootParent() {
        return rootParent;
    }
    public void setEnabled(boolean visible)
    {
        isEnabled = visible;
        if(!isEnabled)
        {
            if(root != null && root.getFocusedElement() == this)
                root.setFocusedElement(null);
        }
    }
    public boolean isEnabled()
    {
        return isEnabled;
    }
    public boolean isVisible()
    {
        if(!isEnabled)
            return false;
        if(parent != null)
        {
            Rectangle rect1 = new Rectangle(parent.globalPositon.x,parent.globalPositon.y, parent.getWidth(), parent.getHeight());
            Rectangle rect2 = new Rectangle(globalPositon.x,globalPositon.y, bounds.width, bounds.height);
            if(rect1.intersects(rect2))
                return true;
            return false;
        }
        return true;
    }
    public void setFocused()
    {
        root.setFocusedElement(this);
    }
    public void removeFocus()
    {
        if(root.getFocusedElement() == this)
            root.setFocusedElement(null);
    }
    public boolean isFocused()
    {
        return root.getFocusedElement() == this;
    }
    public void setGizmoColor(int color)
    {
        gizmoColor = color;
    }
    public int getGizmoColor()
    {
        return gizmoColor;
    }
    public void setBackgroundColor(int color)
    {
        backgroundColor = color;
    }
    public int getBackgroundColor()
    {
        return backgroundColor;
    }

    protected abstract void renderBackground();
    protected abstract void render();
    protected void renderGizmos()
    {
        // Draw debug infos to help debug the layout and so on
        drawOutline(0,0,bounds.width, bounds.height, gizmoColor);
    }
    protected void renderBackgroundColor()
    {
        drawRect(0,0,getWidth(), getHeight(),backgroundColor);
    }

    public void renderBackgroundInternal()
    {
        if(!isVisible())
            return;
        //enableScissor();
        renderBackground();
        //disableScissor();
        for (GuiElement child : childs) {
            child.renderBackgroundInternal();
        }
    }
    public void renderInternal()
    {
        if(!isVisible())
            return;

        //enableScissor();
        render();
        //disableScissor();
        for (GuiElement child : childs) {
            child.renderInternal();
        }

    }
    public void renderGizmosInternal()
    {
        if(!isVisible())
            return;
        //enableScissor();
        renderGizmos();
        //disableScissor();
        for (GuiElement child : childs) {
            child.renderGizmosInternal();
        }
    }

    protected void enableGlobalScissor(Rectangle area)
    {
        // Calculate the scissor box
        /*int scale = (int) Minecraft.getInstance().getWindow().getGuiScale(); // Get GUI scale
        int scissorX = area.x * scale;
        int windowHeight = Minecraft.getInstance().getWindow().getHeight();
        int scissorY = windowHeight - ((area.y+area.height)*scale);
        int scissorWidth = area.width * scale;
        int scissorHeight = area.height * scale;

        // Enable scissor test
        //RenderSystem.assertOnRenderThread(); // Ensure we are on the render thread
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(scissorX, scissorY, scissorWidth, scissorHeight);*/
        root.enableScissor(area);
    }
    protected void enableScissor(Rectangle area)
    {
        enableGlobalScissor(new Rectangle(globalPositon.x+area.x, globalPositon.y+area.y, area.width, area.height));
    }
    protected void enableScissor()
    {
        enableGlobalScissor(new Rectangle(globalPositon.x, globalPositon.y, bounds.width, bounds.height));
    }

    protected void disableScissor()
    {
        // Disable scissor test
        //GL11.glDisable(GL11.GL_SCISSOR_TEST);
        root.disableScissor();
    }

    protected abstract void layoutChanged();
    public void layoutChangedInternal()
    {
        if(root == null)
            return;
        if(!root.isInitialized())
            return;
        layoutChanged();
        for (GuiElement child : childs) {
            child.layoutChangedInternal();
        }
        if(rootParent == this)
        {
            updateTransform(0,0);
        }
        else {
            updateTransform(parent.globalPositon.x, parent.globalPositon.y);
        }
    }
    private void updateTransform(int parentX, int parentY)
    {
        globalPositon.x = parentX + bounds.x;
        globalPositon.y = parentY + bounds.y;
        for(GuiElement child : childs)
        {
            child.updateTransform(globalPositon.x, globalPositon.y);
        }
    }

    public void focusGained()
    {

    }
    public void focusLost()
    {

    }
    public boolean isOver(int globalPosX, int globalPosY) {
        return (globalPosX - globalPositon.x) >= 0 && (globalPosX - globalPositon.x) < bounds.width &&
               (globalPosY - globalPositon.y) >= 0 && (globalPosY - globalPositon.y) < bounds.height;
    }
    public boolean isMouseOver() {
        return isOver(root.getMousePosX(), root.getMousePosY());
    }
    protected void mouseClicked(int button) {
    }
    protected boolean mouseClickedOverElement(int button) {
        return false;
    }
    protected boolean mouseDragged(int button, double deltaX, double deltaY) {
        return false;
    }
    protected void mouseReleased(int button) {
    }
    protected boolean mouseReleasedOverElement(int button) {
        return false;
    }
    protected void mouseScrolled(double delta) {
    }
    protected boolean mouseScrolledOverElement(double delta) {
        return false;
    }
    protected boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }
    protected boolean charTyped(char codePoint, int modifiers) {
        return false;
    }
    public boolean mouseClickedInternal(int button)
    {
        for(GuiElement child : childs)
        {
            if(child.mouseClickedInternal(button))
                return true;
        }
        mouseClicked(button);
        if(isOver(root.getMousePosX(), root.getMousePosY()))
            return mouseClickedOverElement(button);
        return false;
    }
    public boolean mouseReleasedInternal(int button)
    {
        for(GuiElement child : childs)
        {
            if(child.mouseReleasedInternal(button))
                return true;
        }
        mouseReleased(button);
        if(isOver(root.getMousePosX(), root.getMousePosY()))
            return mouseReleasedOverElement(button);
        return false;
    }
    public boolean mouseDraggedInternal(int button, double deltaX, double deltaY)
    {
        for(GuiElement child : childs)
        {
            if(child.mouseDraggedInternal(button, deltaX, deltaY))
                return true;
        }
        return mouseDragged(button, deltaX, deltaY);
    }
    public boolean mouseScrolledInternal(double delta)
    {
        for(GuiElement child : childs)
        {
            if(child.mouseScrolledInternal(delta))
                return true;
        }
        mouseScrolled(delta);
        if(isOver(root.getMousePosX(), root.getMousePosY()))
            return mouseScrolledOverElement(delta);
        return false;
    }
    public boolean keyPressedInternal(int keyCode, int scanCode, int modifiers)
    {
        for(GuiElement child : childs)
        {
            if(child.keyPressedInternal(keyCode, scanCode, modifiers))
                return true;
        }
        return keyPressed(keyCode, scanCode, modifiers);
    }
    public boolean charTypedInternal(char codePoint, int modifiers)
    {
        for(GuiElement child : childs)
        {
            if(child.charTypedInternal(codePoint, modifiers))
                return true;
        }
        return charTyped(codePoint, modifiers);
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
        return root.getMousePosX()-globalPositon.x;
    }
    public int getMouseY() {
        return root.getMousePosY()-globalPositon.y;
    }
    public int getMouseXGlobal() {
        return root.getMousePosX();
    }
    public int getMouseYGlobal() {
        return root.getMousePosY();
    }
    public float getPartialTick() {
        return root.getPartialTick();
    }

    protected void setParent(GuiElement parent, GuiElement rootParent)
    {
        this.parent = parent;
        this.rootParent = rootParent;
        for(GuiElement child : childs)
        {
            child.setParent(this, rootParent);
        }
    }
    public void addChild(GuiElement el)
    {
        if(el.getParent() != null)
        {
            el.getParent().removeChild(el);
        }
        el.setRoot(root);
        el.setParent(this, rootParent);
        childs.add(el);
    }
    public void removeChild(GuiElement el)
    {
        el.setRoot(null);
        el.setParent(null, el);
        childs.remove(el);
    }
    public void removeChilds()
    {
        for(GuiElement child : childs)
        {
            child.setRoot(null);
            child.setParent(null, child);
        }
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
        layoutChangedInternal();
    }
    public void setY(int y) {
        bounds.y = y;
        layoutChangedInternal();
    }
    public void setWidth(int width) {
        bounds.width = width;
        layoutChangedInternal();
    }
    public void setHeight(int height) {
        bounds.height = height;
        layoutChangedInternal();
    }
    public void setBounts(Rectangle rect)
    {
        bounds.x = rect.x;
        bounds.y = rect.y;
        bounds.width = rect.width;
        bounds.height = rect.height;
        layoutChangedInternal();
    }
    public void setBounds(int x, int y, int width, int height)
    {
        bounds.x = x;
        bounds.y = y;
        bounds.width = width;
        bounds.height = height;
        layoutChangedInternal();
    }
    public Rectangle getBounds()
    {
        return bounds;
    }
    public Point getGlobalPositon()
    {
        return globalPositon;
    }
    public Rectangle getChildFrame()
    {
        Rectangle frame = new Rectangle(0,0,0,0);
        for (GuiElement child : childs) {
            Rectangle childFrame = child.getChildFrame();
            if(childFrame.x < frame.x)
                frame.x = childFrame.x;
            if(childFrame.y < frame.y)
                frame.y = childFrame.y;
            if(childFrame.x+childFrame.width > frame.x+frame.width)
                frame.width = childFrame.x+childFrame.width-frame.x;
            if(childFrame.y+childFrame.height > frame.y+frame.height)
                frame.height = childFrame.y+childFrame.height-frame.y;
        }
        return frame;
    }

    public void relayout(int padding, int spacing, LayoutDirection direction)
    {
        relayout(padding, spacing, direction, false);
    }
    public void relayout(int padding, int spacing, LayoutDirection direction, boolean stretch)
    {
        switch (direction)
        {
            case HORIZONTAL:
                relayoutHorizontal(padding, spacing, stretch);
                break;
            case VERTICAL:
                relayoutVertical(padding, spacing,stretch);
                break;
        }
    }
    private void relayoutHorizontal(int padding, int spacing, boolean stretch)
    {
        if(childs.isEmpty())
            return;
        int x = padding;
        int width = (getWidth()-(padding*2))/childs.size();
        for (GuiElement child : childs) {
            child.setX(x);
            child.setY(padding);
            if(stretch) {
                child.setWidth(width);
                child.setHeight(getHeight()-2*padding);
            }
            x += child.getWidth() + spacing;
        }
    }
    private void relayoutVertical(int padding, int spacing, boolean stretch)
    {
        if(childs.isEmpty())
            return;
        int y = padding;
        int height = (getHeight()-(padding*2))/childs.size();
        for (GuiElement child : childs) {
            child.setX(padding);
            child.setY(y);
            if(stretch) {
                child.setWidth(getWidth()-2*padding);
                child.setHeight(height);
            }
            y += child.getHeight() + spacing;
        }
    }



    public void drawText(String text, int x, int y, int color)
    {
        root.drawText(text, x+globalPositon.x, y+globalPositon.y, color);
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
        root.drawRect(x+globalPositon.x,y+globalPositon.y,width, height, color);
    }
    public void drawRect(Rectangle rect, int color)
    {
        drawRect(rect.x,rect.y, rect.width, rect.height, color);
    }

    public void drawGradient(int x, int y, int width, int height, int color1, int color2)
    {
        root.drawGradient(x+globalPositon.x,y+globalPositon.y,width, height, color1, color2);
    }
    public void drawGradient(Rectangle rect, int color1, int color2)
    {
        drawGradient(rect.x,rect.y, rect.width, rect.height, color1, color2);
    }

    public void drawOutline(int x, int y, int width, int height, int color)
    {
        root.drawOutline(x+globalPositon.x,y+globalPositon.y,width, height, color);
    }
    public void drawOutline(Rectangle rect, int color)
    {
        drawOutline(rect.x,rect.y, rect.width, rect.height, color);
    }
    public void drawItem(ItemStack item, int x, int y)
    {
        root.drawItem(item, x+globalPositon.x, y+globalPositon.y, 0);
    }
    public void drawItem(ItemStack item, Point pos)
    {
        root.drawItem(item, pos.x+globalPositon.x, pos.y+globalPositon.y, 0);
    }
    public void drawItem(ItemStack item, int x, int y, int seed)
    {
        root.drawItem(item, x+globalPositon.x, y+globalPositon.y, seed);
    }
    public void drawItem(ItemStack item, Point pos, int seed)
    {
        root.drawItem(item, pos.x+globalPositon.x, pos.y+globalPositon.y, seed);
    }

    public void drawTooltip(Component tooltip, int x, int y)
    {
        root.drawTooltip(tooltip, x+globalPositon.x,y+globalPositon.y);
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
    public double getGuiScale()
    {
        return root.getGuiScale();
    }

    public void playLocalSound(SoundEvent sound, float volume, float pitch)
    {
        root.playLocalSound(sound, volume, pitch);
    }
    public void playLocalSound(SoundEvent sound, float volume)
    {
        playLocalSound(sound, volume, 1.0F);
    }
    public void playLocalSound(SoundEvent sound)
    {
        playLocalSound(sound, 1.0F, 1.0F);
    }
}
