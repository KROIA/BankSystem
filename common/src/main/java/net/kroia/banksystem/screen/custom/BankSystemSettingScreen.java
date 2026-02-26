package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.screen.uiElements.ItemInfoWidget;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.*;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.screens.CreativeModeItemSelectionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

public class BankSystemSettingScreen extends BankSystemGuiScreen {

    private static final class TEXT {
        private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".setting_screen.";
        public static final Component TITLE = Component.translatable(PREFIX + "title");
        public static final Component NEW_BANKING_ITEM_BUTTON = Component.translatable(PREFIX + "new_banking_item");
        public static final Component REMOVE_BANKING_ITEM_BUTTON = Component.translatable(PREFIX + "remove_banking_item");
        public static final Component BANKING_ITEMS = Component.translatable(PREFIX + "banking_items");
        public static final Component ASK_TITLE = Component.translatable(PREFIX + "ask_remove_title");
        public static final Component ASK_MSG = Component.translatable(PREFIX + "ask_remove_message");

        public static final Component ASK_ITEM_FRACTION_SCALE_FACTOR_SCREEN_TITLE = Component.translatable(PREFIX + "ask_item_fraction_scale_factor_screen.title");
        public static final Component ASK_ITEM_FRACTION_SCALE_FACTOR_SCREEN_LABEL = Component.translatable(PREFIX + "ask_item_fraction_scale_factor_screen.label");
        public static final Component ASK_ITEM_FRACTION_SCALE_FACTOR_SCREEN_TOOLTIP= Component.translatable(PREFIX + "ask_item_fraction_scale_factor_screen.tooltip");
        public static final Component ASK_ITEM_FRACTION_SCALE_FACTOR_SCREEN_CONFIRM_BUTTON = Component.translatable(PREFIX + "ask_item_fraction_scale_factor_screen.confirm_button");
    }
    private static final class AskItemFractionScaleFactorScreen extends BankSystemGuiScreen
    {
        private final Label label;
        //private final TextBox textBox;
        private final Button button_1;
        private final Button button_01;
        private final Button button_001;
        //private final Button confirmButton;
        private final Screen parent;
        public AskItemFractionScaleFactorScreen(Screen parent, ItemStack stack, BiConsumer<ItemStack, Integer> onConfirm)
        {
            super(TEXT.ASK_ITEM_FRACTION_SCALE_FACTOR_SCREEN_TITLE);
            this.parent = parent;
            label = new Label(TEXT.ASK_ITEM_FRACTION_SCALE_FACTOR_SCREEN_LABEL.getString());
            label.setAlignment(GuiElement.Alignment.RIGHT);
            label.setHoverTooltipSupplier(TEXT.ASK_ITEM_FRACTION_SCALE_FACTOR_SCREEN_TOOLTIP::getString);
            label.setHoverTooltipMousePositionAlignment(GuiElement.Alignment.TOP);
            label.setHoverTooltipFontScale(0.8f);

            button_1 = new Button("1", () -> {
                onConfirm.accept(stack, 1);
            });
            button_01 = new Button("0.1", () -> {
                onConfirm.accept(stack, 10);
            });
            button_001 = new Button("0.01", () -> {
                onConfirm.accept(stack, 100);
            });
            /*textBox = new TextBox();
            textBox.setAllowNegativeNumbers(false);
            textBox.setAllowLetters(false);
            textBox.setAllowNumbers(true, true);
            textBox.setMaxDecimalChar(2);
            textBox.setHoverTooltipSupplier(TEXT.ASK_ITEM_FRACTION_SCALE_FACTOR_SCREEN_TOOLTIP::getString);
            textBox.setHoverTooltipFontScale(0.8f);
            textBox.setHoverTooltipMousePositionAlignment(GuiElement.Alignment.TOP);
            textBox.setText("1");
            textBox.setOnTextChanged((text)->
            {
                double val = textBox.getDouble();
                if((val < 0.0 || val > 1) && !textBox.getText().isEmpty()) {
                    double newVal = Math.max(0.01, Math.min(1, val));
                    textBox.setText(String.format("%.2f", newVal));
                }
            });*/

            /*confirmButton = new Button(TEXT.ASK_ITEM_FRACTION_SCALE_FACTOR_SCREEN_CONFIRM_BUTTON.getString(), () -> {
                float itemFractionScaleFactor = (float)Math.max(0.01, Math.min(1,textBox.getDouble()));
                int itemFractionScaleFactorInt = (int)(1/itemFractionScaleFactor);
                onConfirm.accept(stack, itemFractionScaleFactorInt);
            });*/

            addElement(label);
            addElement(button_1);
            addElement(button_01);
            addElement(button_001);
            //addElement(textBox);
            //addElement(confirmButton);
        }
        @Override
        protected void updateLayout(Gui gui) {
            int width = getWidth();
            int height = getHeight();
            int spacing  = 5;

            int labelWidth = label.getTextWidth(label.getText());

            label.setBounds((width-labelWidth)/2, (height/2 - 20), labelWidth, 20);

            int buttonWidth = (labelWidth+spacing)/3 - spacing;
            button_1.setBounds(label.getLeft(), label.getBottom() + spacing, buttonWidth, 20);
            button_01.setBounds(button_1.getRight() + spacing, button_1.getTop(), buttonWidth, 20);
            button_001.setBounds(button_01.getRight() + spacing, button_1.getTop(), buttonWidth, 20);

            //textBox.setBounds(label.getLeft() + (labelWidth-50)/2, label.getBottom() + spacing, 50, 20);
            //confirmButton.setBounds(textBox.getLeft(), textBox.getBottom() + spacing, textBox.getWidth(), 20);
        }
        @Override
        public void onClose() {
            super.onClose();
            minecraft.setScreen(parent);
        }
    }

