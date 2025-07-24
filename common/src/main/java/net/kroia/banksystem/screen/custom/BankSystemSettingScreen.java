package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.BankSystemModBackend;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.screen.uiElements.ItemInfoWidget;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiScreen;
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

public class BankSystemSettingScreen extends GuiScreen {
    private static BankSystemModBackend.Instances BACKEND_INSTANCES;

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

    public static void setBackend(BankSystemModBackend.Instances backend) {
        BankSystemSettingScreen.BACKEND_INSTANCES = backend;
    }

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

        ArrayList<ItemStack> allowedItems = new ArrayList<>();
        for(ItemID itemID : BACKEND_INSTANCES.CLIENT_BANK_MANAGER.getAllowedItemIDs())
        {
            allowedItems.add(itemID.getStack());
        }
        currentBankingItemsView = new ItemSelectionView(allowedItems, this::setCurrentBankingItemID);
        currentBankingItemsView.setPosition(padding, padding);
        currentBankingItemsView.setItemLabelText(BANKING_ITEMS.getString());
        currentBankingItemsView.sortItems();

        removeBankingItemButton = new Button(REMOVE_BANKING_ITEM_BUTTON.getString(), () -> {
            if(currentBankingItemID != null) {
                AskPopupScreen popup = new AskPopupScreen(this, () -> {
                    BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestRemoveItemID(currentBankingItemID);
                    setCurrentBankingItemID(null);
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
    }

    public static void openScreen()
    {
        BankSystemSettingScreen screen = new BankSystemSettingScreen();
        Minecraft.getInstance().setScreen(screen);
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
        var items = BACKEND_INSTANCES.CLIENT_BANK_MANAGER.getAllowedItemIDs();
        ItemID newItemID = new ItemID(itemStack);
        if(!items.contains(newItemID)) {
            BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestAllowNewItemID(newItemID);
        }
        setCurrentBankingItemID(itemStack);
    }
    private void setCurrentBankingItemID(ItemStack itemStack) {
        currentBankingItemID = null;
        itemInfoWidget.setItemID(null);
        if(itemStack == null) {
            currentBankingItemView.setItemStack(null);
            return;
        }
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestItemInfo(new ItemID(itemStack));
        currentBankingItemView.setItemStack(null);

        for(ItemID itemID : BACKEND_INSTANCES.CLIENT_BANK_MANAGER.getAllowedItemIDs()) {
            String name = itemID.getName();
            if (name.compareTo(ItemUtilities.getItemID(itemStack.getItem())) == 0) {
                currentBankingItemView.setItemStack(itemID.getStack());
                currentBankingItemID = new ItemID(itemStack);
                itemInfoWidget.setItemID(currentBankingItemID);
                break;
            }
        }
    }

    public void updateBankData()
    {
        var items = BACKEND_INSTANCES.CLIENT_BANK_MANAGER.getAllowedItemIDs();
        ArrayList<ItemStack> allowedItems = new ArrayList<>();
        for(ItemID itemID : items)
        {
            allowedItems.add(itemID.getStack());
        }
        currentBankingItemsView.setItems(allowedItems);
        setCurrentBankingItemID((currentBankingItemID != null?currentBankingItemID.getStack():null));
    }
    public void updateItemInfoData()
    {
        itemInfoWidget.setItemID(currentBankingItemID);
    }

    @Override
    public void tick() {
        if(BACKEND_INSTANCES.CLIENT_BANK_MANAGER.hasUpdatedBankData())
            updateBankData();

        if(BACKEND_INSTANCES.CLIENT_BANK_MANAGER.hasUpdatedItemInfo())
            updateItemInfoData();


        lastTickCount++;
        if(lastTickCount > 20 && currentBankingItemID != null)
        {
            lastTickCount = 0;
            BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestItemInfo(currentBankingItemID);
        }
    }
}
