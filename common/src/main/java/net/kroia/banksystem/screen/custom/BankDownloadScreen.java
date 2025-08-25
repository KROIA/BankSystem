package net.kroia.banksystem.screen.custom;

import net.kroia.banksystem.BankSystemMod;
import net.kroia.banksystem.banking.BankPermission;
import net.kroia.banksystem.menu.custom.BankDownloadContainerMenu;
import net.kroia.banksystem.networking.packet.client_sender.update.entity.UpdateBankDownloadBlockEntityPacket;
import net.kroia.banksystem.networking.packet.server_sender.update.SyncBankDownloadDataPacket;
import net.kroia.banksystem.util.BankSystemGuiContainerScreen;
import net.kroia.banksystem.util.BankSystemGuiElement;
import net.kroia.banksystem.util.ItemID;
import net.kroia.modutilities.gui.Gui;
import net.kroia.modutilities.gui.GuiTexture;
import net.kroia.modutilities.gui.elements.*;
import net.kroia.modutilities.gui.screens.ItemSelectionScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class BankDownloadScreen extends BankSystemGuiContainerScreen<BankDownloadContainerMenu> {
    private static final String prefix = "gui." + BankSystemMod.MOD_ID + ".bank_download_screen.";
    private static final Component INVENTORY_NAME_TEXT = Component.translatable(prefix+"inventory_name");
    private static final Component CONNECT_BUTTON = Component.translatable(prefix+"connect_button");
    private static final Component DISCONNECT_BUTTON = Component.translatable(prefix+"disconnect_button");
    private static final Component TARGET_AMOUNT = Component.translatable(prefix+"target_amount");
    private static final Component SELECT_ITEM = Component.translatable(prefix+"select_item");
    private static final Component APPLY_BUTTON = Component.translatable(prefix+"apply_button");


    private  class SettingsMenu extends BankSystemGuiElement {

        public final Button connectDisconnectButton;
        public final Button selectItemButton;
        public final ItemView itemView;
        public final TextBox targetAmountTextBox;
        public final Label targetAmountLabel;
        public final Button applyButton;
        public static final int elementHeight = 16;
        private final BankDownloadScreen parent;

        public SettingsMenu(BankDownloadScreen parent)
        {
            this.parent = parent;
            connectDisconnectButton = new Button((isOwned?DISCONNECT_BUTTON.getString():CONNECT_BUTTON.getString()), this::onSelectBankAccountButtonClicked);
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
        private void onSelectBankAccountButtonClicked()
        {
            if(isOwned)
            {
                parent.onConnectDisconnectButtonClicked(0); // Disconnect
                return;
            }
            BankAccountSelectionScreen selectionScreen = new BankAccountSelectionScreen(parent, minecraft.player.getUUID(), parent::onConnectDisconnectButtonClicked, BankPermission.DEPOSIT.getValue());
            minecraft.setScreen(selectionScreen);
        }
    }


    private final BlockPos pos;

    private final ContainerView<BankDownloadContainerMenu> inventoryView;
    private final SettingsMenu settingsMenu;

    public static boolean isOwned = false;

    public static ItemID itemID;
    public static int targetAmount;
    public static int maxTargetAmount;
    public static int accountNr = 0; // 0 means no account selected



    private static BankDownloadScreen instance;

    public BankDownloadScreen(BankDownloadContainerMenu pMenu, Inventory pPlayerInventory, Component pTitle) {
        super(pMenu, pPlayerInventory, pTitle);
        instance = this;
        this.pos = pMenu.getBlockPos();



        inventoryView = new ContainerView<>(pMenu, pPlayerInventory, INVENTORY_NAME_TEXT, new GuiTexture(BankSystemMod.MOD_ID, "textures/gui/inventory_hpc.png", 256, 256));
        inventoryView.setSize(176, 166);
        inventoryView.setOnCloseEvent(this::onCloseCleanup);
        settingsMenu = new SettingsMenu(this);

        if (isOwned) {
            settingsMenu.connectDisconnectButton.setLabel(DISCONNECT_BUTTON.getString());
        } else {
            settingsMenu.connectDisconnectButton.setLabel(CONNECT_BUTTON.getString());
        }
        settingsMenu.targetAmountTextBox.setText(String.valueOf(targetAmount));

        if(itemID != null)
        {
        ItemStack itemStack = itemID.getStack();
        settingsMenu.itemView.setItemStack(itemStack);
        }


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
        accountNr = packet.getAccountNr();

        if(instance != null) {
            if (isOwned) {
                instance.settingsMenu.connectDisconnectButton.setLabel(DISCONNECT_BUTTON.getString());
            } else {
                instance.settingsMenu.connectDisconnectButton.setLabel(CONNECT_BUTTON.getString());
            }
            instance.settingsMenu.targetAmountTextBox.setText(String.valueOf(targetAmount));

            if(itemID != null) {
                ItemStack itemStack = itemID.getStack();
                instance.settingsMenu.itemView.setItemStack(itemStack);
            }
            else {
                instance.settingsMenu.itemView.setItemStack(ItemStack.EMPTY);
            }
        }
    }

    private void onCloseCleanup()
    {
        instance = null;
        isOwned = false;
        itemID = null;
        targetAmount = 0;
        accountNr = 0; // Reset account number when closing the screen
    }
    @Override
    public void onClose() {
        onCloseCleanup();
        super.onClose();
    }

    private void onConnectDisconnectButtonClicked(int accountNr)
    {
        this.accountNr = accountNr;
        isOwned = !isOwned;
        applySettigns();
    }
    private void onSelectItemButtonClicked() {
        BACKEND_INSTANCES.CLIENT_BANK_MANAGER.requestBankManagerData((minimalBankManagerData) -> {
            List<ItemStack> allowedItemStacks;
            if(minimalBankManagerData == null)
            {
                allowedItemStacks = new ArrayList<>();
            }
            else {
                allowedItemStacks = minimalBankManagerData.getAllowedItemStacks();
            }
            ItemSelectionScreen itemSelectionScreen = new ItemSelectionScreen(
                    this,
                    allowedItemStacks,
                    this::onItemSelected);

            itemSelectionScreen.sortItems();
            assert this.minecraft != null;
            this.minecraft.setScreen(itemSelectionScreen);
        });
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
        UpdateBankDownloadBlockEntityPacket.sendPacket(pos, isOwned, itemID, targetAmount, accountNr);
    }
}