    private ItemID currentBankingItemID;

    public static final int padding = 10;
    private final CloseButton closeButton;
    private final Button newBankingItemButton;
    private final Button removeBankingItemButton;
    private final ItemSelectionView currentBankingItemsView;
    private final ItemView currentBankingItemView;

    private final ItemInfoWidget itemInfoWidget;
    private static BankSystemSettingScreen instance;
    private int lastTickCount = 0;
    private static boolean screenIsOpen = false;

    private CreativeModeItemSelectionScreen creativeModeItemSelectionScreen;

    public BankSystemSettingScreen() {
        super(TEXT.TITLE);
        instance = this;
        setGuiScale(0.5f);
        //RequestPotentialBankItemIDsPacket.sendRequest();


        closeButton = new CloseButton(this::onClose);

        newBankingItemButton = new Button(TEXT.NEW_BANKING_ITEM_BUTTON.getString());
        newBankingItemButton.setOnFallingEdge(() -> {

            FeatureFlagSet enabledFeatures = minecraft.player.level().enabledFeatures();
            boolean showOperatorTab = false; // Set this to `true` if you need the operator tab

            creativeModeItemSelectionScreen = new CreativeModeItemSelectionScreen(this::onNewBankingItemSelected,()->
            {
                minecraft.setScreen(this);
                creativeModeItemSelectionScreen = null;
            });
            Minecraft.getInstance().setScreen(creativeModeItemSelectionScreen);
        });


        currentBankingItemsView = new ItemSelectionView(this::setCurrentBankingItemID);
        currentBankingItemsView.setPosition(padding, padding);
        currentBankingItemsView.setItemLabelText(TEXT.BANKING_ITEMS.getString());




        removeBankingItemButton = new Button(TEXT.REMOVE_BANKING_ITEM_BUTTON.getString(), () -> {
            if(currentBankingItemID != null) {
                AskPopupScreen popup = new AskPopupScreen(this, () -> {
                    BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestDisallowItem(currentBankingItemID, (success)->{
                        if(success)
                            setCurrentBankingItemID(null);
                        updateCurrentBankingItemsView();
                    });
                }, () -> {}, TEXT.ASK_TITLE.getString() + " " + ItemUtilities.getItemName(currentBankingItemID.getStack().getItem())  + "?", TEXT.ASK_MSG.getString());
                popup.setSize(400,100);
                popup.setColors(0xFFe8711c, 0xFFe04c12, 0xFFf22718, 0xFF70e815);
                minecraft.setScreen(popup);
            }
        });
        removeBankingItemButton.setIdleColor(0xFFe8711c);
        removeBankingItemButton.setHoverColor(0xFFe04c12);
        removeBankingItemButton.setPressedColor(0xFFe04c12);

        currentBankingItemView = new ItemView(null);

        itemInfoWidget = new ItemInfoWidget();


        addElement(closeButton);
        addElement(newBankingItemButton);
        addElement(removeBankingItemButton);
        addElement(currentBankingItemsView);
        addElement(currentBankingItemView);
        addElement(itemInfoWidget);

        updateCurrentBankingItemsView();
    }

    public static void openScreen()
    {
        BankSystemSettingScreen screen = new BankSystemSettingScreen();
        Minecraft.getInstance().setScreen(screen);
        screenIsOpen = true;
    }

