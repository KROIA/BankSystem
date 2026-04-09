package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.screen.uiElements.ItemInfoWidget;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CloseButton;
import net.kroia.modutilities.gui.elements.ItemSelectionView;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.screens.CreativeModeItemSelectionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class BankSystemSettingScreen extends BankSystemGuiScreen {

    private static final class TEXT {
        private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".setting_screen.";
        public static final Component TITLE = Component.translatable(PREFIX + "title");
        public static final Component NEW_BANKING_ITEM_BUTTON = Component.translatable(PREFIX + "new_banking_item");
        public static final Component REMOVE_BANKING_ITEM_BUTTON = Component.translatable(PREFIX + "remove_banking_item");
        public static final Component BANKING_ITEMS = Component.translatable(PREFIX + "banking_items");
        public static final Component ASK_TITLE = Component.translatable(PREFIX + "ask_remove_title");
        public static final Component ASK_MSG = Component.translatable(PREFIX + "ask_remove_message");

        //public static final Component ASK_ITEM_FRACTION_SCALE_FACTOR_SCREEN_TITLE = Component.translatable(PREFIX + "ask_item_fraction_scale_factor_screen.title");
        //public static final Component ASK_ITEM_FRACTION_SCALE_FACTOR_SCREEN_LABEL = Component.translatable(PREFIX + "ask_item_fraction_scale_factor_screen.label");
        //public static final Component ASK_ITEM_FRACTION_SCALE_FACTOR_SCREEN_TOOLTIP= Component.translatable(PREFIX + "ask_item_fraction_scale_factor_screen.tooltip");
        //public static final Component ASK_ITEM_FRACTION_SCALE_FACTOR_SCREEN_CONFIRM_BUTTON = Component.translatable(PREFIX + "ask_item_fraction_scale_factor_screen.confirm_button");
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
                    getBankManager().disallowItemIDAsync(currentBankingItemID).thenAccept((success)->{
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

        setCurrentBankingItemID(itemStack);
        updateCurrentBankingItemsView();
        //minecraft.setScreen(askItemFractionScaleFactorScreen);


        /*getBankManager().requestAllowItem(new ItemID(itemStack), (result) ->
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

        /*for(ItemID itemID : getBankManager().getAllowedItemIDs()) {
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
        getBankManager().getBankManagerDataAsync().thenAccept((minimalBankManagerData) -> {
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
        getBankManager().getItemInfoDataAsync(currentBankingItemID).thenAccept(itemInfoWidget::setItemInfo);
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
