package net.kroia.banksystem.gui.elements;

import net.kroia.banksystem.gui.geometry.Point;
import net.minecraft.client.gui.GuiGraphics;

public class Label extends GuiElement{

    private LayoutType layoutType = LayoutType.LEFT;
    private String text;
    private Point textPos = new Point(0,0);
    public Label()
    {
        super();
        text = "";
    }
    public Label(String text)
    {
        super();
        this.text = text;
    }

    public void setText(String text)
    {
        this.text = text;
    }
    public String getText(){
        return text;
    }


    @Override
    public void renderBackground() {

    }

    @Override
    public void render() {
        drawText(text, textPos);
    }

    @Override
    public void layoutChanged() {
        int textHeight = getFont().lineHeight;
        int textWidth = getFont().width(text);
        int x = getX();
        int y = getY();
        int width = getWidth();
        int height = getHeight();

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
