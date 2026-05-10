package net.kroia.banksystem.screen.uiElements;

import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.modutilities.ColorUtilities;
import net.kroia.modutilities.gui.elements.Button;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class AmountButtonGroup extends BankSystemGuiElement {

    private record ButtonBuilderData(long amount, boolean useAddButton, boolean useRemoveButton)
    {

    }
    private static class ButtonData
    {
        public final long amount;
        public final Button addButton;
        public final Button removeButton;
        public ButtonData(ButtonBuilderData data)
        {
            this.amount = Math.abs(data.amount);
            if(data.useAddButton)
                this.addButton = new Button("+"+this.amount);
            else
                this.addButton = null;
            if(data.useRemoveButton)
                this.removeButton = new Button("-"+this.amount);
            else
                this.removeButton = null;
        }
    }

    public static AmountButtonGroup create(long[] addRemoveValues,
                                           Consumer<Long> onAddValue, Runnable onZero,
                                           Supplier<Long> getCurrentValue)
    {
        List<ButtonBuilderData> buttonData = new ArrayList<>();
        for(Long addValue : addRemoveValues)
        {
            ButtonBuilderData element = new ButtonBuilderData(addValue, true, true);
            buttonData.add(element);
        }
        return new AmountButtonGroup(buttonData, addRemoveButtonFontScale,
                colorGreen, colorRed, colorRed, onAddValue, onZero, getCurrentValue);
    }


    private static final int colorGreen = ColorUtilities.getRGB(50,204,111);
    private static final int colorRed = ColorUtilities.getRGB(204,90,86);
    private static final float addRemoveButtonFontScale = 0.8f;

    @Nullable
    private final Button zeroButton;
    private final List<ButtonData> addRemoveButtons = new ArrayList<>();
    private final Supplier<Long> getCurrentAmountFunc;
    private final int padding = 1;

    private AmountButtonGroup(List<ButtonBuilderData> addRemoveButtonData,
                              float fontScale,
                              int addColor, int removeColor, int zeroColor,
                              Consumer<Long> onAddValue,
                              Runnable onZero,
                              Supplier<Long> getCurrentValue)
    {
        getCurrentAmountFunc = getCurrentValue;
        int sumWidth = 0;
        if(onZero != null) {
            this.zeroButton = new Button("=0", () ->
            {
                onZero.run();
                updateButtons();
            });
            formatButton(zeroButton, fontScale, zeroColor);
            zeroButton.setHeight(zeroButton.getHeight()*2);
            sumWidth += zeroButton.getWidth();
            addChild(zeroButton);
        }
        else
            this.zeroButton = null;

        for(ButtonBuilderData data : addRemoveButtonData)
        {
            if(!data.useAddButton && !data.useRemoveButton)
                continue;
            ButtonData buttons = new ButtonData(data);
            int maxWidth = 0;
            if(buttons.addButton != null)
            {
                formatButton(buttons.addButton, fontScale, addColor);
                buttons.addButton.setOnFallingEdge(()->
                {
                    onAddValue.accept(buttons.amount);
                    updateButtons();
                });
                maxWidth = Math.max(maxWidth, buttons.addButton.getWidth());
                addChild(buttons.addButton);
            }
            if(buttons.removeButton != null)
            {
                formatButton(buttons.removeButton, fontScale, removeColor);
                buttons.removeButton.setOnFallingEdge(()->
                {
                    onAddValue.accept(-buttons.amount);
                    updateButtons();
                });
                maxWidth = Math.max(maxWidth, buttons.removeButton.getWidth());
                addChild(buttons.removeButton);
            }
            sumWidth += maxWidth;
            addRemoveButtons.add(buttons);
        }
        setHeight((getTextHeight()+padding)*2);
        setWidth(sumWidth+padding*2);
    }
    private void formatButton(Button button, float fontscale, int color)
    {
        button.setTextFontScale(fontscale);
        button.setBackgroundColor(color);
        button.setHoverColor(ColorUtilities.setBrightness(color, 0.7f));
        button.setPressedColor(ColorUtilities.setBrightness(color, 0.6f));
        button.setEnableOutline(false);
        button.setWidth(button.getTextWidth(button.getText()) + padding);
        button.setHeight(button.getTextHeight() + padding);
    }

    @Override
    protected void render() {

    }

    @Override
    protected void layoutChanged() {

        int height = getHeight()-padding*2;
        int width = getWidth()-padding*2;



        int sumWidth = 0;
        ArrayList<Integer> textWidths = new ArrayList<>();
        if(zeroButton != null)
        {
            int textWidth = zeroButton.getTextWidth(zeroButton.getText());
            textWidths.add(textWidth);
            sumWidth += textWidth;
        }

        for(ButtonData buttons : addRemoveButtons) {
            int addButtonTextWidth = 0;
            int removeButtonTextWidth = 0;
            if(buttons.addButton != null) {
                addButtonTextWidth = buttons.addButton.getTextWidth(buttons.addButton.getText());
            }
            if(buttons.removeButton != null) {
                removeButtonTextWidth = buttons.removeButton.getTextWidth(buttons.removeButton.getText());
            }
            int maxWidth = Math.max(addButtonTextWidth, removeButtonTextWidth);
            textWidths.add(maxWidth);
            sumWidth += maxWidth;
        }
        ArrayList<Integer> newWidths = new ArrayList<>();
        for(int textWidth : textWidths) {
            newWidths.add((int)Math.round((double)(textWidth*width)/(double)sumWidth));
        }
        int currentX = 1;
        int currentWidthIdx = 0;
        if(zeroButton != null)
        {
            zeroButton.setBounds(currentX, padding, newWidths.getFirst(), height);
            currentX += newWidths.getFirst();
            currentWidthIdx++;
        }
        for(int i=0; i<addRemoveButtons.size(); i++)
        {
            ButtonData  buttons = addRemoveButtons.get(i);
            int textWidth = newWidths.get(currentWidthIdx);
            if(i== addRemoveButtons.size() -1)
            {
                textWidth = Math.min(textWidth, getWidth()-currentX-padding);
            }
            currentWidthIdx++;
            if(buttons.addButton != null)
            {
                buttons.addButton.setBounds(currentX, padding, textWidth, height/2);
            }
            if(buttons.removeButton != null)
            {
                buttons.removeButton.setBounds(currentX, getHeight()/2, textWidth, height/2);
            }
            currentX += textWidth;
        }
    }
    public void updateButtons()
    {
        long newValue = getCurrentAmountFunc.get();
        if(zeroButton != null)
            zeroButton.setEnabled(newValue > 0);

        for(ButtonData buttons : addRemoveButtons)
        {
            if(buttons.removeButton != null)
                buttons.removeButton.setEnabled(newValue >= buttons.amount);
        }
    }
}
