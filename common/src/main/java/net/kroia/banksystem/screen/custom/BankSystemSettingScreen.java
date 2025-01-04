package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.banksystem.networking.packet.client_sender.request.RequestPotentialBankItemIDsPacket;
import net.kroia.banksystem.screen.uiElements.AskPopupScreen;
import net.kroia.banksystem.screen.uiElements.ItemInfoWidget;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiScreen;
import net.kroia.modutilities.gui.elements.Button;
import net.kroia.modutilities.gui.elements.CloseButton;
import net.kroia.modutilities.gui.elements.ItemSelectionView;
import net.kroia.modutilities.gui.elements.ItemView;
import net.kroia.modutilities.gui.screens.ItemSelectionScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public class BankSystemSettingScreen extends GuiScreen {

    private static final String PREFIX = "gui.";
    private static final String NAME = ".setting_screen.";
    public static final Component TITLE = Component.translatable(PREFIX+ BankSystemMod.MOD_ID + NAME + "title");
    public static final Component CLOSE = Component.translatable(PREFIX+ BankSystemMod.MOD_ID + NAME + "close");
    public static final Component NEW_BANKING_ITEM_BUTTON = Component.translatable(PREFIX+ BankSystemMod.MOD_ID + NAME + "new_banking_item");
    public static final Component REMOVE_BANKING_ITEM_BUTTON = Component.translatable(PREFIX+ BankSystemMod.MOD_ID + NAME + "remove_banking_item");
    public static final Component BANKING_ITEMS = Component.translatable(PREFIX+ BankSystemMod.MOD_ID + NAME + "banking_items");
    public static final Component ASK_TITLE = Component.translatable(PREFIX+ BankSystemMod.MOD_ID + NAME + "ask_remove_title");
    public static final Component ASK_MSG = Component.translatable(PREFIX+ BankSystemMod.MOD_ID + NAME + "ask_remove_message");

    private String currentBankingItemID;

    public static final int padding = 10;
    private final CloseButton closeButton;
    private final Button newBankingItemButton;
    private final Button removeBankingItemButton;
    private final ItemSelectionView currentBankingItemsView;
    private final ItemView currentBankingItemView;

    private final ItemInfoWidget itemInfoWidget;
    private static BankSystemSettingScreen instance;
    private int lastTickCount = 0;
    public BankSystemSettingScreen() {
        super(TITLE);
        instance = this;
        if(ClientBankManager.getPotentialBankItemIDs().isEmpty())
        {
            RequestPotentialBankItemIDsPacket.sendRequest();
        }

        closeButton = new CloseButton(this::onClose);

        newBankingItemButton = new Button(NEW_BANKING_ITEM_BUTTON.getString());
        newBankingItemButton.setOnFallingEdge(() -> {
            ItemSelectionScreen itemSelectionScreen = new ItemSelectionScreen(this, ClientBankManager.getPotentialBankItemIDs(), this::onNewBankingItemSelected);
            itemSelectionScreen.sortItems();
            this.minecraft.setScreen(itemSelectionScreen);
        });
        //closeButton = new CloseButton(this::onClose);
        closeButton.setIdleColor(0xFFf55a42);
        closeButton.setHoverColor(0xFFe03d24);
        closeButton.setPressedColor(0xFFde2b10);
        closeButton.setOutlineColor(0xFFde2510);
        closeButton.setSize(20,20);

        currentBankingItemsView = new ItemSelectionView(ClientBankManager.getAllowedItemIDs(), this::setCurrentBankingItemID);
        currentBankingItemsView.setPosition(padding, padding);
        currentBankingItemsView.setItemLabelText(BANKING_ITEMS.getString());
        currentBankingItemsView.sortItems();

        removeBankingItemButton = new Button(REMOVE_BANKING_ITEM_BUTTON.getString(), () -> {
            if(currentBankingItemID != null) {
                AskPopupScreen popup = new AskPopupScreen(this, () -> {
                    ClientBankManager.requestRemoveItemID(currentBankingItemID);
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

        //MinecraftForge.EVENT_BUS.register(this);
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


    private void onNewBankingItemSelected(String itemID) {
        //this.minecraft.setScreen(parentScreen);
        var items = ClientBankManager.getAllowedItemIDs();
        if(!items.contains(itemID)) {
            ClientBankManager.requestAllowNewItemID(itemID);
        }
        setCurrentBankingItemID(itemID);
    }
    private void setCurrentBankingItemID(String newItemID) {
        currentBankingItemID = null;
        itemInfoWidget.setItemID(null);
        if(newItemID == null) {
            currentBankingItemView.setItemStack(null);
            return;
        }
        ClientBankManager.requestItemInfo(newItemID);
        var items = ClientBankManager.getAllowedItemIDs();
        currentBankingItemView.setItemStack(null);

        for(String itemID : items) {
            if (itemID.compareTo(newItemID) == 0) {
                currentBankingItemView.setItemStack(ItemUtilities.createItemStackFromId(newItemID));
                currentBankingItemID = newItemID;
                itemInfoWidget.setItemID(currentBankingItemID);
                break;
            }
        }
    }

    public void updateBankData()
    {
        var items = ClientBankManager.getAllowedItemIDs();
        currentBankingItemsView.setAllowedItems(items);
        currentBankingItemsView.sortItems();
        setCurrentBankingItemID(currentBankingItemID);
    }
    public void updateItemInfoData()
    {
        itemInfoWidget.setItemID(currentBankingItemID);
    }

    @Override
    public void tick() {
        if(ClientBankManager.hasUpdatedBankData())
            updateBankData();

        if(ClientBankManager.hasUpdatedItemInfo())
            updateItemInfoData();


        lastTickCount++;
        if(lastTickCount > 20 && currentBankingItemID != null)
        {
            lastTickCount = 0;
            ClientBankManager.requestItemInfo(currentBankingItemID);
        }
    }
}
