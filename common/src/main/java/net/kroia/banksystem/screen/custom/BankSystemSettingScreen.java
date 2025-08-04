package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.screen.uiElements.ItemInfoWidget;
import net.kroia.banksystem.util.BankSystemGuiScreen;
import net.kroia.banksystem.util.ItemID;
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

public class BankSystemSettingScreen extends BankSystemGuiScreen {

    private static final String PREFIX = "gui." + BankSystemMod.MOD_ID + ".setting_screen.";
    public static final Component TITLE = Component.translatable(PREFIX+ "title");
    //public static final Component CLOSE = Component.translatable(PREFIX+ "close");
    public static final Component NEW_BANKING_ITEM_BUTTON = Component.translatable(PREFIX+ "new_banking_item");
    public static final Component REMOVE_BANKING_ITEM_BUTTON = Component.translatable(PREFIX+ "remove_banking_item");
    public static final Component BANKING_ITEMS = Component.translatable(PREFIX+ "banking_items");
    public static final Component ASK_TITLE = Component.translatable(PREFIX+ "ask_remove_title");
    public static final Component ASK_MSG = Component.translatable(PREFIX+ "ask_remove_message");

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


    public BankSystemSettingScreen() {
        super(TITLE);
        instance = this;
        //RequestPotentialBankItemIDsPacket.sendRequest();


        closeButton = new CloseButton(this::onClose);

        newBankingItemButton = new Button(NEW_BANKING_ITEM_BUTTON.getString());
        newBankingItemButton.setOnFallingEdge(() -> {

            FeatureFlagSet enabledFeatures = minecraft.player.level().enabledFeatures();
            boolean showOperatorTab = false; // Set this to `true` if you need the operator tab


            Minecraft.getInstance().setScreen(new CreativeModeItemSelectionScreen(this::onNewBankingItemSelected,()->
            {
                minecraft.setScreen(this);
            }));

/*
            ArrayList<ItemStack> potentialItems = new ArrayList<>();
            for(ItemID itemID : BACKEND_INSTANCES.CLIENT_BANK_MANAGER.getPotentialBankItemIDs())
            {
                potentialItems.add(itemID.getStack());
            }

            ItemSelectionScreen itemSelectionScreen = new ItemSelectionScreen(this, potentialItems, this::onNewBankingItemSelected);
            itemSelectionScreen.sortItems();
            this.minecraft.setScreen(itemSelectionScreen);*/
        });

        //ArrayList<ItemStack> allowedItems = new ArrayList<>();
        //for(ItemID itemID : BACKEND_INSTANCES.CLIENT_BANK_MANAGER.getAllowedItemIDs())
        //{
        //    allowedItems.add(itemID.getStack());
        //}


        currentBankingItemsView = new ItemSelectionView(this::setCurrentBankingItemID);
        currentBankingItemsView.setPosition(padding, padding);
        currentBankingItemsView.setItemLabelText(BANKING_ITEMS.getString());




        removeBankingItemButton = new Button(REMOVE_BANKING_ITEM_BUTTON.getString(), () -> {
            if(currentBankingItemID != null) {
                AskPopupScreen popup = new AskPopupScreen(this, () -> {
                    BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestDisallowItem(currentBankingItemID, (success)->{
                        if(success)
                            setCurrentBankingItemID(null);
                    });
                }, () -> {}, ASK_TITLE.getString() + " "+currentBankingItemID + "?", ASK_MSG.getString());
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
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestAllowItem(new ItemID(itemStack), (result) ->
        {
            if(!screenIsOpen)
                return; // Do not update if the screen is not open
            setCurrentBankingItemID(itemStack);
            updateCurrentBankingItemsView();
        });
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
        currentBankingItemID = new ItemID(itemStack);
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
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestMinimalBankManagerData((minimalBankManagerData) -> {
            if(!screenIsOpen)
                return; // Do not update if the screen is not open
            ArrayList<ItemStack> allowedItemStacks;
            if(minimalBankManagerData == null)
            {
                allowedItemStacks = new ArrayList<>();
            }
            else {
                allowedItemStacks = minimalBankManagerData.createAllowedItemStacks();
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
