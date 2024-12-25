package net.kroia.banksystem.gui.elements;

import net.kroia.banksystem.gui.elements.base.GuiElement;
import net.kroia.banksystem.gui.elements.base.ListView;

public class VerticalListView extends ListView {
    public VerticalListView(int x, int y, int width, int height) {
        super(x, y, width, height);
        scrollContainer.setBounds(0, 0, width - scrollBarThickness, height);
    }


    @Override
    protected int getContentDimension2()
    {
        return getHeight();
    }
    @Override
    protected void layoutChanged() {
        scrollContainer.setBounds(0, 0, getWidth() - scrollBarThickness, getHeight());
        allObjectSize = 0;
        for(GuiElement child : getChilds())
        {
            allObjectSize += child.getHeight();
        }
    }
    @Override
    protected void setScrollBarBounds(Button scrollBarButton)
    {
        // Render scrollbar
        int scrollbarHeight = (int) ((float)getHeight() / (float) allObjectSize * (float)getHeight());
        int scrollbarY = (int) ((float)scrollOffset / (float) allObjectSize * (float)getHeight());
        scrollBarButton.setBounds(getWidth() - scrollBarThickness, scrollbarY, scrollBarThickness, scrollbarHeight);
    }

    @Override
    public void addChild(GuiElement el)
    {
        allObjectSize += el.getHeight();
        super.addChild(el);
    }
    @Override
    public void removeChild(GuiElement el)
    {
        allObjectSize -= el.getHeight();
        super.removeChild(el);
    }
    @Override
    protected void updateElementPositions()
    {
        int y = 0;
        for(GuiElement child : getChilds())
        {
            child.setY(y - scrollOffset);
            y += child.getHeight();
        }
    }

    @Override
    protected void onScrllBarFallingEdge()
    {
        scrollBarDragStartMouse = getMouseY();
    }

    @Override
    protected void onScrollBarDragging()
    {
        int delta = (getMouseY() - scrollBarDragStartMouse)*allObjectSize/getHeight();
        scrollBarDragStartMouse = getMouseY();

        scrollOffset = Math.max(Math.min(scrollOffset + delta, allObjectSize - getContentDimension2()), 0);
        updateElementPositions();
    }
}
