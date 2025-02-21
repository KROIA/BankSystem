package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.ClientBankManager;
import net.kroia.banksystem.menu.custom.BankDownloadContainerMenu;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankDownloadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDownloadDataPacket;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.ItemUtilities;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiContainerScreen;
import net.kroia.modutilities.gui.GuiTexture;
import net.kroia.modutilities.gui.elements.*;
import net.kroia.modutilities.gui.elements.base.GuiElement;
import net.kroia.modutilities.gui.screens.ItemSelectionScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;

public class BankDownloadScreen extends GuiContainerScreen<BankDownloadContainerMenu> {
    private static final Component INVENTORY_NAME_TEXT = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_download_screen.inventory_name");
    private static final Component CONNECT_BUTTON = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_download_screen.connect_button");
    private static final Component DISCONNECT_BUTTON = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_download_screen.disconnect_button");
    private static final Component TARGET_AMOUNT = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_download_screen.target_amount");
    private static final Component SELECT_ITEM = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_download_screen.select_item");
    private static final Component APPLY_BUTTON = Component.translatable("gui." + BankSystemMod.MOD_ID + ".bank_download_screen.apply_button");


    private  class SettingsMenu extends GuiElement{

        public final Button connectDisconnectButton;
        public final Button selectItemButton;
        public final ItemView itemView;
        public final TextBox targetAmountTextBox;
        public final Label targetAmountLabel;
        public final Button applyButton;
        public static final int elementHeight = 16;


        public SettingsMenu(BankDownloadScreen parent)
        {
            connectDisconnectButton = new Button((isOwned?DISCONNECT_BUTTON.getString():CONNECT_BUTTON.getString()), parent::onConnectDisconnectButtonClicked);
            selectItemButton = new Button(SELECT_ITEM.getString(), parent::onSelectItemButtonClicked);
            itemView = new ItemView();
            targetAmountTextBox = new TextBox();
            targetAmountTextBox.setAllowLetters(false);
            targetAmountTextBox.setAllowNumbers(true, false);
            targetAmountLabel = new Label(TARGET_AMOUNT.getString());
            applyButton = new Button(APPLY_BUTTON.getString(), parent::applySettigns);

            itemView.setSize(elementHeight, elementHeight);

            this.addChild(connectDisconnectButton);
            this.addChild(selectItemButton);
            this.addChild(itemView);
            this.addChild(targetAmountTextBox);
            this.addChild(targetAmountLabel);
            this.addChild(applyButton);
            layoutChanged();
        }

        @Override
        protected void render() {

        }

        @Override
        protected void layoutChanged() {
            int padding = 2;
            int width = this.getWidth()-padding*2;

            connectDisconnectButton.setBounds(padding, padding, width, elementHeight);
            selectItemButton.setBounds(padding, connectDisconnectButton.getBottom()+padding, width-itemView.getWidth()-padding, elementHeight);
            itemView.setPosition(selectItemButton.getRight()+padding, selectItemButton.getTop());
            targetAmountLabel.setBounds(padding, selectItemButton.getBottom()+padding, width/3, elementHeight);
            targetAmountTextBox.setBounds(targetAmountLabel.getRight(), targetAmountLabel.getTop(), width/3, targetAmountLabel.getHeight());
            applyButton.setBounds(targetAmountTextBox.getRight()+padding, targetAmountTextBox.getTop(), width/3, targetAmountTextBox.getHeight());
            this.setHeight(targetAmountTextBox.getBottom()+padding);
        }
    }


    private final BlockPos pos;

    private final ContainerView<BankDownloadContainerMenu> inventoryView;
    private final SettingsMenu settingsMenu;

    public static boolean isOwned = false;
    public static ItemID itemID;
    public static int targetAmount;
    public static int maxTargetAmount;


    private static BankDownloadScreen instance;

