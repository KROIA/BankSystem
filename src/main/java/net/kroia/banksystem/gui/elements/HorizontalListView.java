package net.kroia.banksystem.gui.elements;

import net.kroia.banksystem.gui.elements.base.GuiElement;
import net.kroia.banksystem.gui.elements.base.ListView;

public class HorizontalListView extends ListView {
    public HorizontalListView(int x, int y, int width, int height) {
        super(x, y, width, height);
        scrollContainer.setBounds(0, 0, width, height-scrollBarThickness);
    }


    @Override
    protected void renderBackground() {
        if(enableBackground)
            renderBackgroundColor();
        drawRect(0, getHeight()-scrollBarThickness, getWidth(), scrollBarThickness, scrollBarBackgroundColor);
        if(enableOutline)
            renderOutline();
    }

    @Override
    protected int getContentDimension2()
    {
        return getWidth();
    }

    @Override
    public void addChild(GuiElement el)
    {
        super.addChild(el);

    }
    @Override
    public void removeChild(GuiElement el)
    {
        super.removeChild(el);
    }
    @Override
    protected void updateElementPositions()
    {
        int x = padding;
        for(GuiElement child : getChilds())
        {
            child.setX(x - scrollOffset);
            x += child.getWidth()+spacing;
        }
    }

    @Override
    protected void layoutChanged() {
        scrollContainer.setBounds(1, 1, getWidth()-2, getHeight()-scrollBarThickness-2);
        childsChanged();
    }
    protected void childsChanged()
    {
        allObjectSize = padding*2;
        for(GuiElement child : getChilds())
        {
            allObjectSize += child.getWidth()+spacing;
        }
    }

    @Override
    protected void setScrollBarBounds(Button scrollBarButton)
    {
        // Render scrollbar
        int scrollbarWidth = (int) ((float)(getWidth()) / (float) allObjectSize * (float)getWidth())-outlineThickness;
        int scrollbarX = (int) ((float)scrollOffset / (float) allObjectSize * (float)getWidth())+outlineThickness;
        scrollBarButton.setBounds(scrollbarX, getHeight() - scrollBarThickness, scrollbarWidth, scrollBarThickness);
    }

    @Override
    protected void onScrllBarFallingEdge()
    {
        scrollBarDragStartMouse = getMouseX();
    }

    @Override
    protected void onScrollBarDragging()
    {
        int delta = (getMouseX() - scrollBarDragStartMouse)*allObjectSize/getWidth();
        scrollBarDragStartMouse = getMouseX();

        scrollOffset = Math.max(Math.min(scrollOffset + delta, allObjectSize - getContentDimension2()), 0);
        updateElementPositions();
    }
}
