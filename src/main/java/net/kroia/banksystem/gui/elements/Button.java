package net.kroia.banksystem.gui.elements;


import net.kroia.banksystem.gui.elements.base.GuiElement;
import net.minecraft.sounds.SoundEvents;

public class Button extends GuiElement {

    Label label;
    boolean isPressed = false;
    int colorIdle = DEFAULT_BACKGROUND_COLOR;
    int colorHover = DEFAULT_HOVER_BACKGROUND_COLOR;
    int colorPressed = DEFAULT_FOCUSED_BACKGROUND_COLOR;

    Runnable onFallingEdge = null;
    Runnable onRisingEdge = null;
    Runnable onDown = null;
    public Button(String text) {
        super();
        label = new Label(text);
        label.setLayoutType(LayoutType.CENTER);
        addChild(label);
    }
    public Button(int x, int y, int width, int height, String text) {
        super(x, y, width, height);
        label = new Label(text);
        label.setBounds(0,0,width,height);
        label.setLayoutType(LayoutType.CENTER);
        addChild(label);
    }
    public void setLabel(String text)
    {
        label.setText(text);
    }

    public void setOnFallingEdge(Runnable onFallingEdge)
    {
        this.onFallingEdge = onFallingEdge;
    }
    public void setOnRisingEdge(Runnable onRisingEdge)
    {
        this.onRisingEdge = onRisingEdge;
    }
    public void setOnDown(Runnable onDown)
    {
        this.onDown = onDown;
    }

    public void setIdleColor(int color)
    {
        this.colorIdle = color;
    }
    public void setHoverColor(int color)
    {
        this.colorHover = color;
    }
    public void setPressedColor(int color)
    {
        this.colorPressed = color;
    }
    public int getIdleColor()
    {
        return this.colorIdle;
    }
    public int getHoverColor()
    {
        return this.colorHover;
    }
    public int getPressedColor()
    {
        return this.colorPressed;
    }

    @Override
    protected void renderBackground() {
        int color = colorIdle;
        if(isPressed)
            color = colorPressed;
        else if(isMouseOver())
            color = colorHover;
        drawRect(0,0,getWidth(), getHeight(),color);
    }

    @Override
    protected void render() {

    }

    @Override
    protected void layoutChanged() {
        label.setBounds(0,0,getWidth(),getHeight());
    }

    @Override
    protected boolean mouseClickedOverElement(int buttton)
    {
        if(!isPressed) {
            playLocalSound(SoundEvents.UI_BUTTON_CLICK.get(),0.5F);
            if(onFallingEdge != null) {
                onFallingEdge.run();
            }
        }
        isPressed = true;
        return true;
    }

    @Override
    protected boolean mouseDragged(int button, double deltaX, double deltaY)
    {
        if(isPressed)
        {
            if(onDown != null) {
                onDown.run();
            }
            return true;
        }
        return false;
    }

    @Override
    protected void mouseReleased(int button)
    {
        if(isPressed){
            //playLocalSound(SoundEvents.UI_BUTTON_CLICK.get(),0.5F, 0.9F);
            if(onRisingEdge != null)
                onRisingEdge.run();
        }
        isPressed = false;
    }
}