    public BankDownloadScreen(BankDownloadContainerMenu pMenu, Inventory pPlayerInventory, Component pTitle) {
        super(pMenu, pPlayerInventory, pTitle);
        instance = this;
        this.pos = pMenu.getBlockPos();



        inventoryView = new ContainerView<>(pMenu, pPlayerInventory, INVENTORY_NAME_TEXT, new GuiTexture(BankSystemMod.MOD_ID, "textures/gui/inventory_hpc.png", 176, 166));
        inventoryView.setOnCloseEvent(this::onCloseCleanup);
        settingsMenu = new SettingsMenu(this);

        if (isOwned) {
            settingsMenu.connectDisconnectButton.setLabel(DISCONNECT_BUTTON.getString());
        } else {
            settingsMenu.connectDisconnectButton.setLabel(CONNECT_BUTTON.getString());
        }
        settingsMenu.targetAmountTextBox.setText(String.valueOf(targetAmount));

        ItemStack itemStack = itemID.getStack();
        settingsMenu.itemView.setItemStack(itemStack);


        addElement(inventoryView);
        addElement(settingsMenu);
    }

    @Override
    protected void updateLayout(Gui gui) {
        int width = this.width;
        int height = this.height;
        int spacing = 5;

        int inventoryWidth = inventoryView.getWidth();
        int inventoryHeight = inventoryView.getHeight();

        settingsMenu.setBounds((width-inventoryWidth)/2, (height-inventoryHeight-spacing-settingsMenu.getHeight())/2, inventoryWidth, 0);
        inventoryView.setPosition(settingsMenu.getLeft(), settingsMenu.getBottom()+spacing);
    }

    public static void handlePacket(SyncBankDownloadDataPacket packet) {
        isOwned = packet.isOwned();
        itemID = packet.getItemID();
        targetAmount = packet.getTargetAmount();
        maxTargetAmount = packet.getMaxTargetAmount();

        if(instance != null) {
            if (isOwned) {
                instance.settingsMenu.connectDisconnectButton.setLabel(DISCONNECT_BUTTON.getString());
            } else {
                instance.settingsMenu.connectDisconnectButton.setLabel(CONNECT_BUTTON.getString());
            }
            instance.settingsMenu.targetAmountTextBox.setText(String.valueOf(targetAmount));

            ItemStack itemStack = itemID.getStack();
            instance.settingsMenu.itemView.setItemStack(itemStack);
        }
    }

    private void onCloseCleanup()
    {
        instance = null;
        isOwned = false;
        itemID = null;
        targetAmount = 0;
    }
    @Override
    public void onClose() {
        onCloseCleanup();
        super.onClose();
    }

    private void onConnectDisconnectButtonClicked()
    {
        isOwned = !isOwned;
        applySettigns();
    }
    private void onSelectItemButtonClicked() {
        ArrayList<ItemStack> allowedItemIDs = new ArrayList<>();
        for(ItemID itemID : ClientBankManager.getAllowedItemIDs())
        {
            allowedItemIDs.add(itemID.getStack());
        }
        ItemSelectionScreen itemSelectionScreen = new ItemSelectionScreen(this, allowedItemIDs, this::onItemSelected);
        itemSelectionScreen.sortItems();
        assert this.minecraft != null;
        this.minecraft.setScreen(itemSelectionScreen);
    }
    private void onItemSelected(ItemStack itemStack)
    {
        BankDownloadScreen.itemID = new ItemID(itemStack);;
        instance.settingsMenu.itemView.setItemStack(itemStack);
    }
    private void applySettigns()
    {
        targetAmount = settingsMenu.targetAmountTextBox.getInt();
        if(targetAmount < 0)
            targetAmount = 0;
        if(targetAmount > maxTargetAmount)
            targetAmount = maxTargetAmount;
        settingsMenu.targetAmountTextBox.setText(String.valueOf(targetAmount));
        sendUpdatePacket();
    }

    private void sendUpdatePacket()
    {
        UpdateBankDownloadBlockEntityPacket.sendPacket(pos, isOwned, itemID, targetAmount);
    }
}
