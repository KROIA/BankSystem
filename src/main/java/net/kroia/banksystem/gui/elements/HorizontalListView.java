package net.kroia.banksystem.gui.elements;

import net.kroia.banksystem.gui.elements.base.GuiElement;
import net.kroia.banksystem.gui.elements.base.ListView;

public class HorizontalListView extends ListView {
    public HorizontalListView(int x, int y, int width, int height) {
        super(x, y, width, height);
        scrollContainer.setBounds(0, 0, width, height-scrollBarThickness);
    }


    @Override
    protected int getContentDimension2()
    {
        return getWidth();
    }

    @Override
    public void addChild(GuiElement el)
    {
        allObjectSize += el.getWidth();
        super.addChild(el);
    }
    @Override
    public void removeChild(GuiElement el)
    {
        allObjectSize -= el.getWidth();
        super.removeChild(el);
    }
    @Override
    protected void updateElementPositions()
    {
        int x = 0;
        for(GuiElement child : getChilds())
        {
            child.setX(x - scrollOffset);
            x += child.getWidth();
        }
    }

    @Override
    protected void layoutChanged() {
        scrollContainer.setBounds(0, 0, getWidth(), getHeight()-scrollBarThickness);
        allObjectSize = 0;
        for(GuiElement child : getChilds())
        {
            allObjectSize += child.getWidth();
        }
    }

    @Override
    protected void setScrollBarBounds(Button scrollBarButton)
    {
        // Render scrollbar
        int scrollbarWidth = (int) ((float)getWidth() / (float) allObjectSize * (float)getWidth());
        int scrollbarX = (int) ((float)scrollOffset / (float) allObjectSize * (float)getWidth());
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