    @Override
    public void onClose() {
        screenIsOpen = false;
        super.onClose();
    }

    @Override
    protected void updateLayout(Gui gui) {
        int spacing = 5;
        int width = getWidth()-2*padding;
        int height = getHeight()-2*padding;
        //closeButton.setBounds(getWidth() - 50 - padding, padding, 50, 20);
        closeButton.setPosition(getWidth() - closeButton.getWidth()-padding, padding);
        currentBankingItemsView.setBounds(padding, padding, 150, getHeight()-padding*2);
        newBankingItemButton.setBounds(currentBankingItemsView.getRight()+spacing, padding, 150, 20);
        removeBankingItemButton.setBounds(newBankingItemButton.getLeft(), newBankingItemButton.getBottom()+spacing, newBankingItemButton.getWidth(), newBankingItemButton.getHeight());
        currentBankingItemView.setBounds(newBankingItemButton.getRight()+spacing, padding, 20, 20);

        itemInfoWidget.setPosition(currentBankingItemsView.getRight()+spacing, removeBankingItemButton.getBottom()+spacing);
        itemInfoWidget.setSize(closeButton.getRight()-itemInfoWidget.getLeft(),height- removeBankingItemButton.getBottom()+spacing);
    }


    private void onNewBankingItemSelected(ItemStack itemStack) {

        AskItemFractionScaleFactorScreen askItemFractionScaleFactorScreen = new AskItemFractionScaleFactorScreen(creativeModeItemSelectionScreen, itemStack, (stack, item_fractionScaleFactor) -> {
            if(item_fractionScaleFactor <= 0)
                item_fractionScaleFactor = 1; // Default to 1 if the scale factor is not set or invalid
            BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestAllowItem(ItemID.of(stack), item_fractionScaleFactor, (result) ->
            {
                if(!screenIsOpen)
                    return; // Do not update if the screen is not open
                setCurrentBankingItemID(stack);
                updateCurrentBankingItemsView();
                if(creativeModeItemSelectionScreen != null)
                    minecraft.setScreen(creativeModeItemSelectionScreen);
                else
                    minecraft.setScreen(this);

            });
        });
        minecraft.setScreen(askItemFractionScaleFactorScreen);


        /*BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestAllowItem(new ItemID(itemStack), (result) ->
        {
            if(!screenIsOpen)
                return; // Do not update if the screen is not open
            setCurrentBankingItemID(itemStack);
            updateCurrentBankingItemsView();
        });*/
    }
    private void setCurrentBankingItemID(ItemStack itemStack) {
        currentBankingItemID = null;

        if(itemStack == null) {
            currentBankingItemView.setItemStack(null);
            itemInfoWidget.setItemInfo(null);
            return;
        }
        currentBankingItemView.setItemStack(null);
        currentBankingItemView.setItemStack(itemStack);
        currentBankingItemID = ItemID.of(itemStack);
        updateItemInfoData();

        /*for(ItemID itemID : BACKEND_INSTANCES.CLIENT_BANK_MANAGER.getAllowedItemIDs()) {
            String name = itemID.getName();
            if (name.compareTo(ItemUtilities.getItemIDStr(itemStack.getItem())) == 0) {
                currentBankingItemView.setItemStack(itemID.getStack());
                currentBankingItemID = new ItemID(itemStack);
                updateItemInfoData();
                break;
            }
        }*/
    }

    public void updateCurrentBankingItemsView()
    {
        currentBankingItemsView.clearItems();
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestBankManagerData((minimalBankManagerData) -> {
            if(!screenIsOpen)
                return; // Do not update if the screen is not open
            List<ItemStack> allowedItemStacks;
            if(minimalBankManagerData == null)
            {
                allowedItemStacks = new ArrayList<>();
            }
            else {
                allowedItemStacks = minimalBankManagerData.getAllowedItemStacks();
            }
            currentBankingItemsView.setItems(allowedItemStacks);
            currentBankingItemsView.sortItems();
        });
    }
    public void updateItemInfoData()
    {
        if(currentBankingItemID == null)
            return;
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestItemInfoData(currentBankingItemID, itemInfoWidget::setItemInfo);
        //itemInfoWidget.setItemID(currentBankingItemID);
    }

    @Override
    public void tick() {
        lastTickCount++;
        if(lastTickCount > 20 && currentBankingItemID != null)
        {
            lastTickCount = 0;
            updateItemInfoData();
        }
    }
}
