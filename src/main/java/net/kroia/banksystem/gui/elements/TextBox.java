package net.kroia.banksystem.gui.elements;

import net.kroia.banksystem.gui.elements.base.GuiElement;
import org.lwjgl.glfw.GLFW;
import org.w3c.dom.Text;

public class TextBox extends GuiElement {

    String text = "";

    boolean allowNumbers = true;
    boolean allowLetters = true;
    boolean allowSpecialChars = true;

    private final Label textLabel;
    private int maxChars = 20;
    private int cursorColor = 0xFF111111;
    private int currentCursorPos = 0;
    private int cursorBlinkCounter = 0;
    private boolean cursorVisible = false;
    public TextBox(int x, int y, int width) {
        super(x, y, width, Label.DEFAULT_HEIGHT);
        textLabel = new Label("");
        textLabel.setBounds(0, 0, width, Label.DEFAULT_HEIGHT);
        textLabel.setLayoutType(LayoutType.LEFT);

        addChild(textLabel);
        textLabel.setText(text);
        addChild(textLabel);
    }

    public void setAllowNumbers(boolean allowNumbers) {
        this.allowNumbers = allowNumbers;
    }
    public boolean isAllowNumbers() {
        return allowNumbers;
    }
    public void setAllowLetters(boolean allowLetters) {
        this.allowLetters = allowLetters;
    }
    public boolean isAllowLetters() {
        return allowLetters;
    }
    public void setAllowSpecialChars(boolean allowSpecialChars) {
        this.allowSpecialChars = allowSpecialChars;
    }
    public boolean isAllowSpecialChars() {
        return allowSpecialChars;
    }
    public void setCursorColor(int cursorColor) {
        this.cursorColor = cursorColor;
    }
    public int getCursorColor() {
        return cursorColor;
    }
    public String getText() {
        return text;
    }

    @Override
    protected void renderBackground() {
        renderBackgroundColor();
    }

    @Override
    protected void render() {

        // Draw cursor
        if(isFocused())
        {
            cursorBlinkCounter++;
            if(cursorBlinkCounter > 20)
            {
                cursorBlinkCounter = 0;
                cursorVisible = !cursorVisible;
            }
            if(cursorVisible) {
                int cursorX = textLabel.getFont().width(text.substring(0, currentCursorPos));
                drawRect(cursorX+1, 3,1, getHeight()-6, cursorColor);
                drawRect(cursorX, 2,3, 1, cursorColor);
                drawRect(cursorX, getHeight()-4,3, 1, cursorColor);
            }
            return;
        }
        if(isMouseOver())
        {
            drawRect(0, 0, getWidth(), getHeight(), GuiElement.DEFAULT_HOVER_BACKGROUND_COLOR);
        }
    }

    @Override
    protected void layoutChanged() {

    }

    @Override
    public boolean mouseClickedOverElement(int button)
    {
        setFocused();
        // Get cursor position
        double mouseX = getMouseX();
        int cursorPos = 0;
        for (int i = 0; i < text.length(); i++) {
            String subString = text.substring(0, i);
            int textWidth = textLabel.getFont().width(subString);
            if(textWidth > mouseX)
            {
                break;
            }
            cursorPos++;
        }
        currentCursorPos = cursorPos;
        return true;
    }

    @Override
    public void focusGained() {
        setBackgroundColor(DEFAULT_FOCUSED_BACKGROUND_COLOR);
    }
    @Override
    public void focusLost() {
        setBackgroundColor(DEFAULT_BACKGROUND_COLOR);
        cursorVisible = false;
    }

    @Override
    protected void mouseClicked(int button) {
        if(!isMouseOver())
        {
            removeFocus();
        }
    }

    @Override
    protected boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if(!isFocused())
            return false;

