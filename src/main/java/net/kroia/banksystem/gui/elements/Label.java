package net.kroia.banksystem.gui.elements;

import net.kroia.banksystem.gui.elements.base.GuiElement;
import net.kroia.banksystem.gui.geometry.Point;

public class Label extends GuiElement {

    public static final int DEFAULT_HEIGHT = 15;
    private LayoutType layoutType = LayoutType.LEFT;
    private String text;
    private int padding = GuiElement.DEFAULT_PADDING;
    private int textColor = GuiElement.DEFAULT_TEXT_COLOR;
    private Point textPos = new Point(0,0);
    public Label()
    {
        super(0,0,100,DEFAULT_HEIGHT);
        text = "";
    }
    public Label(String text)
    {
        super(0,0,100,DEFAULT_HEIGHT);
        this.text = text;
    }

    public void setText(String text)
    {
        this.text = text;
        layoutChangedInternal();
    }
    public String getText(){
        return text;
    }

    public void setLayoutType(LayoutType layoutType)
    {
        this.layoutType = layoutType;
        layoutChangedInternal();
    }
    public LayoutType getLayoutType()
    {
        return layoutType;
    }
    public void setPadding(int padding)
    {
        this.padding = padding;
    }
    public int getPadding()
    {
        return padding;
    }
    public void setTextColor(int textColor)
    {
        this.textColor = textColor;
    }
    public int getTextColor()
    {
        return textColor;
    }


    @Override
    public void renderBackground() {

    }

    @Override
    public void render() {
        drawText(text, textPos, textColor);
    }

    @Override
    public void layoutChanged() {
        int textHeight = getFont().lineHeight;
        int textWidth = getFont().width(text);
        int x = padding;
        int y = padding;
        int width = getWidth() - padding*2;
        int height = getHeight()-padding*2;

        textPos.y = (height-textHeight)/2 + y;
        switch(layoutType)
        {
            case CENTER:
            {
                textPos.x = (width-textWidth)/2 + x;
                break;
            }
            case LEFT:
            {
                textPos.x = x;
                break;
            }
            case RIGHT:
            {
                textPos.x = x + width - textWidth;
            }
        }
    }
}
