package net.kroia.banksystem.gui.elements.base;

import net.kroia.banksystem.gui.elements.Button;

import java.util.ArrayList;

public abstract class ListView extends GuiElement{
    protected class ScrollContainer extends GuiElement
    {
        public ScrollContainer(int x, int y, int width, int height) {
            super(x, y, width, height);
        }

        @Override
        protected void renderBackground() {

        }

        @Override
        protected void render() {

        }

        @Override
        protected void layoutChanged() {

        }
        @Override
        public void renderBackgroundInternal()
        {
            if(!isVisible())
                return;
            enableScissor();
            renderBackground();

            for (GuiElement child : getChilds()) {
                child.renderBackgroundInternal();
            }
            disableScissor();
        }
        @Override
        public void renderInternal()
        {
            if(!isVisible())
                return;

            enableScissor();
            render();

            for (GuiElement child : getChilds()) {
                child.renderInternal();
            }
            disableScissor();

        }
        @Override
        public void renderGizmosInternal()
        {
            if(!isVisible())
                return;
            enableScissor();
            renderGizmos();
            for (GuiElement child : getChilds()) {
                child.renderGizmosInternal();
            }
            disableScissor();
        }
    }

    protected int scrollOffset = 0;
    protected int allObjectSize = 0;

    protected int scrolSpeed = 5;
    protected int scrollBarThickness = 5;
    protected final Button scrollBarButton;
    protected final ScrollContainer scrollContainer;
    protected int spacing = 0;
    protected int padding = 0;

    protected int scrollBarDragStartMouse = 0;
    protected int scrollBarBackgroundColor = 0xff444444;
    public ListView(int x, int y, int width, int height) {
        super(x, y, width, height);
        scrollBarButton = new Button(0,0,0,0,"");

        scrollBarButton.setOnDown(this::onScrollBarDragging);
        scrollBarButton.setOnFallingEdge(this::onScrllBarFallingEdge);
        scrollContainer = new ScrollContainer(0,0,0,0);
        super.addChild(scrollBarButton);
        super.addChild(scrollContainer);
    }

    public void setScrolSpeed(int scrolSpeed)
    {
        this.scrolSpeed = scrolSpeed;
    }
    public int getScolSpeed()
    {
        return this.scrolSpeed;
    }
    public void setScrollBarBackgroundColor(int color)
    {
        this.scrollBarBackgroundColor = color;
    }
    public int getScrollBarBackgroundColor()
    {
        return this.scrollBarBackgroundColor;
    }

    protected abstract int getContentDimension2();
    protected abstract void setScrollBarBounds(Button scrollBarButton);

    @Override
    protected void render() {
        if(allObjectSize != 0)
            setScrollBarBounds(scrollBarButton);
    }

    @Override
    public void addChild(GuiElement el)
    {
        scrollContainer.addChild(el);
        updateElementPositions();
    }
    @Override
    public void removeChild(GuiElement el)
    {
        scrollContainer.removeChild(el);
        updateElementPositions();
    }

    @Override
    public void removeChilds()
    {
        allObjectSize = 0;
        scrollContainer.removeChilds();
        updateElementPositions();
    }
    @Override
    public ArrayList<GuiElement> getChilds()
    {
        return scrollContainer.getChilds();
    }

    protected abstract void childsChanged();

    @Override
    public boolean mouseScrolledOverElement(double delta)
    {
        if (delta > 0 && scrollOffset > 0) {
            scrollOffset-=scrolSpeed; // Scroll up
            updateElementPositions();
        } else if (delta < 0 && scrollOffset < allObjectSize - getContentDimension2()) {
            scrollOffset+=scrolSpeed; // Scroll down
            updateElementPositions();
        }
        return true;
    }

    @Override
    public void relayout(int padding, int spacing, LayoutDirection direction, boolean stretchX, boolean stretchY)
    {
        this.padding = padding;
        this.spacing = spacing;
        scrollContainer.relayout(padding, spacing, direction, stretchX, stretchY);
    }


    protected abstract void updateElementPositions();
    protected abstract void onScrllBarFallingEdge();
    protected abstract void onScrollBarDragging();



}