        switch(keyCode)
        {
            case GLFW.GLFW_KEY_BACKSPACE:
            {
                // Check if CTRL is pressed, if so, remove last word
                if((modifiers & GLFW.GLFW_MOD_CONTROL) == GLFW.GLFW_MOD_CONTROL)
                {
                    String textToCursor = text.substring(0, currentCursorPos);
                    String textAfterCursor = text.substring(currentCursorPos);
                    int lastSpace = textToCursor.length()-1;
                    // Find first char that is not a space
                    while(lastSpace > 0 && textToCursor.charAt(lastSpace) == ' ')
                    {
                        lastSpace--;
                    }
                    // Find next space
                    lastSpace = textToCursor.lastIndexOf(' ', lastSpace);
                    if(lastSpace != -1)
                    {
                        textToCursor = textToCursor.substring(0, lastSpace+1);
                        currentCursorPos = textToCursor.length();
                        text = textToCursor + textAfterCursor;
                        updateTextLabel();
                        return true;
                    }
                    else
                    {
                        textToCursor = "";
                        text = textToCursor + textAfterCursor;
                        currentCursorPos = 0;
                        updateTextLabel();
                        return true;
                    }
                }
                else
                {
                    String textToCursor = text.substring(0, currentCursorPos);
                    String textAfterCursor = text.substring(currentCursorPos);
                    if(!textToCursor.isEmpty())
                    {
                        textToCursor = textToCursor.substring(0, textToCursor.length() - 1);
                        currentCursorPos = textToCursor.length();
                        text = textToCursor + textAfterCursor;
                        updateTextLabel();
                        return true;
                    }
                }
                return false;
            }
            case GLFW.GLFW_KEY_DELETE:
            {
                // Check if CTRL is pressed, if so, remove next word
                if((modifiers & GLFW.GLFW_MOD_CONTROL) == GLFW.GLFW_MOD_CONTROL)
                {
                    String textToCursor = text.substring(0, currentCursorPos);
                    String textAfterCursor = text.substring(currentCursorPos);
                    int nextSpace = 0;
                    // Find first char that is not a space
                    while(nextSpace < textAfterCursor.length() && textAfterCursor.charAt(nextSpace) == ' ')
                    {
                        nextSpace++;
                    }
                    // Find next space
                    nextSpace = textAfterCursor.indexOf(' ', nextSpace);

                    if(nextSpace != -1)
                    {
                        textAfterCursor = textAfterCursor.substring(nextSpace);
                        text = textToCursor + textAfterCursor;
                        updateTextLabel();
                        return true;
                    }
                    else
                    {
                        textAfterCursor = "";
                        text = textToCursor + textAfterCursor;
                        updateTextLabel();
                        return true;
                    }
                }
                else
                {
                    String textToCursor = text.substring(0, currentCursorPos);
                    String textAfterCursor = text.substring(currentCursorPos);
                    if(!textAfterCursor.isEmpty())
                    {
                        textAfterCursor = textAfterCursor.substring(1);
                        text = textToCursor + textAfterCursor;
                        updateTextLabel();
                        return true;
                    }
                }
                return false;
            }
            case GLFW.GLFW_KEY_LEFT:
            {
                if(currentCursorPos > 0)
                {
                    if((modifiers & GLFW.GLFW_MOD_CONTROL) == GLFW.GLFW_MOD_CONTROL)
                    {
                        String textToCursor = text.substring(0, currentCursorPos);
                        int lastSpace = textToCursor.length()-1;
                        // Find first char that is not a space
                        while(lastSpace > 0 && textToCursor.charAt(lastSpace) == ' ')
                        {
                            lastSpace--;
                        }
                        // Find next space
                        lastSpace = textToCursor.lastIndexOf(' ', lastSpace);


                        if(lastSpace != -1)
                        {
                            currentCursorPos = lastSpace+1;
                        }
                        else
                        {
                            currentCursorPos = 0;
                        }
                    }
                    else
                        currentCursorPos--;
                }
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT:
            {
                if(currentCursorPos < text.length())
                {
                    if((modifiers & GLFW.GLFW_MOD_CONTROL) == GLFW.GLFW_MOD_CONTROL)
                    {
                        String textAfterCursor = text.substring(currentCursorPos);
                        int nextSpace = 0;
                        // Find first char that is not a space
                        while(nextSpace < textAfterCursor.length() && textAfterCursor.charAt(nextSpace) == ' ')
                        {
                            nextSpace++;
                        }
                        // Find next space
                        nextSpace = textAfterCursor.indexOf(' ', nextSpace);

                        if(nextSpace != -1)
                        {
                            currentCursorPos += nextSpace;
                        }
                        else
                        {
                            currentCursorPos = text.length();
                        }
                    }
                    else
                        currentCursorPos++;
                }
                return true;
            }
            case GLFW.GLFW_KEY_ENTER:
            case GLFW.GLFW_KEY_KP_ENTER:
            case GLFW.GLFW_KEY_ESCAPE:
            {
                removeFocus();
                return true;
            }
        }

        return false;
    }

    @Override
    protected boolean charTyped(char codePoint, int modifiers) {
        if(!isFocused())
            return false;

        if(     allowNumbers && !Character.isDigit(codePoint) ||
                allowLetters && !Character.isLetter(codePoint) ||
                allowSpecialChars && !Character.isLetterOrDigit(codePoint))
        {
            if(text.length() >= maxChars)
                return false;
            // Insert character at cursor position
            text = text.substring(0, currentCursorPos) + codePoint + text.substring(currentCursorPos);
            currentCursorPos++;
            updateTextLabel();
            return true;
        }
        return false;
    }
    private void updateTextLabel()
    {
        textLabel.setText(text);
    }
}
